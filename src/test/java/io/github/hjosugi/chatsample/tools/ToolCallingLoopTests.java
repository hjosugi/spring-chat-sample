package io.github.hjosugi.chatsample.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.memory.Transcript;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The tool calling loop that the {@code ChatClient} runs, from the overview, the loop
 * page and the tools tutorial: the loop for a call and a stream, a chat model that runs
 * no tools, the loop the application drives itself, and where a memory advisor sits.
 */
class ToolCallingLoopTests {

	@Test
	void theTutorialsToolsRunOneAfterTheOtherUntilTheModelAnswers() {
		DateTimeTools tools = new DateTimeTools();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> switch (prompt.getInstructions().size()) {
			case 1 -> ScriptedChatModel.toolCall("getCurrentDateTime", "{}");
			case 3 -> ScriptedChatModel.toolCall("setAlarm", "{\"time\":\"2026-11-21T10:10:00\"}");
			default -> ScriptedChatModel.text("The alarm is set for 10:10.");
		});

		String answer = ChatClient.create(model)
			.prompt("Can you set an alarm 10 minutes from now?")
			.tools(tools)
			.call()
			.content();

		assertThat(answer).isEqualTo("The alarm is set for 10:10.");
		assertThat(tools.alarms).containsExactly(LocalDateTime.parse("2026-11-21T10:10:00"));
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Can you set an alarm 10 minutes from now?",
				"calls: getCurrentDateTime({})", "results: \"2026-11-21T10:00\"", "calls: setAlarm(2026-11-21T10:10:00)",
				"results: \"Done\"");
		// Every round offers the tools
		assertThat(model.prompts).allSatisfy(
				(prompt) -> assertThat(OfferedTools.names(prompt)).containsExactlyInAnyOrder("getCurrentDateTime",
						"setAlarm"));
	}

	@Test
	void aStreamRunsTheSameLoopAndPassesOnOnlyTheAnswer() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("getCurrentDateTime", "{}");

		List<ChatResponse> chunks = ChatClient.create(model)
			.prompt("What time is it?")
			.tools(new DateTimeTools())
			.stream()
			.chatResponse()
			.collectList()
			.block();

		assertThat(chunks).allSatisfy((chunk) -> assertThat(chunk.hasToolCalls()).isFalse());
		assertThat(chunks).extracting((chunk) -> chunk.getResult().getOutput().getText())
			.containsExactly("\"2026-11-21T10:00\"", "");
		assertThat(model.prompts).hasSize(2);
	}

	@Test
	void aChatModelCalledDirectlyReturnsTheToolCallsWithoutRunningThem() {
		DateTimeTools tools = new DateTimeTools();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("setAlarm", "{\"time\":\"2026-11-21T10:10:00\"}");

		ChatResponse response = model.call(new Prompt("Set an alarm",
				ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(tools)).build()));

		assertThat(response.hasToolCalls()).isTrue();
		assertThat(tools.alarms).isEmpty();
	}

	@Test
	void aSecondToolAdvisorIsRefused() {
		ChatClient client = ChatClient.builder(ScriptedChatModel.answering("ok"))
			.defaultAdvisors(ToolCallingAdvisor.builder().build(),
					ToolCallingAdvisor.builder().advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE + 500).build())
			.build();

		assertThatIllegalStateException().isThrownBy(() -> client.prompt("hi").call().content())
			.withMessageStartingWith("At most one ToolAdvisor is allowed in the advisor chain, but found 2");
	}

	@Test
	void withoutAutoRegistrationTheApplicationDrivesTheLoop() {
		DateTimeTools tools = new DateTimeTools();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("getCurrentDateTime", "{}");
		ChatClient client = ChatClient.create(model);
		ToolCallingManager manager = ToolCallingManager.builder().build();
		ToolCallback[] callbacks = ToolCallbacks.from(tools);
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
		String question = "What time is it?";

		ChatClientResponse response = client.prompt()
			.user(question)
			.tools((Object[]) callbacks)
			.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
			.call()
			.chatClientResponse();
		// The tools went to the model, the call came back unrun
		assertThat(OfferedTools.names(model.lastPrompt())).contains("getCurrentDateTime");
		assertThat(response.chatResponse().hasToolCalls()).isTrue();
		Prompt prompt = new Prompt(List.of(new UserMessage(question)), options);
		int rounds = 0;
		while (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
			ToolExecutionResult result = manager.executeToolCalls(prompt, response.chatResponse());
			prompt = new Prompt(result.conversationHistory(), options);
			response = client.prompt()
				.messages(result.conversationHistory())
				.tools((Object[]) callbacks)
				.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
				.call()
				.chatClientResponse();
			rounds++;
		}

		assertThat(rounds).isEqualTo(1);
		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("\"2026-11-21T10:00\"");
	}

	@Test
	void withoutAutoRegistrationAStreamIsDrivenRoundByRound() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("getCurrentDateTime", "{}");
		ChatClient client = ChatClient.create(model);
		ToolCallingManager manager = ToolCallingManager.builder().build();
		ToolCallback[] callbacks = ToolCallbacks.from(new DateTimeTools());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
		Prompt prompt = new Prompt(List.of(new UserMessage("What time is it?")), options);
		List<ChatClientResponse> forwarded = new CopyOnWriteArrayList<>();

		ChatClientResponse response = round(client, prompt.getInstructions(), callbacks, forwarded);
		while (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
			ToolExecutionResult result = manager.executeToolCalls(prompt, response.chatResponse());
			prompt = new Prompt(result.conversationHistory(), options);
			response = round(client, result.conversationHistory(), callbacks, forwarded);
		}

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("\"2026-11-21T10:00\"");
		// Unlike the framework's loop, the application saw the chunk with the tool call too
		assertThat(forwarded).anySatisfy((chunk) -> assertThat(chunk.chatResponse().hasToolCalls()).isTrue());
	}

	@Test
	@SuppressWarnings("removal")
	void aMemoryAdvisorOutsideTheLoopStoresOnlyTheQuestionAndTheAnswer() {
		ChatMemory memory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(new InMemoryChatMemoryRepository())
			.build();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("getCurrentDateTime", "{}");
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
			.build();

		client.prompt("What time is it?")
			.tools(new DateTimeTools())
			.advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c1"))
			.call()
			.content();

		assertThat(Transcript.of(memory.get("c1"))).containsExactly("user: What time is it?",
				"assistant: \"2026-11-21T10:00\"");
		// The loop kept the round's history itself
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: What time is it?",
				"calls: getCurrentDateTime({})", "results: \"2026-11-21T10:00\"");
	}

	/**
	 * Inside the loop the chat memory keeps the whole turn, as the page says. Not on the
	 * page: from the second round on, the model gets a blank system message, the loop's
	 * stand-in for a request without one, which the chat memory advisor passes on.
	 */
	@Test
	@SuppressWarnings("removal")
	void aMemoryAdvisorInsideTheLoopStoresTheToolMessagesAndTheModelGetsABlankSystemMessage() {
		ChatMemory memory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(new InMemoryChatMemoryRepository())
			.build();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("getCurrentDateTime", "{}");
		List<List<String>> forwardedToMemory = new CopyOnWriteArrayList<>();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(new Recorder(BaseAdvisor.HIGHEST_PRECEDENCE + 350, forwardedToMemory),
					MessageChatMemoryAdvisor.builder(memory).order(BaseAdvisor.HIGHEST_PRECEDENCE + 400).build())
			.build();

		client.prompt("What time is it?")
			.tools(new DateTimeTools())
			.advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c1"))
			.call()
			.content();

		assertThat(Transcript.of(memory.get("c1"))).containsExactly("user: What time is it?",
				"calls: getCurrentDateTime({})", "results: \"2026-11-21T10:00\"", "assistant: \"2026-11-21T10:00\"");
		// The auto-registered loop turned its own history off: the second round forwards
		// the empty system stand-in and the tool results, and the memory adds the rest
		assertThat(forwardedToMemory.get(1)).containsExactly("system: ", "results: \"2026-11-21T10:00\"");
		// Unlike the session memory advisor, the chat memory advisor passes that blank
		// system message on to the model
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("system: ", "user: What time is it?",
				"calls: getCurrentDateTime({})", "results: \"2026-11-21T10:00\"");
	}

	@Test
	void aToolCallArgumentDeltaReachesTheStreamButNeverRunsNorAggregates() {
		DateTimeTools tools = new DateTimeTools();
		AssistantMessage.ToolCall complete = new AssistantMessage.ToolCall("c1", "function", "setAlarm",
				"{\"time\":\"2026-11-21T10:10:00\"}");
		ChatModel model = new ArgumentStreamingModel(complete);
		List<ToolCallPart> deltas = new CopyOnWriteArrayList<>();

		List<String> text = ChatClient.create(model)
			.prompt("Set an alarm")
			.tools(tools)
			.stream()
			.chatResponse()
			.doOnNext((chunk) -> chunk.getResults()
				.forEach((generation) -> generation.getOutput()
					.getParts()
					.stream()
					.filter((part) -> part instanceof ToolCallPart && StreamingParts.isPartial(part))
					.forEach((part) -> deltas.add((ToolCallPart) part))))
			.map((chunk) -> chunk.getResult().getOutput().getText())
			.collectList()
			.block();

		assertThat(deltas).extracting((part) -> part.toolCall().arguments())
			.containsExactly("{\"time\":", "\"2026-11-21T10:10:00\"}");
		assertThat(deltas).allSatisfy((part) -> assertThat(part.toolCall().name()).isEqualTo("setAlarm"));
		// The complete call ran once; the deltas were not calls
		assertThat(tools.alarms).hasSize(1);
		assertThat(String.join("", text)).isEqualTo("Alarm set.");
		AssistantMessage withDeltasOnly = AssistantMessage.builder()
			.parts(List.of(StreamingParts.partial(ToolCallPart.of(complete), 0)))
			.build();
		assertThat(withDeltasOnly.hasToolCalls()).isFalse();
	}

	private static ChatClientResponse round(ChatClient client, List<org.springframework.ai.chat.messages.Message> messages,
			ToolCallback[] callbacks, List<ChatClientResponse> forwarded) {
		AtomicReference<ChatClientResponse> aggregated = new AtomicReference<>();
		new ChatClientMessageAggregator().aggregateChatClientResponse(client.prompt()
			.messages(messages)
			.tools((Object[]) callbacks)
			.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
			.stream()
			.chatClientResponse()
			.doOnNext(forwarded::add), aggregated::set).blockLast();
		return aggregated.get();
	}

	static class DateTimeTools {

		final List<LocalDateTime> alarms = new CopyOnWriteArrayList<>();

		@Tool(description = "Get the current date and time in the user's timezone")
		String getCurrentDateTime() {
			return "2026-11-21T10:00";
		}

		@Tool(description = "Set a user alarm for the given time, provided in ISO-8601 format")
		void setAlarm(String time) {
			this.alarms.add(LocalDateTime.parse(time, DateTimeFormatter.ISO_DATE_TIME));
		}

	}

	/**
	 * Keeps the messages of every request that passes it, inside the loop.
	 */
	record Recorder(int order, List<List<String>> requests) implements BaseAdvisor {

		@Override
		public org.springframework.ai.chat.client.ChatClientRequest before(
				org.springframework.ai.chat.client.ChatClientRequest request,
				org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) {
			this.requests.add(Transcript.of(request.prompt()));
			return request;
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response,
				org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) {
			return response;
		}

		@Override
		public int getOrder() {
			return this.order;
		}

	}

	/**
	 * Streams a tool call as Anthropic and OpenAI do: two argument deltas, then the
	 * complete call; after the tool ran, a text answer.
	 */
	static final class ArgumentStreamingModel implements ChatModel {

		private final AssistantMessage.ToolCall call;

		ArgumentStreamingModel(AssistantMessage.ToolCall call) {
			this.call = call;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			if (ScriptedChatModel.endsWithToolResult(prompt)) {
				return Flux.just(chunk(AssistantMessage.builder().parts(List.of(TextPart.of("Alarm set."))).build(),
						"stop"));
			}
			AssistantMessage.ToolCall first = new AssistantMessage.ToolCall(this.call.id(), "function",
					this.call.name(), "{\"time\":");
			AssistantMessage.ToolCall second = new AssistantMessage.ToolCall(this.call.id(), "function",
					this.call.name(), "\"2026-11-21T10:10:00\"}");
			return Flux.just(delta(first), delta(second), chunk(AssistantMessage.builder()
				.parts(List.of(StreamingParts.complete(ToolCallPart.of(this.call), 0)))
				.build(), "tool_calls"));
		}

		private static ChatResponse delta(AssistantMessage.ToolCall fragment) {
			return chunk(
					AssistantMessage.builder().parts(List.of(StreamingParts.partial(ToolCallPart.of(fragment), 0))).build(),
					null);
		}

		private static ChatResponse chunk(AssistantMessage message, String finishReason) {
			return new ChatResponse(
					List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())));
		}

	}

}
