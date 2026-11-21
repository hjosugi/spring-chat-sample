package io.github.hjosugi.chatsample.providers.anthropic;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rows of the page on migrating the Anthropic module to the official Java SDK that
 * can be checked without the real API. The behavior rows (no merging of prompt options,
 * streamed thinking, prompt caching, citation validation) are in the tests of the chat,
 * caching and citations pages.
 */
class AnthropicMigrationTests {

	@Test
	void theModelHasNoPublicConstructorOnlyABuilderWithoutARetryTemplate() {
		assertThat(AnthropicChatModel.class.getConstructors()).isEmpty();
		assertThat(Arrays.stream(AnthropicChatModel.Builder.class.getMethods()).map(Method::getName))
			.contains("options", "toolCallingManager", "observationRegistry", "anthropicClient")
			.doesNotContain("retryTemplate");
	}

	@Test
	void theHandRolledApiAndItsHelpersAreGone() {
		ClassLoader loader = getClass().getClassLoader();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.api.AnthropicApi", loader)).isFalse();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.api.AnthropicCacheType", loader)).isFalse();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.api.StreamHelper", loader)).isFalse();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.api.CitationDocument", loader)).isFalse();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.AnthropicCitationDocument", loader)).isTrue();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.CacheBreakpointTracker", loader)).isTrue();
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.CacheEligibilityResolver", loader)).isTrue();
		assertThat(ClassUtils.isPresent("okhttp3.OkHttpClient", loader)).as("the new transitive OkHttp").isTrue();
	}

	@Test
	void theCacheEnumsKeepTheirValues() {
		assertThat(AnthropicCacheStrategy.values()).extracting(Enum::name)
			.containsExactlyInAnyOrder("NONE", "TOOLS_ONLY", "SYSTEM_ONLY", "SYSTEM_AND_TOOLS", "CONVERSATION_HISTORY");
		assertThat(AnthropicCacheTtl.values()).extracting(Enum::name).containsExactly("FIVE_MINUTES", "ONE_HOUR");
	}

	/**
	 * The migration page lists {@code metadata.AnthropicRateLimit} as removed without a
	 * replacement and tells to read rate limits from {@code RateLimitException} instead.
	 * In 2.1.0 the class is there and fills {@code ChatResponseMetadata#getRateLimit()}
	 * from the headers, as the chat page says (see
	 * {@code AnthropicChatTests#theRateLimitHeadersBecomeAnAnthropicRateLimitOnACallAndAStream}).
	 */
	@Test
	void theRateLimitTypeTheMigrationPageCallsRemovedIsThere() {
		assertThat(ClassUtils.isPresent("org.springframework.ai.anthropic.metadata.AnthropicRateLimit",
				getClass().getClassLoader()))
			.isTrue();
	}

	@Test
	void theDefaultMaxTokensIs4096() {
		assertThat(AnthropicChatOptions.DEFAULT_MAX_TOKENS).isEqualTo(4096);
		assertThat(AnthropicChatOptions.builder().build().getMaxTokens()).isEqualTo(4096);
	}

	@Test
	void theRequestNamesTheApiVersionOfTheSdk() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			Claude.model(anthropic).call(new Prompt("Hello"));

			assertThat(anthropic.single().header("anthropic-version")).isEqualTo("2026-10-21");
		}
	}

	/**
	 * The SDK hands the tool input over as a {@code JsonValue}, whose {@code toString()}
	 * is not JSON; Spring AI turns it into real JSON for the tool call.
	 */
	@Test
	void theArgumentsOfAToolCallAreRealJson() {
		try (StandIn anthropic = new StandIn(Claude.answering("tool_use",
				Claude.toolUse("toolu_1", "orderStatus", "{\"orderId\":\"A-1\",\"items\":[1,2],\"gift\":true}")))) {
			ChatResponse response = Claude.model(anthropic).call(new Prompt("Where is A-1?"));

			AssistantMessage.ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
			assertThat(call.arguments()).isEqualTo("{\"orderId\":\"A-1\",\"items\":[1,2],\"gift\":true}");
			assertThat(call.id()).isEqualTo("toolu_1");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("tool_use");
		}
	}

}
