package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * The metrics that the observations produce, with the names they have in Prometheus, as
 * the page "Metrics Reference" lists them. The registry is set up as Spring Boot sets it
 * up: a timer and a long task timer for every observation, and Spring AI's handler for
 * the token usage.
 */
class MetricsTests {

	private static final String TOOL_CALL = Providers.openAiCompletion("{\"role\":\"assistant\",\"content\":null,"
			+ "\"refusal\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"weather\","
			+ "\"arguments\":\"{}\"}}]}", "tool_calls", "{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}");

	private static final String ANSWER = Providers.openAiCompletion(
			"{\"role\":\"assistant\",\"content\":\"Sunny\",\"refusal\":null}", "stop",
			"{\"prompt_tokens\":30,\"completion_tokens\":4,\"total_tokens\":34}");

	private final PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

	private final ObservationRegistry observations = ObservationRegistry.create();

	MetricsTests() {
		this.observations.observationConfig()
			.observationHandler(new DefaultMeterObservationHandler(this.meters))
			.observationHandler(new ChatModelMeterObservationHandler(this.meters));
	}

	@Test
	void aCallWithAToolProducesTheTimersOfThePage() {
		try (StandIn openAi = new StandIn(
				(request) -> request.text().contains("\"role\":\"tool\"") ? Reply.json(ANSWER) : Reply.json(TOOL_CALL))) {
			ChatClient.create(openAi(openAi), this.observations)
				.prompt("What is the weather?")
				.tools(new Weather())
				.call()
				.content();

			String scrape = this.meters.scrape();
			assertThat(scrape).contains("spring_ai_chat_client_seconds_count{error=\"none\",gen_ai_operation_name=\"framework\","
					+ "gen_ai_system=\"spring_ai\",spring_ai_chat_client_stream=\"false\",spring_ai_kind=\"chat_client\"} 1");
			assertThat(scrape).contains("spring_ai_advisor_seconds_count{error=\"none\",gen_ai_operation_name=\"framework\","
					+ "gen_ai_system=\"spring_ai\",spring_ai_advisor_name=\"call\",spring_ai_kind=\"advisor\"} 2");
			assertThat(scrape).contains("spring_ai_advisor_seconds_count{error=\"none\",gen_ai_operation_name=\"framework\","
					+ "gen_ai_system=\"spring_ai\",spring_ai_advisor_name=\"Tool Calling Advisor\",spring_ai_kind=\"advisor\"} 1");
			assertThat(scrape).contains("gen_ai_client_operation_seconds_count{error=\"none\",gen_ai_operation_name=\"chat\","
					+ "gen_ai_request_model=\"" + Providers.OPENAI_MODEL + "\",gen_ai_response_model=\""
					+ Providers.OPENAI_MODEL + "-2026-01-01\",gen_ai_system=\"openai\"} 2");
			assertThat(scrape).contains("spring_ai_tool_seconds_count{error=\"none\",gen_ai_operation_name=\"execute_tool\","
					+ "gen_ai_system=\"spring_ai\",spring_ai_kind=\"tool_call\",spring_ai_tool_definition_name=\"weather\","
					+ "spring_ai_tool_type=\"function\"} 1");
			assertThat(scrape).contains("gen_ai_client_operation_seconds_sum{", "gen_ai_client_operation_seconds_max{");
		}
	}

	@Test
	void theTokensOfEachModelCallAreCountedByType() {
		try (StandIn openAi = new StandIn(
				(request) -> request.text().contains("\"role\":\"tool\"") ? Reply.json(ANSWER) : Reply.json(TOOL_CALL))) {
			ChatClient.create(openAi(openAi), this.observations)
				.prompt("What is the weather?")
				.tools(new Weather())
				.call()
				.content();

			String scrape = this.meters.scrape();
			String tags = "gen_ai_operation_name=\"chat\",gen_ai_request_model=\"" + Providers.OPENAI_MODEL
					+ "\",gen_ai_response_model=\"" + Providers.OPENAI_MODEL + "-2026-01-01\",gen_ai_system=\"openai\",";
			assertThat(scrape).contains("gen_ai_client_token_usage_total{" + tags + "gen_ai_token_type=\"input\"} 50.0");
			assertThat(scrape).contains("gen_ai_client_token_usage_total{" + tags + "gen_ai_token_type=\"output\"} 9.0");
			assertThat(scrape).contains("gen_ai_client_token_usage_total{" + tags + "gen_ai_token_type=\"total\"} 59.0");
		}
	}

	@Test
	void aFailedModelCallHasTheExceptionInItsErrorTag() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(400, "{\"error\":{\"message\":\"Bad request\"}}"))) {
			assertThatRuntimeException().isThrownBy(() -> openAi(openAi).call(new Prompt("Hi")));

			assertThat(this.meters.scrape()).containsPattern("gen_ai_client_operation_seconds_count\\{error=\"[A-Za-z]+Exception\"");
		}
	}

	@Test
	void aRunningModelCallIsCountedByTheActiveTimer() throws Exception {
		CountDownLatch replying = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (StandIn openAi = new StandIn((request) -> {
			replying.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return Reply.json(ANSWER);
		})) {
			ChatModel model = openAi(openAi);
			Disposable running = Mono.fromCallable(() -> model.call(new Prompt("Hi")))
				.subscribeOn(Schedulers.boundedElastic())
				.subscribe();
			assertThat(replying.await(10, TimeUnit.SECONDS)).isTrue();

			String during = this.meters.scrape();
			release.countDown();

			assertThat(during).contains("gen_ai_client_operation_active_seconds_count{gen_ai_operation_name=\"chat\"");
			assertThat(during).containsPattern("gen_ai_client_operation_active_seconds_count\\{[^}]*\\} 1");
			running.dispose();
		}
	}

	@Test
	void springAiCountsTheTokensOnlyWhenThereIsAMeterRegistry() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ChatObservationAutoConfiguration.class))
			.run((context) -> assertThat(context).doesNotHaveBean(ChatModelMeterObservationHandler.class));
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ChatObservationAutoConfiguration.class))
			.withBean(MeterRegistry.class, () -> this.meters)
			.run((context) -> assertThat(context).hasSingleBean(ChatModelMeterObservationHandler.class));
	}

	private ChatModel openAi(StandIn standIn) {
		return Providers.openAi(standIn, this.observations, (options) -> {
		});
	}

	static class Weather {

		@Tool(description = "Tells the weather")
		String weather() {
			return "sunny";
		}

	}

}
