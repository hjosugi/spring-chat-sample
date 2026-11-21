package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

/**
 * The OpenAI Embeddings page: what goes to {@code /embeddings}, the options at start and
 * for one call, the connection and its overrides, the properties and the retries of the
 * SDK.
 */
class OpenAiEmbeddingTests {

	private final StandIn openAi = new StandIn(EmbeddingModelTests::embeddings);

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private OpenAiEmbeddingOptions.Builder connected() {
		return OpenAiEmbeddingOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").maxRetries(0);
	}

	@Test
	void theTextsGoToTheEmbeddingsEndpointWithTheKeyAndTheDefaultModel() {
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder().options(connected().build()).build();

		model.embedForResponse(List.of("Hello World", "World is big and salvation is near"));

		StandIn.Request request = this.openAi.single();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/v1/embeddings");
		assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
		assertThat(request.body().get("model").asString()).isEqualTo("text-embedding-ada-002");
		assertThat(request.body().get("input").toString())
			.isEqualTo("[\"Hello World\",\"World is big and salvation is near\"]");
	}

	@Test
	void theOptionsOfARequestOverrideTheDefaultsForThatCallOnly() {
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(connected().model("text-embedding-3-large").dimensions(1024).user("user-6").build())
			.build();

		model.call(new EmbeddingRequest(List.of("Hello World"),
				OpenAiEmbeddingOptions.builder()
					.model("Different-Embedding-Model-Deployment-Name")
					.dimensions(256)
					.encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
					.build()));
		model.embed("Hello World");

		JsonNode first = this.openAi.requests.get(0).body();
		assertThat(first.get("model").asString()).isEqualTo("Different-Embedding-Model-Deployment-Name");
		assertThat(first.get("dimensions").asInt()).isEqualTo(256);
		assertThat(first.get("encoding_format").asString()).isEqualTo("float");
		assertThat(first.get("user").asString()).isEqualTo("user-6");
		JsonNode second = this.openAi.requests.get(1).body();
		assertThat(second.get("model").asString()).isEqualTo("text-embedding-3-large");
		assertThat(second.get("dimensions").asInt()).isEqualTo(1024);
	}

	@Test
	void extraBodyGoesToTheTopLevelOfTheRequest() {
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(connected().extraBody(Map.of("truncate_prompt_tokens", 10)).build())
			.build();

		model.embed("Hello World");

		assertThat(this.openAi.single().body().get("truncate_prompt_tokens").asInt()).isEqualTo(10);
		assertThat(this.openAi.single().body().has("extra_body")).isFalse();
	}

	/**
	 * {@code NONE} leaves the metadata out, but not the blank line that the default
	 * {@code ContentFormatter} puts between the metadata and the text: the input starts
	 * with two line breaks. So does the input of a document without metadata.
	 */
	@Test
	void metadataModeNoneSendsTheTextAfterAnEmptyMetadataBlock() {
		Document document = Document.builder().text("The text").metadata(Map.of("title", "A title")).build();
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(connected().build())
			.metadataMode(MetadataMode.NONE)
			.build();

		model.embed(document);

		assertThat(this.openAi.single().body().get("input").get(0).asString()).isEqualTo("\n\nThe text");
	}

	@Test
	void theOrganizationAndTheCustomHeadersGoWithEveryRequest() {
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(connected().organizationId("org-1").customHeaders(Map.of("X-Trace", "abc")).build())
			.build();

		model.embed("Hello World");

		assertThat(this.openAi.single().header("OpenAI-Organization")).isEqualTo("org-1");
		assertThat(this.openAi.single().header("X-Trace")).isEqualTo("abc");
	}

	/**
	 * The connection tables give {@code https://api.openai.com} as the default base URL.
	 * Spring AI hands the base URL to the SDK as it is, and the default it uses when none
	 * is set is {@code https://api.openai.com/v1} ({@code OpenAiSetup.OPENAI_URL}). Set
	 * to the value of the table, the requests would miss the {@code /v1}.
	 */
	@Test
	void theBaseUrlIsUsedAsItIsWithoutAddingV1() {
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(OpenAiEmbeddingOptions.builder().baseUrl(this.openAi.url()).apiKey("test-key").build())
			.build();

		model.embed("Hello World");

		assertThat(this.openAi.single().path()).isEqualTo("/embeddings");
	}

