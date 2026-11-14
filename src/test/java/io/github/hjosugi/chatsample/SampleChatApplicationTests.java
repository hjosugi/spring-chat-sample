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
		properties = { "spring.ai.anthropic.api-key=test-key", "spring.ai.anthropic.max-retries=0" })
class SampleChatApplicationTests {

	private static final Pattern ORDER_ID = Pattern.compile("[AB]-\\d{4}");

	private static final FakeAnthropic anthropic = start();

	private static final JsonMapper json = JsonMapper.shared();

	@Value("${local.server.port}")
	private int port;

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
					return FakeAnthropic.toolUse("orderStatus", "{\"orderId\": \"" + orderId.group() + "\"}");
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
		assertThat(browser.post("/chat/stream", message("What did I order?"), Map.of()).statusCode())
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
	void aMessageWithoutTheCsrfTokenIsRefused() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(alice.post("/chat/stream", message("What did I order?"), Map.of()).statusCode()).isEqualTo(403);
		assertThat(anthropic.requests).isEmpty();
	}

	private static String message(String text) {
		return json.writeValueAsString(Map.of("conversationId", "c-" + text.hashCode(), "text", text));
	}

	/**
	 * Keeps the session cookie, and the CSRF token of the last page, as a browser does.
	 */
	private static final class Browser {

		private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

		private static final Pattern CSRF_DATA = Pattern
			.compile("data-csrf-header=\"([^\"]+)\"\\s+data-csrf-token=\"([^\"]+)\"");

		final HttpClient http = HttpClient.newBuilder()
			.cookieHandler(new CookieManager())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

		private final int port;

		private Map<String, String> csrf = Map.of();

		Browser(int port) {
			this.port = port;
		}

		static Browser signIn(int port, String user) throws Exception {
			Browser browser = new Browser(port);
			Matcher token = CSRF_INPUT.matcher(browser.get("/login").body());
			assertThat(token.find()).as("CSRF token on the login page").isTrue();
			HttpResponse<String> login = browser.http.send(HttpRequest.newBuilder(browser.uri("/login"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=" + user + "&_csrf="
						+ URLEncoder.encode(token.group(1), StandardCharsets.UTF_8)))
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(login.headers().firstValue("Location")).as("redirect after login")
				.get()
				.asString()
				.doesNotContain("error");
			String page = browser.get("/").body();
			Matcher data = CSRF_DATA.matcher(page);
			assertThat(data.find()).as("CSRF token in the chat fragment").isTrue();
			browser.csrf = Map.of(data.group(1), data.group(2));
			return browser;
		}

		String chat(String text) throws Exception {
			HttpResponse<String> response = this.http.send(chatRequest(text), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			return response.body();
		}

		HttpRequest chatRequest(String text) {
			return request("/chat/stream", message(text), this.csrf);
		}

		HttpResponse<String> get(String path) throws Exception {
			return this.http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
		}

		HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
			return this.http.send(request(path, body, headers), HttpResponse.BodyHandlers.ofString());
		}

		private HttpRequest request(String path, String body, Map<String, String> headers) {
			HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
			headers.forEach(request::header);
			return request.build();
		}

		private URI uri(String path) {
			return URI.create("http://localhost:" + this.port + path);
		}

	}

}
