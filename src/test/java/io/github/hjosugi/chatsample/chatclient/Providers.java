package io.github.hjosugi.chatsample.chatclient;

import java.util.function.Consumer;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.github.hjosugi.chatsample.support.StandIn;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The chat models of Anthropic, OpenAI and Gemini pointed at a {@link StandIn}, and the
 * replies their APIs send, as the provider modules' own tests show them. Only what this
 * area needs: a provider's own behavior is the business of its own tests.
 */
final class Providers {

	static final String OPENAI_MODEL = "gpt-5.4-mini";

	static final String ANTHROPIC_MODEL = "claude-haiku-5-5";

	static final String GEMINI_MODEL = "gemini-3.5-flash-lite";

	private static final JsonMapper json = JsonMapper.shared();

	private Providers() {
	}

	static OpenAiChatModel openAi(StandIn standIn) {
		return openAi(standIn, ObservationRegistry.NOOP, (options) -> {
		});
	}

	static OpenAiChatModel openAi(StandIn standIn, ObservationRegistry observationRegistry,
			Consumer<OpenAiChatOptions.Builder> customizer) {
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
			.baseUrl(standIn.url())
			.apiKey("test-key")
			.model(OPENAI_MODEL)
			.maxRetries(0);
		customizer.accept(options);
		return OpenAiChatModel.builder().options(options.build()).observationRegistry(observationRegistry).build();
	}

	static AnthropicChatModel anthropic(StandIn standIn) {
		return anthropic(standIn, ObservationRegistry.NOOP, (options) -> {
		});
	}

	static AnthropicChatModel anthropic(StandIn standIn, ObservationRegistry observationRegistry,
			Consumer<AnthropicChatOptions.Builder> customizer) {
		AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
			.baseUrl(standIn.url())
			.apiKey("test-key")
			.model(ANTHROPIC_MODEL)
			.maxTokens(1024)
			.maxRetries(0);
		customizer.accept(options);
		return AnthropicChatModel.builder().options(options.build()).observationRegistry(observationRegistry).build();
	}

	static GoogleGenAiChatModel gemini(StandIn standIn) {
		return gemini(standIn, ObservationRegistry.NOOP, (options) -> {
		});
	}

	static GoogleGenAiChatModel gemini(StandIn standIn, ObservationRegistry observationRegistry,
			Consumer<GoogleGenAiChatOptions.Builder> customizer) {
		GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder().model(GEMINI_MODEL);
		customizer.accept(options);
		return GoogleGenAiChatModel.builder()
			.genAiClient(geminiClient(standIn))
			.options(options.build())
			.observationRegistry(observationRegistry)
			.build();
	}

	/**
	 * Spring AI has no URL for Gemini: the client of the Google SDK takes it.
	 */
	static Client geminiClient(StandIn standIn) {
		return Client.builder()
			.apiKey("test-key")
			.httpOptions(HttpOptions.builder().baseUrl(standIn.url()).build())
			.build();
	}

	/**
	 * A Chat Completions reply of OpenAI with one choice.
	 * @param message the message of the choice, as JSON
	 * @param finishReason its finish reason
	 * @param usage the usage, as JSON
	 */
	static String openAiCompletion(String message, String finishReason, String usage) {
		return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"" + OPENAI_MODEL
				+ "-2026-01-01\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\""
				+ finishReason + "\",\"logprobs\":null}],\"usage\":" + usage + "}";
	}

	/**
	 * A Chat Completions reply of OpenAI with this text.
	 */
	static String openAiText(String text) {
		return openAiCompletion("{\"role\":\"assistant\",\"content\":" + quote(text) + ",\"refusal\":null}", "stop",
				"{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}");
	}

	/**
	 * A Messages reply of Anthropic.
	 * @param content the content blocks, as a JSON array
	 * @param stopReason the stop reason
	 * @param usage the usage, as JSON
	 */
	static String anthropicMessage(String content, String stopReason, String usage) {
		return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + ANTHROPIC_MODEL
				+ "\",\"content\":" + content + ",\"stop_reason\":\"" + stopReason
				+ "\",\"stop_sequence\":null,\"usage\":" + usage + "}";
	}

	/**
	 * A Messages reply of Anthropic with this text.
	 */
	static String anthropicText(String text) {
		return anthropicMessage("[{\"type\":\"text\",\"text\":" + quote(text) + "}]", "end_turn",
				"{\"input_tokens\":11,\"output_tokens\":7}");
	}

	/**
	 * A generateContent reply of Gemini with one candidate.
	 * @param parts the parts of the candidate, as a JSON array
	 * @param finishReason its finish reason
	 * @param usage the usage metadata, as JSON
	 */
	static String geminiResponse(String parts, String finishReason, String usage) {
		return "{\"candidates\":[{\"content\":{\"parts\":" + parts + ",\"role\":\"model\"},\"finishReason\":\""
				+ finishReason + "\",\"index\":0}],\"usageMetadata\":" + usage + ",\"modelVersion\":\"" + GEMINI_MODEL
				+ "\",\"responseId\":\"resp-1\"}";
	}

	/**
	 * A generateContent reply of Gemini with this text.
	 */
	static String geminiText(String text) {
		return geminiResponse("[{\"text\":" + quote(text) + "}]", "STOP",
				"{\"promptTokenCount\":11,\"candidatesTokenCount\":7,\"totalTokenCount\":18}");
	}

	static String quote(String text) {
		return json.writeValueAsString(text);
	}

}
