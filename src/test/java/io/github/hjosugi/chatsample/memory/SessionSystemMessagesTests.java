package io.github.hjosugi.chatsample.memory;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * System messages and a session: kept out of it by default, sent from code on every
 * request, and when stored after all, the latest one wins.
 */
class SessionSystemMessagesTests {

	@Test
	void theSystemPromptComesFromCodeOnEveryRequestAndIsNeverStored() {
		SessionService sessions = sessions(false);
		ScriptedChatModel model = ScriptedChatModel.answering("Ok");
		ChatClient client = ChatClient.builder(model)
			.defaultSystem("You are a travel assistant. Answer concisely in {language}.")
			.defaultAdvisors(SessionAdvisors.builder(sessions).build())
			.build();

		client.prompt().system((s) -> s.param("language", "French")).user("Hello").advisors(SessionAdvisors.session("s1")).call().content();
		client.prompt().system((s) -> s.param("language", "German")).user("Again").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly(
				"system: You are a travel assistant. Answer concisely in German.", "user: Hello", "assistant: Ok",
				"user: Again");
		assertThat(Transcript.of(sessions.getMessages("s1"))).containsExactly("user: Hello", "assistant: Ok", "user: Again",
				"assistant: Ok");
	}

	@Test
	void theServiceRejectsAStoredSystemMessageByDefault() {
		SessionService sessions = sessions(false);
		sessions.create(CreateSessionRequest.builder().id("s1").build());

		assertThatIllegalArgumentException().isThrownBy(() -> sessions.appendMessage("s1", new SystemMessage("Be brief.")));
	}

	@Test
	void ofTheStoredSystemMessagesTheLatestGoesFirstAndADuplicateGoesOnce() {
		SessionService sessions = sessions(true);
		sessions.create(CreateSessionRequest.builder().id("s1").build());
		sessions.appendMessage("s1", new SystemMessage("Answer in French."));
		sessions.appendMessage("s1", new UserMessage("Hello"));
		sessions.appendMessage("s1", new SystemMessage("Answer in German."));
		ScriptedChatModel model = ScriptedChatModel.answering("Ok");
		ChatClient client = ChatClient.builder(model).defaultAdvisors(SessionAdvisors.builder(sessions).build()).build();

		client.prompt().system("Answer in German.").user("Again").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("system: Answer in German.", "user: Hello",
				"user: Again");
	}

	@Test
	void aStoredSystemPromptAndARequestOneBothGoFirstInThatOrder() {
		SessionService sessions = sessions(true);
		sessions.create(CreateSessionRequest.builder().id("s1").build());
		sessions.appendMessage("s1", new SystemMessage("Answer in French."));
		ScriptedChatModel model = ScriptedChatModel.answering("Ok");
		ChatClient client = ChatClient.builder(model).defaultAdvisors(SessionAdvisors.builder(sessions).build()).build();

		client.prompt().system("Be brief.").user("Hello").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("system: Answer in French.", "system: Be brief.",
				"user: Hello");
	}

	@Test
	void thePropertyAllowsStoredSystemMessagesForTheAutoConfiguredService() {
		ApplicationContextRunner boot = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SessionServiceAutoConfiguration.class))
			.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build());

		boot.run((context) -> {
			SessionService sessions = context.getBean(SessionService.class);
			sessions.create(CreateSessionRequest.builder().id("s1").build());
			assertThatIllegalArgumentException().isThrownBy(() -> sessions.appendMessage("s1", new SystemMessage("x")));
		});
		boot.withPropertyValues("spring.ai.session.allow-system-messages=true").run((context) -> {
			SessionService sessions = context.getBean(SessionService.class);
			sessions.create(CreateSessionRequest.builder().id("s1").build());
			sessions.appendMessage("s1", new SystemMessage("x"));
			assertThat(sessions.getMessages("s1")).hasSize(1);
		});
	}

	private static SessionService sessions(boolean allowSystemMessages) {
		return DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.allowSystemMessages(allowSystemMessages)
			.build();
	}

}
