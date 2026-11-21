package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.image.GoogleGenAiImageConnectionDetails;
import org.springframework.ai.google.genai.image.GoogleGenAiImageGenerationMetadata;
import org.springframework.ai.google.genai.image.GoogleGenAiImageModel;
import org.springframework.ai.google.genai.image.GoogleGenAiImageOptions;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google GenAI Image page: the image models of Gemini answer {@code generateContent}
 * with the image as inline data. What goes out, what comes back, the options and the
 * properties.
 */
class GoogleGenAiImageTests {

	private final StandIn gemini = new StandIn((request) -> Gemini.generated("""
			{"text": "Here is your sunset."},
			{"inlineData": {"mimeType": "image/png", "data": "%s"}}""".formatted(OpenAiImageTests.IMAGE)));

	@AfterEach
	void stop() {
		this.gemini.close();
	}

	private GoogleGenAiImageConnectionDetails connection() {
		return GoogleGenAiImageConnectionDetails.builder().genAiClient(Gemini.client(this.gemini)).build();
	}

	private GoogleGenAiImageModel model(GoogleGenAiImageOptions options) {
		return new GoogleGenAiImageModel(connection(), options);
	}

	@Test
	void thePromptGoesToGenerateContentAndTheInlineImageComesBack() {
		ImageResponse response = model(GoogleGenAiImageOptions.builder().build())
			.call(new ImagePrompt("A painting of a sunset over a mountain"));

		StandIn.Request request = this.gemini.single();
		assertThat(request.path()).isEqualTo("/v1beta/models/gemini-2.5-flash-image:generateContent");
		assertThat(request.apiKey()).isEqualTo("test-key");
		JsonNode body = request.body();
		assertThat(body.get("contents").get(0).get("parts").get(0).get("text").asString())
			.isEqualTo("A painting of a sunset over a mountain");
		assertThat(body.get("generationConfig").get("responseModalities").toString())
			.isEqualTo("[\"TEXT\",\"IMAGE\"]");
		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo(OpenAiImageTests.IMAGE);
		assertThat(response.getResult().getMetadata()).isInstanceOfSatisfying(
				GoogleGenAiImageGenerationMetadata.class,
				(metadata) -> assertThat(metadata.getMimeType()).isEqualTo("image/png"));
	}

	@Test
	void theTextAndTheModelVersionAndTheUsageComeBackAsMetadata() {
		ImageResponse response = model(GoogleGenAiImageOptions.builder().build()).call(new ImagePrompt("A sunset"));

		assertThat((String) response.getMetadata().get("text")).isEqualTo("Here is your sunset.");
		assertThat((String) response.getMetadata().get("model")).isEqualTo("gemini-test-001");
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(27);
	}

	@Test
	void theOptionsOfThePromptGoIntoTheGenerationConfig() {
		model(GoogleGenAiImageOptions.builder().build()).call(new ImagePrompt("A sunset",
				GoogleGenAiImageOptions.builder()
					.model("gemini-3.1-flash-image")
					.n(2)
					.aspectRatio("16:9")
					.imageSize("2K")
					.seed(42)
					.temperature(0.4f)
					.build()));

		StandIn.Request request = this.gemini.single();
		assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3.1-flash-image:generateContent");
		JsonNode config = request.body().get("generationConfig");
		assertThat(config.get("candidateCount").asInt()).isEqualTo(2);
		assertThat(config.get("seed").asInt()).isEqualTo(42);
		assertThat(config.get("temperature").asDouble()).isEqualTo(0.4, org.assertj.core.data.Offset.offset(0.001));
		assertThat(config.get("imageConfig").get("aspectRatio").asString()).isEqualTo("16:9");
		assertThat(config.get("imageConfig").get("imageSize").asString()).isEqualTo("2K");
	}

