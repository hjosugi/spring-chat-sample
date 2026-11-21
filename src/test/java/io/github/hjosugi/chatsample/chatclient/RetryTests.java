package io.github.hjosugi.chatsample.chatclient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * Retries of failed model calls, as the page "Retry" describes them: the
 * {@code RetryTemplate} and {@code ResponseErrorHandler} of Spring AI and their
 * properties, and the retries of the three providers of this sample, which are not
 * theirs. The provider stand-ins answer with {@code retry-after-ms}, so that the SDKs'
 * retries take no time.
 */
class RetryTests {

	private final ApplicationContextRunner retry = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
		.withPropertyValues("spring.ai.retry.backoff.initial-interval=1ms", "spring.ai.retry.backoff.max-interval=2ms");

	@Test
	void theTemplateRetriesTransientErrorsUpToMaxAttemptsAfterTheFirstCall() {
		this.retry.withPropertyValues("spring.ai.retry.max-attempts=3").run((context) -> {
			RetryTemplate template = context.getBean(RetryTemplate.class);
			AtomicInteger calls = new AtomicInteger();

			assertThatExceptionOfType(RetryException.class).isThrownBy(() -> template.execute(() -> {
				calls.incrementAndGet();
				throw new TransientAiException("HTTP 503");
			}));

			assertThat(calls).hasValue(4);
		});
	}

	@Test
	void theTemplateRetriesAnIoErrorButNoOtherException() {
		this.retry.withPropertyValues("spring.ai.retry.max-attempts=2").run((context) -> {
			RetryTemplate template = context.getBean(RetryTemplate.class);
			AtomicInteger io = new AtomicInteger();
			AtomicInteger invalid = new AtomicInteger();
			AtomicInteger other = new AtomicInteger();

			assertThatExceptionOfType(RetryException.class).isThrownBy(() -> template.execute(() -> {
				io.incrementAndGet();
				throw new ResourceAccessException("Connection refused");
			}));
			assertThatExceptionOfType(RetryException.class).isThrownBy(() -> template.execute(() -> {
				invalid.incrementAndGet();
				throw new NonTransientAiException("HTTP 400");
			}));
			assertThatExceptionOfType(RetryException.class).isThrownBy(() -> template.execute(() -> {
				other.incrementAndGet();
				throw new IllegalStateException("A bug");
			}));

			assertThat(io).hasValue(3);
			assertThat(invalid).hasValue(1);
			assertThat(other).hasValue(1);
		});
	}

