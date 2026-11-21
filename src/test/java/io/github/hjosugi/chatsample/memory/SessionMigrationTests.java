package io.github.hjosugi.chatsample.memory;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration;
import org.springframework.ai.model.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springframework.ai.model.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

/**
 * Each row of the migration from chat memory to session memory, and the sessions
 * tutorial: what chat memory did, session memory does with the API the mapping names.
 */
@SuppressWarnings("removal")
class SessionMigrationTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void sessionAdvisorsTakeThePlaceOfTheMemoryAdvisorWithTheSameKey() {
		ScriptedChatModel before = ScriptedChatModel.answering("Hi");
		ScriptedChatModel after = ScriptedChatModel.answering("Hi");
		ChatClient chatMemoryClient = ChatClient.builder(before)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
			.build();
		ChatClient sessionClient = ChatClient.builder(after)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.build();

		for (String text : new String[] { "Hello", "Again" }) {
			chatMemoryClient.prompt(text).advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c1")).call().content();
			// The key of chat memory reaches the session advisors unchanged
			sessionClient.prompt(text).advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c1")).call().content();
		}

		assertThat(Transcript.of(after.lastPrompt())).isEqualTo(Transcript.of(before.lastPrompt()));
		assertThat(ChatMemory.CONVERSATION_ID).isEqualTo(MemoryAdvisor.CONVERSATION_ID);
		assertThat(Transcript.of(this.sessions.getActiveMessages("c1"))).hasSize(4);
	}

	@Test
	void aWindowOnTheMemoryAdvisorOrACompactionTakesThePlaceOfMaxMessages() {
		ScriptedChatModel windowed = ScriptedChatModel.answering("Ok");
		ChatClient client = ChatClient.builder(windowed)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).memory((memory) -> memory.eventFilter(EventFilter.lastN(2))).build())
			.build();

		for (int i = 1; i <= 3; i++) {
			client.prompt("Q" + i).advisors(SessionAdvisors.session("c1")).call().content();
		}

		assertThat(Transcript.of(windowed.lastPrompt())).containsExactly("user: Q2", "assistant: Ok", "user: Q3");
		// Unlike maxMessages, nothing was evicted from the log
		assertThat(this.sessions.getMessages("c1")).hasSize(6);
	}

	@Test
	void theSessionServiceTakesThePlaceOfGetAddAndClearOnceTheSessionExists() {
		assertThatException().isThrownBy(() -> this.sessions.appendMessage("c1", new UserMessage("Hello")));

		this.sessions.findOrCreate(CreateSessionRequest.builder().id("c1").build());
		this.sessions.appendMessage("c1", new UserMessage("Hello"));
		this.sessions.appendMessage("c1", new AssistantMessage("Hi"));
		assertThat(Transcript.of(this.sessions.getActiveMessages("c1"))).containsExactly("user: Hello", "assistant: Hi");
		this.sessions.delete("c1");
		assertThat(this.sessions.findById("c1")).isNull();
	}

	@Test
	void theTwoJdbcStoresDoNotShareTheirTables() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
					JdbcChatMemoryRepositoryAutoConfiguration.class, ChatMemoryAutoConfiguration.class,
					JdbcSessionRepositoryAutoConfiguration.class, SessionServiceAutoConfiguration.class))
			.withPropertyValues("spring.datasource.generate-unique-name=true")
			.run((context) -> {
				context.getBean(ChatMemory.class).add("c1", new UserMessage("Kept in chat memory"));
				SessionService sessions = context.getBean(SessionService.class);

				assertThat(context.getBean(SessionRepository.class).getClass().getSimpleName())
					.isEqualTo("JdbcSessionRepository");
				assertThat(sessions.findById("c1")).isNull();
			});
	}

	@Test
	void theTutorialsTwoRequestsShareASessionAndAnotherIdStartsAfresh() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.text(
				prompt.getContents().contains("My name is Alice") ? "Your name is Alice." : "I do not know."));
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions)
				.compaction(new org.springframework.ai.session.compaction.TurnCountTrigger(20),
						org.springframework.ai.session.compaction.WindowCompactionStrategy.builder().lastTurns(10).build())
				.build())
			.build();

		client.prompt().user("My name is Alice").advisors(SessionAdvisors.session("alice")).call().content();
		String alice = client.prompt().user("What is my name").advisors(SessionAdvisors.session("alice")).call().content();
		String bob = client.prompt().user("What is my name").advisors(SessionAdvisors.session("bob")).call().content();

		assertThat(alice).isEqualTo("Your name is Alice.");
		assertThat(bob).isEqualTo("I do not know.");
	}

}
