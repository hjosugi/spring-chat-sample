package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI-Compatible Endpoints pages (vLLM, Ollama, DeepSeek, Groq, NVIDIA,
 * Perplexity, Docker Model Runner): the OpenAI client with another base URL and key,
 * {@code extraBody}, the {@code reasoning_content} of reasoning models and its replay,
 * and the extra fields of tool calls.
 */
class OpenAiCompatibleTests {

	private final StandIn server = new StandIn(OpenAiCompatibleTests::reply);

	static Reply reply(StandIn.Request request) {
		String last = request.body().get("messages").get(request.body().get("messages").size() - 1).toString();
		if (last.contains("weather")) {
			return completion("""
					{"role": "assistant", "content": null, "tool_calls": [{"id": "call_1", "type": "function",
					  "function": {"name": "get_weather", "arguments": "{\\"location\\":\\"Seoul\\"}"},
					  "extra_content": {"google": {"thought_signature": "sig-123"}}}]}""", "tool_calls");
		}
		return completion("""
				{"role": "assistant", "content": "9.8 is larger.",
				 "reasoning_content": "Compare the tenths: 8 is more than 1."}""", "stop");
	}

	static Reply completion(String message, String finishReason) {
		return Reply.json("""
				{"id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "deepseek-reasoner",
				 "choices": [{"index": 0, "message": %s, "finish_reason": "%s"}],
				 "usage": {"prompt_tokens": 5, "completion_tokens": 7, "total_tokens": 12}}"""
			.formatted(message, finishReason));
	}

	@AfterEach
	void stop() {
		this.server.close();
	}

	private ApplicationContextRunner contexts() {
		return new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.max-retries=0", "spring.ai.model.chat=openai");
	}

	@Test
	void anotherServerIsTheBaseUrlWithItsVersionPathAndItsKey() {
		contexts()
			.withPropertyValues("spring.ai.openai.base-url=" + this.server.url() + "/openai/v1",
					"spring.ai.openai.api-key=groq-key", "spring.ai.openai.chat.model=llama-3.1-8b-instant")
			.run((context) -> {
				context.getBean(ChatModel.class).call("Hello");

				StandIn.Request request = this.server.single();
				assertThat(request.path()).isEqualTo("/openai/v1/chat/completions");
				assertThat(request.apiKey()).isEqualTo("groq-key");
				assertThat(request.body().get("model").asString()).isEqualTo("llama-3.1-8b-instant");
			});
	}

	@Test
	void aServerWithoutAVersionPathGetsNone() {
		contexts()
			.withPropertyValues("spring.ai.openai.base-url=" + this.server.url(),
					"spring.ai.openai.api-key=perplexity-key", "spring.ai.openai.chat.model=sonar")
			.run((context) -> {
				context.getBean(ChatModel.class).call("Hello");

				assertThat(this.server.single().path()).isEqualTo("/chat/completions");
			});
	}

	/**
	 * The Docker Model Runner page says that the OpenAI module needs a key and to set any
	 * value. An empty key works too: Spring AI then sends no {@code Authorization} header
	 * at all.
	 */
	@Test
	void anEmptyKeySendsNoAuthorizationHeader() {
		contexts()
			.withPropertyValues("spring.ai.openai.base-url=" + this.server.url() + "/engines/v1",
					"spring.ai.openai.api-key=", "spring.ai.openai.chat.model=ai/gemma3")
			.run((context) -> {
				context.getBean(ChatModel.class).call("Hello");

				assertThat(this.server.single().header("Authorization")).isNull();
			});
	}

	@Test
	void extraBodyPropertiesBecomeTopLevelParameters() {
		contexts()
			.withPropertyValues("spring.ai.openai.base-url=" + this.server.url() + "/v1",
					"spring.ai.openai.api-key=unused", "spring.ai.openai.chat.model=meta-llama/Llama-3-8B-Instruct",
					"spring.ai.openai.chat.temperature=0.7", "spring.ai.openai.chat.extra-body.top_k=50",
					"spring.ai.openai.chat.extra-body.repetition_penalty=1.1")
			.run((context) -> {
				context.getBean(ChatModel.class).call("Hello");

				JsonNode body = this.server.single().body();
				assertThat(body.get("top_k").asInt()).isEqualTo(50);
				assertThat(body.get("repetition_penalty").asDouble()).isEqualTo(1.1);
				assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
				assertThat(body.has("extra_body")).isFalse();
			});
	}

