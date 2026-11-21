package io.github.hjosugi.chatsample.models;

import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionOptions;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.google.genai.transcription.GoogleGenAiAudioTranscriptionOptions;
import org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionConnectionDetails;
import org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The Transcription API page: one service written against {@code TranscriptionModel}
 * transcribes with OpenAI and with Google GenAI, with and without options, and streams
 * as each provider can.
 */
class TranscriptionModelTests {

	private final StandIn openAi = new StandIn((request) -> Multipart.of(request).parts.containsKey("stream")
			? Reply.events(List.of(StandIn.data("{\"type\":\"transcript.text.delta\",\"delta\":\"Hello\"}"),
					StandIn.data("{\"type\":\"transcript.text.delta\",\"delta\":\" world\"}"),
					StandIn.data("{\"type\":\"transcript.text.done\",\"text\":\"Hello world\"}")))
			: Reply.json("{\"text\": \"Hello world\"}"));

	private final StandIn gemini = new StandIn((request) -> Gemini.generated("{\"text\": \"Hello world\"}"));

	@AfterEach
	void stop() {
		this.openAi.close();
		this.gemini.close();
	}

	/**
	 * The service of the page.
	 */
	record TranscriptionService(TranscriptionModel transcriptionModel) {

		String transcribeAudio(Resource audioFile) {
			return this.transcriptionModel.transcribe(audioFile);
		}

		String transcribeWithOptions(Resource audioFile, AudioTranscriptionOptions options) {
			AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(audioFile, options);
			AudioTranscriptionResponse response = this.transcriptionModel.call(prompt);
			return response.getResult().getOutput();
		}

	}

	private TranscriptionModel openAi() {
		return OpenAiAudioTranscriptionModel.builder()
			.options(OpenAiAudioTranscriptionOptions.builder()
				.baseUrl(this.openAi.url() + "/v1")
				.apiKey("test-key")
				.model("gpt-4o-mini-transcribe")
				.responseFormat(com.openai.models.audio.AudioResponseFormat.JSON)
				.build())
			.build();
	}

	private TranscriptionModel google() {
		return new GoogleGenAiTranscriptionModel(
				GoogleGenAiTranscriptionConnectionDetails.builder().client(Gemini.client(this.gemini)).build(),
				GoogleGenAiAudioTranscriptionOptions.builder()
					.model(GoogleGenAiAudioTranscriptionOptions.DEFAULT_MODEL_NAME)
					.build());
	}

	@Test
	void theSameServiceTranscribesWithEitherProvider() {
		for (TranscriptionModel model : List.of(openAi(), google())) {
			TranscriptionService service = new TranscriptionService(model);

			assertThat(service.transcribeAudio(OpenAiTranscriptionTests.AUDIO)).isEqualTo("Hello world");
		}
		assertThat(this.openAi.requests).hasSize(1);
		assertThat(this.gemini.requests).hasSize(1);
	}

	@Test
	void eachProviderTakesItsOwnOptionsThroughTheSameService() {
		new TranscriptionService(openAi()).transcribeWithOptions(OpenAiTranscriptionTests.AUDIO,
				OpenAiAudioTranscriptionOptions.builder()
					.language("en")
					.responseFormat(com.openai.models.audio.AudioResponseFormat.JSON)
					.build());
		new TranscriptionService(google()).transcribeWithOptions(OpenAiTranscriptionTests.AUDIO,
				GoogleGenAiAudioTranscriptionOptions.builder().language("en-US").build());

		assertThat(Multipart.of(this.openAi.single()).get("language")).isEqualTo("en");
		assertThat(this.gemini.single().body().get("contents").get(0).get("parts").get(1).get("text").asString())
			.endsWith("The audio language is en-US.");
	}

	@Test
	void openAiStreamsTheTextWhileGoogleGivesItAsOneElement() {
		List<String> openAi = openAi().streamTranscribe(OpenAiTranscriptionTests.AUDIO).collectList().block();
		List<String> google = google().streamTranscribe(OpenAiTranscriptionTests.AUDIO).collectList().block();

		assertThat(openAi).contains("Hello", " world");
		assertThat(google).containsExactly("Hello world");
	}

	@Test
	void aModelThatDoesNotStreamFailsWithUnsupportedOperationException() {
		TranscriptionModel callOnly = (prompt) -> new AudioTranscriptionResponse(new AudioTranscription("Hello"));

		Flux<String> stream = callOnly.streamTranscribe(OpenAiTranscriptionTests.AUDIO);

		assertThat(callOnly.transcribe(OpenAiTranscriptionTests.AUDIO)).isEqualTo("Hello");
		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(stream::blockLast);
	}

}
