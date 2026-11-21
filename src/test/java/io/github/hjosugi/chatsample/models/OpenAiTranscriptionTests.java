package io.github.hjosugi.chatsample.models;

import java.util.List;

import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams.TimestampGranularity;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.metadata.OpenAiAudioTranscriptionResponseMetadata;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI Transcription page: the multipart request to
 * {@code /audio/transcriptions}, the response formats and their metadata, streaming, the
 * {@code diarized_json} workaround and the properties.
 */
class OpenAiTranscriptionTests {

	static final Resource AUDIO = new ClassPathResource("models/speech.mp3");

	static final String DIARIZED = """
			{"text":"And so, my fellow Americans, ask not.","segments":[\
			{"type":"transcript.text.segment","text":"And so, my fellow Americans,","speaker":"A","start":0.0,"end":2.1,"id":"seg_0"},\
			{"type":"transcript.text.segment","text":"ask not.","speaker":"B","start":2.8,"end":4.3,"id":"seg_1"}],\
			"usage":{"type":"tokens","total_tokens":432,"input_tokens":110,"output_tokens":322}}""";

	static final String VERBOSE = """
			{"task":"transcribe","language":"english","duration":4.5,"text":"Hello world",
			 "segments":[{"id":0,"seek":0,"start":0.0,"end":4.5,"text":"Hello world","tokens":[1,2],
			              "temperature":0.0,"avg_logprob":-0.2,"compression_ratio":1.0,"no_speech_prob":0.01}],
			 "words":[{"word":"Hello","start":0.0,"end":1.0},{"word":"world","start":1.2,"end":2.0}]}""";

	private final StandIn openAi = new StandIn((request) -> {
		String format = Multipart.of(request).get("response_format");
		if ("verbose_json".equals(format)) {
			return Reply.json(VERBOSE);
		}
		if ("diarized_json".equals(format)) {
			return Reply.json(DIARIZED);
		}
		if ("json".equals(format)) {
			return Reply.json("{\"text\":\"Hello world\"}");
		}
		return Reply.bytes("text/plain; charset=utf-8", "Hello world\n".getBytes());
	});

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private OpenAiAudioTranscriptionOptions.Builder connected() {
		return OpenAiAudioTranscriptionOptions.builder()
			.baseUrl(this.openAi.url() + "/v1")
			.apiKey("test-key")
			.maxRetries(0);
	}

	@Test
	void theAudioGoesAsAMultipartRequestWithTheDefaults() {
		OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
			.options(connected().build())
			.build();

		String text = model.transcribe(AUDIO);

		StandIn.Request request = this.openAi.single();
		assertThat(request.path()).isEqualTo("/v1/audio/transcriptions");
		assertThat(request.apiKey()).isEqualTo("test-key");
		Multipart parts = Multipart.of(request);
		assertThat(parts.get("file")).isEqualTo("speech.mp3");
		assertThat(parts.get("model")).isEqualTo("whisper-1");
		assertThat(parts.get("response_format")).isEqualTo("text");
		assertThat(text).startsWith("Hello world");
	}

	@Test
	void theOptionsOfThePromptOverrideTheDefaults() {
		OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
			.options(connected().build())
			.build();

		AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(AUDIO,
				OpenAiAudioTranscriptionOptions.builder()
					.model("gpt-4o-transcribe")
					.language("en")
					.prompt("Ask not this, but ask that")
					.temperature(0f)
					.responseFormat(AudioResponseFormat.JSON)
					.build()));

