package io.github.hjosugi.chatsample.chatclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.Model;
import org.springframework.ai.model.ModelOptions;
import org.springframework.ai.model.ModelRequest;
import org.springframework.ai.model.ModelResponse;
import org.springframework.ai.model.ModelResult;
import org.springframework.ai.model.ResponseMetadata;
import org.springframework.ai.model.ResultMetadata;
import org.springframework.ai.model.StreamingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * The {@code ChatModel} API below the {@code ChatClient}, as the pages "Chat Model API"
 * and "Generic Model API" show it: the interfaces, the options of a direct call, the
 * roles, the parts of a message, the payloads that only their provider reads, and the
 * parts of a stream.
 */
class ChatModelApiTests {

	@Test
	void theChatApiIsBuiltOnTheGenericModelApi() {
		assertThat(Model.class).isAssignableFrom(ChatModel.class);
		assertThat(StreamingChatModel.class).isAssignableFrom(ChatModel.class);
		assertThat(StreamingModel.class).isAssignableFrom(StreamingChatModel.class);
		assertThat(ModelRequest.class).isAssignableFrom(Prompt.class);
		assertThat(ModelOptions.class).isAssignableFrom(ChatOptions.class);
		assertThat(ModelResponse.class).isAssignableFrom(ChatResponse.class);
		assertThat(ModelResult.class).isAssignableFrom(Generation.class);
		assertThat(ResponseMetadata.class)
			.isAssignableFrom(ChatResponseMetadata.class);
		assertThat(ResultMetadata.class).isAssignableFrom(ChatGenerationMetadata.class);
	}

	@Test
	void aChatModelIsUsedThroughTheGenericInterfaces() {
		Model<Prompt, ChatResponse> model = ScriptedChatModel.answering("Two words");
		ModelRequest<List<Message>> request = new Prompt("Hi", ChatOptions.builder().model("m").build());

		ModelResponse<Generation> response = model.call((Prompt) request);
		ModelResult<AssistantMessage> result = response.getResult();

		assertThat(request.getInstructions()).containsExactly(new UserMessage("Hi"));
		assertThat(((ChatOptions) request.getOptions()).getModel()).isEqualTo("m");
		assertThat(response.getResults()).containsExactly(response.getResult());
		assertThat(result.getOutput().getText()).isEqualTo("Two words");
		assertThat(result.getMetadata()).isInstanceOf(ChatGenerationMetadata.class);
		assertThat(response.getMetadata()).isNotNull();
	}

	/**
	 * Unlike {@code ChatClient}'s {@code stream().content()}, {@code stream(String)}
	 * passes on the empty text of the last chunk, the one with the finish reason.
	 */
	@Test
	void callAndStreamTakeAString() {
		ScriptedChatModel model = ScriptedChatModel.answering("Two words");

		assertThat(model.call("Hi")).isEqualTo("Two words");
		assertThat(model.stream("Hi").collectList().block()).containsExactly("Two ", "words", "");
	}

