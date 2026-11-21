package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google GenAI Text Embeddings page, through the Gemini Developer API of a stand-in:
 * the request, the options of the page, its connection and its properties.
 */
class GoogleGenAiEmbeddingTests {

	/**
	 * Answers each text with a vector of its position and its length.
	 */
	private final StandIn gemini = new StandIn((request) -> {
		JsonNode requests = request.body().get("requests");
		StringBuilder embeddings = new StringBuilder();
		for (int i = 0; i < requests.size(); i++) {
			String text = requests.get(i).get("content").get("parts").get(0).get("text").asString();
			embeddings.append(i > 0 ? "," : "").append("{\"values\": [%d.0, %d.0]}".formatted(i, text.length()));
		}
		return Reply.json("{\"embeddings\": [%s]}".formatted(embeddings));
	});

	@AfterEach
	void stop() {
		this.gemini.close();
	}

	private GoogleGenAiEmbeddingConnectionDetails connection() {
		return GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(Gemini.client(this.gemini)).build();
	}

	@Test
	void theTextsGoToTheBatchEmbeddingEndpointOfTheDefaultModel() {
		var model = new GoogleGenAiTextEmbeddingModel(connection(),
				GoogleGenAiTextEmbeddingOptions.builder().model(GoogleGenAiTextEmbeddingOptions.DEFAULT_MODEL_NAME).build());

		EmbeddingResponse response = model.embedForResponse(List.of("Hello World", "World is big"));

		StandIn.Request request = this.gemini.single();
		assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
		assertThat(request.apiKey()).isEqualTo("test-key");
		assertThat(response.getResults()).hasSize(2);
		assertThat(response.getResults().get(1).getOutput()).containsExactly(1f, 12f);
	}

	@Test
	void dimensionsAreSentAsOutputDimensionality() {
		var model = new GoogleGenAiTextEmbeddingModel(connection(),
				GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(256).build());

		model.embed("Hello World");

		assertThat(this.gemini.single().body().get("requests").get(0).get("outputDimensionality").asInt())
			.isEqualTo(256);
	}

	/**
	 * The page describes the task type, {@code RETRIEVAL_DOCUMENT} by default, and the
	 * title, for better embeddings. {@code GoogleGenAiTextEmbeddingModel} merges them into
	 * its options but never puts them into the request ("We'll need to check the SDK
	 * documentation for this", {@code GoogleGenAiTextEmbeddingModel.java:154}).
	 */
	@Test
	void theTaskTypeAndTheTitleAreNotSent() {
		var model = new GoogleGenAiTextEmbeddingModel(connection(),
				GoogleGenAiTextEmbeddingOptions.builder()
					.model("gemini-embedding-001")
					.taskType(TaskType.RETRIEVAL_DOCUMENT)
					.title("Product Documentation")
					.build());

		model.embed("Hello World");

		String body = this.gemini.single().body().toString();
		assertThat(body).doesNotContainIgnoringCase("task").doesNotContain("Product Documentation");
	}

	/**
	 * {@code dimensions()} looks the model up in a table and so asks nothing, but it
	 * reports the full size of the model even when the options reduce it.
	 */
	@Test
	void dimensionsReportsTheSizeOfTheModelAndNotTheReducedOne() {
		var model = new GoogleGenAiTextEmbeddingModel(connection(),
				GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(256).build());

		assertThat(model.dimensions()).isEqualTo(3072);
		assertThat(this.gemini.requests).isEmpty();
	}

	private ApplicationContextRunner contexts() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
					GoogleGenAiTextEmbeddingAutoConfiguration.class));
	}

	@Test
	void thePropertiesConfigureTheModelAndTheDimensions() {
		contexts().withBean(GoogleGenAiEmbeddingConnectionDetails.class, this::connection)
			.withPropertyValues("spring.ai.google.genai.embedding.text.model=text-embedding-004",
					"spring.ai.google.genai.embedding.text.dimensions=128",
					"spring.ai.google.genai.embedding.text.task-type=SEMANTIC_SIMILARITY")
			.run((context) -> {
				context.getBean(EmbeddingModel.class).embed("Hello World");

				StandIn.Request request = this.gemini.single();
				assertThat(request.path()).isEqualTo("/v1beta/models/text-embedding-004:batchEmbedContents");
				assertThat(request.body().get("requests").get(0).get("outputDimensionality").asInt()).isEqualTo(128);
			});
	}

	@Test
	void anEmbeddingKeyMakesAClientOfTheGeminiDeveloperApi() {
		contexts().withPropertyValues("spring.ai.google.genai.embedding.api-key=embedding-key")
			.run((context) -> assertThat(context.getBean(GoogleGenAiEmbeddingConnectionDetails.class).getApiKey())
				.isEqualTo("embedding-key"));
	}

	/**
	 * As the page says, the embedding model reads only its own connection properties:
	 * the key of the chat is not enough.
	 */
	@Test
	void theKeyOfTheChatIsNotEnough() {
		contexts().withPropertyValues("spring.ai.google.genai.api-key=chat-key")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Google GenAI project-id must be set!"));
	}

}
