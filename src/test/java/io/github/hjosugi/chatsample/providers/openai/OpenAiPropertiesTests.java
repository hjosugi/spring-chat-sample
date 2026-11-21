package io.github.hjosugi.chatsample.providers.openai;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.ai.model.openai.autoconfigure.OpenAiResponsesChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code spring.ai.openai.*} properties of the chat and Responses pages, and the
 * connection details of the OpenAI page, through the auto-configuration of the starter.
 */
class OpenAiPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class,
				OpenAiResponsesChatAutoConfiguration.class, ToolCallingAutoConfiguration.class))
		.withPropertyValues("spring.ai.openai.api-key=test-key", "spring.ai.openai.max-retries=0");

	@Test
	void theChatPropertiesReachTheRequest() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.openai.base-url=" + openai.url() + "/v1",
						"spring.ai.openai.custom-headers.X-Client=shop", "spring.ai.openai.chat.model=gpt-5.4-mini",
						"spring.ai.openai.chat.temperature=0.3", "spring.ai.openai.chat.top-p=0.8",
						"spring.ai.openai.chat.frequency-penalty=0.1", "spring.ai.openai.chat.presence-penalty=0.2",
						"spring.ai.openai.chat.max-completion-tokens=256", "spring.ai.openai.chat.n=1",
						"spring.ai.openai.chat.seed=7", "spring.ai.openai.chat.stop=END,STOP",
						"spring.ai.openai.chat.user=alice", "spring.ai.openai.chat.store=true",
						"spring.ai.openai.chat.metadata.shop=alice", "spring.ai.openai.chat.logprobs=true",
						"spring.ai.openai.chat.top-logprobs=2", "spring.ai.openai.chat.reasoning-effort=low",
						"spring.ai.openai.chat.verbosity=medium", "spring.ai.openai.chat.service-tier=flex",
						"spring.ai.openai.chat.prompt-cache-key=orders", "spring.ai.openai.chat.tool-choice=none",
						"spring.ai.openai.chat.parallel-tool-calls=false",
						"spring.ai.openai.chat.response-format.type=JSON_SCHEMA",
						"spring.ai.openai.chat.response-format.json-schema={\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}",
						"spring.ai.openai.chat.response-format.strict=false",
						"spring.ai.openai.chat.output-modalities=text,audio",
						"spring.ai.openai.chat.output-audio.voice=ALLOY", "spring.ai.openai.chat.output-audio.format=MP3",
						"spring.ai.openai.chat.extra-body.top_k=40")
				.run((context) -> {
					context.getBean(OpenAiChatModel.class).call(new Prompt("Hello"));

					StandIn.Request request = openai.single();
					assertThat(request.apiKey()).isEqualTo("test-key");
					assertThat(request.header("X-Client")).isEqualTo("shop");
					JsonNode body = request.body();
					assertThat(body.get("model").asString()).isEqualTo("gpt-5.4-mini");
					assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
					assertThat(body.get("top_p").asDouble()).isEqualTo(0.8);
					assertThat(body.get("frequency_penalty").asDouble()).isEqualTo(0.1);
					assertThat(body.get("presence_penalty").asDouble()).isEqualTo(0.2);
					assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(256);
					assertThat(body.get("n").asInt()).isEqualTo(1);
					assertThat(body.get("seed").asInt()).isEqualTo(7);
					assertThat(body.get("stop").toString()).isEqualTo("[\"END\",\"STOP\"]");
					assertThat(body.get("user").asString()).isEqualTo("alice");
					assertThat(body.get("store").asBoolean()).isTrue();
					assertThat(body.get("metadata").toString()).isEqualTo("{\"shop\":\"alice\"}");
					assertThat(body.get("logprobs").asBoolean()).isTrue();
					assertThat(body.get("top_logprobs").asInt()).isEqualTo(2);
					assertThat(body.get("reasoning_effort").asString()).isEqualTo("low");
					assertThat(body.get("verbosity").asString()).isEqualTo("medium");
					assertThat(body.get("service_tier").asString()).isEqualTo("flex");
					assertThat(body.get("prompt_cache_key").asString()).isEqualTo("orders");
					assertThat(body.get("tool_choice").asString()).isEqualTo("none");
					assertThat(body.get("parallel_tool_calls").asBoolean()).isFalse();
					assertThat(body.get("response_format").get("json_schema").get("strict").asBoolean()).isFalse();
					assertThat(body.get("response_format").get("json_schema").get("schema").get("properties").has("a"))
						.isTrue();
					assertThat(body.get("modalities").toString()).isEqualTo("[\"text\",\"audio\"]");
					assertThat(body.get("audio").toString()).isEqualTo("{\"format\":\"mp3\",\"voice\":\"alloy\"}");
					assertThat(body.get("top_k").asInt()).isEqualTo(40);
				});
		}
	}

	@Test
	void aStreamIncludesTheUsageUntilAStreamOptionIsSet() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			this.runner.withPropertyValues("spring.ai.openai.base-url=" + openai.url() + "/v1")
				.run((context) -> context.getBean(OpenAiChatModel.class).stream(new Prompt("Hello")).blockLast());
			this.runner
				.withPropertyValues("spring.ai.openai.base-url=" + openai.url() + "/v1",
						"spring.ai.openai.chat.stream-options.include-obfuscation=true")
				.run((context) -> context.getBean(OpenAiChatModel.class).stream(new Prompt("Hello")).blockLast());

			assertThat(openai.requests).extracting((request) -> request.body().get("stream_options").toString())
				.containsExactly("{\"include_usage\":true}", "{\"include_obfuscation\":true,\"include_usage\":false}");
		}
	}

	@Test
	void theChatUrlAndKeyWinOverTheCommonOnes() {
		try (StandIn common = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")));
				StandIn chat = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.openai.base-url=" + common.url() + "/v1",
						"spring.ai.openai.chat.base-url=" + chat.url() + "/v1", "spring.ai.openai.chat.api-key=chat-key")
				.run((context) -> context.getBean(OpenAiChatModel.class).call(new Prompt("Hello")));

			assertThat(common.requests).isEmpty();
			assertThat(chat.single().apiKey()).isEqualTo("chat-key");
		}
	}

	@Test
	void connectionDetailsSupersedeTheCommonPropertiesButNotTheChatOnes() {
		try (StandIn container = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")));
				StandIn properties = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			OpenAiConnectionDetails details = new OpenAiConnectionDetails() {

				@Override
				public String getBaseUrl() {
					return container.url() + "/v1";
				}

				@Override
				public String getApiKey() {
					return "";
				}

			};
			this.runner.withBean(OpenAiConnectionDetails.class, () -> details)
				.withPropertyValues("spring.ai.openai.base-url=" + properties.url() + "/v1",
						"spring.ai.openai.api-key=ignored")
				.run((context) -> context.getBean(OpenAiChatModel.class).call(new Prompt("Hello")));
			this.runner.withBean(OpenAiConnectionDetails.class, () -> details)
				.withPropertyValues("spring.ai.openai.chat.base-url=" + properties.url() + "/v1",
						"spring.ai.openai.chat.api-key=chat-key")
				.run((context) -> context.getBean(OpenAiChatModel.class).call(new Prompt("Hello")));

			assertThat(container.single().header("Authorization")).as("an empty key disables authentication").isNull();
			assertThat(properties.single().apiKey()).isEqualTo("chat-key");
		}
	}

	@Test
	void anOptionOfARequestWinsOverAProperty() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.openai.base-url=" + openai.url() + "/v1",
						"spring.ai.openai.chat.model=gpt-5.4-mini", "spring.ai.openai.chat.temperature=0.3")
				.run((context) -> ChatClient.create(context.getBean(OpenAiChatModel.class))
					.prompt("Hello")
					.options(OpenAiChatOptions.builder().temperature(0.9))
					.call()
					.content());

			assertThat(openai.single().body().get("temperature").asDouble()).isEqualTo(0.9);
			assertThat(openai.single().body().get("model").asString()).isEqualTo("gpt-5.4-mini");
		}
	}

	@Test
	void theFormerOptionsPrefixStillBinds() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.openai.base-url=" + openai.url() + "/v1",
						"spring.ai.openai.chat.options.model=gpt-5.4", "spring.ai.openai.chat.options.temperature=0.6")
				.run((context) -> context.getBean(OpenAiChatModel.class).call(new Prompt("Hello")));

			assertThat(openai.single().body().get("model").asString()).isEqualTo("gpt-5.4");
			assertThat(openai.single().body().get("temperature").asDouble()).isEqualTo(0.6);
		}
	}

	@Test
	void theResponsesApiReplacesTheChatCompletionsModelAndTakesItsOwnProperties() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", Gpt.message("msg_1", "Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.openai.base-url=" + openai.url() + "/v1", "spring.ai.openai.chat.api=responses",
						"spring.ai.openai.responses.model=gpt-5.4-mini", "spring.ai.openai.responses.max-output-tokens=512",
						"spring.ai.openai.responses.reasoning-effort=medium",
						"spring.ai.openai.responses.reasoning-summary=auto", "spring.ai.openai.responses.verbosity=low",
						"spring.ai.openai.responses.strict=true", "spring.ai.openai.responses.max-tool-calls=2",
						"spring.ai.openai.responses.include=web_search_call.results",
						"spring.ai.openai.responses.truncation=disabled",
						"spring.ai.openai.responses.safety-identifier=user-42",
						"spring.ai.openai.responses.hosted-tools.web-search.enabled=true",
						"spring.ai.openai.responses.hosted-tools.web-search.search-context-size=low",
						"spring.ai.openai.responses.hosted-tools.file-search.vector-store-ids=vs_1",
						"spring.ai.openai.responses.hosted-tools.code-interpreter.enabled=true",
						"spring.ai.openai.responses.hosted-tools.image-generation.enabled=true")
				.run((context) -> {
					assertThat(context).doesNotHaveBean(OpenAiChatModel.class);
					assertThat(context.getBean(ChatModel.class)).isInstanceOf(OpenAiResponsesChatModel.class);

					context.getBean(OpenAiResponsesChatModel.class).call(new Prompt("Hello"));

					JsonNode body = openai.single().body();
					assertThat(openai.single().path()).isEqualTo("/v1/responses");
					assertThat(body.get("model").asString()).isEqualTo("gpt-5.4-mini");
					assertThat(body.get("max_output_tokens").asInt()).isEqualTo(512);
					assertThat(body.get("reasoning").toString()).isEqualTo("{\"effort\":\"medium\",\"summary\":\"auto\"}");
					assertThat(body.get("text").toString()).isEqualTo("{\"verbosity\":\"low\"}");
					assertThat(body.get("max_tool_calls").asInt()).isEqualTo(2);
					assertThat(body.get("include").toString())
						.isEqualTo("[\"reasoning.encrypted_content\",\"web_search_call.results\"]");
					assertThat(body.get("truncation").asString()).isEqualTo("disabled");
					assertThat(body.get("safety_identifier").asString()).isEqualTo("user-42");
					assertThat(body.get("tools")).extracting((tool) -> tool.get("type").asString())
						.containsExactlyInAnyOrder("web_search", "file_search", "code_interpreter", "image_generation");
				});
		}
	}

}