	@Test
	void theRolesOfTheMessagesBecomeTheRolesOfOpenAi() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("ok")))) {
			List<Message> messages = List.of(new SystemMessage("Be brief"), new UserMessage("What time is it?"),
					AssistantMessage.builder()
						.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "time", "{}")))
						.build(),
					ToolResponseMessage.builder()
						.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "time", "noon")))
						.build());

			Providers.openAi(openAi).call(new Prompt(messages));

			assertThat(openAi.single().body().get("messages")).extracting((message) -> message.get("role").asString())
				.containsExactly("system", "user", "assistant", "tool");
		}
	}

	@Test
	void aPromptWithoutOptionsUsesTheModelsDefaults() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("ok")))) {
			OpenAiChatModel model = Providers.openAi(openAi, ObservationRegistry.NOOP,
					(options) -> options.temperature(0.3).maxCompletionTokens(500));

			model.call(new Prompt("Hi"));

			JsonNode request = openAi.single().body();
			assertThat(request.get("model").asString()).isEqualTo(Providers.OPENAI_MODEL);
			assertThat(request.get("temperature").asDouble()).isEqualTo(0.3);
			assertThat(request.get("max_completion_tokens").asInt()).isEqualTo(500);
		}
	}

	@Test
	void theOptionsOfAPromptReplaceTheModelsDefaultsOnADirectCall() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("ok")))) {
			OpenAiChatModel model = Providers.openAi(openAi, ObservationRegistry.NOOP,
					(options) -> options.temperature(0.3).maxCompletionTokens(500));

			model.call(new Prompt("Hi", OpenAiChatOptions.builder().temperature(0.9).build()));

			JsonNode request = openAi.single().body();
			assertThat(request.get("temperature").asDouble()).isEqualTo(0.9);
			assertThat(request.has("max_completion_tokens")).isFalse();
			// Not the model's default either: the default of OpenAiChatOptions.
			assertThat(request.get("model").asString()).isEqualTo(OpenAiChatOptions.DEFAULT_CHAT_MODEL);
		}
	}

	@Test
	void startingFromTheModelsOptionsKeepsTheOtherDefaults() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("ok")))) {
			OpenAiChatModel model = Providers.openAi(openAi, ObservationRegistry.NOOP,
					(options) -> options.temperature(0.3).maxCompletionTokens(500));

			model.call(new Prompt("Hi", model.getOptions().mutate().model("gpt-5.4").build()));

			JsonNode request = openAi.single().body();
			assertThat(request.get("model").asString()).isEqualTo("gpt-5.4");
			assertThat(request.get("temperature").asDouble()).isEqualTo(0.3);
			assertThat(request.get("max_completion_tokens").asInt()).isEqualTo(500);
		}
	}

	/**
	 * The page asks for the options type of the model on a direct call. OpenAI fails
	 * with portable options; Anthropic ignores them, without a word.
	 */
	@Test
	void portableOptionsOnADirectCallFailAtOpenAiAndAreIgnoredAtAnthropic() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("ok")));
				StandIn anthropic = new StandIn((request) -> Reply.json(Providers.anthropicText("ok")))) {
			Prompt portable = new Prompt("Hi", ChatOptions.builder().model("claude-big").maxTokens(77).build());

			assertThatRuntimeException().isThrownBy(() -> Providers.openAi(openAi).call(portable));
			Providers.anthropic(anthropic).call(portable);

			assertThat(openAi.requests).isEmpty();
			JsonNode request = anthropic.single().body();
			assertThat(request.get("model").asString()).isNotEqualTo("claude-big");
			assertThat(request.get("max_tokens").asInt()).isNotEqualTo(77);
		}
	}

	@Test
	void theTextAndTheMediaAreViewsOverTheParts() {
		UserMessage question = UserMessage.builder()
			.part(TextPart.of("What changed between these two charts?"))
			.part(MediaPart.of(png("first")))
			.part(TextPart.of(" Answer briefly."))
			.part(MediaPart.of(png("second")))
			.build();

		assertThat(question.getParts()).hasSize(4);
		assertThat(question.getText()).isEqualTo("What changed between these two charts? Answer briefly.");
		assertThat(question.getMedia()).extracting((media) -> media.getName()).containsExactly("first", "second");
	}

	@Test
	void theToolCallsAndTheReasoningOfAnAnswerAreViewsOverItsParts() {
		AssistantMessage answer = AssistantMessage.builder()
			.part(ReasoningPart.of("The user wants the time."))
			.part(TextPart.of("Let me look."))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "time", "{}")))
			.part(StreamingParts.partial(
					ToolCallPart.of(new AssistantMessage.ToolCall("call_2", "function", "date", "{\"da")), 3))
			.build();

		assertThat(answer.getReasoning()).extracting(ReasoningPart::text).containsExactly("The user wants the time.");
		assertThat(answer.getText()).isEqualTo("Let me look.");
		assertThat(answer.getToolCalls()).extracting(AssistantMessage.ToolCall::name).containsExactly("time");
	}

	@Test
	void aMessageWithPartsButNoTextPartHasAnEmptyText() {
		AssistantMessage answer = AssistantMessage.builder()
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "time", "{}")))
			.build();

		assertThat(answer.getText()).isEmpty();
	}

	@Test
	void theLegacyBuildersPutTheTextFirstThenTheToolCallsThenTheMedia() {
		AssistantMessage answer = AssistantMessage.builder()
			.media(List.of(png("chart")))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "time", "{}")))
			.content("Here")
			.build();

		assertThat(answer.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("TextPart", "ToolCallPart", "MediaPart");
	}

	@Test
	void aReasoningPayloadOfAnthropicGoesBackToAnthropicUnchanged() {
		String thinking = Providers.anthropicMessage(
				"[{\"type\":\"thinking\",\"thinking\":\"The user wants the time.\",\"signature\":\"sig-123\"},"
						+ "{\"type\":\"text\",\"text\":\"It is noon.\"}]",
				"end_turn", "{\"input_tokens\":11,\"output_tokens\":7}");
		try (StandIn anthropic = new StandIn((request) -> Reply.json(thinking))) {
			var model = Providers.anthropic(anthropic);
			AssistantMessage answer = model.call(new Prompt("What time is it?")).getResult().getOutput();

			model.call(new Prompt(List.of(new UserMessage("What time is it?"), answer, new UserMessage("And now?"))));

			ReasoningPart reasoning = answer.getReasoning().get(0);
			assertThat(reasoning.text()).isEqualTo("The user wants the time.");
			assertThat(reasoning.payload().provider()).isEqualTo("anthropic");
			assertThat(reasoning.payload().data()).isEqualTo("sig-123");
			JsonNode replayed = anthropic.last().body().get("messages").get(1).get("content").get(0);
			assertThat(replayed.get("type").asString()).isEqualTo("thinking");
			assertThat(replayed.get("signature").asString()).isEqualTo("sig-123");
			assertThat(replayed.get("thinking").asString()).isEqualTo("The user wants the time.");
		}
	}

	@Test
	void aReasoningPayloadOfAnthropicIsSkippedByOpenAi() {
		String thinking = Providers.anthropicMessage(
				"[{\"type\":\"thinking\",\"thinking\":\"The user wants the time.\",\"signature\":\"sig-123\"},"
						+ "{\"type\":\"text\",\"text\":\"It is noon.\"}]",
				"end_turn", "{\"input_tokens\":11,\"output_tokens\":7}");
		try (StandIn anthropic = new StandIn((request) -> Reply.json(thinking));
				StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("Still noon")))) {
			AssistantMessage answer = Providers.anthropic(anthropic)
				.call(new Prompt("What time is it?"))
				.getResult()
				.getOutput();

			Providers.openAi(openAi)
				.call(new Prompt(List.of(new UserMessage("What time is it?"), answer, new UserMessage("And now?"))));

			String request = openAi.single().text();
			assertThat(request).contains("It is noon.").doesNotContain("sig-123").doesNotContain("The user wants the time.");
		}
	}

	@Test
	void aStreamCarriesIndexedPartsThatTheAggregatorRebuildsIntoTheBlockingAnswer() {
		List<String> events = new ArrayList<>();
		events.add(StandIn.event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
				+ "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"" + Providers.ANTHROPIC_MODEL
				+ "\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":11,\"output_tokens\":1}}}"));
		events.add(StandIn.event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
				+ "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
		events.add(textDelta("Let me "));
		events.add(textDelta("look."));
		events.add(StandIn.event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
		events.add(StandIn.event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,"
				+ "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"orderStatus\",\"input\":{}}}"));
		events.add(jsonDelta("{\"orderId\": "));
		events.add(jsonDelta("\"A-1001\"}"));
		events.add(StandIn.event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"));
		events.add(StandIn.event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
				+ "\"stop_sequence\":null},\"usage\":{\"output_tokens\":15}}"));
		events.add(StandIn.event("message_stop", "{\"type\":\"message_stop\"}"));
		String blocking = Providers.anthropicMessage("[{\"type\":\"text\",\"text\":\"Let me look.\"},"
				+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"orderStatus\",\"input\":{\"orderId\":\"A-1001\"}}]",
				"tool_use", "{\"input_tokens\":11,\"output_tokens\":15}");
		try (StandIn anthropic = new StandIn((request) -> request.body().path("stream").asBoolean(false)
				? Reply.events(events) : Reply.json(blocking))) {
			var model = Providers.anthropic(anthropic);
			AtomicReference<ChatResponse> aggregate = new AtomicReference<>();

			List<ChatResponse> chunks = new MessageAggregator()
				.aggregate(model.stream(new Prompt("Where is A-1001?")), aggregate::set)
				.collectList()
				.block();
			AssistantMessage blockingAnswer = model.call(new Prompt("Where is A-1001?")).getResult().getOutput();

			List<MessagePart> streamedParts = chunks.stream()
				.filter((chunk) -> chunk.getResult() != null)
				.flatMap((chunk) -> chunk.getResult().getOutput().getParts().stream())
				.toList();
			// The last chunk, with the stop reason, has an empty text part without an index.
			assertThat(streamedParts).filteredOn((part) -> !(part instanceof TextPart text) || !text.text().isEmpty())
				.allSatisfy((part) -> assertThat(StreamingParts.partIndex(part)).isNotNull());
			assertThat(streamedParts).filteredOn((part) -> part instanceof ToolCallPart && StreamingParts.isPartial(part))
				.isNotEmpty()
				.allSatisfy((part) -> assertThat(StreamingParts.partIndex(part)).isEqualTo(1));
			AssistantMessage aggregated = aggregate.get().getResult().getOutput();
			assertThat(aggregated.getText()).isEqualTo("Let me look.");
			assertThat(aggregated.getToolCalls()).singleElement()
				.satisfies((call) -> assertThat(call.arguments()).isEqualToIgnoringWhitespace("{\"orderId\":\"A-1001\"}"));
			assertThat(aggregated.getParts()).allSatisfy((part) -> assertThat(part.attributes())
				.doesNotContainKeys(StreamingParts.PART_INDEX_ATTRIBUTE, StreamingParts.PARTIAL_ATTRIBUTE));
			assertThat(normalized(aggregated.getParts())).isEqualTo(normalized(blockingAnswer.getParts()));
		}
	}

	private static String textDelta(String text) {
		return StandIn.event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
				+ "\"delta\":{\"type\":\"text_delta\",\"text\":" + Providers.quote(text) + "}}");
	}

	private static String jsonDelta(String json) {
		return StandIn.event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,"
				+ "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + Providers.quote(json) + "}}");
	}

	/**
	 * The parts with the arguments of a tool call without their whitespace, which the
	 * stand-in's two replies write differently.
	 */
	private static List<Object> normalized(List<MessagePart> parts) {
		return parts.stream().map((part) -> (part instanceof ToolCallPart call)
				? Map.of("toolCall", call.toolCall().name() + call.toolCall().arguments().replaceAll("\\s", ""))
				: (Object) part).toList();
	}

	/**
	 * A tiny image that a test can tell apart by its name.
	 */
	static Media png(String name) {
		return Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1, 2, 3 }).name(name).build();
	}

}
