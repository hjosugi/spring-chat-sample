package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.Map;

import com.knuddels.jtokkit.api.EncodingType;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The portable {@code EmbeddingModel} API of the Embeddings page, through the OpenAI
 * embedding model and a stand-in for its API: each shortcut ends in one call, the batched
 * path that vector stores take, and what a {@code Document} sends.
 */
class EmbeddingModelTests {

	private final StandIn openAi = new StandIn(EmbeddingModelTests::embeddings);

	private final EmbeddingModel model = OpenAiEmbeddingModel.builder()
		.options(OpenAiEmbeddingOptions.builder()
			.baseUrl(this.openAi.url() + "/v1")
			.apiKey("test-key")
			.maxRetries(0)
			.build())
		.build();

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	/**
	 * Answers each input with a vector of three numbers: its position, its length and 0.5.
	 */
	static Reply embeddings(StandIn.Request request) {
		JsonNode input = request.body().get("input");
		StringBuilder data = new StringBuilder();
		for (int i = 0; i < input.size(); i++) {
			data.append(i > 0 ? "," : "")
				.append("{\"object\":\"embedding\",\"index\":%d,\"embedding\":[%d.0,%d.0,0.5]}".formatted(i, i,
						input.get(i).asString().length()));
		}
		return Reply.json("""
				{"object": "list", "data": [%s], "model": "%s",
				 "usage": {"prompt_tokens": %d, "total_tokens": %d}}"""
			.formatted(data, request.body().get("model").asString(), 2 * input.size(), 2 * input.size()));
	}

	@Test
	void embedOfATextReturnsTheVectorOfItsOnlyResult() {
		float[] vector = this.model.embed("Hello");

		assertThat(vector).containsExactly(0f, 5f, 0.5f);
		assertThat(this.openAi.single().body().get("input").toString()).isEqualTo("[\"Hello\"]");
	}

	@Test
	void embedOfSeveralTextsAsksForAllOfThemInOneRequest() {
		List<float[]> vectors = this.model.embed(List.of("one", "three"));

		assertThat(vectors).hasSize(2);
		assertThat(vectors.get(1)).containsExactly(1f, 5f, 0.5f);
		assertThat(this.openAi.single().body().get("input").toString()).isEqualTo("[\"one\",\"three\"]");
	}

	@Test
	void embedForResponseKeepsTheIndexOfEachResultAndTheMetadata() {
		EmbeddingResponse response = this.model.embedForResponse(List.of("one", "three"));

		assertThat(response.getResults()).extracting(Embedding::getIndex).containsExactly(0, 1);
		assertThat(response.getResult().getOutput()).containsExactly(0f, 3f, 0.5f);
		assertThat(response.getMetadata().getModel()).isEqualTo("text-embedding-ada-002");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(4);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(4);
	}

	@Test
	void callTakesARequestWithItsOwnOptions() {
		EmbeddingResponse response = this.model
			.call(new EmbeddingRequest(List.of("one"), EmbeddingOptions.builder().model("other-model").build()));

		assertThat(response.getMetadata().getModel()).isEqualTo("other-model");
		assertThat(this.openAi.single().body().get("model").asString()).isEqualTo("other-model");
	}

	@Test
	void aDocumentSendsItsMetadataWithItsTextWhenTheModelSupportsMetadataMode() {
		Document document = Document.builder().text("The text").metadata(Map.of("title", "A title")).build();

		this.model.embed(document);

		assertThat(this.model.getEmbeddingContent(document)).isEqualTo(document.getFormattedContent(MetadataMode.EMBED))
			.contains("title: A title", "The text");
		assertThat(this.openAi.single().body().get("input").get(0).asString())
			.isEqualTo(document.getFormattedContent(MetadataMode.EMBED));
	}

	@Test
	void theBatchedPathSendsTheDocumentsInTheBatchesOfTheStrategy() {
		List<Document> documents = List.of(new Document("one two three four five six seven eight"),
				new Document("nine ten eleven twelve thirteen fourteen"), new Document("fifteen sixteen"));
		// About ten tokens fit in a batch, so each document goes by itself.
		TokenCountBatchingStrategy strategy = new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, 12, 0.1);

		List<float[]> vectors = this.model.embed(documents, OpenAiEmbeddingOptions.builder().build(), strategy);

		assertThat(vectors).hasSize(3);
		assertThat(this.openAi.requests).hasSize(3);
		assertThat(this.openAi.requests).extracting((request) -> request.body().get("input").size())
			.containsExactly(1, 1, 1);
	}

	/**
	 * The page says that {@code dimensions()} embeds a test string unless the model knows
	 * its dimensions. The table of known dimensions has {@code text-embedding-ada-002},
	 * but {@code AbstractEmbeddingModel.dimensions()} looks up the model name
	 * {@code "Test"}, so the OpenAI model asks its API, with "Hello World".
	 */
	@Test
	void dimensionsAsksTheApiEvenForAModelOfTheTableOfKnownDimensions() {
		int dimensions = this.model.dimensions();

		assertThat(dimensions).isEqualTo(3);
		assertThat(this.openAi.single().body().get("input").toString()).isEqualTo("[\"Hello World\"]");
		assertThat(this.openAi.single().body().get("model").asString()).isEqualTo("text-embedding-ada-002");
	}

}