	@Test
	void theDefaultsWaitTwoSecondsThenFiveTimesLongerUpToThreeMinutesForTenRetries() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
			.run((context) -> {
				assertThat(backOffs(context.getBean(RetryTemplate.class).getRetryPolicy())).containsExactly(2000L,
						10000L, 50000L, 180000L, 180000L, 180000L, 180000L, 180000L, 180000L, 180000L);
				assertThat(backOffs(RetryUtils.DEFAULT_RETRY_TEMPLATE.getRetryPolicy()))
					.isEqualTo(backOffs(context.getBean(RetryTemplate.class).getRetryPolicy()));
			});
	}

	@Test
	void theBackoffPropertiesShapeTheWaits() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
			.withPropertyValues("spring.ai.retry.max-attempts=3", "spring.ai.retry.backoff.initial-interval=1s",
					"spring.ai.retry.backoff.multiplier=2", "spring.ai.retry.backoff.max-interval=10s")
			.run((context) -> assertThat(backOffs(context.getBean(RetryTemplate.class).getRetryPolicy()))
				.containsExactly(1000L, 2000L, 4000L));
	}

	@Test
	void theErrorHandlerMakesServerErrorsTransientAndClientErrorsNot() {
		this.retry.run((context) -> {
			ResponseErrorHandler handler = context.getBean(ResponseErrorHandler.class);

			assertThatExceptionOfType(TransientAiException.class).isThrownBy(() -> handle(handler, HttpStatus.SERVICE_UNAVAILABLE))
				.withMessageContaining("HTTP 503");
			assertThatExceptionOfType(NonTransientAiException.class).isThrownBy(() -> handle(handler, HttpStatus.BAD_REQUEST));
			assertThatExceptionOfType(NonTransientAiException.class)
				.isThrownBy(() -> handle(handler, HttpStatus.TOO_MANY_REQUESTS));
		});
	}

	@Test
	void onClientErrorsMakesClientErrorsTransient() {
		this.retry.withPropertyValues("spring.ai.retry.on-client-errors=true").run((context) -> {
			ResponseErrorHandler handler = context.getBean(ResponseErrorHandler.class);

			assertThatExceptionOfType(TransientAiException.class).isThrownBy(() -> handle(handler, HttpStatus.BAD_REQUEST));
		});
	}

	@Test
	void theHttpCodePropertiesTurnSingleCodesOnAndOff() {
		this.retry.withPropertyValues("spring.ai.retry.on-http-codes=429", "spring.ai.retry.exclude-on-http-codes=503")
			.run((context) -> {
				ResponseErrorHandler handler = context.getBean(ResponseErrorHandler.class);

				assertThatExceptionOfType(TransientAiException.class)
					.isThrownBy(() -> handle(handler, HttpStatus.TOO_MANY_REQUESTS));
				assertThatExceptionOfType(NonTransientAiException.class)
					.isThrownBy(() -> handle(handler, HttpStatus.SERVICE_UNAVAILABLE));
				assertThatExceptionOfType(TransientAiException.class)
					.isThrownBy(() -> handle(handler, HttpStatus.BAD_GATEWAY));
			});
	}

	@Test
	void beansOfYourOwnReplaceTheAutoConfiguredOnes() {
		RetryTemplate own = new RetryTemplate(RetryPolicy.builder().maxRetries(1).build());
		ResponseErrorHandler ownHandler = new ResponseErrorHandler() {

			@Override
			public boolean hasError(ClientHttpResponse response) {
				return false;
			}

		};
		this.retry.withBean(RetryTemplate.class, () -> own)
			.withBean(ResponseErrorHandler.class, () -> ownHandler)
			.run((context) -> {
				assertThat(context.getBean(RetryTemplate.class)).isSameAs(own);
				assertThat(context.getBean(ResponseErrorHandler.class)).isSameAs(ownHandler);
			});
	}

	/**
	 * Not on the page: the retry warning of the auto-configured template counts the
	 * retries of all calls together, for as long as the application runs.
	 */
	@Test
	void theRetryCountInTheWarningKeepsGrowingAcrossCalls() {
		this.retry.withPropertyValues("spring.ai.retry.max-attempts=1").run((context) -> {
			RetryTemplate template = context.getBean(RetryTemplate.class);
			try (LogCapture log = new LogCapture(SpringAiRetryAutoConfiguration.class, Level.WARN)) {
				for (int call = 0; call < 2; call++) {
					assertThatExceptionOfType(RetryException.class).isThrownBy(() -> template.execute(() -> {
						throw new TransientAiException("HTTP 503");
					}));
				}

				assertThat(log.messages()).containsExactly("Retry error. Retry count:1", "Retry error. Retry count:2");
			}
		});
	}

	@Test
	void openAiRetriesThroughItsSdkThreeTimesByDefault() {
		try (StandIn openAi = new StandIn(RetryTests::unavailable)) {
			new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
						SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
				.withPropertyValues("spring.ai.openai.api-key=test-key", "spring.ai.openai.base-url=" + openAi.url(),
						"spring.ai.retry.max-attempts=7")
				.run((context) -> {
					OpenAiChatModel model = context.getBean(OpenAiChatModel.class);

					assertThatRuntimeException().isThrownBy(() -> model.call(new Prompt("Hi")));

					assertThat(openAi.requests).hasSize(4);
				});
		}
	}

	@Test
	void theOpenAiRetriesComeFromTheirOwnProperty() {
		try (StandIn openAi = new StandIn(RetryTests::unavailable)) {
			new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
						SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
				.withPropertyValues("spring.ai.openai.api-key=test-key", "spring.ai.openai.base-url=" + openAi.url(),
						"spring.ai.openai.max-retries=1")
				.run((context) -> {
					assertThatRuntimeException()
						.isThrownBy(() -> context.getBean(OpenAiChatModel.class).call(new Prompt("Hi")));

					assertThat(openAi.requests).hasSize(2);
				});
		}
	}

	@Test
	void anthropicRetriesThroughItsSdkTwiceUnlessToldOtherwise() {
		try (StandIn anthropic = new StandIn(RetryTests::unavailable)) {
			ApplicationContextRunner runner = new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
						SpringAiRetryAutoConfiguration.class, AnthropicChatAutoConfiguration.class))
				.withPropertyValues("spring.ai.anthropic.api-key=test-key",
						"spring.ai.anthropic.base-url=" + anthropic.url());

			runner.run((context) -> assertThatRuntimeException()
				.isThrownBy(() -> context.getBean(AnthropicChatModel.class).call(new Prompt("Hi"))));
			int byDefault = anthropic.requests.size();
			anthropic.reset();
			runner.withPropertyValues("spring.ai.anthropic.max-retries=0")
				.run((context) -> assertThatRuntimeException()
					.isThrownBy(() -> context.getBean(AnthropicChatModel.class).call(new Prompt("Hi"))));

			assertThat(byDefault).isEqualTo(3);
			assertThat(anthropic.requests).hasSize(1);
		}
	}

	/**
	 * The page says that the {@code RetryTemplate} does not retry a failed call of the
	 * Google SDK. It does not, but the SDK retries on its own, which the page does not
	 * say: five requests in all, over some 20 seconds with its default delays, which this
	 * test shortens.
	 */
	@Test
	void theRetryTemplateDoesNotRetryAFailedGeminiCallButTheGoogleSdkDoes() {
		try (StandIn gemini = new StandIn(RetryTests::unavailable)) {
			AtomicInteger retries = new AtomicInteger();
			RetryTemplate template = new RetryTemplate(RetryPolicy.builder()
				.maxRetries(3)
				.includes(TransientAiException.class)
				.includes(ResourceAccessException.class)
				.delay(Duration.ofMillis(1))
				.build());
			template.setRetryListener(new RetryListener() {

				@Override
				public void beforeRetry(RetryPolicy retryPolicy, Retryable<?> retryable) {
					retries.incrementAndGet();
				}

			});
			Client client = Client.builder()
				.apiKey("test-key")
				.httpOptions(HttpOptions.builder()
					.baseUrl(gemini.url())
					.retryOptions(HttpRetryOptions.builder().initialDelay(0.001).maxDelay(0.002).jitter(0.0))
					.build())
				.build();
			GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
				.genAiClient(client)
				.options(GoogleGenAiChatOptions.builder().model(Providers.GEMINI_MODEL).build())
				.retryTemplate(template)
				.build();

			assertThatRuntimeException().isThrownBy(() -> model.call(new Prompt("Hi")))
				.isNotInstanceOf(TransientAiException.class);

			assertThat(retries).hasValue(0);
			assertThat(gemini.requests).hasSize(5);
		}
	}

	@Test
	void aModelBuiltWithoutSpringBootTakesItsRetriesFromItsOptions() {
		try (StandIn anthropic = new StandIn(RetryTests::unavailable)) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder()
					.baseUrl(anthropic.url())
					.apiKey("test-key")
					.model(Providers.ANTHROPIC_MODEL)
					.maxTokens(100)
					.maxRetries(1)
					.build())
				.build();

			assertThatRuntimeException().isThrownBy(() -> model.call(new Prompt("Hi")));

			assertThat(anthropic.requests).hasSize(2);
		}
	}

	/**
	 * The waits of a policy until it gives up.
	 */
	private static List<Long> backOffs(RetryPolicy policy) {
		List<Long> waits = new ArrayList<>();
		BackOffExecution execution = policy.getBackOff().start();
		for (long wait = execution.nextBackOff(); wait != BackOffExecution.STOP && waits.size() < 20; wait = execution
			.nextBackOff()) {
			waits.add(wait);
		}
		return waits;
	}

	private static Reply unavailable(StandIn.Request request) {
		return Reply.json(503, "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"Try again\"}}")
			.header("retry-after-ms", "1");
	}

	private static void handle(ResponseErrorHandler handler, HttpStatus status) throws Exception {
		MockClientHttpResponse response = new MockClientHttpResponse("{\"error\":\"x\"}".getBytes(), status);
		handler.handleError(URI.create("http://localhost/v1/chat"), HttpMethod.POST, response);
	}

}
