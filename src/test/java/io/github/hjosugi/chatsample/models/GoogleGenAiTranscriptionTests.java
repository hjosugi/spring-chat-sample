package io.github.hjosugi.chatsample.models;

import java.util.Base64;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.google.genai.transcription.GoogleGenAiAudioTranscriptionMetadata;
import org.springframework.ai.google.genai.transcription.GoogleGenAiAudioTranscriptionOptions;
import org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionConnectionDetails;
import org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionModel;
import org.springframework.ai.model.google.genai.autoconfigure.transcription.GoogleGenAiTranscriptionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.transcription.GoogleGenAiTranscriptionConnectionAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google GenAI Transcriptions page: the audio goes inline in a
 * {@code generateContent} request, with an instruction, and the text of the reply is the
 * transcription.
 */
class GoogleGenAiTranscriptionTests {

	private final StandIn gemini = new StandIn((request) -> Gemini.generated("{\"text\": \"Hello world\"}"));

	@AfterEach
	void stop() {
		this.gemini.close();
	}

	private GoogleGenAiTranscriptionConnectionDetails connection() {
		return GoogleGenAiTranscriptionConnectionDetails.builder().client(Gemini.client(this.gemini)).build();
	}

	@Test
	void theAudioGoesInlineWithTheDefaultInstruction() throws Exception {
		var model = new GoogleGenAiTranscriptionModel(connection(), GoogleGenAiAudioTranscriptionOptions.builder()
			.model(GoogleGenAiAudioTranscriptionOptions.DEFAULT_MODEL_NAME)
			.build());

		AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(OpenAiTranscriptionTests.AUDIO));

		StandIn.Request request = this.gemini.single();
		assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3.5-transcribe:generateContent");
		JsonNode parts = request.body().get("contents").get(0).get("parts");
		assertThat(parts.get(0).get("inlineData").get("mimeType").asString()).isEqualTo("audio/mp3");
		assertThat(parts.get(0).get("inlineData").get("data").asString())
			.isEqualTo(Base64.getEncoder().encodeToString(OpenAiTranscriptionTests.AUDIO.getContentAsByteArray()));
		assertThat(parts.get(1).get("text").asString()).isEqualTo("Transcribe the audio.");
		assertThat(response.getResult().getOutput()).isEqualTo("Hello world");
	}

	@Test
	void thePromptAndTheLanguageMakeTheInstructionAndTheRestGoesIntoTheConfig() {
		var model = new GoogleGenAiTranscriptionModel(connection(), GoogleGenAiAudioTranscriptionOptions.builder()
			.model(GoogleGenAiAudioTranscriptionOptions.DEFAULT_MODEL_NAME)
			.build());

		model.call(new AudioTranscriptionPrompt(OpenAiTranscriptionTests.AUDIO,
				GoogleGenAiAudioTranscriptionOptions.builder()
					.prompt("Transcribe this interview verbatim, including speaker labels.")
					.language("en-US")
					.temperature(0.1f)
					.maxOutputTokens(500)
					.build()));

		JsonNode body = this.gemini.single().body();
		assertThat(body.get("contents").get(0).get("parts").get(1).get("text").asString()).isEqualTo(
				"Transcribe this interview verbatim, including speaker labels. The audio language is en-US.");
		assertThat(body.get("generationConfig").get("temperature").asDouble()).isEqualTo(0.1,
				org.assertj.core.data.Offset.offset(0.001));
		assertThat(body.get("generationConfig").get("maxOutputTokens").asInt()).isEqualTo(500);
	}

	@Test
	void streamingGivesTheWholeTranscriptionAsOneElement() {
		var model = new GoogleGenAiTranscriptionModel(connection(), GoogleGenAiAudioTranscriptionOptions.builder()
			.model(GoogleGenAiAudioTranscriptionOptions.DEFAULT_MODEL_NAME)
			.build());

		List<String> text = model.streamTranscribe(OpenAiTranscriptionTests.AUDIO).collectList().block();

		assertThat(text).containsExactly("Hello world");
	}

	@Test
	void theUsageAndTheModelComeBackAsMetadata() {
		var model = new GoogleGenAiTranscriptionModel(connection(), GoogleGenAiAudioTranscriptionOptions.builder()
			.model(GoogleGenAiAudioTranscriptionOptions.DEFAULT_MODEL_NAME)
			.build());

		AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(OpenAiTranscriptionTests.AUDIO));

		assertThat(response.getMetadata()).isInstanceOfSatisfying(GoogleGenAiAudioTranscriptionMetadata.class,
				(metadata) -> assertThat(metadata.getModelVersion()).isEqualTo("gemini-test-001"));
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(27);
	}

	@Test
	void thePropertiesConfigureTheModelThePromptAndTheLanguage() {
		ApplicationContextRunner contexts = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiTranscriptionConnectionAutoConfiguration.class,
					GoogleGenAiTranscriptionAutoConfiguration.class));

		contexts.withPropertyValues("spring.ai.google.genai.transcription.api-key=transcription-key")
			.run((context) -> assertThat(context).hasSingleBean(TranscriptionModel.class));
		contexts.withBean(GoogleGenAiTranscriptionConnectionDetails.class, this::connection)
			.withPropertyValues("spring.ai.google.genai.transcription.model=gemini-3.5-transcribe-live",
					"spring.ai.google.genai.transcription.prompt=Write it down.",
					"spring.ai.google.genai.transcription.language=ja-JP",
					"spring.ai.google.genai.transcription.seed=7")
			.run((context) -> {
				String text = context.getBean(TranscriptionModel.class).transcribe(OpenAiTranscriptionTests.AUDIO);

				assertThat(text).isEqualTo("Hello world");
				StandIn.Request request = this.gemini.single();
				assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3.5-transcribe-live:generateContent");
				assertThat(request.body().get("contents").get(0).get("parts").get(1).get("text").asString())
					.isEqualTo("Write it down. The audio language is ja-JP.");
				assertThat(request.body().get("generationConfig").get("seed").asInt()).isEqualTo(7);
			});
	}

	/**
	 * Unlike image and speech, transcription has its own connection properties; the key
	 * of the chat is not enough.
	 */
	@Test
	void theKeyOfTheChatIsNotEnough() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiTranscriptionConnectionAutoConfiguration.class,
					GoogleGenAiTranscriptionAutoConfiguration.class))
			.withPropertyValues("spring.ai.google.genai.api-key=chat-key")
			.run((context) -> assertThat(context).hasFailed());
	}

}
