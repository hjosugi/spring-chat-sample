package io.github.hjosugi.chatsample.chatclient;

import java.time.Duration;

import com.openai.models.completions.CompletionUsage;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metadata that a response carries about its provider, as the page "Chat Response
 * Metadata" shows it: id, model, rate limits, token usage, prompt cache usage, and the
 * usage summed over a tool loop. What depends on the provider goes through its own
 * client and a stand-in.
 */
class ResponseMetadataTests {

	private static final String OPENAI_USAGE = "{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18,"
			+ "\"prompt_tokens_details\":{\"cached_tokens\":4,\"audio_tokens\":0},"
			+ "\"completion_tokens_details\":{\"reasoning_tokens\":3,\"audio_tokens\":0,"
			+ "\"accepted_prediction_tokens\":0,\"rejected_prediction_tokens\":0}}";

	private static final String OPENAI_ANSWER = Providers.openAiCompletion(
			"{\"role\":\"assistant\",\"content\":\"Sunny\",\"refusal\":null}", "stop", OPENAI_USAGE);

	@Test
	void theMetadataOfAnOpenAiResponseTellsItsIdModelAndUsage() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_ANSWER))) {
			ChatResponse response = Providers.openAi(openAi).call(new Prompt("What is the weather like today?"));

			ChatResponseMetadata metadata = response.getMetadata();
			assertThat(metadata.getId()).isEqualTo("chatcmpl-1");
			assertThat(metadata.getModel()).isEqualTo(Providers.OPENAI_MODEL + "-2026-01-01");
			assertThat(metadata.getPromptMetadata()).isNotNull();
			Usage usage = metadata.getUsage();
			assertThat(usage.getPromptTokens()).isEqualTo(11);
			assertThat(usage.getCompletionTokens()).isEqualTo(7);
			assertThat(usage.getTotalTokens()).isEqualTo(18);
		}
	}

	@Test
	void theNativeUsageOfOpenAiHasTheDetailedTokens() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_ANSWER))) {
			Usage usage = Providers.openAi(openAi).call(new Prompt("Hi")).getMetadata().getUsage();

			assertThat(usage.getNativeUsage()).isInstanceOfSatisfying(CompletionUsage.class, (nativeUsage) -> {
				assertThat(nativeUsage.promptTokensDetails().flatMap((details) -> details.cachedTokens())).hasValue(4L);
				assertThat(nativeUsage.completionTokensDetails().flatMap((details) -> details.reasoningTokens()))
					.hasValue(3L);
			});
		}
	}

	@Test
	void openAiMapsItsRateLimitHeaders() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_ANSWER)
			.header("x-ratelimit-limit-requests", "500")
			.header("x-ratelimit-remaining-requests", "499")
			.header("x-ratelimit-reset-requests", "120ms")
			.header("x-ratelimit-limit-tokens", "30000")
			.header("x-ratelimit-remaining-tokens", "29982")
			.header("x-ratelimit-reset-tokens", "1m30s"))) {
			RateLimit rateLimit = Providers.openAi(openAi).call(new Prompt("Hi")).getMetadata().getRateLimit();

			assertThat(rateLimit.getRequestsLimit()).isEqualTo(500L);
			assertThat(rateLimit.getRequestsRemaining()).isEqualTo(499L);
			assertThat(rateLimit.getRequestsReset()).isEqualTo(Duration.ofMillis(120));
			assertThat(rateLimit.getTokensLimit()).isEqualTo(30000L);
			assertThat(rateLimit.getTokensRemaining()).isEqualTo(29982L);
			assertThat(rateLimit.getTokensReset()).isEqualTo(Duration.ofSeconds(90));
		}
	}

	@Test
	void withoutRateLimitHeadersTheRateLimitIsEmptyAndReadsZero() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_ANSWER))) {
			RateLimit rateLimit = Providers.openAi(openAi).call(new Prompt("Hi")).getMetadata().getRateLimit();

			assertThat(rateLimit).isInstanceOf(EmptyRateLimit.class);
			assertThat(rateLimit.getRequestsLimit()).isZero();
			assertThat(rateLimit.getTokensRemaining()).isZero();
			assertThat(rateLimit.getTokensReset()).isEqualTo(Duration.ZERO);
		}
	}

	@Test
	void aModelThatReportsNoRateLimitHasAnEmptyOne() {
		ChatResponse response = ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").call().chatResponse();

		assertThat(response.getMetadata().getRateLimit()).isInstanceOf(EmptyRateLimit.class);
	}

	@Test
	void theChatClientGivesTheSameUsage() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_ANSWER))) {
			Usage usage = ChatClient.create(Providers.openAi(openAi))
				.prompt("What is the weather like today?")
				.call()
				.chatResponse()
				.getMetadata()
				.getUsage();

			assertThat(usage.getTotalTokens()).isEqualTo(18);
			assertThat(usage.getNativeUsage()).isInstanceOf(CompletionUsage.class);
		}
	}

	@Test
	void openAiReportsTheTokensReadFromItsCacheButNoneWritten() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(OPENAI_ANSWER))) {
			Usage usage = Providers.openAi(openAi).call(new Prompt("Hi")).getMetadata().getUsage();

			assertThat(usage.getCacheReadInputTokens()).isEqualTo(4L);
			assertThat(usage.getCacheWriteInputTokens()).isNull();
		}
	}

	@Test
	void anthropicReportsTheTokensReadFromAndWrittenToItsCache() {
		String answer = Providers.anthropicMessage("[{\"type\":\"text\",\"text\":\"Sunny\"}]", "end_turn",
				"{\"input_tokens\":11,\"output_tokens\":7,\"cache_read_input_tokens\":100,"
						+ "\"cache_creation_input_tokens\":50}");
		try (StandIn anthropic = new StandIn((request) -> Reply.json(answer))) {
			Usage usage = Providers.anthropic(anthropic).call(new Prompt("Hi")).getMetadata().getUsage();

			assertThat(usage.getCacheReadInputTokens()).isEqualTo(100L);
			assertThat(usage.getCacheWriteInputTokens()).isEqualTo(50L);
			// Anthropic counts the cached tokens apart; Spring AI adds them to the prompt.
			assertThat(usage.getPromptTokens()).isEqualTo(161);
			assertThat(usage.getTotalTokens()).isEqualTo(168);
		}
	}

	@Test
	void geminiReportsTheTokensReadFromItsCacheButNoneWritten() {
		String answer = Providers.geminiResponse("[{\"text\":\"Sunny\"}]", "STOP",
				"{\"promptTokenCount\":11,\"candidatesTokenCount\":7,\"totalTokenCount\":18,"
						+ "\"cachedContentTokenCount\":6}");
		try (StandIn gemini = new StandIn((request) -> Reply.json(answer))) {
			Usage usage = Providers.gemini(gemini).call(new Prompt("Hi")).getMetadata().getUsage();

			assertThat(usage.getCacheReadInputTokens()).isEqualTo(6L);
			assertThat(usage.getCacheWriteInputTokens()).isNull();
		}
	}

	@Test
	void aModelWithoutPromptCachingReportsNoCacheTokens() {
		Usage usage = ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").call().chatResponse().getMetadata().getUsage();

		assertThat(usage.getCacheReadInputTokens()).isNull();
		assertThat(usage.getCacheWriteInputTokens()).isNull();
	}

	@Test
	void theUsageOfAToolLoopIsTheSumOfItsModelCallsWithoutTheNativeUsage() {
		String toolCall = Providers.openAiCompletion("{\"role\":\"assistant\",\"content\":null,\"refusal\":null,"
				+ "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"weather\","
				+ "\"arguments\":\"{}\"}}]}", "tool_calls",
				"{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25,"
						+ "\"prompt_tokens_details\":{\"cached_tokens\":10}}");
		try (StandIn openAi = new StandIn((request) -> request.text().contains("\"role\":\"tool\"")
				? Reply.json(OPENAI_ANSWER) : Reply.json(toolCall))) {
			ChatResponse response = ChatClient.create(Providers.openAi(openAi))
				.prompt("What is the weather in Paris?")
				.tools(new WeatherTools())
				.call()
				.chatResponse();

			assertThat(openAi.requests).hasSize(2);
			Usage usage = response.getMetadata().getUsage();
			assertThat(usage.getPromptTokens()).isEqualTo(31);
			assertThat(usage.getCompletionTokens()).isEqualTo(12);
			assertThat(usage.getTotalTokens()).isEqualTo(43);
			assertThat(usage.getCacheReadInputTokens()).isEqualTo(14L);
			assertThat(usage.getNativeUsage()).isNull();
		}
	}

	static class WeatherTools {

		@Tool(description = "Tells the weather")
		String weather() {
			return "sunny";
		}

	}

}
