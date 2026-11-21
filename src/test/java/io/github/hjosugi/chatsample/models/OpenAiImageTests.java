package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.metadata.OpenAiImageGenerationMetadata;
import org.springframework.ai.openai.metadata.OpenAiImageResponseMetadata;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The OpenAI Image Generation page: what goes to {@code /images/generations}, the
 * options at start and for one call, the portable options, the properties, and what
 * comes back.
 */
class OpenAiImageTests {

	static final String IMAGE = "iVBORw0KGgo=";

	private final StandIn openAi = new StandIn((request) -> Reply.json("""
			{"created": 1700000000,
			 "data": [{"b64_json": "%s", "revised_prompt": "A cream colored mini golden doodle, sitting"}],
			 "usage": {"input_tokens": 12, "output_tokens": 4160, "total_tokens": 4172,
			           "input_tokens_details": {"text_tokens": 12, "image_tokens": 0}}}""".formatted(IMAGE)));

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private OpenAiImageOptions.Builder connected() {
		return OpenAiImageOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").maxRetries(0);
	}

	@Test
	void thePromptGoesToTheImagesEndpointWithTheDefaultModel() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().build()).build();

		ImageResponse response = model.call(new ImagePrompt("A light cream colored mini golden doodle"));

		StandIn.Request request = this.openAi.single();
		assertThat(request.path()).isEqualTo("/v1/images/generations");
		assertThat(request.apiKey()).isEqualTo("test-key");
		assertThat(request.body().get("model").asString()).isEqualTo("gpt-image-1-mini");
		assertThat(request.body().get("prompt").asString()).isEqualTo("A light cream colored mini golden doodle");
		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo(IMAGE);
		assertThat(response.getResult().getOutput().getUrl()).isNull();
	}

	@Test
	void theRevisedPromptAndTheCreationTimeComeBackAsMetadata() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().build()).build();

		ImageResponse response = model.call(new ImagePrompt("A light cream colored mini golden doodle"));

		assertThat(response.getResult().getMetadata()).isInstanceOfSatisfying(OpenAiImageGenerationMetadata.class,
				(metadata) -> assertThat(metadata.getRevisedPrompt())
					.isEqualTo("A cream colored mini golden doodle, sitting"));
		assertThat(response.getMetadata()).isInstanceOf(OpenAiImageResponseMetadata.class);
		assertThat(response.getMetadata().getCreated()).isEqualTo(1700000000L);
	}

	@Test
	void theOptionsOfThePromptOverrideTheDefaultsAndWidthAndHeightMakeTheSize() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().quality("low").build()).build();

		model.call(new ImagePrompt("A light cream colored mini golden doodle",
				OpenAiImageOptions.builder().quality("high").n(4).height(1024).width(1536).user("user-1").build()));

		JsonNode body = this.openAi.single().body();
		assertThat(body.get("quality").asString()).isEqualTo("high");
		assertThat(body.get("n").asInt()).isEqualTo(4);
		assertThat(body.get("size").asString()).isEqualTo("1536x1024");
		assertThat(body.get("user").asString()).isEqualTo("user-1");
	}

	/**
	 * The page says that the options of the prompt override the defaults. An
	 * {@code OpenAiImageOptions} always has a model, {@code gpt-image-1-mini} unless it
	 * says otherwise, so options that change only the quality, as in the example of the
	 * page, also put the model back to {@code gpt-image-1-mini}, whatever
	 * {@code spring.ai.openai.image.model} says.
	 */
	@Test
	void runtimeOptionsWithoutAModelPutTheModelBackToGptImage1Mini() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().model("gpt-image-1").build()).build();

		model.call(new ImagePrompt("A doodle", OpenAiImageOptions.builder().quality("high").build()));
		model.call(new ImagePrompt("A doodle", ImageOptionsBuilder.builder().n(1).build()));

		assertThat(this.openAi.requests.get(0).body().get("model").asString()).isEqualTo("gpt-image-1-mini");
		assertThat(this.openAi.requests.get(1).body().get("model").asString()).isEqualTo("gpt-image-1");
	}

	@Test
	void portableImageOptionsWorkToo() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().build()).build();

		model.call(new ImagePrompt("A doodle",
				ImageOptionsBuilder.builder()
					.model("dall-e-3")
					.n(1)
					.width(1024)
					.height(1024)
					.responseFormat("url")
					.style("vivid")
					.build()));

		JsonNode body = this.openAi.single().body();
		assertThat(body.get("model").asString()).isEqualTo("dall-e-3");
		assertThat(body.get("size").asString()).isEqualTo("1024x1024");
		assertThat(body.get("response_format").asString()).isEqualTo("url");
		assertThat(body.get("style").asString()).isEqualTo("vivid");
	}

	@Test
	void anImageThatComesAsAUrlKeepsItsUrl() {
		try (StandIn dalle = new StandIn((request) -> Reply.json("""
				{"created": 1700000000, "data": [{"url": "https://images.example/1.png"}]}"""))) {
			OpenAiImageModel model = OpenAiImageModel.builder()
				.options(OpenAiImageOptions.builder().baseUrl(dalle.url() + "/v1").apiKey("test-key").build())
				.build();

			ImageResponse response = model.call(new ImagePrompt("A doodle"));

			assertThat(response.getResult().getOutput().getUrl()).isEqualTo("https://images.example/1.png");
		}
	}

	@Test
	void onlyTheTextOfTheFirstMessageIsSentAndItsWeightIsIgnored() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().build()).build();

		model.call(new ImagePrompt(List.of(new ImageMessage("A doodle", 1.0f), new ImageMessage("Not a cat", -1.0f)),
				ImageOptionsBuilder.builder().build()));

		assertThat(this.openAi.single().body().get("prompt").asString()).isEqualTo("A doodle");
		assertThat(this.openAi.single().body().toString()).doesNotContain("Not a cat");
	}

	/**
	 * The options of an {@code ImagePrompt} are optional on the page, and
	 * {@code new ImagePrompt(List)} leaves them out. The OpenAI model then fails before it
	 * sends anything, in its observation context.
	 */
	@Test
	void aPromptOfMessagesWithoutOptionsFailsBeforeItIsSent() {
		OpenAiImageModel model = OpenAiImageModel.builder().options(connected().build()).build();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> model.call(new ImagePrompt(List.of(new ImageMessage("A doodle")))))
			.withMessage("image options cannot be null");
		assertThat(this.openAi.requests).isEmpty();
	}

	@Test
	void thePropertiesConfigureTheDefaultOptions() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenAiImageAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.api-key=common-key",
					"spring.ai.openai.image.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.image.api-key=image-key", "spring.ai.openai.image.model=gpt-image-1",
					"spring.ai.openai.image.quality=medium", "spring.ai.openai.image.n=2",
					"spring.ai.openai.image.width=1024", "spring.ai.openai.image.height=1536",
					"spring.ai.openai.image.user=user-2", "spring.ai.openai.max-retries=0")
			.run((context) -> {
				context.getBean(ImageModel.class).call(new ImagePrompt("A doodle"));

				StandIn.Request request = this.openAi.single();
				assertThat(request.apiKey()).isEqualTo("image-key");
				assertThat(request.body().get("model").asString()).isEqualTo("gpt-image-1");
				assertThat(request.body().get("quality").asString()).isEqualTo("medium");
				assertThat(request.body().get("n").asInt()).isEqualTo(2);
				assertThat(request.body().get("size").asString()).isEqualTo("1024x1536");
				assertThat(request.body().get("user").asString()).isEqualTo("user-2");
			});
	}

	@Test
	void theSizePropertyWinsOverWidthAndHeight() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenAiImageAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.api-key=common-key",
					"spring.ai.openai.base-url=" + this.openAi.url() + "/v1", "spring.ai.openai.image.size=auto",
					"spring.ai.openai.image.width=1024", "spring.ai.openai.image.height=1536")
			.run((context) -> {
				context.getBean(ImageModel.class).call(new ImagePrompt("A doodle"));

				assertThat(this.openAi.single().body().get("size").asString()).isEqualTo("auto");
			});
	}

}
