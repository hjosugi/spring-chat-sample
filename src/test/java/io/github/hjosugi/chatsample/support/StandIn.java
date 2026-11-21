package io.github.hjosugi.chatsample.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the HTTP API of a provider. It keeps each request and answers with what
 * a script makes of it, so that Spring AI's own client for the provider builds the
 * request and reads the reply. No test talks to a provider, and none needs a key.
 */
public final class StandIn implements AutoCloseable {

	/**
	 * Among the events of a reply, a pause of 50 milliseconds.
	 */
	public static final String PAUSE = "pause";

	private static final JsonMapper json = JsonMapper.shared();

	private final HttpServer server;

	private final Function<Request, Reply> script;

	/**
	 * The requests so far, oldest first.
	 */
	public final List<Request> requests = new CopyOnWriteArrayList<>();

	/**
	 * Completes when a client closes a reply before its end.
	 */
	public volatile CompletableFuture<Void> clientGone = new CompletableFuture<>();

	/**
	 * @param script the reply to a request
	 */
	public StandIn(Function<Request, Reply> script) {
		this.script = script;
		try {
			this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		// A slow reply must not hold back the next request.
		this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	public String url() {
		return "http://localhost:" + this.server.getAddress().getPort();
	}

	/**
	 * The only request so far.
	 * @throws IllegalStateException if there is none, or more than one
	 */
	public Request single() {
		if (this.requests.size() != 1) {
			throw new IllegalStateException("Expected one request but there are " + this.requests.size());
		}
		return this.requests.get(0);
	}

	/**
	 * The newest request.
	 */
	public Request last() {
		return this.requests.get(this.requests.size() - 1);
	}

	/**
	 * Forgets the requests so far.
	 */
	public void reset() {
		this.requests.clear();
		this.clientGone = new CompletableFuture<>();
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestURI().getQuery(), Map.copyOf(exchange.getRequestHeaders()),
				exchange.getRequestBody().readAllBytes());
		this.requests.add(request);
		Reply reply;
		try {
			reply = this.script.apply(request);
		}
		catch (RuntimeException ex) {
			// A script that cannot answer is a mistake of the test: say so where the client reports it.
			reply = Reply.json(500, "{\"error\":{\"message\":" + json.writeValueAsString("The stand-in's script failed: " + ex)
					+ "}}");
		}
		exchange.getResponseHeaders().add("Content-Type", reply.contentType());
		reply.headers().forEach(exchange.getResponseHeaders()::add);
		exchange.sendResponseHeaders(reply.status(), 0);
		try (OutputStream out = exchange.getResponseBody()) {
			for (byte[] piece : reply.pieces()) {
				if (piece == Reply.PAUSE_BYTES) {
					Thread.sleep(50);
					continue;
				}
				out.write(piece);
				out.flush();
			}
		}
		catch (IOException ex) {
			this.clientGone.complete(null);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * A server-sent event with a name, as Anthropic sends them.
	 */
	public static String event(String name, String data) {
		return "event: " + name + "\ndata: " + data + "\n\n";
	}

	/**
	 * A server-sent event without a name, as OpenAI and Gemini send them.
	 */
	public static String data(String data) {
		return "data: " + data + "\n\n";
	}

	/**
	 * What a client sent.
	 *
	 * @param method GET, POST and so on
	 * @param path the path, without the query
	 * @param query the query, or {@code null}
	 * @param headers the headers; {@link #header} reads one whatever its case
	 * @param bytes the body
	 */
	public record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] bytes) {

		/**
		 * The body as JSON.
		 */
		public JsonNode body() {
			return json.readTree(this.bytes);
		}

		/**
		 * The body as text: a multipart body, say.
		 */
		public String text() {
			return new String(this.bytes, StandardCharsets.UTF_8);
		}

		/**
		 * The first value of a header, or {@code null}.
		 */
		public String header(String name) {
			return this.headers.entrySet()
				.stream()
				.filter((header) -> header.getKey().equalsIgnoreCase(name))
				.map((header) -> header.getValue().get(0))
				.findFirst()
				.orElse(null);
		}

		/**
		 * The key the client authenticated with: a bearer token, or the header that
		 * Anthropic had before Spring AI 2.1 and Gemini has.
		 */
		public String apiKey() {
			String authorization = header("Authorization");
			if (authorization != null && authorization.startsWith("Bearer ")) {
				return authorization.substring("Bearer ".length());
			}
			String key = header("x-api-key");
			return (key != null) ? key : header("x-goog-api-key");
		}

	}

	/**
	 * What the stand-in answers.
	 *
	 * @param status the HTTP status
	 * @param contentType the Content-Type header
	 * @param headers further headers
	 * @param pieces the body, each piece written and flushed by itself
	 */
	public record Reply(int status, String contentType, Map<String, String> headers, List<byte[]> pieces) {

		private static final byte[] PAUSE_BYTES = new byte[0];

		/**
		 * A JSON document.
		 */
		public static Reply json(String body) {
			return json(200, body);
		}

		/**
		 * A JSON document with a status: an error, say.
		 */
		public static Reply json(int status, String body) {
			return new Reply(status, "application/json", Map.of(), List.of(body.getBytes(StandardCharsets.UTF_8)));
		}

		/**
		 * A stream of server-sent events, as {@link StandIn#event} and
		 * {@link StandIn#data} make them. {@link StandIn#PAUSE} among them waits.
		 */
		public static Reply events(List<String> events) {
			return new Reply(200, "text/event-stream", Map.of(),
					events.stream()
						.map((event) -> event.equals(PAUSE) ? PAUSE_BYTES : event.getBytes(StandardCharsets.UTF_8))
						.toList());
		}

		/**
		 * Anything else: audio, say.
		 */
		public static Reply bytes(String contentType, byte[] body) {
			return new Reply(200, contentType, Map.of(), List.of(body));
		}

		/**
		 * This reply with one more header.
		 */
		public Reply header(String name, String value) {
			Map<String, String> headers = new java.util.LinkedHashMap<>(this.headers);
			headers.put(name, value);
			return new Reply(this.status, this.contentType, Map.copyOf(headers), this.pieces);
		}

	}

}
