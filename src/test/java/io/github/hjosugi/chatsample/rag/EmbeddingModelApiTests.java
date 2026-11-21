package io.github.hjosugi.chatsample.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code EmbeddingModel} API of the embeddings page, the interface itself: every
 * shortcut goes through {@code call}, and what the defaults do when a model implements
 * only that and {@code embed(Document)}. The providers' models are verified elsewhere.
 */
class EmbeddingModelApiTests {

	private final MinimalEmbeddingModel model = new MinimalEmbeddingModel();

	@Test
	void embeddingATextIsACallWithOneInputAndDefaultOptions() {
		float[] vector = this.model.embed("grinder");

		assertThat(vector).containsExactly(7, 0);
		assertThat(this.model.requests).singleElement().satisfies((request) -> {
			assertThat(request.getInstructions()).containsExactly("grinder");
			assertThat(request.getOptions()).isNotNull();
			assertThat(request.getOptions().getModel()).isNull();
		});
	}

	@Test
	void aBatchOfTextsIsOneCallAndTheVectorsComeBackInOrder() {
		List<float[]> vectors = this.model.embed(List.of("kettle", "scale", "beans"));

		assertThat(vectors).extracting((vector) -> vector[0]).containsExactly(6f, 5f, 5f);
		assertThat(vectors).extracting((vector) -> vector[1]).containsExactly(0f, 1f, 2f);
		assertThat(this.model.requests).hasSize(1);
	}

	@Test
	void embedForResponseGivesTheWholeResponse() {
		EmbeddingResponse response = this.model.embedForResponse(List.of("kettle", "scale"));

		assertThat(response.getResults()).extracting(Embedding::getIndex).containsExactly(0, 1);
		assertThat(response.getResult().getOutput()).containsExactly(6, 0);
		assertThat(response.getMetadata().getModel()).isEqualTo("minimal");
	}

	@Test
	void byDefaultTheEmbeddingContentOfADocumentIsItsTextWithoutMetadata() {
		Document document = new Document("grinder", Map.of("source", "handbook.txt"));

		assertThat(this.model.getEmbeddingContent(document)).isEqualTo("grinder");
	}

	@Test
	void theBatchedPathEmbedsTheEmbeddingContentInBatchesWithTheOptionsGiven() {
		List<Document> documents = List.of(new Document("grinder", Map.of("source", "a")), new Document("kettle"));
		EmbeddingOptions options = EmbeddingOptions.builder().model("text-embedding-3-small").dimensions(2).build();

		List<float[]> vectors = this.model.embed(documents, options, new TokenCountBatchingStrategy());

		assertThat(vectors).hasSize(2);
		assertThat(this.model.requests).singleElement().satisfies((request) -> {
			assertThat(request.getInstructions()).containsExactly("grinder", "kettle");
			assertThat(request.getOptions().getModel()).isEqualTo("text-embedding-3-small");
			assertThat(request.getOptions().getDimensions()).isEqualTo(2);
		});
	}

	@Test
	void aModelThatEmbedsTheMetadataOverridesTheEmbeddingContent() {
		EmbeddingModel withMetadata = new MinimalEmbeddingModel() {

			@Override
			public String getEmbeddingContent(Document document) {
				return document.getFormattedContent(MetadataMode.EMBED);
			}

		};

		withMetadata.embed(List.of(new Document("grinder", Map.of("source", "a"))), null,
				new TokenCountBatchingStrategy());

		assertThat(((MinimalEmbeddingModel) withMetadata).requests.get(0).getInstructions())
			.containsExactly("source: a\n\ngrinder");
	}

	@Test
	void byDefaultTheDimensionsAreFoundByEmbeddingATestString() {
		assertThat(this.model.dimensions()).isEqualTo(2);
		assertThat(this.model.dimensions()).isEqualTo(2);

		// Not remembered: every call asks the model again.
		assertThat(this.model.requests).extracting(EmbeddingRequest::getInstructions)
			.containsExactly(List.of("Test String"), List.of("Test String"));
	}

	/**
	 * A model with only what the interface requires: {@code call}, and
	 * {@code embed(Document)}. A text becomes its length and its index.
	 */
	static class MinimalEmbeddingModel implements EmbeddingModel {

		final List<EmbeddingRequest> requests = new CopyOnWriteArrayList<>();

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			this.requests.add(request);
			List<Embedding> embeddings = new ArrayList<>();
			for (int i = 0; i < request.getInstructions().size(); i++) {
				embeddings.add(new Embedding(new float[] { request.getInstructions().get(i).length(), i }, i));
			}
			EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
			metadata.setModel("minimal");
			return new EmbeddingResponse(embeddings, metadata);
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

	}

}
