package io.github.hjosugi.chatsample.memory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.MessageFilter;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The session advisors on a blocking {@code ChatClient} call: their places around the
 * loop, the context keys, what is stored and read, what each advisor gives on its own,
 * and compaction after a turn. Streams are in {@code StreamedSessionTurnTests}.
 */
class SessionChatClientTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void sessionAdvisorsBuildsTheTurnAdvisorOutsideTheLoopAndTheMemoryAdvisorInside() {
		List<Advisor> advisors = SessionAdvisors.builder(this.sessions).build();

		assertThat(advisors).hasSize(2);
		assertThat(advisors.get(0)).isInstanceOf(SessionTurnAdvisor.class);
		assertThat(advisors.get(0).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);
		assertThat(advisors.get(1)).isInstanceOf(SessionMemoryAdvisor.class);
		assertThat(advisors.get(1).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1000);
		assertThat(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY).isEqualTo("chat_memory_conversation_id");
	}

	@Test
	void aCallWithoutASessionIdIsRefused() {
		ChatClient client = client(ScriptedChatModel.answering("Hi"));

		assertThatIllegalStateException().isThrownBy(() -> client.prompt("Hello").call().content());
	}

	@Test
	void theFirstCallCreatesTheSessionWithTheDefaultMetadataAndARequestReplacesIt() {
		ChatClient client = ChatClient.builder(ScriptedChatModel.answering("Hi"))
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultAdvisors((a) -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
					Map.of(SessionAttributes.AGENT_NAME, "support")))
			.build();

		client.prompt("Hello").advisors(SessionAdvisors.session("s1")).call().content();
		client.prompt("Hello")
			.advisors(SessionAdvisors.session("s2"))
			.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
					Map.of(SessionAttributes.PRINCIPAL_NAME, "alice")))
			.call()
			.content();

		assertThat(SessionAttributes.agentName(this.sessions.findById("s1"))).isEqualTo("support");
		assertThat(this.sessions.findById("s2").metadata()).containsEntry(SessionAttributes.PRINCIPAL_NAME, "alice")
			.doesNotContainKey(SessionAttributes.AGENT_NAME);
	}

	@Test
	void theResponseNamesTheEventsThatStoredTheRequestAndTheReply() {
		ChatClientResponse response = client(ScriptedChatModel.answering("Hi")).prompt("Hello")
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.chatClientResponse();

		List<SessionEvent> log = this.sessions.getEvents("s1");
		assertThat(response.context()).containsEntry(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY, log.get(0).getId())
			.containsEntry(SessionMemoryAdvisor.RESPONSE_EVENT_IDS_CONTEXT_KEY, List.of(log.get(1).getId()));
	}

	@Test
	void aPerRequestFilterNarrowsTheHistoryOfOneCall() {
		ScriptedChatModel model = ScriptedChatModel.answering("Ok");
		ChatClient client = client(model);
		for (int i = 1; i <= 3; i++) {
			client.prompt("Question " + i).advisors(SessionAdvisors.session("s1")).call().content();
		}

		client.prompt("Quick summary please")
			.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "s1")
				.param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(2)))
			.call()
			.content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Question 3", "assistant: Ok",
				"user: Quick summary please");
		assertThatIllegalArgumentException().isThrownBy(() -> client.prompt("Again")
			.advisors((a) -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "s1")
				.param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, "lastN(2)"))
			.call()
			.content());
	}

	@Test
	void anEmptyReplyIsNotStoredByDefault() {
		ChatClient client = client(ScriptedChatModel.answering(""));

		client.prompt("Hello").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(Transcript.of(this.sessions.getMessages("s1"))).containsExactly("user: Hello");
	}

	@Test
	void aMessageFilterDecidesWhatIsStoredButToolMessagesAreAlwaysStored() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is confidential.")
				: ScriptedChatModel.lastText(prompt).contains("time") ? ScriptedChatModel.toolCall("time", "{}")
						: ScriptedChatModel.text("A confidential answer"));
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions)
				.memory((memory) -> memory.messageFilter(
						MessageFilter.containsText("confidential").negate().and(MessageFilter.skipEmptyMessages())))
				.build())
			.defaultTools(new Clock())
			.build();

		client.prompt("What time is it?").advisors(SessionAdvisors.session("s1")).call().content();
		String answer = client.prompt("Something else").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(answer).isEqualTo("A confidential answer");
		assertThat(Transcript.of(this.sessions.getMessages("s1"))).containsExactly("user: What time is it?",
				"calls: time({})", "results: \"noon\"", "user: Something else");
		assertThat(MessageFilter.byMessageType(MessageType.USER).shouldPersist(new AssistantMessage("x"))).isFalse();
		assertThat(MessageFilter.all().shouldPersist(new AssistantMessage(""))).isTrue();
	}

	@Test
	void hidingToolMessagesOnReadDropsTheToolCallLeftWithoutItsResults() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon.")
				: ScriptedChatModel.lastText(prompt).contains("time") ? ScriptedChatModel.toolCall("time", "{}")
						: ScriptedChatModel.text("Fine."));
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions)
				.memory((memory) -> memory.eventFilter(
						EventFilter.builder().messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT)).build()))
				.build())
			.defaultTools(new Clock())
			.build();
		client.prompt("What time is it?").advisors(SessionAdvisors.session("s1")).call().content();

		client.prompt("Thanks").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: What time is it?", "assistant: It is noon.",
				"user: Thanks");
		assertThat(this.sessions.getMessages("s1")).hasSize(6);
	}

	@Test
	void onlyBothAdvisorsStoreTheResultsOfAReturnDirectTool() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.toolCall("directTime", "{}"));
		SessionService alone = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();

		client(model).prompt("Time?").tools(new Clock()).advisors(SessionAdvisors.session("s1")).call().content();
		ChatClient.builder(model)
			.defaultAdvisors(SessionMemoryAdvisor.builder(alone).build())
			.build()
			.prompt("Time?")
			.tools(new Clock())
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.content();

		assertThat(Transcript.of(this.sessions.getMessages("s1"))).containsExactly("user: Time?", "calls: directTime({})",
				"results: \"noon\"");
		assertThat(Transcript.of(alone.getMessages("s1"))).containsExactly("user: Time?", "calls: directTime({})");
	}

	@Test
	void theTurnAdvisorAloneStoresNothingOfTheConversation() {
		ChatClient.builder(ScriptedChatModel.answering("Hi"))
			.defaultAdvisors(SessionTurnAdvisor.builder(this.sessions).build())
			.build()
			.prompt("Hello")
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.content();

		assertThat(this.sessions.findById("s1")).isNotNull();
		assertThat(this.sessions.getMessages("s1")).isEmpty();
	}

	@Test
	void theTurnAdvisorCompactsOncePerCallAndNeedsTriggerAndStrategyTogether() {
		ScriptedChatModel model = ScriptedChatModel.answering("Ok");
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions)
				.compaction((request) -> request.currentTurnCount() > 2, WindowCompactionStrategy.builder().lastTurns(1).build())
				.build())
			.build();

		for (int i = 1; i <= 3; i++) {
			client.prompt("Question " + i).advisors(SessionAdvisors.session("s1")).call().content();
		}
		client.prompt("Question 4").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Question 3", "assistant: Ok",
				"user: Question 4");
		assertThatIllegalArgumentException().isThrownBy(() -> SessionTurnAdvisor.builder(this.sessions)
			.compactionStrategy(WindowCompactionStrategy.builder().build())
			.build());
	}

	@Test
	void withSessionIdInToolContextEveryToolSeesTheSessionId() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text(ScriptedChatModel.lastText(prompt)) : ScriptedChatModel.toolCall("whichSession", "{}"));
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).sessionIdInToolContext(true).build())
			.defaultTools(new Clock())
			.build();

		assertThat(client.prompt("Which?").advisors(SessionAdvisors.session("s1")).call().content()).isEqualTo("\"s1\"");
		assertThat(client.prompt("Which?")
			.advisors(SessionAdvisors.session("s2"))
			.toolContext(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "mine"))
			.call()
			.content()).isEqualTo("\"mine\"");
	}

	private ChatClient client(ScriptedChatModel model) {
		return ChatClient.builder(model).defaultAdvisors(SessionAdvisors.builder(this.sessions).build()).build();
	}

	static class Clock {

		@Tool(description = "Tells the time")
		String time() {
			return "noon";
		}

		@Tool(description = "Tells the time, as the answer", returnDirect = true)
		String directTime() {
			return "noon";
		}

		@Tool(description = "Tells the session")
		String whichSession(ToolContext context) {
			return (String) context.getContext().get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
		}

	}

}
