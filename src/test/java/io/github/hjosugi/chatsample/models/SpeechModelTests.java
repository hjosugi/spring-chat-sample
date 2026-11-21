package io.github.hjosugi.chatsample.models;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechModel;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions;
import org.springframework.ai.elevenlabs.api.ElevenLabsApi;
import org.springframework.ai.google.genai.tts.GoogleGenAiAudioSpeechOptions;
import org.springframework.ai.google.genai.tts.GoogleGenAiTextToSpeechConnectionDetails;
import org.springframework.ai.google.genai.tts.GoogleGenAiTextToSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Text-To-Speech API page: one service written against {@code TextToSpeechModel}
 * speaks with OpenAI, ElevenLabs and Google GenAI; streaming; several providers side by
 * side; and how far portable options really carry.
 */
class SpeechModelTests {

	private final StandIn openAi = new StandIn((request) -> Reply.bytes("audio/mpeg", OpenAiSpeechTests.AUDIO));

	private final StandIn elevenLabs = new StandIn((request) -> Reply.bytes("audio/mpeg", OpenAiSpeechTests.AUDIO));

	private final StandIn gemini = new StandIn((request) -> Gemini.generated(
			"{\"inlineData\": {\"mimeType\": \"audio/L16\", \"data\": \"%s\"}}"
				.formatted(Base64.getEncoder().encodeToString(GoogleGenAiSpeechTests.PCM))));

	@AfterEach
	void stop() {
		this.openAi.close();
		this.elevenLabs.close();
		this.gemini.close();
	}

	private OpenAiAudioSpeechModel openAi() {
		return OpenAiAudioSpeechModel.builder()
			.options(OpenAiAudioSpeechOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").build())
			.build();
	}

	private ElevenLabsTextToSpeechModel elevenLabs() {
		return ElevenLabsTextToSpeechModel.builder()
			.elevenLabsApi(ElevenLabsApi.builder().baseUrl(this.elevenLabs.url()).apiKey("test-key").build())
			.options(ElevenLabsTextToSpeechOptions.builder().voiceId("default-voice").build())
			.build();
	}

	private GoogleGenAiTextToSpeechModel google() {
		return new GoogleGenAiTextToSpeechModel(
				GoogleGenAiTextToSpeechConnectionDetails.builder().genAiClient(Gemini.client(this.gemini)).build(),
				GoogleGenAiAudioSpeechOptions.builder().build());
	}

	/**
	 * The narration service of the page.
	 */
	record NarrationService(TextToSpeechModel textToSpeechModel) {

		byte[] narrate(String text) {
			return this.textToSpeechModel.call(text);
		}

		byte[] createPortableNarration(String text) {
			TextToSpeechOptions options = this.textToSpeechModel.getOptions();
			TextToSpeechResponse response = this.textToSpeechModel.call(new TextToSpeechPrompt(text, options));
			return response.getResult().getOutput();
		}

	}

	@Test
	void theSameServiceSpeaksWithEveryProvider() {
		for (TextToSpeechModel model : List.<TextToSpeechModel>of(openAi(), elevenLabs(), google())) {
			NarrationService service = new NarrationService(model);

			assertThat(service.narrate("Hello")).isNotEmpty();
			assertThat(service.createPortableNarration("Hello")).isNotEmpty();
		}
		assertThat(this.openAi.requests).hasSize(2);
		assertThat(this.elevenLabs.requests).hasSize(2);
		assertThat(this.gemini.requests).hasSize(2);
	}

	/**
	 * Google GenAI applies its default voice, {@code Kore}, when it sends a request, so
	 * the options it returns have no voice unless one was configured.
	 */
	@Test
	void getOptionsReturnsTheOptionsTheModelWasBuiltWith() {
		assertThat(openAi().getOptions().getVoice()).isEqualTo("alloy");
		assertThat(elevenLabs().getOptions().getVoice()).isEqualTo("default-voice");
		assertThat(google().getOptions().getVoice()).isNull();
	}

	@Test
	void streamingWorksWithEveryProvider() {
		for (TextToSpeechModel model : List.<TextToSpeechModel>of(openAi(), elevenLabs(), google())) {
			List<byte[]> chunks = model.stream("Hello").collectList().block();

			assertThat(chunks).isNotEmpty();
		}
	}

	@Test
	void severalProvidersCanBeInjectedSideBySide() {
		List<TextToSpeechModel> models = List.of(openAi(), elevenLabs(), google());

		Map<String, TextToSpeechModel> providers = models.stream()
			.collect(Collectors.toMap((model) -> model.getClass().getSimpleName(), (model) -> model));

		assertThat(providers).containsOnlyKeys("OpenAiAudioSpeechModel", "ElevenLabsTextToSpeechModel",
				"GoogleGenAiTextToSpeechModel");
		assertThat(providers.get("ElevenLabsTextToSpeechModel").call("Hello")).isEqualTo(OpenAiSpeechTests.AUDIO);
	}

	/**
	 * The page recommends the portable {@code TextToSpeechOptions} for code that works
	 * with any provider. A portable voice reaches OpenAI and Google GenAI, but
	 * ElevenLabs ignores portable options and keeps its default voice.
	 */
	@Test
	void aPortableVoiceReachesOpenAiAndGoogleButNotElevenLabs() {
		TextToSpeechOptions portable = TextToSpeechOptions.builder().voice("nova").build();

		openAi().call(new TextToSpeechPrompt("Hello", portable));
		elevenLabs().call(new TextToSpeechPrompt("Hello", portable));
		google().call(new TextToSpeechPrompt("Hello", portable));

		assertThat(this.openAi.single().body().get("voice").asString()).isEqualTo("nova");
		assertThat(this.elevenLabs.single().path()).isEqualTo("/v1/text-to-speech/default-voice");
		assertThat(this.gemini.single()
			.body()
			.get("generationConfig")
			.get("speechConfig")
			.get("voiceConfig")
			.get("prebuiltVoiceConfig")
			.get("voiceName")
			.asString()).isEqualTo("nova");
	}

	@Test
	void openAiSpecificOptionsCanBeMergedOverPortableOnes() {
		TextToSpeechOptions base = TextToSpeechOptions.builder().voice("echo").speed(1.0).build();

		OpenAiAudioSpeechOptions options = OpenAiAudioSpeechOptions.builder()
			.merge(base)
			.model("gpt-4o-mini-tts")
			.speed(1.0)
			.build();
		openAi().call(new TextToSpeechPrompt("Hello", options));

		assertThat(this.openAi.single().body().get("voice").asString()).isEqualTo("echo");
		assertThat(this.openAi.single().body().get("model").asString()).isEqualTo("gpt-4o-mini-tts");
	}

}
