package io.github.hjosugi.chatsample.repro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The embeddings of the stand-in model: a bag of words, each word hashed to one of
 * {@value #DIMENSIONS} dimensions. Texts that share words point the same way, which is
 * all that finding a section of the guidelines for a question takes; it knows nothing of meaning, so
 * "parcel" and "package" are as far apart as any two words.
 */
final class BagOfWords {

	// Many dimensions, so that two words seldom share one: a question and a section that
	// share no word would otherwise be close now and then.
	static final int DIMENSIONS = 4096;

	// Without them every question would be close to every page.
	private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "can", "do",
			"does", "for", "from", "how", "i", "if", "in", "is", "it", "its", "me", "my", "of", "on", "or", "our", "the",
			"to", "we", "what", "when", "where", "which", "who", "will", "with", "you", "your");

	private BagOfWords() {
	}

	/**
	 * The answer to a request of OpenAI's embeddings API. The official client asks for
	 * the vectors as base64.
	 */
	static String openAiResponse(JsonNode request) {
		boolean base64 = "base64".equals(request.path("encoding_format").asString(""));
		JsonNode input = request.path("input");
		List<String> texts = new ArrayList<>();
		if (input.isString()) {
			texts.add(input.asString());
		}
		else {
			input.forEach((text) -> texts.add(text.asString("")));
		}
		List<Map<String, Object>> data = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++) {
			float[] vector = vector(texts.get(i));
			data.add(Map.of("object", "embedding", "index", i, "embedding", base64 ? base64(vector) : vector));
		}
		return JsonMapper.shared()
			.writeValueAsString(Map.of("object", "list", "data", data, "model", request.path("model").asString("fake"),
					"usage", Map.of("prompt_tokens", 8, "total_tokens", 8)));
	}

	static float[] vector(String text) {
		float[] vector = new float[DIMENSIONS];
		boolean empty = true;
		for (String word : words(text)) {
			vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
			empty = false;
		}
		// A text without words still needs a direction: a vector store cannot compare a
		// zero vector.
		if (empty) {
			vector[0] = 1;
		}
		return vector;
	}

	private static List<String> words(String text) {
		return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
			.filter((word) -> !word.isEmpty() && !STOP_WORDS.contains(word))
			// "returns" finds "return", which is as much stemming as this takes.
			.map((word) -> (word.length() > 3 && word.endsWith("s")) ? word.substring(0, word.length() - 1) : word)
			.toList();
	}

	private static String base64(float[] vector) {
		ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : vector) {
			bytes.putFloat(value);
		}
		return Base64.getEncoder().encodeToString(bytes.array());
	}

}
