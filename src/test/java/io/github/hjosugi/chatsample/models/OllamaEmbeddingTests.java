package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ollama Embeddings page, against a stand-in for Ollama's {@code /api/embed}: the
 * request, the options at start and for one call, the properties, and the pulling of
 * models at startup.
 */
class OllamaEmbeddingTests {

	private final StandIn ollama = new StandIn(OllamaEmbeddingTests::reply);

	static Reply reply(StandIn.Request request) {
		if (request.path().equals("/api/tags")) {
			return Reply.json("{\"models\": []}");
		}
		JsonNode input = request.body().get("input");
		StringBuilder embeddings = new StringBuilder();
		for (int i = 0; i < input.size(); i++) {
			embeddings.append(i > 0 ? "," : "").append("[%d.0, %d.0]".formatted(i, input.get(i).asString().length()));
		}
		return Reply.json("""
				{"model": "%s", "embeddings": [%s], "total_duration": 1000, "load_duration": 10,
				 "prompt_eval_count": 6}""".formatted(request.body().get("model").asString(), embeddings));
	}

	@AfterEach
	void stop() {
		this.ollama.close();
	}

	private OllamaEmbeddingModel model(OllamaEmbeddingOptions options) {
		return OllamaEmbeddingModel.builder()
			.ollamaApi(OllamaApi.builder().baseUrl(this.ollama.url()).build())
			.options(options)
			.build();
	}

	@Test
	void theTextsGoToTheEmbedEndpointWithTheDefaultModel() {
		EmbeddingResponse response = model(OllamaEmbeddingOptions.builder().build())
			.embedForResponse(List.of("Hello World", "World is big and salvation is near"));

		StandIn.Request request = this.ollama.single();
		assertThat(request.path()).isEqualTo("/api/embed");
		JsonNode body = request.body();
		assertThat(body.get("model").asString()).isEqualTo("mxbai-embed-large");
		assertThat(body.get("input")).hasSize(2);
		assertThat(response.getResults().get(1).getOutput()).containsExactly(1f, 34f);
		assertThat(response.getMetadata().getModel()).isEqualTo("mxbai-embed-large");
	}

	@Test
	void theOptionsOfARequestOverrideTheDefaultsAndTheModelOptionsGoIntoOptions() {
		OllamaEmbeddingModel model = model(OllamaEmbeddingOptions.builder()
			.model("nomic-embed-text")
			.keepAlive("10m")
			.numCtx(2048)
			.build());

		model.call(new EmbeddingRequest(List.of("Hello World", "World is big and salvation is near"),
				OllamaEmbeddingOptions.builder()
					.model("chroma/all-minilm-l6-v2-f32")
					.truncate(false)
					.build()));

		JsonNode body = this.ollama.single().body();
		assertThat(body.get("model").asString()).isEqualTo("chroma/all-minilm-l6-v2-f32");
		assertThat(body.get("truncate").asBoolean()).isFalse();
		assertThat(body.get("keep_alive").asString()).isEqualTo("10m");
		assertThat(body.get("options").get("num_ctx").asInt()).isEqualTo(2048);
	}

	/**
	 * An {@code OllamaEmbeddingOptions} always has a model, {@code mxbai-embed-large}
	 * unless it says otherwise, so request options that change only {@code truncate}
	 * put the model back to {@code mxbai-embed-large}, whatever the defaults say.
	 */
	@Test
	void requestOptionsWithoutAModelPutTheModelBackToMxbaiEmbedLarge() {
		OllamaEmbeddingModel model = model(OllamaEmbeddingOptions.builder().model("nomic-embed-text").build());

		model.call(new EmbeddingRequest(List.of("Hello"), OllamaEmbeddingOptions.builder().truncate(false).build()));

		assertThat(this.ollama.single().body().get("model").asString()).isEqualTo("mxbai-embed-large");
	}