	private OpenAiChatModel model(OpenAiChatOptions.Builder options) {
		return OpenAiChatModel.builder()
			.options(options.baseUrl(this.server.url() + "/v1").apiKey("test-key").maxRetries(0).build())
			.build();
	}

	@Test
	void extraBodyOfTheOptionsBecomesTopLevelParametersToo() {
		model(OpenAiChatOptions.builder().model("llama3.2"))
			.call(new Prompt("Generate text", OpenAiChatOptions.builder()
				.extraBody(Map.of("num_predict", 100, "top_k", 40, "repeat_penalty", 1.1))
				.build()));

		JsonNode body = this.server.single().body();
		assertThat(body.get("num_predict").asInt()).isEqualTo(100);
		assertThat(body.get("repeat_penalty").asDouble()).isEqualTo(1.1);
	}

	@Test
	void reasoningContentBecomesAReasoningPart() {
		ChatResponse response = model(OpenAiChatOptions.builder().model("deepseek-reasoner"))
			.call(new Prompt("Which number is larger: 9.11 or 9.8?"));

		AssistantMessage message = response.getResult().getOutput();
		assertThat(message.getText()).isEqualTo("9.8 is larger.");
		assertThat(message.getReasoning()).hasSize(1);
		assertThat(message.getReasoning().get(0).text()).isEqualTo("Compare the tenths: 8 is more than 1.");
		assertThat(message.getMetadata()).containsEntry("reasoningContent", "Compare the tenths: 8 is more than 1.");
	}

	@Test
	void theReasoningOfAnEarlierAnswerIsReplayedUnlessReplayIsOff() {
		OpenAiChatModel model = model(OpenAiChatOptions.builder().model("deepseek-reasoner"));
		AssistantMessage earlier = model.call(new Prompt("Which number is larger: 9.11 or 9.8?"))
			.getResult()
			.getOutput();
		List<org.springframework.ai.chat.messages.Message> history = List.of(
				new UserMessage("Which number is larger: 9.11 or 9.8?"), earlier, new UserMessage("And 9.9?"));

		model.call(new Prompt(history));
		model.call(new Prompt(history, OpenAiChatOptions.builder().replayReasoningContent(false).build()));

		JsonNode replayed = this.server.requests.get(1).body().get("messages").get(1);
		assertThat(replayed.get("reasoning_content").asString()).isEqualTo("Compare the tenths: 8 is more than 1.");
		JsonNode notReplayed = this.server.requests.get(2).body().get("messages").get(1);
		assertThat(notReplayed.has("reasoning_content")).isFalse();
	}

	@Test
	void theExtraFieldsOfAToolCallAreKeptAndSentBack() {
		OpenAiChatModel model = model(OpenAiChatOptions.builder().model("gemini-2.5-flash"));
		AssistantMessage toolCall = model.call(new Prompt("What is the weather in Seoul?"))
			.getResult()
			.getOutput();

		ToolCallPart part = toolCall.getParts()
			.stream()
			.filter(ToolCallPart.class::isInstance)
			.map(ToolCallPart.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(part.payload().provider()).isEqualTo(OpenAiChatModel.CHAT_COMPLETIONS_PROVIDER);
		assertThat(part.payload().data()).contains("thought_signature", "sig-123");

		model.call(new Prompt(List.of(new UserMessage("What is the weather in Seoul?"), toolCall,
				org.springframework.ai.chat.messages.ToolResponseMessage.builder()
					.responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
							"call_1", "get_weather", "Sunny")))
					.build())));

		JsonNode replayed = this.server.requests.get(1).body().get("messages").get(1).get("tool_calls").get(0);
		assertThat(replayed.get("extra_content").get("google").get("thought_signature").asString())
			.isEqualTo("sig-123");
	}

}
