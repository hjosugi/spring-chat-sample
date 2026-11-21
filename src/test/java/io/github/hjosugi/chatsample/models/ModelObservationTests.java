package io.github.hjosugi.chatsample.models;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.embedding.observation.EmbeddingModelMeterObservationHandler;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.observation.ImageModelPromptContentObservationHandler;
import org.springframework.ai.model.audio.observation.autoconfigure.AudioObservationAutoConfiguration;
import org.springframework.ai.model.decision.observation.autoconfigure.DecisionObservationAutoConfiguration;
import org.springframework.ai.model.embedding.observation.autoconfigure.EmbeddingObservationAutoConfiguration;
import org.springframework.ai.model.image.observation.autoconfigure.ImageObservationAutoConfiguration;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.OpenAiDecisionModel;
import org.springframework.ai.openai.OpenAiDecisionOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model sections of the Observability page and the Metrics Reference: each call of
 * an embedding, image, text-to-speech, transcription or decision model is a
 * {@code gen_ai.client.operation} observation with the documented keys, a timer and the
 * token usage counter, and the content properties log what the page says.
 */
@ExtendWith(OutputCaptureExtension.class)
class ModelObservationTests {

	private final TestObservationRegistry observations = TestObservationRegistry.create();

	private final StandIn openAi = new StandIn((request) -> switch (request.path()) {
		case "/v1/embeddings" -> EmbeddingModelTests.embeddings(request);
		case "/v1/images/generations" -> Reply.json("""
				{"created": 1, "data": [{"b64_json": "%s"}]}""".formatted(OpenAiImageTests.IMAGE));
		case "/v1/audio/speech" -> Reply.bytes("audio/mpeg", OpenAiSpeechTests.AUDIO);
		case "/v1/audio/transcriptions" -> (request.text().contains("name=\"stream\"")) ? Reply.events(
				List.of(StandIn.data("{\"type\":\"transcript.text.delta\",\"delta\":\"Hello\"}"),
						StandIn.data("{\"type\":\"transcript.text.done\",\"text\":\"Hello\"}")))
				: Reply.json("{\"text\": \"Hello\"}");
		case "/v1/decisions" -> DecisionModelTests.triage(request);
		default -> throw new IllegalArgumentException(request.path());
	});

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private String v1() {
		return this.openAi.url() + "/v1";
	}

