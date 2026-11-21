package io.github.hjosugi.chatsample;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application with a database and Spring AI's JDBC session starter, which is all it
 * takes for conversations that outlast the application: spring-chat then keeps them in
 * the {@code SessionService} of that starter. The application itself ships without
 * either; here they come from the test classpath, with H2 in memory, once the
 * exclusion that the other application tests have is lifted.
 * <p>
 * A reply that waits for the user is continued from what the database holds.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key=test-key",
				"spring.ai.anthropic.max-retries=0", "spring.ai.anthropic.chat.model=claude-haiku-5-5",
				// What src/test/resources/config/application.properties leaves out comes back.
				"spring.autoconfigure.exclude=" })
class ConversationsInADatabaseTests {

	private static final FakeAnthropic anthropic = anthropic();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private SessionService sessions;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Orders orders;

	private static FakeAnthropic anthropic() {
		try {
			return new FakeAnthropic((body) -> {
				String result = FakeAnthropic.toolResult(body);
				if (result != null) {
					return FakeAnthropic.text("Done: ", result);
				}
				return body.toString().contains("Cancel A-1002")
						? FakeAnthropic.toolUse("cancelOrder", "{\"orderId\": \"A-1002\"}")
						: FakeAnthropic.text("Hello, ", "Alice.");
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void anthropicUrl(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.anthropic.base-url", anthropic::url);
	}

	@AfterAll
	static void stop() {
		anthropic.stop();
	}

	@BeforeEach
	void forget() {
		anthropic.reset();
	}

	@Test
	void theChatUsesTheSessionServiceOfTheStarterAndSweepsNothingItself() {
		assertThat(this.context.getBean(SessionRepository.class)).isInstanceOf(JdbcSessionRepository.class);
		assertThat(this.context.getBeansOfType(SessionService.class)).hasSize(1);
		// The sweeper is for the conversations that spring-chat keeps in memory itself.
		assertThat(this.context.containsBean("chatExpiredSessionSweeper")).isFalse();
	}

	@Test
	void aConversationIsInTheTablesOfTheSessionStore() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(alice.chat("Hello")).contains("Alice.", "event:done").doesNotContain("event:error");

		List<String> ids = this.jdbc.queryForList("select id from AI_SESSION where id like 'spring-chat:%'",
				String.class);
		assertThat(ids).isNotEmpty();
		assertThat(conversationThatStartsWith("alice", "Hello")).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT);
	}

	@Test
	void aReplyThatWaitsForTheUserIsContinuedFromTheDatabase() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(alice.chat("Cancel A-1002")).contains("event:confirm", "cancelOrder");
		assertThat(conversationThatStartsWith("alice", "Cancel A-1002")).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT);
		assertThat(this.orders.find("alice", "A-1002").orElseThrow().status()).isEqualTo(Orders.BEING_PACKED);

		assertThat(alice.decide("Cancel A-1002", true)).contains("A-1002 is cancelled", "event:done");

		assertThat(this.orders.find("alice", "A-1002").orElseThrow().status()).isEqualTo(Orders.CANCELLED);
		assertThat(conversationThatStartsWith("alice", "Cancel A-1002")).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
	}

	private List<Message> conversationThatStartsWith(String user, String question) {
		List<List<Message>> conversations = this.sessions.findByMetadata(SessionAttributes.PRINCIPAL_NAME, user)
			.stream()
			.map((session) -> this.sessions.getMessages(session.id()))
			.filter((messages) -> !messages.isEmpty() && question.equals(messages.get(0).getText()))
			.toList();
		assertThat(conversations).as("the conversations of %s that start with \"%s\"", user, question).hasSize(1);
		return conversations.get(0);
	}

}
