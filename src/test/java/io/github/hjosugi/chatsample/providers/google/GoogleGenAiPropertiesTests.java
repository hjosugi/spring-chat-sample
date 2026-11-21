package io.github.hjosugi.chatsample.providers.google;

import com.google.genai.Client;
import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code spring.ai.google.genai.*} properties of the Google GenAI chat page: which
 * API the client of the auto-configuration talks to, and the chat properties in the
 * request. Spring AI has no property for the URL of Gemini, so the chat properties are
 * checked with a client of the test's own, pointed at the stand-in.
 */
class GoogleGenAiPropertiesTests {

	private static final String CREDENTIALS = "spring.ai.google.genai.credentials-uri=classpath:providers/google-credentials.json";

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(GoogleGenAiChatAutoConfiguration.class, ToolCallingAutoConfiguration.class));

	@Test
	void anApiKeyChoosesTheGeminiDeveloperApi() {
		this.runner.withPropertyValues("spring.ai.google.genai.api-key=test-key").run((context) -> {
			Client client = context.getBean(Client.class);
			assertThat(client.vertexAI()).isFalse();
			assertThat(client.apiKey()).isEqualTo("test-key");
		});
	}

	/**
	 * Vertex AI needs credentials; without application default credentials on this
	 * machine, the test gives some through {@code credentials-uri}.
	 */
	@Test
	void aProjectAndALocationChooseVertexAi() {
		this.runner
			.withPropertyValues("spring.ai.google.genai.project-id=shop-project",
					"spring.ai.google.genai.location=us-central1", CREDENTIALS)
			.run((context) -> {
				Client client = context.getBean(Client.class);
				assertThat(client.vertexAI()).isTrue();
				assertThat(client.project()).isEqualTo("shop-project");
				assertThat(client.location()).isEqualTo("us-central1");
			});
	}

	@Test
	void withBothTheKeyWinsUnlessVertexAiIsAskedFor() {
		String[] both = { "spring.ai.google.genai.api-key=test-key", "spring.ai.google.genai.project-id=shop-project",
				"spring.ai.google.genai.location=us-central1", CREDENTIALS };
		this.runner.withPropertyValues(both)
			.run((context) -> assertThat(context.getBean(Client.class).vertexAI()).isFalse());
		this.runner.withPropertyValues(both)
			.withPropertyValues("spring.ai.google.genai.vertex-ai=true")
			.run((context) -> assertThat(context.getBean(Client.class).vertexAI()).isTrue());
	}

	@Test
	void anIncompleteConfigurationFailsAtTheStart() {
		this.runner.run((context) -> assertThat(context).getFailure()
			.rootCause()
			.hasMessageContaining("Incomplete Google GenAI configuration"));
		this.runner.withPropertyValues("spring.ai.google.genai.api-key=test-key", "spring.ai.google.genai.vertex-ai=true")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.hasMessageContaining("Vertex AI mode requires both 'project-id' and 'location'"));
	}

	@Test
	void theChatPropertiesReachTheRequestAndAnOptionOfARequestWinsOverThem() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			this.runner.withBean(Client.class, () -> Gemini.client(gemini))
				.withPropertyValues("spring.ai.google.genai.chat.model=gemini-2.5-flash",
						"spring.ai.google.genai.chat.temperature=0.5", "spring.ai.google.genai.chat.top-k=10",
						"spring.ai.google.genai.chat.top-p=0.5", "spring.ai.google.genai.chat.candidate-count=1",
						"spring.ai.google.genai.chat.max-output-tokens=100",
						"spring.ai.google.genai.chat.stop-sequences=END",
						"spring.ai.google.genai.chat.response-mime-type=application/json",
						"spring.ai.google.genai.chat.thinking-budget=0",
						"spring.ai.google.genai.chat.include-thoughts=false",
						"spring.ai.google.genai.chat.google-search-retrieval=true",
						"spring.ai.google.genai.chat.include-server-side-tool-invocations=true",
						"spring.ai.google.genai.chat.safety-settings[0].category=HARM_CATEGORY_HARASSMENT",
						"spring.ai.google.genai.chat.safety-settings[0].threshold=BLOCK_LOW_AND_ABOVE",
						"spring.ai.google.genai.chat.tool-choice.mode=ANY",
						"spring.ai.google.genai.chat.tool-choice.allowed-function-names=orderStatus",
						"spring.ai.google.genai.chat.use-cached-content=true",
						"spring.ai.google.genai.chat.cached-content-name=cachedContents/abc")
				.run((context) -> {
					GoogleGenAiChatModel model = context.getBean(GoogleGenAiChatModel.class);

					model.call(new Prompt("Hello"));
					ChatClient.create(model)
						.prompt("Hello")
						.options(GoogleGenAiChatOptions.builder().temperature(0.9))
						.call()
						.content();

					StandIn.Request request = gemini.requests.get(0);
					assertThat(request.path()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
					JsonNode body = request.body();
					JsonNode config = body.get("generationConfig");
					assertThat(config.get("temperature").asDouble()).isEqualTo(0.5);
					assertThat(config.get("topK").asDouble()).isEqualTo(10);
					assertThat(config.get("topP").asDouble()).isEqualTo(0.5);
					assertThat(config.get("candidateCount").asInt()).isEqualTo(1);
					assertThat(config.get("maxOutputTokens").asInt()).isEqualTo(100);
					assertThat(config.get("stopSequences").toString()).isEqualTo("[\"END\"]");
					assertThat(config.get("responseMimeType").asString()).isEqualTo("application/json");
					assertThat(config.get("thinkingConfig").toString())
						.isEqualTo("{\"includeThoughts\":false,\"thinkingBudget\":0}");
					assertThat(body.get("tools").toString()).isEqualTo("[{\"googleSearch\":{}}]");
					assertThat(body.get("toolConfig").toString())
						.isEqualTo("{\"functionCallingConfig\":{\"allowedFunctionNames\":[\"orderStatus\"],\"mode\":\"ANY\"},"
								+ "\"includeServerSideToolInvocations\":true}");
					assertThat(body.get("safetySettings").toString())
						.isEqualTo("[{\"category\":\"HARM_CATEGORY_HARASSMENT\",\"threshold\":\"BLOCK_LOW_AND_ABOVE\"}]");
					assertThat(body.get("cachedContent").asString()).isEqualTo("cachedContents/abc");
					JsonNode viaClient = gemini.requests.get(1).body().get("generationConfig");
					assertThat(viaClient.get("temperature").asDouble()).isEqualTo(0.9);
					assertThat(viaClient.get("maxOutputTokens").asInt()).isEqualTo(100);
				});
		}
	}

	@Test
	void anotherChatProviderLeavesNoGeminiModel() {
		this.runner.withPropertyValues("spring.ai.google.genai.api-key=test-key", "spring.ai.model.chat=anthropic")
			.run((context) -> assertThat(context).doesNotHaveBean(GoogleGenAiChatModel.class));
	}

}
