package io.github.hjosugi.chatsample.memory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.tools.ShopTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springframework.ai.model.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.jdbc.H2JdbcSessionRepositoryDialect;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepositoryDialect;
import org.springframework.ai.test.session.AbstractSessionRepositoryContractTests;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The JDBC session repository on H2 in memory: Spring AI's own repository contract, the
 * auto-configuration and its properties, and a {@code ChatClient} turn with tools whose
 * calls and results survive the database.
 */
class JdbcSessionRepositoryTests {

	private static DataSource h2;

	private final ApplicationContextRunner boot = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
				DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
				JdbcSessionRepositoryAutoConfiguration.class, SessionServiceAutoConfiguration.class))
		.withPropertyValues("spring.datasource.generate-unique-name=true");

	@BeforeAll
	static void createTheSchema() {
		h2 = new DriverManagerDataSource("jdbc:h2:mem:sessions;DB_CLOSE_DELAY=-1");
		new ResourceDatabasePopulator(new ClassPathResource("org/springframework/ai/session/jdbc/schema-h2.sql"))
			.execute(h2);
	}

	@Test
	void theDialectIsDetectedFromTheDatabase() {
		assertThat(JdbcSessionRepositoryDialect.from(h2)).isInstanceOf(H2JdbcSessionRepositoryDialect.class);
	}

	@Test
	void theAutoConfigurationCreatesTheSchemaTheRepositoryAndTheServiceOnAnEmbeddedDatabase() {
		this.boot.withPropertyValues("spring.ai.session.time-to-live=2h").run((context) -> {
			assertThat(context.getBean(SessionRepository.class)).isInstanceOf(JdbcSessionRepository.class);
			assertThat(context.getBean(SessionService.class)).isInstanceOf(DefaultSessionService.class);
			assertThat(tables(context.getBean(JdbcTemplate.class))).contains("AI_SESSION", "AI_SESSION_INDEX",
					"AI_SESSION_EVENT");
			Session session = context.getBean(SessionService.class).create(CreateSessionRequest.builder().build());
			assertThat(session.expiresAt()).isCloseTo(session.createdAt().plus(Duration.ofHours(2)),
					within(Duration.ofSeconds(1)));
		});
	}

	@Test
	void withInitializeSchemaNeverNoTableIsCreated() {
		this.boot.withPropertyValues("spring.ai.session.repository.jdbc.initialize-schema=never")
			.run((context) -> assertThat(tables(context.getBean(JdbcTemplate.class))).doesNotContain("AI_SESSION"));
	}

	@Test
	void indexedAttributesAnswerFindByMetadataBesidesThePrincipalName() {
		this.boot.withPropertyValues("spring.ai.session.repository.jdbc.indexed-attributes=tenant").run((context) -> {
			SessionService sessions = context.getBean(SessionService.class);
			sessions.create(CreateSessionRequest.builder().id("s1").metadata("tenant", "acme").build());
			sessions.create(CreateSessionRequest.builder().id("s2").metadata("principalName", "alice").build());

			assertThat(sessions.findByMetadata("tenant", "acme")).extracting(Session::id).containsExactly("s1");
			assertThat(sessions.findByMetadata("principalName", "alice")).extracting(Session::id).containsExactly("s2");
			assertThat(context.getBean(JdbcTemplate.class)
				.queryForList("SELECT attr_name FROM AI_SESSION_INDEX", String.class)).containsExactlyInAnyOrder("tenant",
						"principalName");
		});
	}

	@Test
	void aRepositoryOfYourOwnMakesTheJdbcOneBackOff() {
		this.boot.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build())
			.run((context) -> assertThat(context.getBean(SessionRepository.class))
				.isInstanceOf(InMemorySessionRepository.class));
	}

	@Test
	void aTurnWithToolsIsStoredInTheDatabaseAndReadBackWhole() {
		this.boot.run((context) -> {
			SessionService sessions = context.getBean(SessionService.class);
			ShopTools tools = new ShopTools();
			ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
					? ScriptedChatModel.text("It was shipped.")
					: ScriptedChatModel.lastText(prompt).startsWith("Where")
							? AssistantMessage.builder()
								.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "orderStatus",
										"{\"orderId\":\"A-1001\"}")))
								.build()
							: ScriptedChatModel.text("You are welcome."));
			ChatClient client = ChatClient.builder(model)
				.defaultAdvisors(SessionAdvisors.builder(sessions).build())
				.defaultTools(tools)
				.build();

			client.prompt("Where is A-1001?").advisors(SessionAdvisors.session("s1")).toolContext(Map.of("user", "alice"))
				.stream().content().blockLast();
			client.prompt("Thanks").advisors(SessionAdvisors.session("s1")).toolContext(Map.of("user", "alice"))
				.call().content();

			assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Where is A-1001?",
					"calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"", "assistant: It was shipped.",
					"user: Thanks");
			assertThat(context.getBean(JdbcTemplate.class)
				.queryForList("SELECT event_type FROM AI_SESSION_EVENT ORDER BY seq", String.class)).hasSize(6);
		});
	}

	private static List<String> tables(JdbcTemplate jdbc) {
		return jdbc.queryForList("SELECT table_name FROM information_schema.tables", String.class);
	}

	/**
	 * Spring AI's contract for a session repository, run against H2.
	 */
	@Nested
	class Contract extends AbstractSessionRepositoryContractTests {

		@Override
		protected SessionRepository createRepository() {
			new JdbcTemplate(h2).update("DELETE FROM AI_SESSION");
			return JdbcSessionRepository.builder().dataSource(h2).build();
		}

	}

}
