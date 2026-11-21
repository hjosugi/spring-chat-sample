package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore.EmbeddingMath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The "Understanding Vectors" page: cosine similarity, the formula that
 * {@code SimpleVectorStore} uses, and the score it gives the documents it finds.
 */
class VectorSimilarityTests {

	@Test
	void similarPerpendicularAndOppositeVectors() {
		float[] a = { 1, 2 };

		assertThat(EmbeddingMath.cosineSimilarity(a, new float[] { 2, 4 })).isCloseTo(1.0, within(1e-6));
		assertThat(EmbeddingMath.cosineSimilarity(a, new float[] { -2, 1 })).isCloseTo(0.0, within(1e-6));
		assertThat(EmbeddingMath.cosineSimilarity(a, new float[] { -1, -2 })).isCloseTo(-1.0, within(1e-6));
	}

	@Test
	void theSimilarityIsTheDotProductOverTheProductOfTheMagnitudesInAnyDimension() {
		float[] a = { 1, 2, 3, 4, 5 };
		float[] b = { 5, -1, 0, 2, 1 };
		double dot = 0;
		double aa = 0;
		double bb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			aa += a[i] * a[i];
			bb += b[i] * b[i];
		}

		assertThat(EmbeddingMath.cosineSimilarity(a, b)).isCloseTo(dot / Math.sqrt(aa * bb), within(1e-6));
	}

	@Test
	void theScoreOfAFoundDocumentIsTheCosineSimilarityToTheQuery() {
		BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddings).build();
		vectorStore.add(List.of(new Document("kettle", CoffeeGear.KETTLE.getText(), Map.of())));

		Document found = vectorStore.similaritySearch(SearchRequest.builder().query("gooseneck kettle").build()).get(0);

		assertThat(found.getScore())
			.isCloseTo(EmbeddingMath.cosineSimilarity(BagOfWordsEmbeddingModel.vector("gooseneck kettle"),
					BagOfWordsEmbeddingModel.vector(CoffeeGear.KETTLE.getText())), within(1e-6));
	}

	/**
	 * With the default threshold of 0.0, a document pointing the opposite way is not
	 * "accepted": the threshold only lets through similarities of 0.0 and more.
	 */
	@Test
	void theAcceptAllThresholdLeavesOutOppositeVectors() {
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(new SignedEmbeddingModel()).build();
		vectorStore.add(List.of(new Document("up", "up", Map.of()), new Document("down", "down", Map.of())));

		assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("up").similarityThresholdAll().build()))
			.extracting(Document::getId)
			.containsExactly("up");
	}

	/**
	 * Two words, pointing opposite ways.
	 */
	static final class SignedEmbeddingModel implements EmbeddingModel {

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			return new EmbeddingResponse(request.getInstructions()
				.stream()
				.map((text) -> new Embedding(text.equals("up") ? new float[] { 1, 0 } : new float[] { -1, 0 }, 0))
				.toList());
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		@Override
		public int dimensions() {
			return 2;
		}

	}

}
