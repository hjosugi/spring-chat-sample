package io.github.hjosugi.chatsample.providers.openai;

import java.util.ArrayList;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/**
 * What the OpenAI APIs answer: a chat completion, whole or as a stream of chunks, and a
 * response of the Responses API, whole or as a stream of events. Messages and output
 * items are JSON.
 */
final class Gpt {

	static final String MODEL = "gpt-6.1-terra";

	private static final JsonMapper json = JsonMapper.shared();

	private Gpt() {
	}

	/**
	 * A Chat Completions model of Spring AI that talks to the stand-in, with the key
	 * {@code test-key} and no retries.
	 */
	static OpenAiChatModel chatModel(StandIn standIn) {
		return chatModel(standIn, OpenAiChatOptions.builder());
	}

	static OpenAiChatModel chatModel(StandIn standIn, OpenAiChatOptions.Builder options) {
		return OpenAiChatModel.builder()
			.options(options.baseUrl(standIn.url() + "/v1").apiKey("test-key").maxRetries(0).build())
			.build();
	}

	/**
	 * A Responses API model of Spring AI that talks to the stand-in.
	 */
	static OpenAiResponsesChatModel responsesModel(StandIn standIn, OpenAiResponsesChatOptions.Builder options) {
		return OpenAiResponsesChatModel.builder()
			.options(options.baseUrl(standIn.url() + "/v1").apiKey("test-key").maxRetries(0).build())
			.build();
	}

	// ---------- Chat Completions ----------

	/**
	 * The reply of the Chat Completions API: chunks if the request asked for a stream, a
	 * whole completion otherwise.
	 * @param message the assistant message, as JSON
	 */
	static Reply completion(Request request, String finishReason, String message) {
		return request.body().path("stream").asBoolean() ? Reply.events(chunks(finishReason, message))
				: Reply.json(completion(finishReason, message));
	}

	static String completion(String finishReason, String message) {
		return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"" + MODEL
				+ "\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finishReason
				+ "\",\"logprobs\":null}],\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":15,\"total_tokens\":40}}";
	}

	/**
	 * The chunks of a message: reasoning and content in two deltas each, each tool call
	 * announced with its name and then its arguments in two pieces, the finish reason,
	 * the usage, and {@code [DONE]}.
	 */
	static List<String> chunks(String finishReason, String message) {
		JsonNode node = json.readTree(message);
		List<String> chunks = new ArrayList<>();
		chunks.add(StandIn.data(chunk("{\"role\":\"assistant\",\"content\":\"\"}", null)));
		for (String field : List.of("reasoning_content", "content")) {
			if (node.hasNonNull(field) && !node.get(field).asString().isEmpty()) {
				for (String half : halves(node.get(field).asString())) {
					chunks.add(StandIn.data(chunk("{\"" + field + "\":" + quote(half) + "}", null)));
				}
			}
		}
		JsonNode toolCalls = node.path("tool_calls");
		for (int index = 0; index < toolCalls.size(); index++) {
			JsonNode call = toolCalls.get(index);
			ObjectNode start = ((ObjectNode) call.deepCopy());
			start.put("index", index);
			((ObjectNode) start.get("function")).put("arguments", "");
			chunks.add(StandIn.data(chunk("{\"tool_calls\":[" + start + "]}", null)));
			for (String half : halves(call.get("function").get("arguments").asString())) {
				chunks.add(StandIn.data(chunk("{\"tool_calls\":[{\"index\":" + index + ",\"function\":{\"arguments\":"
						+ quote(half) + "}}]}", null)));
			}
		}
		chunks.add(StandIn.data(chunk("{}", finishReason)));
		chunks.add(StandIn.data("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
				+ "\"model\":\"" + MODEL + "\",\"choices\":[],"
				+ "\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":15,\"total_tokens\":40}}"));
		chunks.add(StandIn.data("[DONE]"));
		return chunks;
	}