	/**
	 * The page says that the {@code spring.ai.google.genai.image} properties are the
	 * defaults. {@code new ImagePrompt(String)}, which its sample controller uses, carries
	 * portable options, and with options that are not {@code GoogleGenAiImageOptions} the
	 * model keeps only the model and the number of images of its defaults: the aspect
	 * ratio, the seed, the temperature and the rest are dropped
	 * ({@code GoogleGenAiImageModel.buildImagePrompt}).
	 */
	@Test
	void aPromptWithoutGoogleOptionsKeepsOnlyTheModelAndTheNumberOfTheDefaults() {
		GoogleGenAiImageModel model = model(
				GoogleGenAiImageOptions.builder().n(2).aspectRatio("16:9").seed(42).temperature(0.4f).build());

		model.call(new ImagePrompt("A sunset"));
		model.call(new ImagePrompt("A sunset", GoogleGenAiImageOptions.builder().build()));

		JsonNode portable = this.gemini.requests.get(0).body().get("generationConfig");
		assertThat(portable.get("candidateCount").asInt()).isEqualTo(2);
		assertThat(portable.has("seed")).isFalse();
		assertThat(portable.has("temperature")).isFalse();
		assertThat(portable.has("imageConfig")).isFalse();
		JsonNode google = this.gemini.requests.get(1).body().get("generationConfig");
		assertThat(google.get("seed").asInt()).isEqualTo(42);
		assertThat(google.get("imageConfig").get("aspectRatio").asString()).isEqualTo("16:9");
	}

	/**
	 * The page gives {@code 1:1} as the default aspect ratio. Spring AI sends none unless
	 * one is set, and leaves the default to the model.
	 */
	@Test
	void noAspectRatioIsSentUnlessOneIsSet() {
		model(GoogleGenAiImageOptions.builder().build()).call(new ImagePrompt("A sunset"));

		assertThat(this.gemini.single().body().get("generationConfig").has("imageConfig")).isFalse();
	}

	@Test
	void theMediaOfAMessageIsSentToTheModel() {
		ImageMessage withPhoto = new ImageMessage("Put a hat on this cat", null,
				List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2, 3 }))), Map.of());

		model(GoogleGenAiImageOptions.builder().build())
			.call(new ImagePrompt(List.of(withPhoto), GoogleGenAiImageOptions.builder().build()));

		JsonNode parts = this.gemini.single().body().get("contents").get(0).get("parts");
		assertThat(parts.get(0).get("text").asString()).isEqualTo("Put a hat on this cat");
		assertThat(parts.get(1).get("inlineData").get("mimeType").asString()).isEqualTo("image/png");
		assertThat(parts.get(1).get("inlineData").get("data").asString()).isEqualTo("AQID");
	}

	@Test
	void thePropertiesConfigureTheOptionsAndTheConnectionIsSharedWithTheChat() {
		ApplicationContextRunner contexts = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiImageConnectionAutoConfiguration.class,
					GoogleGenAiImageAutoConfiguration.class));

		contexts.withPropertyValues("spring.ai.google.genai.api-key=chat-key")
			.run((context) -> assertThat(context.getBean(GoogleGenAiImageConnectionDetails.class).getApiKey())
				.isEqualTo("chat-key"));
		contexts.withBean(GoogleGenAiImageConnectionDetails.class, this::connection)
			.withPropertyValues("spring.ai.google.genai.image.model=gemini-3-pro-image",
					"spring.ai.google.genai.image.n=3", "spring.ai.google.genai.image.aspect-ratio=4:3",
					"spring.ai.google.genai.image.max-output-tokens=100")
			.run((context) -> {
				context.getBean(ImageModel.class)
					.call(new ImagePrompt("A sunset", GoogleGenAiImageOptions.builder().build()));

				StandIn.Request request = this.gemini.single();
				assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3-pro-image:generateContent");
				JsonNode config = request.body().get("generationConfig");
				assertThat(config.get("candidateCount").asInt()).isEqualTo(3);
				assertThat(config.get("maxOutputTokens").asInt()).isEqualTo(100);
				assertThat(config.get("imageConfig").get("aspectRatio").asString()).isEqualTo("4:3");
			});
	}

}