	/**
	 * The page gives {@code 5m} and {@code true} as the defaults of keep-alive and
	 * truncate. Spring AI sends neither unless they are set, and leaves them to Ollama,
	 * whose own defaults they are.
	 */
	@Test
	void keepAliveAndTruncateAreNotSentUnlessSet() {
		model(OllamaEmbeddingOptions.builder().build()).embed("Hello");

		assertThat(this.ollama.single().body().has("keep_alive")).isFalse();
		assertThat(this.ollama.single().body().has("truncate")).isFalse();
	}

	private ApplicationContextRunner contexts() {
		return new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(OllamaApiAutoConfiguration.class, OllamaEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.ollama.base-url=" + this.ollama.url());
	}

	/**
	 * The page says that the {@code spring.ai.ollama.embedding} properties are the
	 * defaults of every request. {@code embed(String)}, {@code embed(List)} and
	 * {@code embedForResponse} send portable options, and with options that are not
	 * {@code OllamaEmbeddingOptions} the model keeps only the model and the dimensions of
	 * its defaults: {@code keep-alive} and {@code truncate} are dropped
	 * ({@code OllamaEmbeddingModel.mergeOptions}).
	 */
	@Test
	void theKeepAliveAndTruncatePropertiesAreDroppedByTheShortcutMethods() {
		contexts()
			.withPropertyValues("spring.ai.ollama.embedding.model=nomic-embed-text",
					"spring.ai.ollama.embedding.keep-alive=1m", "spring.ai.ollama.embedding.truncate=false")
			.run((context) -> {
				OllamaEmbeddingModel model = context.getBean(OllamaEmbeddingModel.class);

				model.embed("Hello");

				assertThat(context.getBean(OllamaEmbeddingProperties.class).toOptions().getKeepAlive()).isEqualTo("1m");
				assertThat(context.getBean(OllamaEmbeddingProperties.class).toOptions().getTruncate()).isFalse();
				JsonNode body = this.ollama.single().body();
				assertThat(body.get("model").asString()).isEqualTo("nomic-embed-text");
				assertThat(body.has("keep_alive")).isFalse();
				assertThat(body.has("truncate")).isFalse();
			});
	}

	@Test
	void withPullModelStrategyAlwaysTheModelAndTheAdditionalModelsArePulledAtStartup() {
		try (StandIn pulling = new StandIn((request) -> switch (request.path()) {
			case "/api/pull" -> new Reply(200, "application/x-ndjson", java.util.Map.of(),
					List.of("{\"status\": \"pulling manifest\"}\n{\"status\": \"success\"}\n"
						.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			case "/api/tags" -> Reply.json("""
					{"models": [{"name": "mxbai-embed-large:latest", "model": "mxbai-embed-large:latest"},
					            {"name": "nomic-embed-text:latest", "model": "nomic-embed-text:latest"}]}""");
			default -> reply(request);
		})) {
			new ApplicationContextRunner()
				.withConfiguration(
						AutoConfigurations.of(OllamaApiAutoConfiguration.class, OllamaEmbeddingAutoConfiguration.class))
				.withPropertyValues("spring.ai.ollama.base-url=" + pulling.url(),
						"spring.ai.ollama.init.pull-model-strategy=always",
						"spring.ai.ollama.init.embedding.additional-models=nomic-embed-text")
				.run((context) -> {
					assertThat(context).hasNotFailed();
					assertThat(pulling.requests).filteredOn((request) -> request.path().equals("/api/pull"))
						.extracting((request) -> request.body().get("model").asString())
						.containsExactlyInAnyOrder("mxbai-embed-large", "nomic-embed-text");
				});
		}
	}

	@Test
	void withoutAPullStrategyNothingIsAskedAtStartup() {
		contexts().run((context) -> {
			assertThat(context).hasSingleBean(OllamaEmbeddingModel.class);
			assertThat(this.ollama.requests).isEmpty();
		});
	}

}