	@Test
	void theSdkRetriesAFailedRequestAsOftenAsMaxRetriesSays() {
		try (StandIn failing = new StandIn((request) -> Reply.json(500, "{\"error\":{\"message\":\"down\"}}")
			.header("retry-after-ms", "1"))) {
			OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
				.options(OpenAiEmbeddingOptions.builder()
					.baseUrl(failing.url() + "/v1")
					.apiKey("test-key")
					.maxRetries(2)
					.build())
				.build();

			assertThatException().isThrownBy(() -> model.embed("Hello World")).withMessageContaining("down");
			assertThat(failing.requests).hasSize(3);
		}
	}

	private ApplicationContextRunner contexts() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.api-key=common-key", "spring.ai.openai.max-retries=0");
	}

	@Test
	void thePropertiesConfigureTheDefaultOptions() {
		contexts()
			.withPropertyValues("spring.ai.openai.embedding.model=text-embedding-3-small",
					"spring.ai.openai.embedding.dimensions=512", "spring.ai.openai.embedding.user=user-7",
					"spring.ai.openai.embedding.encoding-format=float",
					"spring.ai.openai.embedding.extra-body.truncate_prompt_tokens=10",
					"spring.ai.openai.embedding.metadata-mode=none")
			.run((context) -> {
				EmbeddingModel model = context.getBean(EmbeddingModel.class);

				model.embed(Document.builder().text("The text").metadata(Map.of("title", "A title")).build());

				JsonNode body = this.openAi.single().body();
				assertThat(body.get("model").asString()).isEqualTo("text-embedding-3-small");
				assertThat(body.get("dimensions").asInt()).isEqualTo(512);
				assertThat(body.get("user").asString()).isEqualTo("user-7");
				assertThat(body.get("encoding_format").asString()).isEqualTo("float");
				assertThat(body.get("truncate_prompt_tokens").asInt()).isEqualTo(10);
				assertThat(body.get("input").get(0).asString()).isEqualTo("\n\nThe text");
				assertThat(this.openAi.single().apiKey()).isEqualTo("common-key");
			});
	}

	@Test
	void theEmbeddingConnectionPropertiesTakePrecedenceOverTheCommonOnes() {
		try (StandIn other = new StandIn(EmbeddingModelTests::embeddings)) {
			contexts()
				.withPropertyValues("spring.ai.openai.embedding.base-url=" + other.url() + "/v1",
						"spring.ai.openai.embedding.api-key=embedding-key",
						"spring.ai.openai.organization-id=common-org",
						"spring.ai.openai.embedding.organization-id=embedding-org")
				.run((context) -> {
					context.getBean(EmbeddingModel.class).embed("Hello World");

					assertThat(this.openAi.requests).isEmpty();
					assertThat(other.single().apiKey()).isEqualTo("embedding-key");
					assertThat(other.single().header("OpenAI-Organization")).isEqualTo("embedding-org");
				});
		}
	}

	/**
	 * The OpenAI module retries with the SDK, not with {@code spring.ai.retry}: with
	 * {@code max-retries=0} a failed request is not sent again, whatever
	 * {@code spring.ai.retry.max-attempts} says.
	 */
	@Test
	void theSpringAiRetryPropertiesDoNotApply() {
		try (StandIn failing = new StandIn((request) -> Reply.json(500, "{\"error\":{\"message\":\"down\"}}"))) {
			new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
				.withPropertyValues("spring.ai.openai.base-url=" + failing.url() + "/v1",
						"spring.ai.openai.api-key=test-key", "spring.ai.openai.embedding.max-retries=0",
						"spring.ai.retry.max-attempts=5")
				.run((context) -> {
					EmbeddingModel model = context.getBean(EmbeddingModel.class);

					assertThatException().isThrownBy(() -> model.embed("Hello World"));
					assertThat(failing.requests).hasSize(1);
				});
		}
	}

	@Test
	void aRequestCanUseTheEmbeddingModelOfTheSampleController() {
		contexts().run((context) -> {
			EmbeddingResponse response = context.getBean(EmbeddingModel.class)
				.embedForResponse(List.of("Tell me a joke"));

			assertThat(response.getResult().getOutput()).containsExactly(0f, 14f, 0.5f);
		});
	}

}
