package io.github.hjosugi.chatsample.models;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;

/**
 * What the Google GenAI tests share: a client of the Gemini Developer API that talks to a
 * stand-in, and the reply of {@code generateContent}.
 */
final class Gemini {

	private Gemini() {
	}

	/**
	 * Spring AI has no property for the URL of the Gemini API, but every Google GenAI
	 * model takes a client of its own through its connection details.
	 */
	static Client client(StandIn standIn) {
		return Client.builder()
			.apiKey("test-key")
			.httpOptions(HttpOptions.builder().baseUrl(standIn.url()).build())
			.build();
	}

	/**
	 * A {@code generateContent} reply of one candidate with these parts, as JSON.
	 */
	static Reply generated(String parts) {
		return Reply.json("""
				{"candidates": [{"content": {"role": "model", "parts": [%s]}, "finishReason": "STOP", "index": 0}],
				 "usageMetadata": {"promptTokenCount": 7, "candidatesTokenCount": 20, "totalTokenCount": 27},
				 "modelVersion": "gemini-test-001"}""".formatted(parts));
	}

}