	static String chunk(String delta, String finishReason) {
		return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"" + MODEL
				+ "\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ ((finishReason != null) ? quote(finishReason) : "null") + "}]}";
	}

	static String assistant(String content) {
		return "{\"role\":\"assistant\",\"content\":" + quote(content) + "}";
	}

	static String toolCall(String id, String name, String arguments) {
		return "{\"id\":" + quote(id) + ",\"type\":\"function\",\"function\":{\"name\":" + quote(name)
				+ ",\"arguments\":" + quote(arguments) + "}}";
	}

	static String toolCalls(String... toolCalls) {
		return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + String.join(",", toolCalls) + "]}";
	}

	/**
	 * The newest message of a request.
	 */
	static JsonNode lastMessage(Request request) {
		JsonNode messages = request.body().get("messages");
		return messages.get(messages.size() - 1);
	}

	// ---------- Responses API ----------

	/**
	 * The reply of the Responses API: events if the request asked for a stream, a whole
	 * response otherwise.
	 * @param items the output items, as JSON
	 */
	static Reply response(Request request, String status, String... items) {
		return request.body().path("stream").asBoolean() ? Reply.events(responseEvents(status, items))
				: Reply.json(response(status, items));
	}

	static String response(String status, String... items) {
		return "{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1700000000,\"status\":\"" + status
				+ "\",\"model\":\"gpt-5.4-mini\",\"output\":[" + String.join(",", items) + "],"
				+ "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],\"temperature\":1.0,\"top_p\":1.0,"
				+ "\"error\":null,\"incomplete_details\":"
				+ (status.equals("incomplete") ? "{\"reason\":\"max_output_tokens\"}" : "null")
				+ ",\"instructions\":null,\"metadata\":{},"
				+ "\"usage\":{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40,"
				+ "\"input_tokens_details\":{\"cached_tokens\":5},\"output_tokens_details\":{\"reasoning_tokens\":7}}}";
	}

	/**
	 * The events of a streamed response: each item added, the text of a message in two
	 * deltas, each item done, and the completed response.
	 */
	static List<String> responseEvents(String status, String... items) {
		List<String> events = new ArrayList<>();
		int sequence = 0;
		events.add(event("response.created", "{\"type\":\"response.created\",\"sequence_number\":" + sequence++
				+ ",\"response\":" + response("in_progress") + "}"));
		for (int index = 0; index < items.length; index++) {
			JsonNode item = json.readTree(items[index]);
			ObjectNode added = (ObjectNode) item.deepCopy();
			if (item.get("type").asString().equals("message")) {
				added.putArray("content");
			}
			events.add(event("response.output_item.added", "{\"type\":\"response.output_item.added\",\"sequence_number\":"
					+ sequence++ + ",\"output_index\":" + index + ",\"item\":" + added + "}"));
			if (item.get("type").asString().equals("message")) {
				for (String half : halves(item.get("content").get(0).get("text").asString())) {
					events.add(event("response.output_text.delta", "{\"type\":\"response.output_text.delta\","
							+ "\"sequence_number\":" + sequence++ + ",\"item_id\":" + item.get("id") + ",\"output_index\":"
							+ index + ",\"content_index\":0,\"delta\":" + quote(half) + ",\"logprobs\":[]}"));
				}
			}
			events.add(event("response.output_item.done", "{\"type\":\"response.output_item.done\",\"sequence_number\":"
					+ sequence++ + ",\"output_index\":" + index + ",\"item\":" + items[index] + "}"));
		}
		String terminal = status.equals("incomplete") ? "response.incomplete" : "response.completed";
		events.add(event(terminal, "{\"type\":\"" + terminal + "\",\"sequence_number\":" + sequence + ",\"response\":"
				+ response(status, items) + "}"));
		return events;
	}

	static String message(String id, String text) {
		return "{\"type\":\"message\",\"id\":" + quote(id) + ",\"status\":\"completed\",\"role\":\"assistant\","
				+ "\"content\":[{\"type\":\"output_text\",\"text\":" + quote(text) + ",\"annotations\":[]}]}";
	}

	static String reasoning(String id, String summary, String encryptedContent) {
		return "{\"type\":\"reasoning\",\"id\":" + quote(id) + ",\"summary\":[{\"type\":\"summary_text\",\"text\":"
				+ quote(summary) + "}],\"encrypted_content\":" + quote(encryptedContent) + "}";
	}

	static String functionCall(String id, String callId, String name, String arguments) {
		return "{\"type\":\"function_call\",\"id\":" + quote(id) + ",\"call_id\":" + quote(callId) + ",\"name\":"
				+ quote(name) + ",\"arguments\":" + quote(arguments) + ",\"status\":\"completed\"}";
	}

	static String quote(String text) {
		return json.writeValueAsString(text);
	}

	private static String event(String name, String data) {
		return StandIn.event(name, data);
	}

	private static List<String> halves(String text) {
		int middle = text.length() / 2;
		return (middle == 0) ? List.of(text) : List.of(text.substring(0, middle), text.substring(middle));
	}

}
