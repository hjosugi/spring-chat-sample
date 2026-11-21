package io.github.hjosugi.chatsample.models;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.google.genai.tts.GoogleGenAiAudioSpeechOptions;
import org.springframework.ai.google.genai.tts.GoogleGenAiAudioSpeechOptions.MultiSpeakerTurn;
import org.springframework.ai.google.genai.tts.GoogleGenAiAudioSpeechOptions.SpeakerVoiceConfig;
import org.springframework.ai.google.genai.tts.GoogleGenAiTextToSpeechConnectionDetails;
import org.springframework.ai.google.genai.tts.GoogleGenAiTextToSpeechModel;
import org.springframework.ai.model.google.genai.autoconfigure.tts.GoogleGenAiTextToSpeechAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.tts.GoogleGenAiTextToSpeechConnectionAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google GenAI Text to Speech page: Gemini-TTS answers {@code generateContent} with
 * PCM audio as inline data. The voices, the style prompt, several speakers, the encoding
 * and the properties.
 */
class GoogleGenAiSpeechTests {

	static final byte[] PCM = "16-bit samples".getBytes(StandardCharsets.UTF_8);

	private final StandIn gemini = new StandIn((request) -> Gemini.generated(
			"{\"inlineData\": {\"mimeType\": \"audio/L16;codec=pcm;rate=24000\", \"data\": \"%s\"}}"
				.formatted(Base64.getEncoder().encodeToString(PCM))));

	@AfterEach
	void stop() {
		this.gemini.close();
	}

	private GoogleGenAiTextToSpeechModel model(GoogleGenAiAudioSpeechOptions options) {
		return new GoogleGenAiTextToSpeechModel(
				GoogleGenAiTextToSpeechConnectionDetails.builder().genAiClient(Gemini.client(this.gemini)).build(),
				options);
	}

	@Test
	void theTextGoesToGenerateContentForAudioWithThePrebuiltVoice() {
		model(GoogleGenAiAudioSpeechOptions.builder().build()).call("Hello from Spring AI and Gemini text to speech.");

		StandIn.Request request = this.gemini.single();
		assertThat(request.path()).isEqualTo("/v1beta/models/gemini-2.5-flash-tts:generateContent");
		JsonNode body = request.body();
		assertThat(body.get("contents").get(0).get("parts").get(0).get("text").asString())
			.isEqualTo("Hello from Spring AI and Gemini text to speech.");
		JsonNode config = body.get("generationConfig");
		assertThat(config.get("responseModalities").toString()).isEqualTo("[\"AUDIO\"]");
		assertThat(config.get("speechConfig").get("voiceConfig").get("prebuiltVoiceConfig").get("voiceName").asString())
			.isEqualTo("Kore");
	}

	@Test
	void linear16WrapsThePcmInAWavHeaderAndPcmReturnsItAsItIs() {
		GoogleGenAiTextToSpeechModel model = model(GoogleGenAiAudioSpeechOptions.builder().build());

		byte[] wav = model.call("Hello");
		byte[] pcm = model
			.call(new TextToSpeechPrompt("Hello",
					GoogleGenAiAudioSpeechOptions.builder()
						.audioEncoding(GoogleGenAiAudioSpeechOptions.AudioEncoding.PCM)
						.build()))
			.getResult()
			.getOutput();

		assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
		assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
		assertThat(Arrays.copyOfRange(wav, wav.length - PCM.length, wav.length)).isEqualTo(PCM);
		assertThat(pcm).isEqualTo(PCM);
	}