	@Test
	void anEmbeddingCallIsAnEmbeddingOperationWithItsModelsAndUsage() {
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(OpenAiEmbeddingOptions.builder()
				.baseUrl(v1())
				.apiKey("test-key")
				.model("text-embedding-3-small")
				.dimensions(3)
				.build())
			.observationRegistry(this.observations)
			.build();

		model.embed("Hello");

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasContextualNameEqualTo("embedding text-embedding-3-small")
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "embedding")
			.hasLowCardinalityKeyValue("gen_ai.system", "openai")
			.hasLowCardinalityKeyValue("gen_ai.request.model", "text-embedding-3-small")
			.hasLowCardinalityKeyValue("gen_ai.response.model", "text-embedding-3-small")
			.hasHighCardinalityKeyValue("gen_ai.request.embedding.dimensions", "3")
			.hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "2")
			.hasHighCardinalityKeyValue("gen_ai.usage.total_tokens", "2")
			.hasBeenStarted()
			.hasBeenStopped();
	}

	/**
	 * The page lists the usage keys for image observations too. OpenAI's image response
	 * metadata keeps only the creation time, not the usage the API reports, so an OpenAI
	 * image observation has none. The size key is made from the width and the height
	 * only, so the {@code size} option of OpenAI, the one its page describes, leaves it
	 * out.
	 */
	@Test
	void anImageCallIsAnImageOperationWithTheSizeTheFormatAndTheStyleButNoUsageForOpenAi() {
		OpenAiImageModel model = OpenAiImageModel.builder()
			.options(OpenAiImageOptions.builder().baseUrl(v1()).apiKey("test-key").build())
			.observationRegistry(this.observations)
			.build();

		model.call(new ImagePrompt("A doodle",
				OpenAiImageOptions.builder().model("dall-e-3").width(1024).height(1792).responseFormat("b64_json")
					.style("vivid")
					.build()));

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "image")
			.hasLowCardinalityKeyValue("gen_ai.system", "openai")
			.hasLowCardinalityKeyValue("gen_ai.request.model", "dall-e-3")
			.hasHighCardinalityKeyValue("gen_ai.request.image.size", "1024x1792")
			.hasHighCardinalityKeyValue("gen_ai.request.image.response_format", "b64_json")
			.hasHighCardinalityKeyValue("gen_ai.request.image.style", "vivid")
			.doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.usage.input_tokens");

		this.observations.clear();
		model.call(new ImagePrompt("A doodle", OpenAiImageOptions.builder().size("1024x1024").build()));

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.request.image.size");
	}

	@Test
	void aSpeechCallIsATextToSpeechOperationAndAStreamSaysSo() {
		OpenAiAudioSpeechModel model = OpenAiAudioSpeechModel.builder()
			.options(OpenAiAudioSpeechOptions.builder().baseUrl(v1()).apiKey("test-key").build())
			.observationRegistry(this.observations)
			.build();

		model.call(new TextToSpeechPrompt("Hello"));

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "text_to_speech")
			.hasLowCardinalityKeyValue("gen_ai.request.model", "gpt-4o-mini-tts")
			.hasHighCardinalityKeyValue("gen_ai.request.audio.voice", "alloy")
			.hasHighCardinalityKeyValue("gen_ai.request.audio.response_format", "mp3")
			.hasHighCardinalityKeyValue("gen_ai.request.audio.speed", "1.0")
			.doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.request.stream");

		this.observations.clear();
		model.stream(new TextToSpeechPrompt("Hello")).blockLast();

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasHighCardinalityKeyValue("gen_ai.request.stream", "true")
			.hasBeenStopped();
	}

	@Test
	void aTranscriptionCallIsATranscriptionOperationAndAStreamSaysSo() {
		OpenAiAudioTranscriptionModel model = OpenAiAudioTranscriptionModel.builder()
			.options(OpenAiAudioTranscriptionOptions.builder()
				.baseUrl(v1())
				.apiKey("test-key")
				.responseFormat(com.openai.models.audio.AudioResponseFormat.JSON)
				.build())
			.observationRegistry(this.observations)
			.build();

		model.call(new AudioTranscriptionPrompt(OpenAiTranscriptionTests.AUDIO));

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "transcription")
			.hasLowCardinalityKeyValue("gen_ai.system", "openai")
			.hasLowCardinalityKeyValue("gen_ai.request.model", "whisper-1")
			.doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.request.stream");

		this.observations.clear();
		model.stream(new AudioTranscriptionPrompt(OpenAiTranscriptionTests.AUDIO)).blockLast();

		assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasHighCardinalityKeyValue("gen_ai.request.stream", "true")
			.hasBeenStopped();
	}

	@Test
	void aDecisionWithSeveralQuestionsIsOneDecisionOperationWithTheResponseId() {
		OpenAiDecisionModel model = OpenAiDecisionModel.builder()
			.options(OpenAiDecisionOptions.builder().baseUrl(v1()).apiKey("test-key").build())
			.observationRegistry(this.observations)
			.build();

		model.call(DecisionModelTests.triage().build());

		// The OpenAI client observes its HTTP requests too, as okhttp.requests.
		assertThat(this.observations).hasObservationWithNameEqualTo("okhttp.requests");
		assertThat(this.observations).hasNumberOfObservationsWithNameEqualTo("gen_ai.client.operation", 1)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "decision")
			.hasLowCardinalityKeyValue("gen_ai.system", "openai")
			.hasLowCardinalityKeyValue("gen_ai.request.model", "gpt-6-luna")
			.hasLowCardinalityKeyValue("gen_ai.response.model", "gpt-6-luna-2026-05")
			.hasHighCardinalityKeyValue("gen_ai.response.id", "req_123")
			.hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "120")
			.hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "9")
			.hasHighCardinalityKeyValue("gen_ai.usage.total_tokens", "129");
	}

	@Test
	void eachCallIsATimerAndItsTokensCountInTheUsageCounter() {
		MeterRegistry meters = new SimpleMeterRegistry();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig()
			.observationHandler(new DefaultMeterObservationHandler(meters))
			.observationHandler(new EmbeddingModelMeterObservationHandler(meters));
		OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
			.options(OpenAiEmbeddingOptions.builder().baseUrl(v1()).apiKey("test-key").build())
			.observationRegistry(registry)
			.build();

		model.embed(List.of("one", "two"));

		assertThat(meters.get("gen_ai.client.operation")
			.tag("gen_ai.operation.name", "embedding")
			.tag("gen_ai.system", "openai")
			.tag("gen_ai.request.model", "text-embedding-ada-002")
			.tag("gen_ai.response.model", "text-embedding-ada-002")
			.tag("error", "none")
			.timer()
			.count()).isEqualTo(1);
		assertThat(meters.get("gen_ai.client.token.usage").tag("gen_ai.token.type", "input").counter().count())
			.isEqualTo(4);
		assertThat(meters.get("gen_ai.client.token.usage").tag("gen_ai.token.type", "total").counter().count())
			.isEqualTo(4);
	}

	@Test
	void theObservationAutoConfigurationsRegisterTheMeterHandlersWhenThereIsAMeterRegistry() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(EmbeddingObservationAutoConfiguration.class,
					ImageObservationAutoConfiguration.class, AudioObservationAutoConfiguration.class,
					DecisionObservationAutoConfiguration.class));

		runner.run((context) -> assertThat(context).doesNotHaveBean(EmbeddingModelMeterObservationHandler.class));
		runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run((context) -> {
			assertThat(context).hasSingleBean(EmbeddingModelMeterObservationHandler.class);
			assertThat(context.getBeansOfType(io.micrometer.observation.ObservationHandler.class)).hasSize(5);
		});
	}

	@Test
	void imagePromptsAreLoggedOnlyWithLogPrompt(CapturedOutput output) {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ImageObservationAutoConfiguration.class));

		runner.run((context) -> assertThat(context).doesNotHaveBean(ImageModelPromptContentObservationHandler.class));
		runner.withPropertyValues("spring.ai.image.observations.log-prompt=true").run((context) -> {
			ObservationRegistry registry = ObservationRegistry.create();
			registry.observationConfig()
				.observationHandler(context.getBean(ImageModelPromptContentObservationHandler.class));
			OpenAiImageModel.builder()
				.options(OpenAiImageOptions.builder().baseUrl(v1()).apiKey("test-key").build())
				.observationRegistry(registry)
				.build()
				.call(new ImagePrompt("A secret doodle"));
		});

		assertThat(output).contains("A secret doodle")
			.contains("You have enabled logging out the image prompt content");
	}

	@Test
	void theSpeechTextAndTheTranscriptAreLoggedOnlyWithTheirProperties() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AudioObservationAutoConfiguration.class));

		runner.run((context) -> assertThat(context.getBeansOfType(io.micrometer.observation.ObservationHandler.class))
			.isEmpty());
		runner
			.withPropertyValues("spring.ai.audio.speech.observations.log-text=true",
					"spring.ai.audio.transcription.observations.log-transcript=true")
			.run((context) -> assertThat(context.getBeansOfType(io.micrometer.observation.ObservationHandler.class)
				.keySet()).containsExactlyInAnyOrder("textToSpeechModelTextContentObservationHandler",
						"transcriptionModelTranscriptObservationHandler"));
	}

	@Test
	void decisionCallsAreObservedForEveryProviderWithItsOwnSystem() {
		try (StandIn typeSafe = new StandIn(SystemOne::answersWithRequestId)) {
			var model = org.springframework.ai.systemone.SystemOneDecisionModel
				.builder(org.springframework.ai.systemone.api.SystemOneClient.builder()
					.baseUrl(typeSafe.url())
					.apiKey("test-key")
					.build())
				.provider("laya")
				.observationRegistry(this.observations)
				.build();

			model.call("Late?", Map.of("late", YesNoQuestion.of("Is it late?")));

			assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
				.that()
				.hasLowCardinalityKeyValue("gen_ai.operation.name", "decision")
				.hasLowCardinalityKeyValue("gen_ai.system", "laya")
				.hasHighCardinalityKeyValue("gen_ai.response.id", "ts-req-1");
		}
	}

}
