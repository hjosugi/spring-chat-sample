package io.github.hjosugi.chatsample.memory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.tools.ShopTools;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A streamed {@code ChatClient} call with tools on a session, as a chat UI makes it: what
 * the session holds after a turn, after a turn that pauses for a person's confirmation,
 * after its resumption, and after a reply the user stopped; and when, in the stream, it
 * holds it.
 */
class StreamedSessionTurnTests {

	private static final String SESSION = "alice-1";

	private static final Map<String, Object> ALICE = Map.of(ShopTools.USER, "alice");

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final ShopTools tools = new ShopTools();

	@Test
	void aStreamedTurnLeavesEveryRoundOfTheToolLoopInTheSession() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It was shipped.") : call("c1", "orderStatus", "A-1001"));

		List<String> text = ask(client(model), "Where is A-1001?").collectList().block();

		assertThat(text).containsExactly("It ", "was ", "shipped.");
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Where is A-1001?",
				"calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"", "assistant: It was shipped.");
		// Each round's prompt is rebuilt from the session: no system message, no repeats
		assertThat(model.prompts).hasSize(2);
		assertThat(Transcript.of(model.prompts.get(0))).containsExactly("user: Where is A-1001?");
		assertThat(Transcript.of(model.prompts.get(1))).containsExactly("user: Where is A-1001?",
				"calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"");
		assertThat(this.tools.calls).containsExactly(new ShopTools.Call("orderStatus", "A-1001", ALICE));
	}

