package io.github.hjosugi.chatsample.providers.openai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI-compatible endpoints pages and the message parts of the OpenAI chat page:
 * extra body parameters, the reasoning content of compatible servers and its replay,
 * the extra fields of tool calls, and the base URLs of Groq, NVIDIA, Perplexity and the
 * Docker Model Runner.
 */
class OpenAiCompatibleTests {

	private static final String REASONED = "{\"role\":\"assistant\",\"reasoning_content\":\"9.8 has more tenths.\","
			+ "\"content\":\"9.8\"}";

	@Test
	void extraBodyParametersAreFlattenedIntoTheTopLevelOfTheRequest() {
		try (StandIn vllm = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Once")))) {
			Gpt.chatModel(vllm)
				.call(new Prompt("Tell me a story", OpenAiChatOptions.builder()
					.model("meta-llama/Llama-3-8B-Instruct")
					.temperature(0.7)
					.extraBody(Map.of("top_k", 50, "repetition_penalty", 1.1))
					.build()));

			JsonNode body = vllm.single().body();
			assertThat(body.get("top_k").asInt()).isEqualTo(50);
			assertThat(body.get("repetition_penalty").asDouble()).isEqualTo(1.1);
			assertThat(body.has("extra_body")).isFalse();
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void reasoningContentBecomesAReasoningPartBeforeTheText() {
		try (StandIn deepseek = new StandIn((request) -> Gpt.completion(request, "stop",
				(request.body().get("messages").get(0).get("content").asString().contains("plain")) ? Gpt.assistant("Hi")
						: REASONED))) {
			OpenAiChatModel model = Gpt.chatModel(deepseek);

			AssistantMessage reasoned = model.call(new Prompt("Which is larger: 9.11 or 9.8?")).getResult().getOutput();
			AssistantMessage plain = model.call(new Prompt("plain")).getResult().getOutput();

			assertThat(reasoned.getParts()).containsExactly(ReasoningPart.of("9.8 has more tenths."), TextPart.of("9.8"));
			assertThat(reasoned.getReasoning()).extracting(ReasoningPart::text).containsExactly("9.8 has more tenths.");
			assertThat(reasoned.getText()).isEqualTo("9.8");
			assertThat(reasoned.getMetadata()).containsEntry("reasoningContent", "9.8 has more tenths.");
			assertThat(plain.getReasoning()).isEmpty();
			assertThat(plain.getMetadata()).containsEntry("reasoningContent", "");
		}
	}

	@Test
	void streamedReasoningArrivesAsPartialPartsAndAggregatesToOneReasoningPartAndOneTextPart() {
		try (StandIn deepseek = new StandIn((request) -> Gpt.completion(request, "stop", REASONED))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			List<ChatResponse> chunks = new MessageAggregator()
				.aggregate(Gpt.chatModel(deepseek).stream(new Prompt("Which is larger?")), aggregated::set)
				.collectList()
				.block();

			List<MessagePart> reasoningDeltas = chunks.stream()
				.filter((chunk) -> chunk.getResult() != null)
				.flatMap((chunk) -> chunk.getResult().getOutput().getParts().stream())
				.filter(ReasoningPart.class::isInstance)
				.toList();
			assertThat(reasoningDeltas).hasSize(2).allSatisfy((part) -> assertThat(StreamingParts.isPartial(part)).isTrue());
			assertThat(chunks.stream()
				.filter((chunk) -> chunk.getResult() != null && !chunk.getResult().getOutput().getReasoning().isEmpty())
				.map((chunk) -> chunk.getResult().getOutput().getMetadata().get("reasoningContent"))
				.toList()).as("the deprecated key holds the reasoning so far, not the delta")
				.containsExactly("9.8 has mo", "9.8 has more tenths.");
			assertThat(aggregated.get().getResult().getOutput().getParts())
				.containsExactly(ReasoningPart.of("9.8 has more tenths."), TextPart.of("9.8"));
		}
	}

	@Test
	void reasoningIsReplayedAsReasoningContentUnlessTurnedOffOrSignedByAnotherProvider() {
		AssistantMessage unsigned = AssistantMessage.builder()
			.part(ReasoningPart.of("Thinking."))
			.part(TextPart.of("9.8"))
			.build();
		AssistantMessage signed = AssistantMessage.builder()
			.part(new ReasoningPart("Thinking.", null, new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
			.part(TextPart.of("9.8"))
			.build();
		try (StandIn deepseek = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Sure")))) {
			OpenAiChatModel model = Gpt.chatModel(deepseek);
			List<UserMessage> question = List.of(new UserMessage("Which is larger?"), new UserMessage("Why?"));

			model.call(new Prompt(List.of(question.get(0), unsigned, question.get(1)), OpenAiChatOptions.builder().build()));
			model.call(new Prompt(List.of(question.get(0), unsigned, question.get(1)),
					OpenAiChatOptions.builder().replayReasoningContent(false).build()));
			model.call(new Prompt(List.of(question.get(0), signed, question.get(1)), OpenAiChatOptions.builder().build()));

			assertThat(deepseek.requests).extracting((request) -> request.body().get("messages").get(1).toString())
				.containsExactly("{\"role\":\"assistant\",\"content\":\"9.8\",\"reasoning_content\":\"Thinking.\"}",
						"{\"role\":\"assistant\",\"content\":\"9.8\"}", "{\"role\":\"assistant\",\"content\":\"9.8\"}");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void theExtraFieldsOfAToolCallAreKeptInItsPayloadAndReplayedInACallAndInAStream() {
		String geminiToolCall = "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"weather\","
				+ "\"arguments\":\"{\\\"location\\\":\\\"Seoul\\\"}\"},\"extra_content\":{\"google\":{\"thought_signature\":\"sig-1\"}}}";
		try (StandIn gemini = new StandIn((request) -> "tool".equals(Gpt.lastMessage(request).get("role").asString())
				? Gpt.completion(request, "stop", Gpt.assistant("Sunny"))
				: Gpt.completion(request, "tool_calls", Gpt.toolCalls(geminiToolCall)))) {
			OpenAiChatModel model = Gpt.chatModel(gemini);

			AssistantMessage message = model.call(new Prompt("Weather in Seoul?")).getResult().getOutput();
			ChatClient.create(model).prompt("Weather in Seoul?").tools(new Weather()).call().content();
			ChatClient.create(model).prompt("Weather in Seoul?").tools(new Weather()).stream().content().collectList().block();

			assertThat(message.getParts()).singleElement().isInstanceOfSatisfying(ToolCallPart.class, (part) -> {
				assertThat(part.payload().provider()).isEqualTo(OpenAiChatModel.CHAT_COMPLETIONS_PROVIDER);
				assertThat(part.payload().kind()).isEqualTo(OpenAiChatModel.PAYLOAD_TOOL_CALL_ADDITIONAL_PROPERTIES);
				assertThat(part.payload().data()).isEqualTo("{\"extra_content\":{\"google\":{\"thought_signature\":\"sig-1\"}}}");
			});
			assertThat((Map<String, String>) message.getMetadata().get("openai.tool_calls.additional_properties"))
				.containsKey("call_1");
			for (Request second : List.of(gemini.requests.get(2), gemini.requests.get(4))) {
				JsonNode toolCall = second.body().get("messages").get(1).get("tool_calls").get(0);
				assertThat(toolCall.get("extra_content").toString()).isEqualTo("{\"google\":{\"thought_signature\":\"sig-1\"}}");
			}
		}
	}

	/**
	 * The base URL is passed to the SDK as it is: Groq, NVIDIA and the Docker Model Runner
	 * need their {@code /v1} path in it, Perplexity has none.
	 */
	@Test
	void compatibleServicesAreReachedThroughTheBaseUrlAsItIs() {
		try (StandIn services = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			for (String path : List.of("/openai/v1", "/v1", "/engines/v1", "")) {
				OpenAiChatModel.builder()
					.options(OpenAiChatOptions.builder()
						.baseUrl(services.url() + path)
						.apiKey("test-key")
						.model("llama-3.1-8b-instant")
						.maxRetries(0)
						.build())
					.build()
					.call(new Prompt("Hello"));
			}

			assertThat(services.requests).extracting(Request::path)
				.containsExactly("/openai/v1/chat/completions", "/v1/chat/completions", "/engines/v1/chat/completions",
						"/chat/completions");
		}
	}

	static class Weather {

		@Tool(description = "Tells the weather")
		String weather(String location) {
			return "sunny";
		}

	}

}
