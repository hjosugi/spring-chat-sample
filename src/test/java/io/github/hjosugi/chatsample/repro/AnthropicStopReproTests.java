package io.github.hjosugi.chatsample.repro;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancelling a stream of Spring AI's {@link AnthropicChatModel} should close its
 * connection, so that the model stops writing and billing. Nothing else is involved:
 * no Spring Boot, no ChatClient, no spring-chat.
 * <p>
 * With Spring AI 2.0.2 the first test fails: the connection stays open and the model
 * streams the whole reply. The cancel closes the response on the cancelling thread
 * while the reading thread is inside a read; OkHttp, discarding the rest of the body,
 * enters the read timeout a second time and throws "Unbalanced enter/exit".
 */
class AnthropicStopReproTests {

	private final List<Throwable> dropped = new CopyOnWriteArrayList<>();

	@AfterEach
	void resetHooks() {
		Hooks.resetOnErrorDropped();
	}

	@Test
	void cancellingAStreamClosesItsConnection() throws Exception {
		Hooks.onErrorDropped(this.dropped::add);
		// 200 words, 50 ms apart: the reply takes 10 seconds.
		try (SlowModel anthropic = new SlowModel(0, 200, Duration.ofMillis(50))) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder()
					.baseUrl(anthropic.url())
					.apiKey("test-key")
					.model("claude-haiku-5-5")
					.maxTokens(1024)
					.build())
				.build();
			CountDownLatch firstWords = new CountDownLatch(1);

			Disposable reply = model.stream(new Prompt("Tell me a long story")).subscribe((response) -> firstWords.countDown());
			assertThat(firstWords.await(10, TimeUnit.SECONDS)).as("the first words").isTrue();
			reply.dispose();

			assertThat(anthropic.closedByClient())
				.as(() -> "the connection closed after the cancel; errors dropped while cancelling: " + this.dropped)
				.succeedsWithin(Duration.ofSeconds(5));
		}
	}

	/**
	 * Shows that {@link SlowModel} sees a client that closes the connection, so a
	 * failure of the test above is not the stand-in's.
	 */
	@Test
	void theStandInSeesAClientThatClosesTheConnection() throws Exception {
		try (SlowModel anthropic = new SlowModel(0, 200, Duration.ofMillis(50))) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(anthropic.url() + "/v1/messages"))
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build();
			InputStream body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
			body.readNBytes(400);

			body.close();

			assertThat(anthropic.closedByClient()).succeedsWithin(Duration.ofSeconds(5));
		}
	}

}
