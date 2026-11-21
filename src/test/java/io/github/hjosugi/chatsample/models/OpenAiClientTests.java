package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every OpenAI page shares: the HTTP client customizers ("Customizing the HTTP
 * Client") and OpenAI models deployed on Microsoft Foundry (Azure and Microsoft
 * Foundry).
 */
class OpenAiClientTests {

	private final StandIn openAi = new StandIn((request) -> request.path().contains("images")
			? Reply.json("{\"created\": 1, \"data\": [{\"b64_json\": \"%s\"}]}".formatted(OpenAiImageTests.IMAGE))
			: EmbeddingModelTests.embeddings(request));

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	/**
	 * Sets a header on every request, through an OkHttp interceptor.
	 */
	record Header(String name, String value, int order) implements OpenAiHttpClientBuilderCustomizer, Ordered {

		@Override
		public void customize(SpringAiOpenAiHttpClient.Builder builder) {
			builder.interceptor((chain) -> chain
				.proceed(chain.request().newBuilder().header(this.name, this.value).build()));
		}

		@Override
		public int getOrder() {
			return this.order;
		}

	}

	@Test
	void customizerBeansApplyToEveryOpenAiModelInTheirOrder() {
		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class, OpenAiImageAutoConfiguration.class))
			// Registered in the reverse of their order: the order decides which one runs last.
			.withBean("later", Header.class, () -> new Header("X-Order", "second", 2))
			.withBean("tenant", Header.class, () -> new Header("X-Tenant", "shop-1", 1))
			.withBean("earlier", Header.class, () -> new Header("X-Order", "first", 0))
			.withPropertyValues("spring.ai.openai.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.api-key=test-key", "spring.ai.openai.max-retries=0")
			.run((context) -> {
				context.getBean(EmbeddingModel.class).embed("Hello");
				context.getBean(ImageModel.class).call(new ImagePrompt("A doodle"));

				assertThat(this.openAi.requests).hasSize(2)
					.allSatisfy((request) -> assertThat(request.header("X-Tenant")).isEqualTo("shop-1"));
				assertThat(this.openAi.requests).allSatisfy(
						(request) -> assertThat(request.headers().get("X-order")).containsExactly("second"));
			});
	}

	@Test
	void aCustomizerCanBeGivenToTheBuilderOfAModel() {
		OpenAiEmbeddingModel.builder()
			.options(OpenAiEmbeddingOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").build())
			.httpClientBuilderCustomizer(new Header("X-Tenant", "shop-1", 0))
			.build()
			.embed("Hello");

		assertThat(this.openAi.single().header("X-Tenant")).isEqualTo("shop-1");
	}

	@Test
	void microsoftFoundryUsesTheDeploymentAndTheApiKeyHeader() {
		OpenAiEmbeddingModel.builder()
			.options(OpenAiEmbeddingOptions.builder()
				.baseUrl(this.openAi.url())
				.apiKey("azure-key")
				.microsoftFoundry(true)
				.deploymentName("my-embeddings")
				.build())
			.build()
			.embed("Hello");

		StandIn.Request request = this.openAi.single();
		assertThat(request.path()).isEqualTo("/openai/deployments/my-embeddings/embeddings");
		assertThat(request.query()).startsWith("api-version=");
		assertThat(request.header("api-key")).isEqualTo("azure-key");
		assertThat(request.body().get("model").asString()).isEqualTo("my-embeddings");
		assertThat(List.of(request.headers().keySet().toArray())).noneMatch((name) -> "Authorization".equals(name));
	}

}
