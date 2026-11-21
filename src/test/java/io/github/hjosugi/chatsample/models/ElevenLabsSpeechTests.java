package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechModel;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions;
import org.springframework.ai.elevenlabs.api.ElevenLabsApi;
import org.springframework.ai.elevenlabs.api.ElevenLabsVoicesApi;
import org.springframework.ai.model.elevenlabs.autoconfigure.ElevenLabsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ElevenLabs Text-to-Speech page: the voice in the path, the format in the query, the
 * options in the body, streaming, the Voices API and the properties.
 */
class ElevenLabsSpeechTests {

	private final StandIn elevenLabs = new StandIn((request) -> {
		if (request.path().startsWith("/v1/voices")) {
			return Reply.json("""
					{"voices": [{"voice_id": "9BWtsMINqrJLrRacOk9x", "name": "Aria", "category": "premade"}]}""");
		}
		return Reply.bytes("audio/mpeg", OpenAiSpeechTests.AUDIO);
	});

	@AfterEach
	void stop() {
		this.elevenLabs.close();
	}

	private ElevenLabsTextToSpeechModel model(ElevenLabsTextToSpeechOptions options) {
		return ElevenLabsTextToSpeechModel.builder()
			.elevenLabsApi(ElevenLabsApi.builder().baseUrl(this.elevenLabs.url()).apiKey("test-key").build())
			.options(options)
			.build();
	}

	@Test
	void theVoiceGoesInThePathTheFormatInTheQueryAndTheKeyInItsHeader() {
		byte[] audio = model(ElevenLabsTextToSpeechOptions.builder()
			.model("eleven_turbo_v2_5")
			.voiceId("9BWtsMINqrJLrRacOk9x")
			.outputFormat("mp3_44100_128")
			.build()).call("Hello, this is a text-to-speech example.");

		assertThat(audio).isEqualTo(OpenAiSpeechTests.AUDIO);
		StandIn.Request request = this.elevenLabs.single();
		assertThat(request.path()).isEqualTo("/v1/text-to-speech/9BWtsMINqrJLrRacOk9x");
		assertThat(request.query()).isEqualTo("output_format=mp3_44100_128");
		assertThat(request.header("xi-api-key")).isEqualTo("test-key");
		assertThat(request.body().get("text").asString()).isEqualTo("Hello, this is a text-to-speech example.");
		assertThat(request.body().get("model_id").asString()).isEqualTo("eleven_turbo_v2_5");
	}

	@Test
	void theOptionsOfThePromptOverrideTheDefaultsAndTheVoiceSettingsGoInTheBody() {
		ElevenLabsTextToSpeechModel model = model(
				ElevenLabsTextToSpeechOptions.builder().model("eleven_turbo_v2_5").voiceId("default-voice").build());

		model.call(new TextToSpeechPrompt("This is a test with custom voice settings!",
				ElevenLabsTextToSpeechOptions.builder()
					.model("eleven_multilingual_v2")
					.voiceId("your_voice_id")
					.voiceSettings(new ElevenLabsApi.SpeechRequest.VoiceSettings(0.75, 0.75, 0.0, true, 1.0))
					.languageCode("en")
					.seed(42)
					.previousText("Before.")
					.build()));

		StandIn.Request request = this.elevenLabs.single();
		assertThat(request.path()).isEqualTo("/v1/text-to-speech/your_voice_id");
		JsonNode body = request.body();
		assertThat(body.get("model_id").asString()).isEqualTo("eleven_multilingual_v2");
		assertThat(body.get("voice_settings").get("stability").asDouble()).isEqualTo(0.75);
		assertThat(body.get("voice_settings").get("use_speaker_boost").asBoolean()).isTrue();
		assertThat(body.get("language_code").asString()).isEqualTo("en");
		assertThat(body.get("seed").asInt()).isEqualTo(42);
		assertThat(body.get("previous_text").asString()).isEqualTo("Before.");
	}

	/**
	 * The speech page shows portable {@code TextToSpeechOptions} for code that works with
	 * any provider. ElevenLabs takes only its own options from a prompt and ignores
	 * portable ones: the voice of the defaults is used.
	 */
	@Test
	void portableOptionsAreIgnored() {
		model(ElevenLabsTextToSpeechOptions.builder().voiceId("default-voice").build())
			.call(new TextToSpeechPrompt("Hello", TextToSpeechOptions.builder().voice("other-voice").build()));

		assertThat(this.elevenLabs.single().path()).isEqualTo("/v1/text-to-speech/default-voice");
	}

	@Test
	void streamingCallsTheStreamEndpoint() {
		List<TextToSpeechResponse> responses = model(
				ElevenLabsTextToSpeechOptions.builder().voiceId("9BWtsMINqrJLrRacOk9x").build())
			.stream(new TextToSpeechPrompt("Today is a wonderful day to build something people love!"))
			.collectList()
			.block();

		assertThat(responses).isNotEmpty();
		assertThat(responses.stream().mapToInt((response) -> response.getResult().getOutput().length).sum())
			.isEqualTo(OpenAiSpeechTests.AUDIO.length);
		assertThat(this.elevenLabs.single().path()).isEqualTo("/v1/text-to-speech/9BWtsMINqrJLrRacOk9x/stream");
	}

	@Test
	void theVoicesApiListsTheVoices() {
		ElevenLabsVoicesApi voices = ElevenLabsVoicesApi.builder()
			.baseUrl(this.elevenLabs.url())
			.apiKey("test-key")
			.build();

		List<ElevenLabsVoicesApi.Voice> list = voices.getVoices().getBody().voices();

		assertThat(list).extracting(ElevenLabsVoicesApi.Voice::voiceId).containsExactly("9BWtsMINqrJLrRacOk9x");
		assertThat(this.elevenLabs.single().path()).isEqualTo("/v1/voices");
		assertThat(this.elevenLabs.single().header("xi-api-key")).isEqualTo("test-key");
	}

	@Test
	void thePropertiesConfigureTheModelTheVoiceAndTheFormat() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ElevenLabsAutoConfiguration.class))
			.withPropertyValues("spring.ai.elevenlabs.api-key=test-key",
					"spring.ai.elevenlabs.base-url=" + this.elevenLabs.url(),
					"spring.ai.elevenlabs.tts.model-id=eleven_turbo_v2_5",
					"spring.ai.elevenlabs.tts.voice-id=9BWtsMINqrJLrRacOk9x",
					"spring.ai.elevenlabs.tts.output-format=pcm_16000")
			.run((context) -> {
				context.getBean(TextToSpeechModel.class).call("Hello");

				StandIn.Request request = this.elevenLabs.single();
				assertThat(request.path()).isEqualTo("/v1/text-to-speech/9BWtsMINqrJLrRacOk9x");
				assertThat(request.query()).isEqualTo("output_format=pcm_16000");
				assertThat(request.body().get("model_id").asString()).isEqualTo("eleven_turbo_v2_5");
			});
	}

}