	/**
	 * The page says that the {@code spring.ai.google.genai.tts} options are the defaults,
	 * overridden at runtime. {@code call(String)}, {@code stream(String)} and
	 * {@code new TextToSpeechPrompt(String)} carry portable options, and with options that
	 * are not {@code GoogleGenAiAudioSpeechOptions} the model keeps only the model and the
	 * voice of its defaults: the language, the style prompt, the speakers, the replicated
	 * voice and the encoding are dropped ({@code GoogleGenAiTextToSpeechModel
	 * .buildTextToSpeechPrompt}).
	 */
	@Test
	void aPromptWithoutGoogleOptionsKeepsOnlyTheModelAndTheVoiceOfTheDefaults() {
		GoogleGenAiTextToSpeechModel model = model(GoogleGenAiAudioSpeechOptions.builder()
			.voiceName("Leda")
			.languageCode("de")
			.stylePrompt("Langsam")
			.audioEncoding(GoogleGenAiAudioSpeechOptions.AudioEncoding.PCM)
			.build());

		byte[] portable = model.call("Hallo");
		byte[] google = model.call(new TextToSpeechPrompt("Hallo", GoogleGenAiAudioSpeechOptions.builder().build()))
			.getResult()
			.getOutput();

		JsonNode first = this.gemini.requests.get(0).body();
		assertThat(first.get("contents").get(0).get("parts").get(0).get("text").asString()).isEqualTo("Hallo");
		assertThat(first.get("generationConfig").get("speechConfig").has("languageCode")).isFalse();
		assertThat(first.get("generationConfig")
			.get("speechConfig")
			.get("voiceConfig")
			.get("prebuiltVoiceConfig")
			.get("voiceName")
			.asString()).isEqualTo("Leda");
		assertThat(new String(portable, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
		JsonNode second = this.gemini.requests.get(1).body();
		assertThat(second.get("contents").get(0).get("parts").get(0).get("text").asString())
			.isEqualTo("Langsam: Hallo");
		assertThat(second.get("generationConfig").get("speechConfig").get("languageCode").asString()).isEqualTo("de");
		assertThat(google).isEqualTo(PCM);
	}

	@Test
	void theStylePromptGoesBeforeTheTextAndTheLanguageIntoTheSpeechConfig() {
		model(GoogleGenAiAudioSpeechOptions.builder().build())
			.call(new TextToSpeechPrompt("Is it ready?", GoogleGenAiAudioSpeechOptions.builder()
				.voiceName("Leda")
				.languageCode("en")
				.stylePrompt("Say the following in a curious way")
				.build()));

		JsonNode body = this.gemini.single().body();
		assertThat(body.get("contents").get(0).get("parts").get(0).get("text").asString())
			.isEqualTo("Say the following in a curious way: Is it ready?");
		JsonNode speech = body.get("generationConfig").get("speechConfig");
		assertThat(speech.get("languageCode").asString()).isEqualTo("en");
		assertThat(speech.get("voiceConfig").get("prebuiltVoiceConfig").get("voiceName").asString()).isEqualTo("Leda");
	}

	@Test
	void twoSpeakersGetAVoiceEachAndTheTurnsReplaceTheText() {
		model(GoogleGenAiAudioSpeechOptions.builder().build())
			.call(new TextToSpeechPrompt("ignored, turns take precedence", GoogleGenAiAudioSpeechOptions.builder()
				.speakerVoiceConfigs(List.of(new SpeakerVoiceConfig("Sam", "Kore"), new SpeakerVoiceConfig("Bob", "Charon")))
				.multiSpeakerTurns(List.of(new MultiSpeakerTurn("Sam", "How's it going today?"),
						new MultiSpeakerTurn("Bob", "Not too bad, how about you?")))
				.build()));

		JsonNode body = this.gemini.single().body();
		assertThat(body.get("contents").get(0).get("parts").get(0).get("text").asString())
			.isEqualTo("Sam: How's it going today?\nBob: Not too bad, how about you?\n");
		JsonNode speakers = body.get("generationConfig")
			.get("speechConfig")
			.get("multiSpeakerVoiceConfig")
			.get("speakerVoiceConfigs");
		assertThat(speakers).hasSize(2);
		assertThat(speakers.get(1).get("speaker").asString()).isEqualTo("Bob");
		assertThat(speakers.get(1).get("voiceConfig").get("prebuiltVoiceConfig").get("voiceName").asString())
			.isEqualTo("Charon");
	}

	@Test
	void aReplicatedVoiceSendsTheSampleAndTheConsent() {
		model(GoogleGenAiAudioSpeechOptions.builder().build()).call(new TextToSpeechPrompt("Hello",
				GoogleGenAiAudioSpeechOptions.builder()
					.replicatedVoice(new GoogleGenAiAudioSpeechOptions.ReplicatedVoice(new byte[] { 1, 2, 3 },
							new byte[] { 4, 5, 6 }, "audio/wav", null))
					.build()));

		JsonNode replicated = this.gemini.single()
			.body()
			.get("generationConfig")
			.get("speechConfig")
			.get("voiceConfig")
			.get("replicatedVoiceConfig");
		assertThat(replicated.get("mimeType").asString()).isEqualTo("audio/wav");
		assertThat(replicated.get("voiceSampleAudio").asString()).isEqualTo("AQID");
		assertThat(replicated.get("consentAudio").asString()).isEqualTo("BAUG");
	}

	@Test
	void streamingGivesTheWholeAudioAsOneElementAndTheUsageComesBack() {
		GoogleGenAiTextToSpeechModel model = model(GoogleGenAiAudioSpeechOptions.builder().build());

		List<TextToSpeechResponse> responses = model
			.stream(new TextToSpeechPrompt("Hello",
					GoogleGenAiAudioSpeechOptions.builder()
						.audioEncoding(GoogleGenAiAudioSpeechOptions.AudioEncoding.PCM)
						.build()))
			.collectList()
			.block();

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).getResult().getOutput()).isEqualTo(PCM);
		assertThat(responses.get(0).getMetadata().getUsage().getPromptTokens()).isEqualTo(7);
	}