	@Test
	void theNextStreamedTurnSeesTheEarlierOnes() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It was shipped.")
				: ScriptedChatModel.lastText(prompt).startsWith("Where") ? call("c1", "orderStatus", "A-1001")
						: ScriptedChatModel.text("You are welcome."));
		ChatClient client = client(model);
		ask(client, "Where is A-1001?").blockLast();

		ask(client, "Thanks").blockLast();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Where is A-1001?",
				"calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"", "assistant: It was shipped.",
				"user: Thanks");
	}

	@Test
	void theSystemPromptGoesFirstInEveryRoundAndIsNotStored() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It was shipped.") : call("c1", "orderStatus", "A-1001"));
		ChatClient client = ChatClient.builder(model)
			.defaultSystem("You are a shop assistant.")
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultTools(this.tools)
			.build();

		ask(client, "Where is A-1001?").blockLast();

		assertThat(model.prompts).allSatisfy((prompt) -> assertThat(Transcript.of(prompt).get(0))
			.isEqualTo("system: You are a shop assistant."));
		assertThat(Transcript.of(model.prompts.get(1))).containsExactly("system: You are a shop assistant.",
				"user: Where is A-1001?", "calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"");
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).noneMatch((line) -> line.startsWith("system"));
	}

	@Test
	void theSessionHoldsTheReplyWhenTheStreamCompletesNotWhenItsLastChunkArrives() {
		ScriptedChatModel model = ScriptedChatModel.answering("Hello there");
		AtomicReference<List<String>> atCompletion = new AtomicReference<>();

		ask(client(model), "Hello")
			.doOnComplete(() -> atCompletion.set(Transcript.of(this.sessions.getActiveMessages(SESSION))))
			.blockLast();

		assertThat(atCompletion.get()).containsExactly("user: Hello", "assistant: Hello there");
	}

	@Test
	void streamedChunksDoNotNameTheEventsThatStoredTheReply() {
		List<ChatClientResponse> chunks = client(ScriptedChatModel.answering("Hello there")).prompt()
			.user("Hello")
			.advisors(SessionAdvisors.session(SESSION))
			.stream()
			.chatClientResponse()
			.collectList()
			.block();

		assertThat(chunks).isNotEmpty()
			.allSatisfy((chunk) -> assertThat(chunk.context())
				.doesNotContainKey(SessionMemoryAdvisor.RESPONSE_EVENT_IDS_CONTEXT_KEY)
				.containsKey(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY));
	}

	// --- A reply that waits for a person's confirmation ---

	@Test
	void aPausedStreamEndsOnAChunkWithTheCallsAndTheirKeys() {
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> call("I will cancel it. ", "c1", "cancelOrder", "A-1001"));

		List<ChatResponse> chunks = client(model).prompt()
			.user("Cancel A-1001")
			.advisors(SessionAdvisors.session(SESSION))
			.toolContext(ALICE)
			.stream()
			.chatResponse()
			.collectList()
			.block();

		ChatResponse last = chunks.get(chunks.size() - 1);
		assertThat(ToolCallDecisions.awaitingDecision(last)).containsExactly("c1");
		assertThat(last.getResult().getOutput().getToolCalls()).extracting(AssistantMessage.ToolCall::name)
			.containsExactly("cancelOrder");
		assertThat(last.getResult().getOutput().getText()).isNullOrEmpty();
		assertThat(last.getResult().getMetadata().getFinishReason()).isEqualTo("tool_calls");
		// The text came before, in chunks that name no call
		List<ChatResponse> text = chunks.subList(0, chunks.size() - 1);
		assertThat(text).extracting((chunk) -> chunk.getResult().getOutput().getText())
			.containsExactly("I ", "will ", "cancel ", "it. ");
		assertThat(text).allSatisfy((chunk) -> assertThat(ToolCallDecisions.isAwaitingDecision(chunk)).isFalse());
		assertThat(this.tools.calls).isEmpty();
	}

	@Test
	void aPausedStreamLeavesTheReplyWithItsCallsAndNoResultsInTheSession() {
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> call("I will cancel it. ", "c1", "cancelOrder", "A-1001"));

		ask(client(model), "Cancel A-1001").blockLast();

		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Cancel A-1001",
				"assistant: I will cancel it. | calls: cancelOrder(A-1001)");
	}

	@Test
	void theSessionHoldsThePausedReplyWhenItsLastChunkArrives() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> call("", "c1", "cancelOrder", "A-1001"));
		AtomicReference<List<String>> atLastChunk = new AtomicReference<>();

		client(model).prompt()
			.user("Cancel A-1001")
			.advisors(SessionAdvisors.session(SESSION))
			.toolContext(ALICE)
			.stream()
			.chatResponse()
			.filter(ToolCallDecisions::isAwaitingDecision)
			.doOnNext((last) -> atLastChunk.set(Transcript.of(this.sessions.getActiveMessages(SESSION))))
			.blockLast();

		assertThat(atLastChunk.get()).containsExactly("user: Cancel A-1001", "calls: cancelOrder(A-1001)");
	}

	@Test
	void aPausedStreamIsResumedWithTheReplyFromTheSessionAndOnlyTheResultsSentOn() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Done: " + ScriptedChatModel.lastText(prompt))
				: call("I will cancel it. ", "c1", "cancelOrder", "A-1001"));
		ChatClient client = client(model);
		ask(client, "Cancel A-1001").blockLast();

		List<String> text = resume(client, ToolCallDecisions.of(ToolCallDecision.allow("c1"))).collectList().block();

		assertThat(String.join("", text)).isEqualTo("Done: \"A-1001 of alice is cancelled\"");
		assertThat(this.tools.calls).containsExactly(new ShopTools.Call("cancelOrder", "A-1001", ALICE));
		// The session gave the model the turn so far; the request brought only the results
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Cancel A-1001",
				"assistant: I will cancel it. | calls: cancelOrder(A-1001)",
				"results: \"A-1001 of alice is cancelled\"");
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Cancel A-1001",
				"assistant: I will cancel it. | calls: cancelOrder(A-1001)",
				"results: \"A-1001 of alice is cancelled\"", "assistant: Done: \"A-1001 of alice is cancelled\"");
	}

	@Test
	void aPausedStreamCanAlsoBeResumedWithTheWholeTurnSentOn() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Done.") : call("", "c1", "cancelOrder", "A-1001"));
		ChatClient client = client(model);
		ask(client, "Cancel A-1001").blockLast();
		List<Message> turn = this.sessions.getActiveMessages(SESSION);
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(ToolCallbacks.from(this.tools))
			.toolContext(ALICE)
			.build();
		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(new Prompt(turn, options), new ChatResponse(List.of(new Generation(pausedReply()))),
					ToolCallDecisions.of(ToolCallDecision.allow("c1")));

		// The prompt ended with the paused reply: the history holds it once
		assertThat(Transcript.of(result.conversationHistory())).containsExactly("user: Cancel A-1001",
				"calls: cancelOrder(A-1001)", "results: \"A-1001 of alice is cancelled\"");
		client.prompt()
			.messages(result.conversationHistory())
			.advisors(SessionAdvisors.session(SESSION))
			.toolContext(ALICE)
			.stream()
			.content()
			.blockLast();

		// The session recognizes the turn it has and adds only the results
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Cancel A-1001",
				"calls: cancelOrder(A-1001)", "results: \"A-1001 of alice is cancelled\"");
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Cancel A-1001",
				"calls: cancelOrder(A-1001)", "results: \"A-1001 of alice is cancelled\"", "assistant: Done.");
	}

	@Test
	void aDeniedCallAnswersTheModelWithTheDenyMessage() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("I did not cancel it.") : call("", "c1", "cancelOrder", "A-1001"));
		ChatClient client = client(model);
		ask(client, "Cancel A-1001").blockLast();

		resume(client, ToolCallDecisions.of(ToolCallDecision.deny("c1", "Alice changed her mind."))).blockLast();

		assertThat(this.tools.calls).isEmpty();
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Cancel A-1001",
				"calls: cancelOrder(A-1001)", "results: Alice changed her mind.", "assistant: I did not cancel it.");
	}

	/**
	 * The tool context of the turn that paused is gone: the manager takes the tool context
	 * from the options of the prompt it is given, and a tool that takes a
	 * {@code ToolContext} refuses to run with none.
	 */
	@Test
	void aResumeWithoutTheToolContextIsRefusedByAToolThatTakesOne() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> call("", "c1", "cancelOrder", "A-1001"));
		ask(client(model), "Cancel A-1001").blockLast();
		Prompt withoutContext = new Prompt(List.of(),
				ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(this.tools)).build());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> ToolCallingManager.builder()
				.build()
				.executeToolCalls(withoutContext, new ChatResponse(List.of(new Generation(pausedReply()))),
						ToolCallDecisions.of(ToolCallDecision.allow("c1"))))
			.withMessage("ToolContext is required by the method as an argument");
		assertThat(this.tools.calls).isEmpty();
	}

	@Test
	void aNewMessageInsteadOfADecisionAnswersThePausedCallsAsUnrecorded() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.lastText(prompt).startsWith("Cancel")
				? call("", "c1", "cancelOrder", "A-1001") : ScriptedChatModel.text("Fine."));
		ChatClient client = client(model);
		ask(client, "Cancel A-1001").blockLast();

		ask(client, "Never mind").blockLast();

		assertThat(this.tools.calls).isEmpty();
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Cancel A-1001",
				"calls: cancelOrder(A-1001)", "results: " + SessionTurnAdvisor.UNANSWERED_TOOL_RESULT,
				"user: Never mind");
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).endsWith("user: Never mind",
				"assistant: Fine.");
	}

	// --- A reply the user stopped ---

	@Test
	void aStoppedReplyLeavesTheQuestionWithoutAnAnswer() throws InterruptedException {
		UnfinishedReplies model = new UnfinishedReplies(ScriptedChatModel.answering("A long answer that goes on"),
				(prompt) -> true);

		List<String> seen = ask(client(model), "Tell me a story").take(2).collectList().block();

		assertThat(seen).containsExactly("A ", "long ");
		assertThat(model.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Tell me a story");
	}

	@Test
	void theTurnAfterAStoppedReplySendsBothQuestionsInARow() throws InterruptedException {
		ScriptedChatModel script = new ScriptedChatModel((prompt) -> ScriptedChatModel.text("An answer that goes on"));
		UnfinishedReplies model = new UnfinishedReplies(script, (prompt) -> prompt.getInstructions().size() == 1);
		ChatClient client = client(model);
		ask(client, "Tell me a story").take(1).blockLast();
		assertThat(model.cancelled.await(5, TimeUnit.SECONDS)).isTrue();

		List<String> text = ask(client, "A short one, please").collectList().block();

		assertThat(String.join("", text)).isEqualTo("An answer that goes on");
		assertThat(Transcript.of(script.lastPrompt())).containsExactly("user: Tell me a story",
				"user: A short one, please");
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Tell me a story",
				"user: A short one, please", "assistant: An answer that goes on");
	}

	@Test
	void aReplyStoppedAfterItsToolRanKeepsTheCallAndItsResult() throws InterruptedException {
		ScriptedChatModel script = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It was shipped on Monday.")
				: ScriptedChatModel.lastText(prompt).startsWith("Where") ? call("", "c1", "orderStatus", "A-1001")
						: ScriptedChatModel.text("Fine."));
		UnfinishedReplies model = new UnfinishedReplies(script, ScriptedChatModel::endsWithToolResult);
		ChatClient client = client(model);

		assertThat(ask(client, "Where is A-1001?").take(1).collectList().block()).containsExactly("It ");
		assertThat(model.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Where is A-1001?",
				"calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"");

		ask(client, "Thanks").blockLast();

		assertThat(Transcript.of(script.lastPrompt())).containsExactly("user: Where is A-1001?",
				"calls: orderStatus(A-1001)", "results: \"A-1001 of alice was shipped\"", "user: Thanks");
	}

	/**
	 * Stopping the reply cancels the stream, and with it the task that runs the tool: its
	 * thread is interrupted. The tool may have done part of its work, and nothing records
	 * its result; the next turn finds the call open and answers it.
	 */
	@Test
	void aReplyStoppedWhileItsToolRunsInterruptsTheToolAndLeavesTheCallUnanswered() throws InterruptedException {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.lastText(prompt).equals("Wait")
				? call("", "c1", "slowLookup", "A-1001") : ScriptedChatModel.text("Fine."));
		SlowTool slow = new SlowTool();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultTools(slow)
			.build();
		Disposable reply = client.prompt().user("Wait").advisors(SessionAdvisors.session(SESSION)).stream().content().subscribe();
		assertThat(slow.running.await(5, TimeUnit.SECONDS)).isTrue();

		reply.dispose();

		assertThat(slow.ended.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(slow.interrupted).isTrue();
		assertThat(Transcript.of(this.sessions.getActiveMessages(SESSION))).containsExactly("user: Wait",
				"calls: slowLookup(A-1001)");
		client.prompt().user("Hello?").advisors(SessionAdvisors.session(SESSION)).stream().content().blockLast();
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Wait", "calls: slowLookup(A-1001)",
				"results: " + SessionTurnAdvisor.UNANSWERED_TOOL_RESULT, "user: Hello?");
	}

	// --- Threads ---

	/**
	 * The loop runs a streamed call's tools on a thread of its own and carries no thread
	 * local over: a tool that took the user from the security context would find none.
	 * The tool context is how the user reaches a tool.
	 */
	@Test
	void inAStreamTheToolsRunWithoutTheCallersSecurityContext() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Done.") : call("", "c1", "whoAmI", ""));
		WhoAmI whoAmI = new WhoAmI();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultTools(whoAmI)
			.build();
		SecurityContextHolder.getContext().setAuthentication(alice());
		try {
			client.prompt().user("Who am I?").advisors(SessionAdvisors.session(SESSION)).call().content();
			client.prompt().user("Who am I?").advisors(SessionAdvisors.session("alice-2")).stream().content().blockLast();
		}
		finally {
			SecurityContextHolder.clearContext();
		}

		assertThat(whoAmI.seen).containsExactly("alice", "nobody");
		assertThat(whoAmI.threads.get(0)).isEqualTo(Thread.currentThread().getName());
		assertThat(whoAmI.threads.get(1)).startsWith("boundedElastic-");
	}

	/**
	 * The session advisors carry the caller's context over to the session calls of a
	 * stream, which run on their scheduler: a session service that checks who writes sees
	 * the signed-in user.
	 */
	@Test
	void inAStreamTheSessionIsWrittenOnAnotherThreadWithTheCallersSecurityContext() {
		List<String> writers = new CopyOnWriteArrayList<>();
		SessionService recording = (SessionService) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { SessionService.class }, (proxy, method, args) -> {
					if (method.getName().startsWith("append")) {
						Authentication user = SecurityContextHolder.getContext().getAuthentication();
						writers.add(((user != null) ? user.getName() : "nobody") + " on "
								+ Thread.currentThread().getName().replaceAll("-\\d+$", ""));
					}
					return method.invoke(this.sessions, args);
				});
		ChatClient client = ChatClient.builder(ScriptedChatModel.answering("Hi"))
			.defaultAdvisors(SessionAdvisors.builder(recording).build())
			.build();
		SecurityContextHolder.getContext().setAuthentication(alice());
		try {
			client.prompt().user("Hello").advisors(SessionAdvisors.session(SESSION)).stream().content().blockLast();
		}
		finally {
			SecurityContextHolder.clearContext();
		}

		assertThat(writers).containsExactly("alice on boundedElastic", "alice on boundedElastic");
	}

	private ChatClient client(ChatModel model) {
		return ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultTools(this.tools)
			.build();
	}

	private static Flux<String> ask(ChatClient client, String question) {
		return client.prompt()
			.user(question)
			.advisors(SessionAdvisors.session(SESSION))
			.toolContext(ALICE)
			.stream()
			.content();
	}

	/**
	 * Resumes the paused turn as an application that keeps the conversation in the
	 * session does: the paused reply from the session, never from the client; the calls
	 * run with the decisions, the tools and the tool context; and only their results go
	 * out as the next request, since the session has the rest.
	 */
	private Flux<String> resume(ChatClient client, ToolCallDecisions decisions) {
		AssistantMessage paused = pausedReply();
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(ToolCallbacks.from(this.tools))
			.toolContext(ALICE)
			.build();
		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(new Prompt(List.of(), options), new ChatResponse(List.of(new Generation(paused))),
					decisions);
		List<Message> history = result.conversationHistory();
		return client.prompt()
			.messages(history.get(history.size() - 1))
			.advisors(SessionAdvisors.session(SESSION))
			.toolContext(ALICE)
			.stream()
			.content();
	}

	private AssistantMessage pausedReply() {
		List<Message> messages = this.sessions.getActiveMessages(SESSION);
		return (AssistantMessage) messages.get(messages.size() - 1);
	}

	private static AssistantMessage call(String id, String tool, String orderId) {
		return call("", id, tool, orderId);
	}

	private static AssistantMessage call(String text, String id, String tool, String orderId) {
		return AssistantMessage.builder()
			.content(text)
			.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool,
					"{\"orderId\":\"" + orderId + "\"}")))
			.build();
	}

	private static Authentication alice() {
		return UsernamePasswordAuthenticationToken.authenticated("alice", "alice", List.of());
	}

	/**
	 * A tool that says who the security context holds, and on which thread it ran.
	 */
	static class WhoAmI {

		final List<String> seen = new CopyOnWriteArrayList<>();

		final List<String> threads = new CopyOnWriteArrayList<>();

		@Tool(description = "Tells who is signed in")
		String whoAmI() {
			Authentication user = SecurityContextHolder.getContext().getAuthentication();
			this.seen.add((user != null) ? user.getName() : "nobody");
			this.threads.add(Thread.currentThread().getName());
			return "someone";
		}

	}

	/**
	 * A tool that runs until it is interrupted.
	 */
	static class SlowTool {

		final CountDownLatch running = new CountDownLatch(1);

		final CountDownLatch ended = new CountDownLatch(1);

		volatile boolean interrupted;

		@Tool(description = "Looks an order up, slowly")
		String slowLookup(String orderId) {
			this.running.countDown();
			try {
				// Only the interrupt ends it; the time is a safety net for a broken test
				new CountDownLatch(1).await(10, TimeUnit.SECONDS);
				return "found " + orderId;
			}
			catch (InterruptedException ex) {
				this.interrupted = true;
				Thread.currentThread().interrupt();
				return "interrupted";
			}
			finally {
				this.ended.countDown();
			}
		}

	}

}