		Multipart parts = Multipart.of(this.openAi.single());
		assertThat(parts.get("model")).isEqualTo("gpt-4o-transcribe");
		assertThat(parts.get("language")).isEqualTo("en");
		assertThat(parts.get("prompt")).isEqualTo("Ask not this, but ask that");
		assertThat(parts.get("temperature")).isEqualTo("0.0");
		assertThat(parts.get("response_format")).isEqualTo("json");
		assertThat(response.getResult().getOutput()).isEqualTo("Hello world");
	}

	/**
	 * The page says that the options of the prompt override those of the start. The
	 * merge takes the model and the response format of the prompt's options even when
	 * they set none, so options that only set the language put the model back to
	 * {@code whisper-1} and the format back to {@code text}. Timestamp granularities are
	 * added to those of the start rather than replacing them.
	 */
	@Test
	void runtimeOptionsPutTheModelAndTheFormatBackToTheDefaults() {
		OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
			.options(connected().model("gpt-4o-transcribe")
				.responseFormat(AudioResponseFormat.VERBOSE_JSON)
				.timestampGranularities(List.of(TimestampGranularity.SEGMENT))
				.build())
			.build();

		model.call(new AudioTranscriptionPrompt(AUDIO, OpenAiAudioTranscriptionOptions.builder()
			.language("en")
			.timestampGranularities(List.of(TimestampGranularity.WORD))
			.build()));

		Multipart parts = Multipart.of(this.openAi.single());
		assertThat(parts.get("language")).isEqualTo("en");
		assertThat(parts.get("model")).isEqualTo("whisper-1");
		assertThat(parts.get("response_format")).isEqualTo("text");
		assertThat(parts.parts.get("timestamp_granularities[]")).containsExactly("segment", "word");
	}

	@Test
	void verboseJsonGivesTheDurationTheLanguageTheSegmentsAndTheWords() {
		OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
			.options(connected().responseFormat(AudioResponseFormat.VERBOSE_JSON)
				.timestampGranularities(List.of(TimestampGranularity.WORD, TimestampGranularity.SEGMENT))
				.build())
			.build();

		AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(AUDIO));

		assertThat(Multipart.of(this.openAi.single()).parts.get("timestamp_granularities[]"))
			.containsExactly("word", "segment");
		assertThat(response.getResult().getOutput()).isEqualTo("Hello world");
		assertThat(response.getMetadata()).isInstanceOfSatisfying(OpenAiAudioTranscriptionResponseMetadata.class,
				(metadata) -> {
					assertThat(metadata.getDuration()).isEqualTo(4.5);
					assertThat(metadata.getLanguage()).isEqualTo("english");
					assertThat(metadata.getSegments()).hasSize(1);
					assertThat(metadata.getWords()).hasSize(2);
				});
	}

	@Test
	void theDiarizedJsonWorkaroundGivesTheCleanTextAndTheSpeakerSegments() {
		OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
			.options(connected().model("gpt-4o-transcribe-diarize")
				.responseFormat(AudioResponseFormat.DIARIZED_JSON)
				.build())
			.build();

		AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(AUDIO));

		assertThat(response.getResult().getOutput()).isEqualTo("And so, my fellow Americans, ask not.");
		assertThat(response.getMetadata()).isInstanceOfSatisfying(OpenAiAudioTranscriptionResponseMetadata.class,
				(metadata) -> {
					assertThat(metadata.getSegments()).hasSize(2);
					assertThat(metadata.getDuration()).isNull();
				});
	}

	@Test
	void streamingGivesTheTextAsItIsTranscribed() {
		try (StandIn streaming = new StandIn((request) -> Reply.events(List.of(
				StandIn.data("{\"type\":\"transcript.text.delta\",\"delta\":\"Hello\"}"),
				StandIn.data("{\"type\":\"transcript.text.delta\",\"delta\":\" world\"}"),
				StandIn.data("{\"type\":\"transcript.text.done\",\"text\":\"Hello world\"}"))))) {
			OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
				.options(OpenAiAudioTranscriptionOptions.builder()
					.baseUrl(streaming.url() + "/v1")
					.apiKey("test-key")
					.model("gpt-4o-mini-transcribe")
					.responseFormat(AudioResponseFormat.JSON)
					.build())
				.build();

			List<String> text = model.streamTranscribe(AUDIO).collectList().block();

			assertThat(String.join("", text)).isEqualTo("Hello world");
			assertThat(text).contains("Hello", " world");
			assertThat(Multipart.of(streaming.single()).get("stream")).isEqualTo("true");
		}
	}

	@Test
	void thePropertiesConfigureTheDefaultOptions() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OpenAiAudioTranscriptionAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.api-key=common-key",
					"spring.ai.openai.audio.transcription.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.audio.transcription.api-key=transcription-key",
					"spring.ai.openai.audio.transcription.model=gpt-4o-mini-transcribe",
					"spring.ai.openai.audio.transcription.response-format=verbose_json",
					"spring.ai.openai.audio.transcription.language=de",
					"spring.ai.openai.audio.transcription.prompt=Ein Interview",
					"spring.ai.openai.audio.transcription.temperature=0.2",
					"spring.ai.openai.audio.transcription.timestamp-granularities=word",
					"spring.ai.openai.max-retries=0")
			.run((context) -> {
				String text = context.getBean(TranscriptionModel.class).transcribe(AUDIO);

				StandIn.Request request = this.openAi.single();
				assertThat(request.apiKey()).isEqualTo("transcription-key");
				Multipart parts = Multipart.of(request);
				assertThat(parts.get("model")).isEqualTo("gpt-4o-mini-transcribe");
				assertThat(parts.get("response_format")).isEqualTo("verbose_json");
				assertThat(parts.get("language")).isEqualTo("de");
				assertThat(parts.get("prompt")).isEqualTo("Ein Interview");
				assertThat(parts.get("temperature")).startsWith("0.2");
				assertThat(parts.parts.get("timestamp_granularities[]")).containsExactly("word");
				assertThat(text).isEqualTo("Hello world");
			});
	}

}
