package io.github.hjosugi.chatsample.providers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The sections of the upgrade guides to 2.0 and 2.1 that concern the options and
 * properties of every chat provider: immutable options changed through
 * {@code mutate()}, {@code n()} instead of {@code N()}, {@code getOptions()} instead of
 * {@code getDefaultOptions()}, and the renamed Ollama property. The flattened
 * {@code .options} properties are checked by the properties tests of each provider.
 */
class UpgradingTests {

	private static final List<ChatOptions.Builder<?>> BUILDERS = List.of(AnthropicChatOptions.builder(),
			OpenAiChatOptions.builder(), OpenAiResponsesChatOptions.builder(), GoogleGenAiChatOptions.builder(),
			MistralAiChatOptions.builder(), OllamaChatOptions.builder(), DeepSeekChatOptions.builder(),
			BedrockChatOptions.builder());

	/**
	 * The Responses API takes no stop sequences (its page says so), so its options keep
	 * none; every other provider keeps them in a list that cannot be changed.
	 */
	@Test
	void optionsAreImmutableAndMutateMakesAChangedCopy() {
		List<String> sequences = new ArrayList<>(List.of("END"));
		for (ChatOptions.Builder<?> builder : BUILDERS) {
			ChatOptions options = builder.stopSequences(sequences).build();
			String name = options.getClass().getSimpleName();
			if (options instanceof OpenAiResponsesChatOptions) {
				assertThat(options.getStopSequences()).as(name).isNull();
				continue;
			}

			ChatOptions changed = options.mutate().stopSequences(List.of("STOP")).build();

			assertThat(options.getStopSequences()).as(name).containsExactly("END");
			assertThat(changed.getStopSequences()).as(name).containsExactly("STOP");
			assertThatExceptionOfType(UnsupportedOperationException.class).as(name)
				.isThrownBy(() -> options.getStopSequences().add("MORE"));
		}
		sequences.add("LATER");
		assertThat(AnthropicChatOptions.builder().stopSequences(sequences).build().getStopSequences()).hasSize(2);
	}

	@Test
	void anUnsetCollectionIsNullRatherThanEmpty() {
		for (ChatOptions.Builder<?> builder : BUILDERS) {
			ChatOptions options = builder.build();
			assertThat(options.getStopSequences()).as(options.getClass().getSimpleName()).isNull();
		}
	}

	@Test
	void theBuildersHaveNInsteadOfCapitalN() {
		for (Class<?> builder : List.of(OpenAiChatOptions.Builder.class, MistralAiChatOptions.Builder.class)) {
			assertThat(Arrays.stream(builder.getMethods()).map(Method::getName)).as(builder.getName())
				.contains("n")
				.doesNotContain("N");
		}
	}

	@Test
	@SuppressWarnings("removal")
	void getDefaultOptionsIsDeprecatedForRemovalInFavorOfGetOptions() throws Exception {
		Method getDefaultOptions = ChatModel.class.getMethod("getDefaultOptions");

		assertThat(getDefaultOptions.getAnnotation(Deprecated.class).forRemoval()).isTrue();
		assertThat(ChatModel.class.getMethod("getOptions")).isNotNull();
	}

	/**
	 * The Anthropic section of the 2.0 guide builds the model with
	 * {@code AnthropicChatModel.builder().apiKey(apiKey).defaultOptions(options)}; the
	 * builder has neither: the key is an option, and the options are set with
	 * {@code options(...)}, as the migration page of the module says.
	 */
	@Test
	void theAnthropicBuilderOfThe20GuideDoesNotExist() {
		assertThat(Arrays.stream(AnthropicChatModel.Builder.class.getMethods()).map(Method::getName))
			.contains("options")
			.doesNotContain("apiKey", "defaultOptions");
	}

	@Test
	void theOllamaThinkOptionPropertyIsNowThink() {
		try (StandIn ollama = new StandIn((request) -> Reply.json("{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\","
				+ "\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"done\":true,\"done_reason\":\"stop\"}"))) {
			ApplicationContextRunner runner = new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(OllamaApiAutoConfiguration.class, OllamaChatAutoConfiguration.class,
						ToolCallingAutoConfiguration.class))
				.withPropertyValues("spring.ai.ollama.base-url=" + ollama.url());

			runner.withPropertyValues("spring.ai.ollama.chat.think-option=true")
				.run((context) -> context.getBean(OllamaChatModel.class).call(new Prompt("Hi")));
			runner.withPropertyValues("spring.ai.ollama.chat.think=true")
				.run((context) -> context.getBean(OllamaChatModel.class).call(new Prompt("Hi")));

			assertThat(ollama.requests.get(0).body().has("think")).as("the former name binds to nothing").isFalse();
			assertThat(ollama.requests.get(1).body().get("think").asBoolean()).isTrue();
		}
	}

}
