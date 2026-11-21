package io.github.hjosugi.chatsample.providers.anthropic;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code spring.ai.anthropic.*} properties of the chat page, through the
 * auto-configuration of the starter: each reaches the request, and an option on a prompt
 * wins over it.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AnthropicChatAutoConfiguration.class, ToolCallingAutoConfiguration.class))
		.withPropertyValues("spring.ai.anthropic.api-key=test-key", "spring.ai.anthropic.max-retries=0");

	@Test
	void theConnectionAndChatPropertiesReachTheRequest() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.anthropic.base-url=" + anthropic.url(),
						"spring.ai.anthropic.custom-headers.X-Client=shop", "spring.ai.anthropic.chat.model=claude-opus-5-5",
						"spring.ai.anthropic.chat.max-tokens=500", "spring.ai.anthropic.chat.stop-sequences=END,STOP",
						"spring.ai.anthropic.chat.http-headers.X-Request-Source=properties",
						"spring.ai.anthropic.chat.inference-geo=eu", "spring.ai.anthropic.chat.service-tier=auto",
						"spring.ai.anthropic.chat.disable-parallel-tool-use=true",
						"spring.ai.anthropic.chat.cache-options.strategy=SYSTEM_ONLY",
						"spring.ai.anthropic.chat.cache-options.multi-block-system-caching=true",
						"spring.ai.anthropic.chat.web-search-tool.max-uses=5",
						"spring.ai.anthropic.chat.web-search-tool.allowed-domains=docs.spring.io,github.com",
						"spring.ai.anthropic.chat.web-search-tool.blocked-domains=example.com",
						"spring.ai.anthropic.chat.web-search-tool.user-location.city=San Francisco",
						"spring.ai.anthropic.chat.web-search-tool.user-location.country=US",
						"spring.ai.anthropic.chat.web-search-tool.user-location.region=California",
						"spring.ai.anthropic.chat.web-search-tool.user-location.timezone=America/Los_Angeles")
				.run((context) -> {
					context.getBean(AnthropicChatModel.class)
						.call(new Prompt(List.of(new SystemMessage("Static."), new SystemMessage("Dynamic."),
								new UserMessage("Hello"))));

					StandIn.Request request = anthropic.single();
					assertThat(request.apiKey()).isEqualTo("test-key");
					assertThat(request.header("X-Client")).isEqualTo("shop");
					assertThat(request.header("X-Request-Source")).isEqualTo("properties");
					JsonNode body = request.body();
					assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
					assertThat(body.get("max_tokens").asInt()).isEqualTo(500);
					assertThat(body.get("stop_sequences").toString()).isEqualTo("[\"END\",\"STOP\"]");
					assertThat(body.get("inference_geo").asString()).isEqualTo("eu");
					assertThat(body.get("service_tier").asString()).isEqualTo("auto");
					assertThat(body.get("tool_choice").toString())
						.isEqualTo("{\"type\":\"auto\",\"disable_parallel_tool_use\":true}");
					assertThat(body.get("system").get(0).has("cache_control")).isTrue();
					assertThat(body.get("system").get(1).has("cache_control")).isFalse();
					JsonNode webSearch = body.get("tools").get(0);
					assertThat(webSearch.get("max_uses").asInt()).isEqualTo(5);
					assertThat(webSearch.get("allowed_domains").toString())
						.isEqualTo("[\"docs.spring.io\",\"github.com\"]");
					assertThat(webSearch.get("blocked_domains").toString()).isEqualTo("[\"example.com\"]");
					assertThat(webSearch.get("user_location").get("city").asString()).isEqualTo("San Francisco");
					assertThat(webSearch.get("user_location").get("timezone").asString())
						.isEqualTo("America/Los_Angeles");
				});
		}
	}

	@Test
	void anOptionOfARequestWinsOverAProperty() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.anthropic.base-url=" + anthropic.url(),
						"spring.ai.anthropic.chat.model=claude-opus-5-5", "spring.ai.anthropic.chat.max-tokens=500")
				.run((context) -> {
					AnthropicChatModel model = context.getBean(AnthropicChatModel.class);

					ChatClient.create(model)
						.prompt("Hello")
						.options(AnthropicChatOptions.builder().model("claude-haiku-5-5"))
						.call()
						.content();
					model.call(new Prompt("Hello", model.getOptions().mutate().maxTokens(64).build()));

					JsonNode viaClient = anthropic.requests.get(0).body();
					assertThat(viaClient.get("model").asString()).isEqualTo("claude-haiku-5-5");
					assertThat(viaClient.get("max_tokens").asInt()).isEqualTo(500);
					JsonNode viaModel = anthropic.requests.get(1).body();
					assertThat(viaModel.get("model").asString()).isEqualTo("claude-opus-5-5");
					assertThat(viaModel.get("max_tokens").asInt()).isEqualTo(64);
				});
		}
	}

	@Test
	void theSamplingPropertiesAreIgnoredWithAWarning(CapturedOutput output) {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.anthropic.base-url=" + anthropic.url(),
						"spring.ai.anthropic.chat.temperature=0.5", "spring.ai.anthropic.chat.options.top-k=7")
				.run((context) -> {
					context.getBean(AnthropicChatModel.class).call(new Prompt("Hello"));

					JsonNode body = anthropic.single().body();
					assertThat(body.has("temperature")).isFalse();
					assertThat(body.has("top_k")).isFalse();
					assertThat(output).contains("ignoring temperature=0.5, topP=null, topK=7");
				});
		}
	}

	/**
	 * The upgrade guide of 2.0 flattened {@code spring.ai.anthropic.chat.options.*} into
	 * {@code spring.ai.anthropic.chat.*}; the old names still bind, deprecated.
	 */
	@Test
	void theFormerOptionsPrefixStillBinds() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			this.runner
				.withPropertyValues("spring.ai.anthropic.base-url=" + anthropic.url(),
						"spring.ai.anthropic.chat.options.model=claude-sonnet-5-5",
						"spring.ai.anthropic.chat.options.max-tokens=300")
				.run((context) -> {
					context.getBean(AnthropicChatModel.class).call(new Prompt("Hello"));

					assertThat(anthropic.single().body().get("model").asString()).isEqualTo("claude-sonnet-5-5");
					assertThat(anthropic.single().body().get("max_tokens").asInt()).isEqualTo(300);
				});
		}
	}

	@Test
	void anotherChatProviderLeavesNoAnthropicModel() {
		this.runner.withPropertyValues("spring.ai.model.chat=openai")
			.run((context) -> assertThat(context).doesNotHaveBean(AnthropicChatModel.class));
	}

	@Test
	void customizerBeansApplyInTheirOrderSoTheLastOneWins() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			this.runner.withPropertyValues("spring.ai.anthropic.base-url=" + anthropic.url())
				.withUserConfiguration(Customizers.class)
				.run((context) -> {
					context.getBean(AnthropicChatModel.class).call(new Prompt("Hello"));

					assertThat(anthropic.single().header("X-Customized")).isEqualTo("second");
				});
		}
	}

	@Test
	void connectionPoolMetricsAreBoundWhenEnabled() {
		this.runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
			.withPropertyValues("spring.ai.anthropic.chat.connection-pool-metrics-enabled=true")
			.run((context) -> assertThat(context.getBean(MeterRegistry.class).find("okhttp.pool.connection.count").gauges())
				.isNotEmpty());
		this.runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
			.run((context) -> assertThat(context.getBean(MeterRegistry.class).find("okhttp.pool.connection.count").gauges())
				.isEmpty());
	}

	@Configuration(proxyBeanMethods = false)
	static class Customizers {

		@Bean
		@Order(2)
		AnthropicHttpClientBuilderCustomizer second() {
			return (builder) -> builder.interceptor(
					(chain) -> chain.proceed(chain.request().newBuilder().header("X-Customized", "second").build()));
		}

		@Bean
		@Order(1)
		AnthropicHttpClientBuilderCustomizer first() {
			return (builder) -> builder.interceptor(
					(chain) -> chain.proceed(chain.request().newBuilder().header("X-Customized", "first").build()));
		}

	}

}
