package io.github.hjosugi.chatsample.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The batching page: {@code TokenCountBatchingStrategy} groups documents into batches
 * that fit the embedding model's input, and the embedding model asks once per batch. The
 * store's part, and the auto-configuration's, is in {@code PgVectorStoreTests}.
 */
class BatchingStrategyTests {

	@Test
	void theDefaultStrategyFillsBatchesUpToNinetyPercentOfOpenAisLimit() {
		// One token per word, so 1000 words are 1000 tokens: 7 fit in 8191 * 0.9 = 7372.
		List<Document> documents = documents(10, 1000);

		List<List<Document>> batches = new TokenCountBatchingStrategy().batch(documents);

		assertThat(batches).extracting(List::size).containsExactly(7, 3);
	}

	@Test
	void theLimitAndTheReserveCanBeSet() {
		BatchingStrategy strategy = new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, 8000, 0.1);

		// 7200 tokens: 7 documents of 1000.
		assertThat(strategy.batch(documents(10, 1000))).extracting(List::size).containsExactly(7, 3);
		// A document is a token over 1000: the text of a document counts with its
		// formatting.
		assertThat(new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, 2100, 0.0).batch(documents(10, 1000)))
			.extracting(List::size)
			.containsExactly(2, 2, 2, 2, 2);
	}

	@Test
	void aDocumentOverTheLimitFailsTheBatching() {
		BatchingStrategy strategy = new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, 1000, 0.1);

		assertThatIllegalArgumentException().isThrownBy(() -> strategy.batch(documents(1, 950)))
			.withMessage("Tokens in a single document exceeds the maximum number of allowed input tokens");
	}

	@Test
	void aTokenCountEstimatorOfYourOwnCountsTheTokens() {
		AtomicInteger asked = new AtomicInteger();
		TokenCountEstimator oneTokenPerCharacter = new TokenCountEstimator() {

			@Override
			public int estimate(String text) {
				asked.incrementAndGet();
				return text.length();
			}

			@Override
			public int estimate(org.springframework.ai.content.MediaContent content) {
				return estimate(content.getText());
			}

			@Override
			public int estimate(Iterable<org.springframework.ai.content.MediaContent> messages) {
				int total = 0;
				for (var message : messages) {
					total += estimate(message);
				}
				return total;
			}

		};
		TokenCountBatchingStrategy strategy = new TokenCountBatchingStrategy(oneTokenPerCharacter, 100, 0.0,
				Document.DEFAULT_CONTENT_FORMATTER, MetadataMode.NONE);

		List<List<Document>> batches = strategy
			.batch(List.of(new Document("x".repeat(60)), new Document("y".repeat(60))));

		assertThat(batches).hasSize(2);
		assertThat(asked).hasValue(2);
	}

	@Test
	void theEmbeddingModelAsksOncePerBatch() {
		BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();

		List<float[]> vectors = embeddings.embed(documents(10, 1000), EmbeddingOptions.builder().build(),
				new TokenCountBatchingStrategy());

		assertThat(vectors).hasSize(10);
		assertThat(embeddings.requests).extracting((request) -> request.getInstructions().size()).containsExactly(7, 3);
	}

	/**
	 * The page says a vector store passes its batching strategy to the embedding model.
	 * {@code SimpleVectorStore} takes one in its builder and never uses it: it embeds the
	 * documents one at a time.
	 */
	@Test
	void theSimpleStoreIgnoresItsBatchingStrategyAndEmbedsOneDocumentPerCall() {
		BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();
		List<List<Document>> batched = new ArrayList<>();
		BatchingStrategy recording = (documents) -> {
			batched.add(documents);
			return List.of(documents);
		};
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddings).batchingStrategy(recording).build();

		vectorStore.add(documents(3, 10));

		assertThat(batched).isEmpty();
		assertThat(embeddings.requests).hasSize(3)
			.allSatisfy((request) -> assertThat(request.getInstructions()).hasSize(1));
	}

	private static List<Document> documents(int count, int words) {
		List<Document> documents = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			documents.add(new Document((" coffee").repeat(words).substring(1)));
		}
		return documents;
	}

}
