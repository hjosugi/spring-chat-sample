package io.github.hjosugi.chatsample.providers;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The columns of the chat model comparison table that the classes can answer: tool
 * calling, streaming, retry, observability and native structured output. The input
 * column is checked by the tests of each provider, which send each kind of media.
 */
class ComparisonTests {

	private static final List<Row> ROWS = List.of(new Row("Anthropic", AnthropicChatModel.class, AnthropicChatOptions.class),
			new Row("Bedrock Converse", BedrockProxyChatModel.class, BedrockChatOptions.class),
			new Row("DeepSeek", DeepSeekChatModel.class, DeepSeekChatOptions.class),
			new Row("Google GenAI", GoogleGenAiChatModel.class, GoogleGenAiChatOptions.class),
			new Row("Mistral AI", MistralAiChatModel.class, MistralAiChatOptions.class),
			new Row("Ollama", OllamaChatModel.class, OllamaChatOptions.class),
			new Row("OpenAI Chat Completions", OpenAiChatModel.class, OpenAiChatOptions.class),
			new Row("OpenAI Responses API", OpenAiResponsesChatModel.class, OpenAiResponsesChatOptions.class));

	@Test
	void everyChatModelStreamsTakesToolsAndIsObserved() {
		for (Row row : ROWS) {
			assertThat(row.model()).as(row.name()).isAssignableTo(StreamingChatModel.class);
			assertThat(row.options()).as(row.name()).isAssignableTo(ToolCallingChatOptions.class);
			assertThat(builderMethods(row)).as(row.name()).contains("observationRegistry");
		}
	}

	/**
	 * The table says yes for every row; DeepSeek's options do not implement
	 * {@code StructuredOutputChatOptions}, so a {@code ChatClient} asks for the format
	 * in the prompt instead (see {@code DeepSeekChatTests}).
	 */
	@Test
	void everyChatModelButDeepSeekHasNativeStructuredOutput() {
		assertThat(ROWS.stream().filter((row) -> !StructuredOutputChatOptions.class.isAssignableFrom(row.options())))
			.extracting(Row::name)
			.containsExactly("DeepSeek");
	}

	/**
	 * The SDK based models retry with the SDK, through a maximum of retries in their
	 * options or in the client; the others take a {@code RetryTemplate}.
	 */
	@Test
	void everyChatModelRetries() {
		assertThat(AnthropicChatOptions.builder().maxRetries(3).build().getMaxRetries()).isEqualTo(3);
		assertThat(OpenAiChatOptions.builder().maxRetries(3).build().getMaxRetries()).isEqualTo(3);
		assertThat(OpenAiResponsesChatOptions.builder().maxRetries(3).build().getMaxRetries()).isEqualTo(3);
		for (Row row : List.of(ROWS.get(2), ROWS.get(3), ROWS.get(4), ROWS.get(5))) {
			assertThat(builderMethods(row)).as(row.name()).contains("retryTemplate");
		}
		assertThat(builderMethods(ROWS.get(1))).as("Bedrock: the AWS SDK's client, which retries by itself")
			.contains("bedrockRuntimeClient");
	}

	private static List<String> builderMethods(Row row) {
		try {
			Method builder = row.model().getMethod("builder");
			return Arrays.stream(builder.getReturnType().getMethods()).map(Method::getName).toList();
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(row.name() + " has no builder", ex);
		}
	}

	record Row(String name, Class<?> model, Class<?> options) {
	}

}
