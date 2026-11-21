package io.github.hjosugi.chatsample.models;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.metadata.OpenAiAudioSpeechResponseMetadata;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI Text to Speech page: what goes to {@code /audio/speech}, the options, the
 * rate limit metadata, streaming and the properties.
 */
class OpenAiSpeechTests {

	static final byte[] AUDIO = "ID3 mp3 audio".getBytes(StandardCharsets.UTF_8);

	private final StandIn openAi = new StandIn((request) -> Reply.bytes("audio/mpeg", AUDIO)
		.header("x-ratelimit-limit-requests", "500")
		.header("x-ratelimit-remaining-requests", "499")
		.header("x-ratelimit-reset-requests", "120ms"));

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private OpenAiAudioSpeechOptions.Builder connected() {
		return OpenAiAudioSpeechOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").maxRetries(0);
	}

	@Test
	void theTextGoesToTheSpeechEndpointWithTheDefaults() {
		OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder().options(connected().build()).build();

		byte[] audio = model.call("Hello, world!");

		assertThat(audio).isEqualTo(AUDIO);
		StandIn.Request request = this.openAi.single();
		assertThat(request.path()).isEqualTo("/v1/audio/speech");
		assertThat(request.apiKey()).isEqualTo("test-key");
		JsonNode body = request.body();
		assertThat(body.get("model").asString()).isEqualTo("gpt-4o-mini-tts");
		assertThat(body.get("input").asString()).isEqualTo("Hello, world!");
		assertThat(body.get("voice").asString()).isEqualTo("alloy");
		assertThat(body.get("response_format").asString()).isEqualTo("mp3");
		assertThat(body.get("speed").asDouble()).isEqualTo(1.0);
	}

	@Test
	void theOptionsOfThePromptOverrideTheDefaults() {
		OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder().options(connected().build()).build();

		TextToSpeechResponse response = model.call(new TextToSpeechPrompt("Hello, this is a text-to-speech example.",
				OpenAiAudioSpeechOptions.builder()
					.model("tts-1-hd")
					.voice(OpenAiAudioSpeechOptions.Voice.NOVA)
					.responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.OPUS)
					.speed(1.5)
					.instructions("Friendly; warm tone; natural pauses")
					.build()));

		assertThat(response.getResult().getOutput()).isEqualTo(AUDIO);
		JsonNode body = this.openAi.single().body();
		assertThat(body.get("model").asString()).isEqualTo("tts-1-hd");
		assertThat(body.get("voice").asString()).isEqualTo("nova");
		assertThat(body.get("response_format").asString()).isEqualTo("opus");
		assertThat(body.get("speed").asDouble()).isEqualTo(1.5);
		assertThat(body.get("instructions").asString()).isEqualTo("Friendly; warm tone; natural pauses");
	}

	/**
	 * The page says that the options of the prompt override those of the start. An
	 * {@code OpenAiAudioSpeechOptions} always has a model, a voice, a format and a speed,
	 * the defaults unless it says otherwise, so options that change only the speed also
	 * put the other three back to {@code gpt-4o-mini-tts}, {@code alloy} and {@code mp3}.
	 */
	@Test
	void runtimeOptionsPutWhatTheyDoNotSetBackToTheDefaultsOfTheClass() {
		OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder()
			.options(connected().model("tts-1")
				.voice(OpenAiAudioSpeechOptions.Voice.NOVA)
				.responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.WAV)
				.build())
			.build();

		model.call(new TextToSpeechPrompt("Hello", OpenAiAudioSpeechOptions.builder().speed(1.5).build()));

		JsonNode body = this.openAi.single().body();
		assertThat(body.get("speed").asDouble()).isEqualTo(1.5);
		assertThat(body.get("model").asString()).isEqualTo("gpt-4o-mini-tts");
		assertThat(body.get("voice").asString()).isEqualTo("alloy");
		assertThat(body.get("response_format").asString()).isEqualTo("mp3");
	}

	@Test
	void theMetadataHasTheRateLimitOfTheResponseHeaders() {
		OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder().options(connected().build()).build();

		TextToSpeechResponse response = model.call(new TextToSpeechPrompt("Hello"));

		OpenAiAudioSpeechResponseMetadata metadata = (OpenAiAudioSpeechResponseMetadata) response.getMetadata();
		assertThat(metadata.getRateLimit().getRequestsLimit()).isEqualTo(500L);
		assertThat(metadata.getRateLimit().getRequestsRemaining()).isEqualTo(499L);
	}

	@Test
	void streamingAsksForAnAudioStreamAndHandsOnTheBytesAsTheyCome() {
		byte[] large = new byte[20_000];
		java.util.Arrays.fill(large, (byte) 7);
		try (StandIn chunked = new StandIn((request) -> Reply.bytes("audio/mpeg", large))) {
			OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder()
				.options(OpenAiAudioSpeechOptions.builder().baseUrl(chunked.url() + "/v1").apiKey("test-key").build())
				.build();

			List<byte[]> chunks = model.stream("Today is a wonderful day to build something people love!")
				.collectList()
				.block();

			assertThat(chunks).isNotEmpty().allSatisfy((chunk) -> assertThat(chunk.length).isLessThanOrEqualTo(8192));
			assertThat(chunks.stream().mapToInt((chunk) -> chunk.length).sum()).isEqualTo(20_000);
			assertThat(chunked.single().body().get("stream_format").asString()).isEqualTo("audio");
		}
	}

	@Test
	void streamingWithAPromptGivesResponses() {
		OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder().options(connected().build()).build();

		List<TextToSpeechResponse> responses = model
			.stream(new TextToSpeechPrompt("Hello", OpenAiAudioSpeechOptions.builder().voice("echo").build()))
			.collectList()
			.block();

		assertThat(responses).isNotEmpty();
		assertThat(responses.get(0).getResult().getOutput()).isEqualTo(AUDIO);
		assertThat(this.openAi.single().body().get("voice").asString()).isEqualTo("echo");
	}

	@Test
	void thePropertiesConfigureTheDefaultOptions() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OpenAiAudioSpeechAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.api-key=common-key",
					"spring.ai.openai.audio.speech.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.audio.speech.api-key=speech-key",
					"spring.ai.openai.audio.speech.model=tts-1", "spring.ai.openai.audio.speech.voice=shimmer",
					"spring.ai.openai.audio.speech.response-format=wav", "spring.ai.openai.audio.speech.speed=0.5",
					"spring.ai.openai.audio.speech.instructions=Slowly", "spring.ai.openai.max-retries=0")
			.run((context) -> {
				context.getBean(TextToSpeechModel.class).call("Hello");

				StandIn.Request request = this.openAi.single();
				assertThat(request.apiKey()).isEqualTo("speech-key");
				assertThat(request.body().get("model").asString()).isEqualTo("tts-1");
				assertThat(request.body().get("voice").asString()).isEqualTo("shimmer");
				assertThat(request.body().get("response_format").asString()).isEqualTo("wav");
				assertThat(request.body().get("speed").asDouble()).isEqualTo(0.5);
				assertThat(request.body().get("instructions").asString()).isEqualTo("Slowly");
			});
	}

}
