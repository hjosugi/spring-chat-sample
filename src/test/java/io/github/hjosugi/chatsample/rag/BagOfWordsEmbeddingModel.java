package io.github.hjosugi.chatsample.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * An embedding model for tests, and not a real one: a bag of words, each word hashed to
 * one of {@value #DIMENSIONS} dimensions. Texts that share words point the same way,
 * which is all a vector store needs to find them again; it knows nothing of meaning, so
 * "parcel" and "package" are as far apart as any two words.
 * <p>
 * It keeps the requests it was asked, so a test can see what a store or an advisor
 * embedded, and in how many calls.
 */
public final class BagOfWordsEmbeddingModel implements EmbeddingModel {

	public static final int DIMENSIONS = 512;

	// Without them every question would be close to every document.
	private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "can", "do",
			"does", "for", "from", "how", "i", "if", "in", "is", "it", "its", "me", "my", "of", "on", "or", "our",
			"the", "to", "we", "what", "when", "where", "which", "who", "will", "with", "you", "your");

	/**
	 * The requests so far, oldest first.
	 */
	public final List<EmbeddingRequest> requests = new CopyOnWriteArrayList<>();

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		this.requests.add(request);
		List<Embedding> embeddings = new ArrayList<>();
		for (int i = 0; i < request.getInstructions().size(); i++) {
			embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	@Override
	public float[] embed(Document document) {
		return embed(Objects.requireNonNullElse(document.getText(), ""));
	}

	/**
	 * Known without asking, as the documentation advises a model that knows its size: the
	 * default embeds a test string on every call.
	 */
	@Override
	public int dimensions() {
		return DIMENSIONS;
	}

	/**
	 * The texts embedded so far, in the order they were asked.
	 */
	public List<String> embeddedTexts() {
		return this.requests.stream().flatMap((request) -> request.getInstructions().stream()).toList();
	}

	/**
	 * The vector of a text, without going through a model.
	 */
	public static float[] vector(String text) {
		float[] vector = new float[DIMENSIONS];
		for (String word : words(text)) {
			vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
		}
		// A text without words still needs a direction: cosine similarity has none for a
		// zero vector, and SimpleVectorStore refuses one.
		boolean empty = true;
		for (float value : vector) {
			empty &= value == 0;
		}
		if (empty) {
			vector[0] = 1;
		}
		return vector;
	}

	static List<String> words(String text) {
		return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
			.filter((word) -> !word.isEmpty() && !STOP_WORDS.contains(word))
			// "returns" finds "return", which is as much stemming as the tests need.
			.map((word) -> (word.length() > 3 && word.endsWith("s")) ? word.substring(0, word.length() - 1) : word)
			.toList();
	}

	/**
	 * The OpenAI embeddings API as a script for a {@link StandIn}, with the bag of words
	 * for vectors. The client may ask for them as base64, which is what the official SDK
	 * prefers.
	 */
	public static Reply openAiEmbeddings(StandIn.Request request) {
		JsonNode body = request.body();
		boolean base64 = "base64".equals(body.path("encoding_format").asString(""));
		List<Map<String, Object>> data = new ArrayList<>();
		JsonNode input = body.get("input");
		for (int i = 0; i < input.size(); i++) {
			float[] vector = BagOfWordsEmbeddingModel.vector(input.get(i).asString());
			data.add(Map.of("object", "embedding", "index", i, "embedding", base64 ? base64(vector) : vector));
		}
		return Reply.json(JsonMapper.shared()
			.writeValueAsString(Map.of("object", "list", "data", data, "model", body.get("model").asString(), "usage",
					Map.of("prompt_tokens", 8, "total_tokens", 8))));
	}

	private static String base64(float[] vector) {
		ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : vector) {
			bytes.putFloat(value);
		}
		return Base64.getEncoder().encodeToString(bytes.array());
	}

}
