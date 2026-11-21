package io.github.hjosugi.chatsample;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application as a browser uses it: the login form, the page and its CSRF token, then
 * the chat over SSE and WebSocket. Spring AI's own Anthropic client talks to a stand-in
 * for the API, which asks for the tools as Claude would.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.anthropic.api-key=test-key", "spring.ai.anthropic.max-retries=0",
				// Whatever the environment says: a test never talks to a provider.
				"spring.ai.model.chat=anthropic", "spring.ai.anthropic.chat.model=claude-haiku-5-5" })
class SampleChatApplicationTests {

	private static final Pattern ORDER_ID = Pattern.compile("[AB]-\\d{4}");

	private static final FakeAnthropic anthropic = start();

	private static final JsonMapper json = JsonMapper.shared();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private Orders orders;

	private static FakeAnthropic start() {
		try {
			return new FakeAnthropic((body) -> {
				String result = FakeAnthropic.toolResult(body);
				if (result != null) {
					return FakeAnthropic.text("Found: ", result);
				}
				String question = body.get("messages").get(body.get("messages").size() - 1).toString();
				if (question.contains("long story")) {
					// 400 words: 20 seconds.
					return FakeAnthropic.slowText(400);
				}
				Matcher orderId = ORDER_ID.matcher(question);
				if (orderId.find()) {
					return FakeAnthropic.toolUse(question.contains("Cancel") ? "cancelOrder" : "orderStatus",
							"{\"orderId\": \"" + orderId.group() + "\"}");
				}
				return FakeAnthropic.toolUse("myOrders", "{}");
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void anthropicUrl(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.anthropic.base-url", anthropic::url);
	}

	@AfterAll
	static void stop() {
		anthropic.stop();
	}

	@BeforeEach
	void forget() {
		anthropic.reset();
	}

	@Test
	void someoneWhoIsNotSignedInGetsTheLoginPageAndNoChat() throws Exception {
		Browser browser = new Browser(this.port);

		HttpResponse<String> home = browser.get("/");
		assertThat(home.statusCode()).isEqualTo(302);
		assertThat(home.headers().firstValue("Location")).get().asString().endsWith("/login");
		assertThat(browser.post("/chat/stream", Browser.message("What did I order?"), Map.of()).statusCode())
			.isNotEqualTo(200);
		assertThat(anthropic.requests).isEmpty();
	}

	@Test
	void aliceAsksAboutHerOrdersOverSse() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		String reply = alice.chat("What did I order?");

		assertThat(reply).contains("event:done", "A-1001", "A-1002").doesNotContain("B-2001", "event:error");
		JsonNode first = anthropic.requests.get(0);
		assertThat(first.get("tools").toString()).contains("myOrders", "orderStatus");
		assertThat(first.get("system").toString()).contains("assistant of a small online shop");
	}

	@Test
	void aliceCannotReadBobsOrder() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(alice.chat("Where is B-2001?")).contains("has no order B-2001").doesNotContain("Kettle");
	}

	@Test
	void bobAsksOverWebSocket() throws Exception {
		Browser bob = Browser.signIn(this.port, "bob");
		CompletableFuture<String> reply = new CompletableFuture<>();
		StringBuilder frames = new StringBuilder();
		WebSocket socket = bob.http.newWebSocketBuilder()
			.header("Origin", "http://localhost:" + this.port)
			.buildAsync(URI.create("ws://localhost:" + this.port + "/chat/ws"), new WebSocket.Listener() {

				@Override
				public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
					frames.append(data).append('\n');
					if (data.toString().contains("\"done\"") || data.toString().contains("\"error\"")) {
						reply.complete(frames.toString());
					}
					webSocket.request(1);
					return null;
				}

			})
			.get(10, TimeUnit.SECONDS);
		socket.sendText(json.writeValueAsString(
				Map.of("type", "message", "conversationId", "bob-ws", "text", "What did I order?")), true);

		assertThat(reply.get(15, TimeUnit.SECONDS)).contains("B-2001", "Kettle").doesNotContain("A-1001");
		socket.abort();
	}

	@Test
	void stoppingAReplyEndsItAtAnthropic() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		CompletableFuture<Void> firstWords = new CompletableFuture<>();
		WebSocket socket = alice.http.newWebSocketBuilder()
			.header("Origin", "http://localhost:" + this.port)
			.buildAsync(URI.create("ws://localhost:" + this.port + "/chat/ws"), new WebSocket.Listener() {

				@Override
				public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
					if (data.toString().contains("\"text\"")) {
						firstWords.complete(null);
					}
					webSocket.request(1);
					return null;
				}

			})
			.get(10, TimeUnit.SECONDS);
		socket.sendText(json.writeValueAsString(
				Map.of("type", "message", "conversationId", "alice-stop", "text", "Tell me a long story")), true);
		firstWords.get(10, TimeUnit.SECONDS);

