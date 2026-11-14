package io.github.hjosugi.chatsample;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the Anthropic Messages API: it keeps each request and streams back the
 * events the real API sends, so that Spring AI's own Anthropic client parses them.
 */
final class FakeAnthropic {

	private static final JsonMapper json = JsonMapper.shared();

	private final HttpServer server;

	private final Function<JsonNode, List<String>> replies;

	final List<JsonNode> requests = new CopyOnWriteArrayList<>();

	/**
	 * @param replies the events to stream for a request body, as {@link #text} and
	 * {@link #toolUse} make them
	 */
	FakeAnthropic(Function<JsonNode, List<String>> replies) throws IOException {
		this.replies = replies;
		this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	String url() {
		return "http://localhost:" + this.server.getAddress().getPort();
	}

	void stop() {
		this.server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
		this.requests.add(body);
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			for (String event : this.replies.apply(body)) {
				out.write(event.getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		}
	}

	/**
	 * The result of the last tool call in a request, or {@code null}.
	 */
	static String toolResult(JsonNode body) {
		String result = null;
		for (JsonNode message : body.get("messages")) {
			JsonNode content = message.get("content");
			if (content.isArray()) {
				for (JsonNode block : content) {
					if ("tool_result".equals(block.path("type").asString())) {
						JsonNode value = block.get("content");
						result = value.isString() ? value.asString() : value.toString();
					}
				}
			}
		}
		return result;
	}

	/**
	 * A reply of text, in pieces, with a ping among them as the API sends.
	 */
	static List<String> text(String... pieces) {
		List<String> events = new ArrayList<>();
		events.add(messageStart());
		events.add(event("content_block_start",
				"{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
		events.add(event("ping", "{\"type\":\"ping\"}"));
		for (String piece : pieces) {
			events.add(event("content_block_delta",
					"{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
							+ json.writeValueAsString(piece) + "}}"));
		}
		events.add(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
		events.addAll(messageEnd("end_turn"));
		return events;
	}

	/**
	 * A reply that asks to call a tool.
	 */
	static List<String> toolUse(String name, String input) {
		List<String> events = new ArrayList<>();
		events.add(messageStart());
		events.add(event("content_block_start",
				"{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
						+ "\"name\":\"" + name + "\",\"input\":{}}}"));
		events.add(event("content_block_delta",
				"{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
						+ json.writeValueAsString(input) + "}}"));
		events.add(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
		events.addAll(messageEnd("tool_use"));
		return events;
	}

	private static String messageStart() {
		return event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
				+ "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-opus-5-5\",\"stop_reason\":null,"
				+ "\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}");
	}

	private static List<String> messageEnd(String stopReason) {
		return List.of(
				event("message_delta",
						"{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
								+ "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":15}}"),
				event("message_stop", "{\"type\":\"message_stop\"}"));
	}

	private static String event(String name, String data) {
		return "event: " + name + "\ndata: " + data + "\n\n";
	}

}
