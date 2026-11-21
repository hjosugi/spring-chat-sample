package io.github.hjosugi.chatsample;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses the application as a browser does: it signs in through the login form, keeps the
 * session cookie and the CSRF token of the last page, and sends what the chat's page
 * sends.
 */
final class Browser {

	private static final JsonMapper json = JsonMapper.shared();

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
		return reply(chatRequest(text));
	}

	/**
	 * Answers the tool call that the reply to a question waits for.
	 */
	String decide(String question, boolean allow) throws Exception {
		return reply(request("/chat/stream", decisions(question, allow), this.csrf));
	}

	/**
	 * Answers the tool call of a reply that names its own, as the chat's page does with
	 * the id that the {@code confirm} event carried.
	 */
	String decide(String question, String callId, boolean allow) throws Exception {
		return reply(request("/chat/stream", decisions(question, callId, allow), this.csrf));
	}

	/**
	 * The whole reply to a request, as the events the chat sent. With a limit, so that a
	 * reply that never ends fails its test and does not hold the build.
	 */
	private String reply(HttpRequest request) throws Exception {
		HttpResponse<String> response = this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.get(60, TimeUnit.SECONDS);
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

	/**
	 * What the chat's page sends for a message. The same words are the same conversation.
	 */
	static String message(String text) {
		return json.writeValueAsString(Map.of("conversationId", conversationOf(text), "text", text));
	}

	/**
	 * The conversation of a question, so that its decisions go to the same one.
	 */
	static String conversationOf(String text) {
		return "c-" + text.hashCode();
	}

	/**
	 * The user's answer to the one tool call that the stand-in asks for.
	 */
	static String decisions(String question, boolean allow) {
		return decisions(question, "toolu_1", allow);
	}

	static String decisions(String question, String callId, boolean allow) {
		return json.writeValueAsString(Map.of("conversationId", conversationOf(question), "decisions",
				List.of(Map.of("id", callId, "allow", allow))));
	}

}