	@Test
	void thePropertiesConfigureTheVoiceTheSpeakersAndTheEncoding() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiTextToSpeechConnectionAutoConfiguration.class,
					GoogleGenAiTextToSpeechAutoConfiguration.class))
			.withBean(GoogleGenAiTextToSpeechConnectionDetails.class,
					() -> GoogleGenAiTextToSpeechConnectionDetails.builder()
						.genAiClient(Gemini.client(this.gemini))
						.build())
			.withPropertyValues("spring.ai.google.genai.tts.model=gemini-2.5-pro-tts",
					"spring.ai.google.genai.tts.language-code=de",
					"spring.ai.google.genai.tts.style-prompt=Langsam",
					"spring.ai.google.genai.tts.speaker-voice-configs[0].speaker=Sam",
					"spring.ai.google.genai.tts.speaker-voice-configs[0].voice-name=Kore",
					"spring.ai.google.genai.tts.speaker-voice-configs[1].speaker=Bob",
					"spring.ai.google.genai.tts.speaker-voice-configs[1].voice-name=Puck",
					"spring.ai.google.genai.tts.audio-encoding=PCM")
			.run((context) -> {
				byte[] audio = context.getBean(TextToSpeechModel.class)
					.call(new TextToSpeechPrompt("Sam: Hallo\nBob: Hallo", GoogleGenAiAudioSpeechOptions.builder().build()))
					.getResult()
					.getOutput();

				assertThat(audio).isEqualTo(PCM);
				StandIn.Request request = this.gemini.single();
				assertThat(request.path()).isEqualTo("/v1beta/models/gemini-2.5-pro-tts:generateContent");
				assertThat(request.body().get("contents").get(0).get("parts").get(0).get("text").asString())
					.isEqualTo("Langsam: Sam: Hallo\nBob: Hallo");
				JsonNode speech = request.body().get("generationConfig").get("speechConfig");
				assertThat(speech.get("languageCode").asString()).isEqualTo("de");
				assertThat(speech.get("multiSpeakerVoiceConfig").get("speakerVoiceConfigs")).hasSize(2);
			});
	}

}
