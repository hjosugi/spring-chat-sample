package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.mistralai.MistralAiEmbeddingModel;
import org.springframework.ai.mistralai.MistralAiEmbeddingOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.ai.model.mistralai.autoconfigure.MistralAiEmbeddingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Mistral AI Embeddings page: what goes to {@code /v1/embeddings}, the options, the
 * properties and their overrides, and the retries of {@code spring.ai.retry}.
 */
class MistralAiEmbeddingTests {

	private final StandIn mistral = new StandIn(EmbeddingModelTests::embeddings);

	@AfterEach
	void stop() {
		this.mistral.close();
	}

	private MistralAiEmbeddingModel model(MistralAiEmbeddingOptions options) {
		return MistralAiEmbeddingModel.builder()
			.mistralAiApi(MistralAiApi.builder().baseUrl(this.mistral.url()).apiKey("test-key").build())
			.options(options)
			.build();
	}

	@Test
	void theTextsGoToTheEmbeddingsEndpointWithTheDefaultModelAndFormat() {
		EmbeddingResponse response = model(MistralAiEmbeddingOptions.builder().build())
			.embedForResponse(List.of("Hello World", "World is big and salvation is near"));

		StandIn.Request request = this.mistral.single();
		assertThat(request.path()).isEqualTo("/v1/embeddings");
		assertThat(request.apiKey()).isEqualTo("test-key");
		assertThat(request.body().get("model").asString()).isEqualTo("mistral-embed");
		assertThat(request.body().get("encoding_format").asString()).isEqualTo("float");
		assertThat(request.body().get("input")).hasSize(2);
		assertThat(response.getResults().get(1).getOutput()).containsExactly(1f, 34f, 0.5f);
		assertThat(response.getMetadata().getModel()).isEqualTo("mistral-embed");
	}

	@Test
	void theModelOfARequestOverridesTheDefaultForThatCall() {
		MistralAiEmbeddingModel model = model(MistralAiEmbeddingOptions.builder().build());

		model.call(new EmbeddingRequest(List.of("public class HelloWorld {}", "def hello_world():"),
				MistralAiEmbeddingOptions.builder().model("codestral-embed").build()));
		model.embed("Hello");

		assertThat(this.mistral.requests.get(0).body().get("model").asString()).isEqualTo("codestral-embed");
		assertThat(this.mistral.requests.get(1).body().get("model").asString()).isEqualTo("mistral-embed");
	}

	/**
	 * {@code MistralAiEmbeddingModel.dimensions()} reads
	 * {@code KNOWN_EMBEDDING_DIMENSIONS.getOrDefault(model, super.dimensions())}: Java
	 * evaluates {@code super.dimensions()} before the lookup, so it asks the API even for
	 * {@code mistral-embed}, whose 1024 dimensions it knows.
	 */
	@Test
	void dimensionsAsksTheApiEvenForAModelItKnows() {
		int dimensions = model(MistralAiEmbeddingOptions.builder().build()).dimensions();

		assertThat(dimensions).isEqualTo(1024);
		assertThat(this.mistral.requests).hasSize(1);
	}

	private ApplicationContextRunner contexts() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MistralAiEmbeddingAutoConfiguration.class));
	}

	@Test
	void thePropertiesConfigureTheModelAndTheEmbeddingConnectionTakesPrecedence() {
		try (StandIn other = new StandIn(EmbeddingModelTests::embeddings)) {
			contexts()
				.withPropertyValues("spring.ai.mistralai.base-url=" + this.mistral.url(),
						"spring.ai.mistralai.api-key=common-key",
						"spring.ai.mistralai.embedding.base-url=" + other.url(),
						"spring.ai.mistralai.embedding.api-key=embedding-key",
						"spring.ai.mistralai.embedding.model=codestral-embed")
				.run((context) -> {
					context.getBean(EmbeddingModel.class).embed("Hello");

					assertThat(this.mistral.requests).isEmpty();
					assertThat(other.single().apiKey()).isEqualTo("embedding-key");
					assertThat(other.single().body().get("model").asString()).isEqualTo("codestral-embed");
				});
		}
	}

	/**
	 * The page says that {@code spring.ai.mistralai.embedding.base-url} overrides
	 * {@code spring.ai.mistralai.base-url}. The embedding properties default their own
	 * base URL to {@code https://api.mistral.ai} ({@code MistralAiEmbeddingProperties}),
	 * so the common one is never used: an application that points only
	 * {@code spring.ai.mistralai.base-url} at a proxy still calls Mistral. The moderation
	 * properties do the same. The request is caught before it leaves.
	 */
	@Test
	void theCommonBaseUrlIsIgnoredBecauseTheEmbeddingBaseUrlHasADefault() {
		List<String> urls = new java.util.concurrent.CopyOnWriteArrayList<>();
		contexts().withBean(RestClient.Builder.class, () -> RestClient.builder().requestInterceptor((request, body,
				execution) -> {
			urls.add(request.getURI().toString());
			MockClientHttpResponse response = new MockClientHttpResponse(("""
					{"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": [1.0]}],
					 "model": "mistral-embed", "usage": {"prompt_tokens": 1, "total_tokens": 1}}""")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			return response;
		}))
			.withPropertyValues("spring.ai.mistralai.base-url=" + this.mistral.url(),
					"spring.ai.mistralai.api-key=test-key")
			.run((context) -> {
				context.getBean(EmbeddingModel.class).embed("Hello");

				assertThat(urls).containsExactly("https://api.mistral.ai/v1/embeddings");
				assertThat(this.mistral.requests).isEmpty();
			});
	}

	@Test
	void theSpringAiRetryPropertiesRetryAFailedRequest() {
		AtomicInteger calls = new AtomicInteger();
		try (StandIn flaky = new StandIn((request) -> (calls.incrementAndGet() == 1)
				? Reply.json(503, "{\"message\":\"busy\"}") : EmbeddingModelTests.embeddings(request))) {
			new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class,
						MistralAiEmbeddingAutoConfiguration.class))
				.withPropertyValues("spring.ai.mistralai.embedding.base-url=" + flaky.url(),
						"spring.ai.mistralai.api-key=test-key", "spring.ai.retry.max-attempts=2",
						"spring.ai.retry.backoff.initial-interval=1ms")
				.run((context) -> {
					float[] vector = context.getBean(EmbeddingModel.class).embed("Hello");

					assertThat(vector).containsExactly(0f, 5f, 0.5f);
					assertThat(flaky.requests).hasSize(2);
				});
		}
	}

}