		socket.sendText("{\"type\":\"stop\"}", true);

		assertThat(anthropic.clientGone).as("the connection to Anthropic closed").succeedsWithin(Duration.ofSeconds(5));
		socket.abort();
	}

	@Test
	void leavingAReplyEndsItAtAnthropic() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		InputStream reply = alice.http
			.send(alice.chatRequest("Tell me a long story"), HttpResponse.BodyHandlers.ofInputStream())
			.body();
		String received = "";
		while (!received.contains("event:text")) {
			received += new String(reply.readNBytes(64), StandardCharsets.UTF_8);
		}

		reply.close();

		assertThat(anthropic.clientGone).as("the connection to Anthropic closed").succeedsWithin(Duration.ofSeconds(5));
	}

	@Test
	void cancellingAnOrderWaitsForTheUsersAnswerAndCancelsNothing() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		// Its own words: the same words are the same conversation, and another test's answer is in it.
		String reply = alice.chat("Cancel A-1002 now");

		assertThat(reply).contains("event:confirm", "cancelOrder", "A-1002").doesNotContain("event:done", "event:error");
		assertThat(anthropic.requests).hasSize(1);
		assertThat(anthropic.requests.get(0).get("tools").toString()).contains("cancelOrder");
		assertThat(status("alice", "A-1002")).isEqualTo(Orders.BEING_PACKED);
	}

	@Test
	void allowingTheCancellationCancelsTheOrderAndTheModelTellsSo() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Cancel A-1003");

		String answer = alice.decide("Cancel A-1003", true);

		assertThat(answer).contains("A-1003 is cancelled", "event:done").doesNotContain("event:error");
		assertThat(status("alice", "A-1003")).isEqualTo(Orders.CANCELLED);
		assertThat(anthropic.requests).hasSize(2);
		assertThat(anthropic.requests.get(1).toString()).contains("tool_result", "A-1003 is cancelled");
	}

	@Test
	void denyingTheCancellationKeepsTheOrderAndTheModelHearsIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Cancel A-1002");

		String answer = alice.decide("Cancel A-1002", false);

		assertThat(answer).contains("event:done").doesNotContain("event:error");
		assertThat(status("alice", "A-1002")).isEqualTo(Orders.BEING_PACKED);
		assertThat(anthropic.requests.get(1).toString()).contains("tool_result", "did not allow");
	}

	@Test
	void bobCannotAllowAlicesCancellation() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		Browser bob = Browser.signIn(this.port, "bob");
		alice.chat("Cancel A-1004");

		// Bob sends the same conversation id: it is not Alice's conversation.
		String answer = bob.decide("Cancel A-1004", true);

		assertThat(answer).contains("event:error").doesNotContain("event:done", "event:text");
		assertThat(status("alice", "A-1004")).isEqualTo(Orders.BEING_PACKED);
		assertThat(anthropic.requests).hasSize(1);
		// Alice still can.
		assertThat(alice.decide("Cancel A-1004", true)).contains("A-1004 is cancelled");
	}

	@Test
	void aliceCannotCancelBobsOrderEvenWhenSheAllowsIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Cancel B-2001");

		assertThat(alice.decide("Cancel B-2001", true)).contains("has no order B-2001");
		assertThat(status("bob", "B-2001")).isEqualTo("delivered on November 9");
	}

	@Test
	void anOrderThatShippedCannotBeCancelled() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Cancel A-1001");

		assertThat(alice.decide("Cancel A-1001", true)).contains("can no longer be cancelled");
		assertThat(status("alice", "A-1001")).startsWith("shipped");
	}

	private String status(String user, String order) {
		return this.orders.find(user, order).orElseThrow().status();
	}

	@Test
	void aMessageWithoutTheCsrfTokenIsRefused() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(alice.post("/chat/stream", Browser.message("What did I order?"), Map.of()).statusCode()).isEqualTo(403);
		assertThat(anthropic.requests).isEmpty();
	}

}
