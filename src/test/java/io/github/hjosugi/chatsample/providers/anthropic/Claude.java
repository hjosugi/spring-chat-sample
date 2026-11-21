package io.github.hjosugi.chatsample.providers.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;

/**
 * What the Anthropic Messages API answers, as Claude would: a whole message, or the same
 * content blocks as a stream of events. The blocks are JSON, as {@link #text},
 * {@link #thinking} and {@link #toolUse} make them.
 */
final class Claude {

	static final String MODEL = "claude-haiku-5-5";

	private static final JsonMapper json = JsonMapper.shared();

	private Claude() {
	}

	/**
	 * A chat model of Spring AI that talks to the stand-in, with the key {@code test-key}
	 * and no retries.
	 */
	static AnthropicChatModel model(StandIn standIn) {
		return model(standIn, AnthropicChatOptions.builder());
	}

	/**
	 * A chat model of Spring AI that talks to the stand-in, with these default options.
	 */
	static AnthropicChatModel model(StandIn standIn, AnthropicChatOptions.Builder options) {
		return AnthropicChatModel.builder()
			.options(options.baseUrl(standIn.url()).apiKey("test-key").maxRetries(0).build())
			.build();
	}

	/**
	 * A script that answers a streaming request with events and any other with a whole
	 * message, made of the same blocks.
	 */
	static Function<Request, Reply> answering(String stopReason, String... blocks) {
		return (request) -> reply(request, stopReason, blocks);
	}

	/**
	 * The reply to a request: events if it asked for a stream, a whole message otherwise.
	 */
	static Reply reply(Request request, String stopReason, String... blocks) {
		return request.body().path("stream").asBoolean() ? Reply.events(events(stopReason, blocks))
				: Reply.json(message(stopReason, blocks));
	}

	/**
	 * A whole message.
	 */
	static String message(String stopReason, String... blocks) {
		return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + MODEL
				+ "\",\"content\":[" + String.join(",", blocks) + "],\"stop_reason\":\"" + stopReason
				+ "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":15}}";
	}

	/**
	 * The events of a stream of these blocks: text and thinking in two deltas each, a
	 * signature by itself, tool input in two pieces, and any other block whole at its
	 * start.
	 */
	static List<String> events(String stopReason, String... blocks) {
		List<String> events = new ArrayList<>();
		events.add(StandIn.event("message_start",
				"{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
						+ "\"content\":[],\"model\":\"" + MODEL + "\",\"stop_reason\":null,\"stop_sequence\":null,"
						+ "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}"));
		for (int index = 0; index < blocks.length; index++) {
			JsonNode block = json.readTree(blocks[index]);
			String type = block.get("type").asString();
			switch (type) {
				case "text" -> {
					events.add(blockStart(index, "{\"type\":\"text\",\"text\":\"\"}"));
					for (String half : halves(block.get("text").asString())) {
						events.add(delta(index, "{\"type\":\"text_delta\",\"text\":" + quote(half) + "}"));
					}
					if (block.has("citations")) {
						for (JsonNode citation : block.get("citations")) {
							events.add(delta(index, "{\"type\":\"citations_delta\",\"citation\":" + citation + "}"));
						}
					}
				}
				case "thinking" -> {
					events.add(blockStart(index, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}"));
					for (String half : halves(block.get("thinking").asString())) {
						events.add(delta(index, "{\"type\":\"thinking_delta\",\"thinking\":" + quote(half) + "}"));
					}
					events.add(delta(index, "{\"type\":\"signature_delta\",\"signature\":"
							+ quote(block.get("signature").asString()) + "}"));
				}
				case "tool_use", "server_tool_use" -> {
					events.add(blockStart(index, "{\"type\":\"" + type + "\",\"id\":" + block.get("id") + ",\"name\":"
							+ block.get("name") + ",\"input\":{}}"));
					for (String half : halves(block.get("input").toString())) {
						events.add(delta(index, "{\"type\":\"input_json_delta\",\"partial_json\":" + quote(half) + "}"));
					}
				}
				default -> events.add(blockStart(index, blocks[index]));
			}
			events.add(StandIn.event("content_block_stop",
					"{\"type\":\"content_block_stop\",\"index\":" + index + "}"));
		}
		events.add(StandIn.event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
				+ stopReason + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":15}}"));
		events.add(StandIn.event("message_stop", "{\"type\":\"message_stop\"}"));
		return events;
	}

	static String text(String text) {
		return "{\"type\":\"text\",\"text\":" + quote(text) + "}";
	}

	/**
	 * A text block that cites these citations, as JSON objects.
	 */
	static String citedText(String text, String... citations) {
		return "{\"type\":\"text\",\"text\":" + quote(text) + ",\"citations\":[" + String.join(",", citations) + "]}";
	}

	static String thinking(String thinking, String signature) {
		return "{\"type\":\"thinking\",\"thinking\":" + quote(thinking) + ",\"signature\":" + quote(signature) + "}";
	}

	static String redactedThinking(String data) {
		return "{\"type\":\"redacted_thinking\",\"data\":" + quote(data) + "}";
	}

	static String toolUse(String id, String name, String input) {
		return "{\"type\":\"tool_use\",\"id\":" + quote(id) + ",\"name\":" + quote(name) + ",\"input\":" + input + "}";
	}

	static String quote(String text) {
		return json.writeValueAsString(text);
	}

	/**
	 * The user and assistant messages of a request, as JSON.
	 */
	static JsonNode messages(Request request) {
		return request.body().get("messages");
	}

	/**
	 * The content of the newest message of a request, always as a list of blocks.
	 */
	static JsonNode lastContent(Request request) {
		JsonNode messages = messages(request);
		return content(messages.get(messages.size() - 1));
	}

	/**
	 * The content of a message, always as a list of blocks: a plain string becomes one
	 * text block.
	 */
	static JsonNode content(JsonNode message) {
		JsonNode content = message.get("content");
		return content.isString() ? json.readTree("[" + text(content.asString()) + "]") : content;
	}

	private static String blockStart(int index, String block) {
		return StandIn.event("content_block_start",
				"{\"type\":\"content_block_start\",\"index\":" + index + ",\"content_block\":" + block + "}");
	}

	private static String delta(int index, String delta) {
		return StandIn.event("content_block_delta",
				"{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}");
	}

	private static List<String> halves(String text) {
		int middle = text.length() / 2;
		return (middle == 0) ? List.of(text) : List.of(text.substring(0, middle), text.substring(middle));
	}

}
