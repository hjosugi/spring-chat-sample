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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * for the API, which asks for the tools as Claude would, in a workspace of its own.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.anthropic.api-key=test-key", "spring.ai.anthropic.max-retries=0",
				// Whatever the environment says: a test never talks to a provider.
				"spring.ai.model.chat=anthropic", "spring.ai.anthropic.chat.model=claude-haiku-5-5" })
class SampleChatApplicationTests {

	private static final Pattern WRITE = Pattern.compile("Write (\\S+) (.+)");

	private static final Pattern RUN = Pattern.compile("Run (.+)");

	private static final Pattern READ = Pattern.compile("Read (\\S+)");

	private static final Path WORKSPACE = workspace();

	private static final FakeAnthropic anthropic = start();

	private static final JsonMapper json = JsonMapper.shared();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private Workspace workspace;

	private static Path workspace() {
		try {
			return Files.createTempDirectory("agent-workspace");
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static FakeAnthropic start() {
		try {
			return new FakeAnthropic((body) -> {
				String result = FakeAnthropic.toolResult(body);
				if (result != null) {
					return FakeAnthropic.text("Done: ", result);
				}
				String question = body.get("messages").get(body.get("messages").size() - 1).toString();
				if (question.contains("long story")) {
					// 400 words: 20 seconds.
					return FakeAnthropic.slowText(400);
				}
				Matcher write = WRITE.matcher(text(question));
				if (write.find()) {
					return FakeAnthropic.toolUse("writeFile", json.writeValueAsString(
							Map.of("path", write.group(1), "content", write.group(2))));
				}
				Matcher run = RUN.matcher(text(question));
				if (run.find()) {
					return FakeAnthropic.toolUse("runCommand", json.writeValueAsString(Map.of("command", run.group(1))));
				}
				Matcher read = READ.matcher(text(question));
				if (read.find()) {
					return FakeAnthropic.toolUse("readFile", json.writeValueAsString(Map.of("path", read.group(1))));
				}
				return FakeAnthropic.toolUse("listFiles", "{\"path\": \".\"}");
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * What the user wrote in the last message of a request, which the stand-in sees as JSON.
	 */
	private static String text(String message) {
		try {
			JsonNode content = json.readTree(message).get("content");
			return content.isString() ? content.asString() : content.get(0).get("text").asString();
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	@DynamicPropertySource
	static void standIns(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.anthropic.base-url", anthropic::url);
		registry.add("agent.workspace", WORKSPACE::toString);
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
		assertThat(browser.post("/chat/stream", Browser.message("List the files"), Map.of()).statusCode())
			.isNotEqualTo(200);
		assertThat(anthropic.requests).isEmpty();
	}

	@Test
	void aliceListsHerFilesOverSse() throws Exception {
		Files.writeString(this.workspace.of("alice").resolve("notes.txt"), "hello");
		Browser alice = Browser.signIn(this.port, "alice");

		String reply = alice.chat("List my files");

		assertThat(reply).contains("event:done", "notes.txt (5 bytes)").doesNotContain("event:error");
		JsonNode first = anthropic.requests.get(0);
		assertThat(first.get("tools").toString()).contains("listFiles", "readFile", "searchFiles", "writeFile",
				"replaceInFile", "runCommand");
		assertThat(first.get("system").toString()).contains("You are a coding agent");
	}

	@Test
	void aliceCannotReadBobsFiles() throws Exception {
		Files.writeString(this.workspace.of("bob").resolve("secret.txt"), "bobs secret");
		Browser alice = Browser.signIn(this.port, "alice");

		String reply = alice.chat("Read ../bob/secret.txt");

		assertThat(reply).contains("is outside the workspace").doesNotContain("bobs secret");
	}

	@Test
	void bobListsHisFilesOverWebSocket() throws Exception {
		Files.writeString(this.workspace.of("bob").resolve("bobs.txt"), "mine");
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
				Map.of("type", "message", "conversationId", "bob-ws", "text", "List my files")), true);

		assertThat(reply.get(15, TimeUnit.SECONDS)).contains("bobs.txt").doesNotContain("notes.txt");
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
	void writingAFileWaitsForTheUsersAnswerAndWritesNothing() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		// Its own words: the same words are the same conversation, and another test's answer is in it.
		String reply = alice.chat("Write waiting.txt now");

		assertThat(reply).contains("event:confirm", "writeFile", "waiting.txt").doesNotContain("event:done", "event:error");
		assertThat(anthropic.requests).hasSize(1);
		assertThat(anthropic.requests.get(0).get("tools").toString()).contains("writeFile");
		assertThat(this.workspace.of("alice").resolve("waiting.txt")).doesNotExist();
	}

	@Test
	void allowingTheWriteWritesTheFileAndTheModelTellsSo() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Write allowed.txt hi there");

		String answer = alice.decide("Write allowed.txt hi there", true);

		assertThat(answer).contains("Created allowed.txt", "event:done").doesNotContain("event:error");
		assertThat(this.workspace.of("alice").resolve("allowed.txt")).hasContent("hi there");
		assertThat(anthropic.requests).hasSize(2);
		assertThat(anthropic.requests.get(1).toString()).contains("tool_result", "Created allowed.txt");
	}

	@Test
	void denyingTheWriteKeepsTheFileOutAndTheModelHearsIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Write denied.txt no thanks");

		String answer = alice.decide("Write denied.txt no thanks", false);

		assertThat(answer).contains("event:done").doesNotContain("event:error");
		assertThat(this.workspace.of("alice").resolve("denied.txt")).doesNotExist();
		assertThat(anthropic.requests.get(1).toString()).contains("tool_result", "did not allow");
	}

	@Test
	void bobCannotAllowAlicesWrite() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		Browser bob = Browser.signIn(this.port, "bob");
		alice.chat("Write stolen.txt yes");

		// Bob sends the same conversation id: it is not Alice's conversation.
		String answer = bob.decide("Write stolen.txt yes", true);

		assertThat(answer).contains("event:error").doesNotContain("event:done", "event:text");
		assertThat(this.workspace.of("alice").resolve("stolen.txt")).doesNotExist();
		assertThat(this.workspace.of("bob").resolve("stolen.txt")).doesNotExist();
		assertThat(anthropic.requests).hasSize(1);
		// Alice still can.
		assertThat(alice.decide("Write stolen.txt yes", true)).contains("Created stolen.txt");
		assertThat(this.workspace.of("alice").resolve("stolen.txt")).hasContent("yes");
	}

	@Test
	void aliceCannotWriteInBobsWorkspaceEvenWhenSheAllowsIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Write ../bob/planted.txt x");

		assertThat(alice.decide("Write ../bob/planted.txt x", true)).contains("is outside the workspace");
		assertThat(this.workspace.of("bob").resolve("planted.txt")).doesNotExist();
	}

	@Test
	void aCommandRunsOnlyAfterTheUserAllowedItAndInTheirWorkspace() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		String command = "Run echo ran > ran.txt && echo hello";

		String reply = alice.chat(command);

		assertThat(reply).contains("event:confirm", "runCommand", "echo ran").doesNotContain("event:done", "event:error");
		assertThat(this.workspace.of("alice").resolve("ran.txt")).doesNotExist();

		String answer = alice.decide(command, true);

		assertThat(answer).contains("exit code 0", "hello", "event:done").doesNotContain("event:error");
		assertThat(this.workspace.of("alice").resolve("ran.txt")).exists();
		assertThat(this.workspace.of("bob").resolve("ran.txt")).doesNotExist();
	}

	@Test
	void aCommandDoesNotSeeTheKeysOfTheApplication() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Run env");

		String answer = alice.decide("Run env", true);

		assertThat(answer).contains("exit code 0", "HOME=").doesNotContain("ANTHROPIC", "OPENAI", "test-key");
	}

	@Test
	void aMessageWithoutTheCsrfTokenIsRefused() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(alice.post("/chat/stream", Browser.message("List the files"), Map.of()).statusCode()).isEqualTo(403);
		assertThat(anthropic.requests).isEmpty();
	}

}
