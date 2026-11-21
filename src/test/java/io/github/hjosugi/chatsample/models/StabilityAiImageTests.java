package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.stabilityai.autoconfigure.StabilityAiImageAutoConfiguration;
import org.springframework.ai.stabilityai.StabilityAiImageGenerationMetadata;
import org.springframework.ai.stabilityai.StabilityAiImageModel;
import org.springframework.ai.stabilityai.api.StabilityAiApi;
import org.springframework.ai.stabilityai.api.StabilityAiImageOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Stability AI Image Generation page: the text-to-image request of the v1 API, the
 * weighted prompts, the options and the properties.
 */
class StabilityAiImageTests {

	private final StandIn stability = new StandIn((request) -> Reply.json("""
			{"artifacts": [{"base64": "%s", "seed": 1234, "finishReason": "SUCCESS"},
			               {"base64": "%s", "seed": 1235, "finishReason": "CONTENT_FILTERED"}]}"""
		.formatted(OpenAiImageTests.IMAGE, OpenAiImageTests.IMAGE)));

	@AfterEach
	void stop() {
		this.stability.close();
	}

	private StabilityAiImageModel model(StabilityAiImageOptions options) {
		return new StabilityAiImageModel(
				new StabilityAiApi("test-key", "stable-diffusion-v1-6", this.stability.url() + "/v1"), options);
	}

	@Test
	void thePromptGoesToTheTextToImageEndpointOfTheModel() {
		ImageResponse response = model(StabilityAiImageOptions.builder().build())
			.call(new ImagePrompt("A light cream colored mini golden doodle"));

		StandIn.Request request = this.stability.single();
		assertThat(request.path()).isEqualTo("/v1/generation/stable-diffusion-v1-6/text-to-image");
		assertThat(request.apiKey()).isEqualTo("test-key");
		assertThat(request.header("Accept")).isEqualTo("application/json");
		assertThat(request.body().get("text_prompts").get(0).get("text").asString())
			.isEqualTo("A light cream colored mini golden doodle");
		assertThat(response.getResults()).hasSize(2);
		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo(OpenAiImageTests.IMAGE);
		assertThat(response.getResults().get(1).getMetadata()).isInstanceOfSatisfying(
				StabilityAiImageGenerationMetadata.class, (metadata) -> {
					assertThat(metadata.getFinishReason()).isEqualTo("CONTENT_FILTERED");
					assertThat(metadata.getSeed()).isEqualTo(1235L);
				});
	}

	@Test
	void everyMessageIsATextPromptWithItsWeight() {
		model(StabilityAiImageOptions.builder().build()).call(new ImagePrompt(
				List.of(new ImageMessage("A doodle", 1.0f), new ImageMessage("blurry", -0.5f)),
				StabilityAiImageOptions.builder().build()));

		JsonNode prompts = this.stability.single().body().get("text_prompts");
		assertThat(prompts).hasSize(2);
		assertThat(prompts.get(1).get("text").asString()).isEqualTo("blurry");
		assertThat(prompts.get(1).get("weight").asDouble()).isEqualTo(-0.5);
	}

	@Test
	void theOptionsOfThePromptOverrideTheDefaults() {
		model(StabilityAiImageOptions.builder().stylePreset("anime").steps(20).build())
			.call(new ImagePrompt("A light cream colored mini golden doodle",
					StabilityAiImageOptions.builder().stylePreset("cinematic").n(4).height(1024).width(1024).seed(7L)
						.build()));

		JsonNode body = this.stability.single().body();
		assertThat(body.get("style_preset").asString()).isEqualTo("cinematic");
		assertThat(body.get("samples").asInt()).isEqualTo(4);
		assertThat(body.get("height").asInt()).isEqualTo(1024);
		assertThat(body.get("width").asInt()).isEqualTo(1024);
		assertThat(body.get("seed").asLong()).isEqualTo(7);
		assertThat(body.get("steps").asInt()).isEqualTo(20);
	}

	/**
	 * The model is part of the URL, and the URL takes the model of the
	 * {@code StabilityAiApi}: a model in the options of the prompt, portable or not,
	 * changes nothing.
	 */
	@Test
	void theModelOfThePromptOptionsIsIgnored() {
		model(StabilityAiImageOptions.builder().build())
			.call(new ImagePrompt("A doodle", StabilityAiImageOptions.builder().model("stable-diffusion-xl-1024-v1-0")
				.build()));

		assertThat(this.stability.single().path()).isEqualTo("/v1/generation/stable-diffusion-v1-6/text-to-image");
	}

	@Test
	void thePropertiesConfigureTheModelTheSizeAndTheDiffusion() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(StabilityAiImageAutoConfiguration.class))
			.withPropertyValues("spring.ai.stabilityai.api-key=common-key",
					"spring.ai.stabilityai.image.base-url=" + this.stability.url() + "/v1",
					"spring.ai.stabilityai.image.api-key=image-key",
					"spring.ai.stabilityai.image.model=stable-diffusion-xl-1024-v1-0",
					"spring.ai.stabilityai.image.width=1024", "spring.ai.stabilityai.image.height=1024",
					"spring.ai.stabilityai.image.n=2", "spring.ai.stabilityai.image.cfg-scale=10",
					"spring.ai.stabilityai.image.steps=40", "spring.ai.stabilityai.image.style-preset=photographic")
			.run((context) -> {
				context.getBean(ImageModel.class).call(new ImagePrompt("A doodle"));

				StandIn.Request request = this.stability.single();
				assertThat(request.path()).isEqualTo("/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image");
				assertThat(request.apiKey()).isEqualTo("image-key");
				JsonNode body = request.body();
				assertThat(body.get("width").asInt()).isEqualTo(1024);
				assertThat(body.get("samples").asInt()).isEqualTo(2);
				assertThat(body.get("cfg_scale").asDouble()).isEqualTo(10.0);
				assertThat(body.get("steps").asInt()).isEqualTo(40);
				assertThat(body.get("style_preset").asString()).isEqualTo("photographic");
			});
	}

}
