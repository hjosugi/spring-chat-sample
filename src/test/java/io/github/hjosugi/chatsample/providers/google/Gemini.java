package io.github.hjosugi.chatsample.providers.google;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/**
 * What the Gemini API answers: a whole {@code GenerateContentResponse}, or the same parts
 * streamed as server-sent events, one response per part, the text parts in two halves.
 * Parts are JSON, as {@link #text}, {@link #thought} and {@link #functionCall} make them.
 */
final class Gemini {

	static final String MODEL = "gemini-3.5-flash-lite";

	private static final JsonMapper json = JsonMapper.shared();

	private Gemini() {
	}

	/**
	 * A client of the Gemini Developer API, with the key {@code test-key}, that talks to
	 * the stand-in.
	 */
	static Client client(StandIn standIn) {
		return Client.builder()
			.apiKey("test-key")
			.httpOptions(HttpOptions.builder().baseUrl(standIn.url()).build())
			.build();
	}

	/**
	 * A client of Vertex AI, with an access token, that talks to the stand-in.
	 */
	static Client vertexClient(StandIn standIn) {
		return Client.builder()
			.vertexAI(true)
			.project("shop-project")
			.location("us-central1")
			.credentials(GoogleCredentials.create(new AccessToken("vertex-token", new Date(Long.MAX_VALUE))))
			.httpOptions(HttpOptions.builder().baseUrl(standIn.url()).build())
			.build();
	}

	static GoogleGenAiChatModel model(StandIn standIn) {
		return model(client(standIn), GoogleGenAiChatOptions.builder().model(MODEL));
	}

	static GoogleGenAiChatModel model(Client client, GoogleGenAiChatOptions.Builder options) {
		return GoogleGenAiChatModel.builder().genAiClient(client).options(options.build()).build();
	}

	/**
	 * The reply to a request: events if it asked for a stream, a whole response
	 * otherwise.
	 */
	static Reply reply(Request request, String finishReason, String... parts) {
		return request.path().endsWith(":streamGenerateContent") ? Reply.events(events(finishReason, parts))
				: Reply.json(response(finishReason, parts));
	}

	static String response(String finishReason, String... parts) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + String.join(",", parts) + "]},"
				+ ((finishReason != null) ? "\"finishReason\":\"" + finishReason + "\"," : "") + "\"index\":0}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":25,\"candidatesTokenCount\":15,\"totalTokenCount\":45,"
				+ "\"thoughtsTokenCount\":5},\"modelVersion\":\"" + MODEL + "\",\"responseId\":\"resp-1\"}";
	}

	static List<String> events(String finishReason, String... parts) {
		List<String> pieces = new ArrayList<>();
		for (String part : parts) {
			JsonNode node = json.readTree(part);
			if (node.has("text") && node.get("text").asString().length() > 1) {
				String text = node.get("text").asString();
				int middle = text.length() / 2;
				tools.jackson.databind.node.ObjectNode first = (tools.jackson.databind.node.ObjectNode) node.deepCopy();
				first.put("text", text.substring(0, middle));
				first.remove("thoughtSignature");
				tools.jackson.databind.node.ObjectNode second = (tools.jackson.databind.node.ObjectNode) node.deepCopy();
				second.put("text", text.substring(middle));
				pieces.add(first.toString());
				pieces.add(second.toString());
			}
			else {
				pieces.add(part);
			}
		}
		List<String> events = new ArrayList<>();
		for (int i = 0; i < pieces.size(); i++) {
			events.add(StandIn.data(response((i == pieces.size() - 1) ? finishReason : null, pieces.get(i))));
		}
		return events;
	}

	static String text(String text) {
		return "{\"text\":" + quote(text) + "}";
	}

	static String thought(String text, String signature) {
		return "{\"text\":" + quote(text) + ",\"thought\":true,\"thoughtSignature\":\"" + base64(signature) + "\"}";
	}

	static String functionCall(String id, String name, String args, String signature) {
		return "{\"functionCall\":{\"id\":" + quote(id) + ",\"name\":" + quote(name) + ",\"args\":" + args + "}"
				+ ((signature != null) ? ",\"thoughtSignature\":\"" + base64(signature) + "\"" : "") + "}";
	}

	static String base64(String text) {
		return Base64.getEncoder().encodeToString(text.getBytes());
	}

	static String quote(String text) {
		return json.writeValueAsString(text);
	}

	/**
	 * The parts of the newest content of a request.
	 */
	static JsonNode lastParts(Request request) {
		JsonNode contents = request.body().get("contents");
		return contents.get(contents.size() - 1).get("parts");
	}

}
