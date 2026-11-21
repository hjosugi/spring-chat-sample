package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ContentFilteredException;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * How the chat models of OpenAI, Gemini and Anthropic classify why their provider
 * stopped, including the stops that the finish reason alone does not tell, as the
 * section "Finish Reason Categories" of the page "Responses and Streaming" describes it.
 */
class ProviderFinishReasonTests {

	private static final String OPENAI_REFUSAL = Providers.openAiCompletion(
			"{\"role\":\"assistant\",\"content\":null,\"refusal\":\"I can't help with that.\"}", "stop",
			"{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}");

	@Test
	void aRefusalOfOpenAiWithAnOrdinaryStopIsFiltered() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_REFUSAL))) {
			ChatResponse response = Providers.openAi(openAi).call(new Prompt("Generate a filmography"));

			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
			assertThat(response.getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.FILTERED);
			assertThat(response.getResult().getOutput().getMetadata()).containsEntry("refusal",
					"I can't help with that.");
		}
	}

	@Test
	void entityThrowsForARefusalOfOpenAi() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_REFUSAL))) {
			ChatClient client = ChatClient.create(Providers.openAi(openAi));

			assertThatExceptionOfType(ContentFilteredException.class)
				.isThrownBy(() -> client.prompt("Generate a filmography").call().entity(ActorFilms.class))
				.satisfies((ex) -> assertThat(ex.getFinishReason()).isEqualTo("STOP"));
		}
	}

	/**
	 * The page says that the raw finish reason is the name the provider uses, such as
	 * {@code content_filter}. OpenAI's chat model reports the name of the SDK's constant
	 * instead: {@code CONTENT_FILTER}, {@code STOP}.
	 */
	@Test
	void aContentFilterOfOpenAiIsFilteredUnderTheNameOfTheSdkConstant() {
		String filtered = Providers.openAiCompletion("{\"role\":\"assistant\",\"content\":\"\",\"refusal\":null}",
				"content_filter", "{\"prompt_tokens\":11,\"completion_tokens\":0,\"total_tokens\":11}");
		try (StandIn openAi = new StandIn((request) -> Reply.json(filtered))) {
			ChatResponse response = Providers.openAi(openAi).call(new Prompt("Hi"));

			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("CONTENT_FILTER");
			assertThat(response.getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.FILTERED);
		}
	}

	@Test
	void aToolCallOfGeminiWithAnOrdinaryStopIsAToolCall() {
		String toolCall = Providers.geminiResponse("[{\"functionCall\":{\"name\":\"time\",\"args\":{}}}]", "STOP",
				"{\"promptTokenCount\":11,\"candidatesTokenCount\":7,\"totalTokenCount\":18}");
		try (StandIn gemini = new StandIn((request) -> Reply.json(toolCall))) {
			ChatResponse response = Providers.gemini(gemini).call(new Prompt("What time is it?"));

			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualToIgnoringCase("stop");
			assertThat(response.getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.TOOL_CALL);
			assertThat(response.getResult().getOutput().getToolCalls()).extracting((call) -> call.name())
				.containsExactly("time");
		}
	}

	@Test
	void aSafetyStopOfGeminiIsFiltered() {
		String blocked = Providers.geminiResponse("[{\"text\":\"\"}]", "SAFETY",
				"{\"promptTokenCount\":11,\"candidatesTokenCount\":0,\"totalTokenCount\":11}");
		try (StandIn gemini = new StandIn((request) -> Reply.json(blocked))) {
			ChatResponse response = Providers.gemini(gemini).call(new Prompt("Hi"));

			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualToIgnoringCase("safety");
			assertThat(response.getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.FILTERED);
		}
	}

	@Test
	void aRefusalOfAnthropicIsFiltered() {
		String refusal = Providers.anthropicMessage("[{\"type\":\"text\",\"text\":\"I can't\"}]", "refusal",
				"{\"input_tokens\":11,\"output_tokens\":2}");
		try (StandIn anthropic = new StandIn((request) -> Reply.json(refusal))) {
			ChatResponse response = Providers.anthropic(anthropic).call(new Prompt("Hi"));

			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("refusal");
			assertThat(response.getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.FILTERED);
		}
	}

	/**
	 * The page promises that {@code entity()} throws a {@code ContentFilteredException}
	 * for a filtered answer. A message of Anthropic without content blocks becomes a
	 * response without any generation: the stop reason, its category and the usage are
	 * gone, and {@code entity()} returns {@code null}.
	 */
	@Test
	void anEmptyRefusalOfAnthropicLosesItsStopReasonAndEntityReturnsNull() {
		String refusal = Providers.anthropicMessage("[]", "refusal", "{\"input_tokens\":11,\"output_tokens\":0}");
		try (StandIn anthropic = new StandIn((request) -> Reply.json(refusal))) {
			ChatResponse response = Providers.anthropic(anthropic).call(new Prompt("Hi"));
			ActorFilms films = ChatClient.create(Providers.anthropic(anthropic))
				.prompt("Generate a filmography")
				.call()
				.entity(ActorFilms.class);

			assertThat(response.getResults()).isEmpty();
			assertThat(response.hasFinishReasonCategory(FinishReasonCategory.FILTERED)).isFalse();
			assertThat(response.getMetadata().getUsage().getTotalTokens()).isZero();
			assertThat(films).isNull();
		}
	}

	@Test
	void aTruncatedAnswerOfAnthropicIsTruncated() {
		String truncated = Providers.anthropicMessage("[{\"type\":\"text\",\"text\":\"Once upon\"}]", "max_tokens",
				"{\"input_tokens\":11,\"output_tokens\":2}");
		try (StandIn anthropic = new StandIn((request) -> Reply.json(truncated))) {
			ChatResponse response = Providers.anthropic(anthropic).call(new Prompt("Tell me a story"));

			assertThat(response.getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.TRUNCATED);
		}
	}

	/**
	 * A refusal that OpenAI streams over several chunks ends with an ordinary stop: the
	 * last chunk, and the aggregated response, are not filtered.
	 */
	@Test
	void aStreamedRefusalOfOpenAiIsNotTheCategoryOfTheLastChunkNorOfTheAggregate() {
		try (StandIn openAi = new StandIn((request) -> Reply.events(List.of(
				StandIn.data(openAiChunk("{\"role\":\"assistant\",\"content\":null,\"refusal\":\"\"}", null)),
				StandIn.data(openAiChunk("{\"refusal\":\"I can't \"}", null)),
				StandIn.data(openAiChunk("{\"refusal\":\"help with that.\"}", null)),
				StandIn.data(openAiChunk("{}", "stop")), StandIn.data("[DONE]"))))) {
			AtomicReference<ChatResponse> aggregate = new AtomicReference<>();

			List<ChatResponse> chunks = new MessageAggregator()
				.aggregate(Providers.openAi(openAi).stream(new Prompt("Hi")), aggregate::set)
				.collectList()
				.block();

			assertThat(chunks).isNotEmpty();
			assertThat(chunks).filteredOn((chunk) -> chunk.getResult() != null)
				.extracting((chunk) -> chunk.getResult().getMetadata().getFinishReasonCategory())
				.contains(FinishReasonCategory.FILTERED)
				.last()
				.isNotEqualTo(FinishReasonCategory.FILTERED);
			assertThat(aggregate.get().getResult().getMetadata().getFinishReasonCategory())
				.isNotEqualTo(FinishReasonCategory.FILTERED);
		}
	}

	/**
	 * Gemini streams the tool call in one chunk and the finish reason in the next: the
	 * chunk with the call is a tool call, the last one is not.
	 */
	@Test
	void aStreamedToolCallOfGeminiIsNotTheCategoryOfTheLastChunk() {
		String usage = "\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":7,\"totalTokenCount\":18}";
		try (StandIn gemini = new StandIn((request) -> Reply.events(List.of(
				StandIn.data("{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"time\",\"args\":{}}}],"
						+ "\"role\":\"model\"},\"index\":0}]," + usage + "}"),
				StandIn.data("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}],\"role\":\"model\"},"
						+ "\"finishReason\":\"STOP\",\"index\":0}]," + usage + "}"))))) {
			List<ChatResponse> chunks = Providers.gemini(gemini).stream(new Prompt("What time is it?")).collectList().block();

			assertThat(chunks).extracting((chunk) -> chunk.getResult().getMetadata().getFinishReasonCategory())
				.containsExactly(FinishReasonCategory.TOOL_CALL, FinishReasonCategory.COMPLETED);
			assertThat(chunks.get(0).getResult().getOutput().getToolCalls()).hasSize(1);
		}
	}

	static String openAiChunk(String delta, String finishReason) {
		return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\""
				+ Providers.OPENAI_MODEL + "\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ ((finishReason != null) ? "\"" + finishReason + "\"" : "null") + "}]}";
	}

	record ActorFilms(String actor, List<String> movies) {
	}

}
