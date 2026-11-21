package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.image.GoogleGenAiImageConnectionDetails;
import org.springframework.ai.google.genai.image.GoogleGenAiImageModel;
import org.springframework.ai.google.genai.image.GoogleGenAiImageOptions;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.stabilityai.StabilityAiImageModel;
import org.springframework.ai.stabilityai.api.StabilityAiApi;
import org.springframework.ai.stabilityai.api.StabilityAiImageOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Image Model API page: an {@code ImagePrompt} of messages and portable options
 * goes to OpenAI, Stability AI and Google GenAI, and only Google sends the media of a
 * message.
 */
class ImageModelTests {

	private final StandIn openAi = new StandIn(
			(request) -> Reply.json("{\"created\": 1, \"data\": [{\"b64_json\": \"%s\"}]}".formatted(OpenAiImageTests.IMAGE)));

	private final StandIn stability = new StandIn((request) -> Reply.json(
			"{\"artifacts\": [{\"base64\": \"%s\", \"seed\": 1, \"finishReason\": \"SUCCESS\"}]}"
				.formatted(OpenAiImageTests.IMAGE)));

	private final StandIn gemini = new StandIn((request) -> Gemini.generated(
			"{\"inlineData\": {\"mimeType\": \"image/png\", \"data\": \"%s\"}}".formatted(OpenAiImageTests.IMAGE)));

	@AfterEach
	void stop() {
		this.openAi.close();
		this.stability.close();
		this.gemini.close();
	}

	private List<ImageModel> models() {
		return List.of(
				OpenAiImageModel.builder()
					.options(OpenAiImageOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").build())
					.build(),
				new StabilityAiImageModel(
						new StabilityAiApi("test-key", "stable-diffusion-v1-6", this.stability.url() + "/v1"),
						StabilityAiImageOptions.builder().build()),
				new GoogleGenAiImageModel(
						GoogleGenAiImageConnectionDetails.builder().genAiClient(Gemini.client(this.gemini)).build(),
						GoogleGenAiImageOptions.builder().build()));
	}

	@Test
	void everyProviderAnswersAnImagePromptWithImageGenerations() {
		for (ImageModel model : models()) {
			ImageResponse response = model.call(new ImagePrompt("A painting of a sunset over a mountain"));

			assertThat(response.getResult()).isSameAs(response.getResults().get(0));
			assertThat(response.getResult().getOutput().getB64Json()).isEqualTo(OpenAiImageTests.IMAGE);
			assertThat(response.getMetadata()).isNotNull();
		}
	}

	@Test
	void portableOptionsReachEachProvider() {
		ImageOptions portable = ImageOptionsBuilder.builder().n(2).width(1024).height(1024).build();

		for (ImageModel model : models()) {
			model.call(new ImagePrompt("A sunset", portable));
		}

		assertThat(this.openAi.single().body().get("n").asInt()).isEqualTo(2);
		assertThat(this.openAi.single().body().get("size").asString()).isEqualTo("1024x1024");
		assertThat(this.stability.single().body().get("samples").asInt()).isEqualTo(2);
		assertThat(this.stability.single().body().get("width").asInt()).isEqualTo(1024);
		assertThat(this.gemini.single().body().get("generationConfig").get("candidateCount").asInt()).isEqualTo(2);
	}

	@Test
	void onlyGoogleGenAiSendsTheMediaOfAMessage() {
		ImageMessage withPhoto = new ImageMessage("Put a hat on this cat", null,
				List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2, 3 }))), Map.of());

		for (ImageModel model : models()) {
			model.call(new ImagePrompt(List.of(withPhoto), ImageOptionsBuilder.builder().build()));
		}

		assertThat(this.openAi.single().text()).doesNotContain("AQID");
		assertThat(this.stability.single().text()).doesNotContain("AQID");
		assertThat(this.gemini.single().text()).contains("AQID");
	}

}
