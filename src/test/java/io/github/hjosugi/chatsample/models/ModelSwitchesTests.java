package io.github.hjosugi.chatsample.models;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.image.GoogleGenAiImageModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionModel;
import org.springframework.ai.google.genai.tts.GoogleGenAiTextToSpeechModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.mistralai.MistralAiEmbeddingModel;
import org.springframework.ai.mistralai.moderation.MistralAiModerationModel;
import org.springframework.ai.model.elevenlabs.autoconfigure.ElevenLabsAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.transcription.GoogleGenAiTranscriptionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.transcription.GoogleGenAiTranscriptionConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.tts.GoogleGenAiTextToSpeechAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.tts.GoogleGenAiTextToSpeechConnectionAutoConfiguration;
import org.springframework.ai.model.mistralai.autoconfigure.MistralAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.mistralai.autoconfigure.MistralAiModerationAutoConfiguration;
import org.springframework.ai.model.mistralai.autoconfigure.MistralAiOcrAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaDecisionAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiDecisionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.model.stabilityai.autoconfigure.StabilityAiImageAutoConfiguration;
import org.springframework.ai.model.systemone.autoconfigure.SystemOneDecisionAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiMultiModalEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.ollama.OllamaDecisionModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiDecisionModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.stabilityai.StabilityAiImageModel;
import org.springframework.ai.systemone.SystemOneDecisionModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each {@code spring.ai.model.*} switch does, provider by provider: a provider's
 * model is on unless the switch names another provider or {@code none}. The sample turns
 * off embedding, image, speech, transcription and moderation this way; this shows what
 * that covers, and what it leaves on.
 */
class ModelSwitchesTests {

	private final ApplicationContextRunner openAi = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class,
				OpenAiImageAutoConfiguration.class, OpenAiAudioSpeechAutoConfiguration.class,
				OpenAiAudioTranscriptionAutoConfiguration.class, OpenAiModerationAutoConfiguration.class,
				OpenAiDecisionAutoConfiguration.class))
		.withPropertyValues("spring.ai.openai.api-key=test-key");

	@Test
	void withoutSwitchesTheOpenAiStarterMakesEveryModelButChat() {
		this.openAi.run((context) -> assertThat(context).hasSingleBean(OpenAiEmbeddingModel.class)
			.hasSingleBean(OpenAiImageModel.class)
			.hasSingleBean(OpenAiAudioSpeechModel.class)
			.hasSingleBean(OpenAiAudioTranscriptionModel.class)
			.hasSingleBean(OpenAiModerationModel.class)
			.hasSingleBean(OpenAiDecisionModel.class));
	}

	@ParameterizedTest
	@CsvSource({ "embedding, org.springframework.ai.embedding.EmbeddingModel",
			"image, org.springframework.ai.image.ImageModel",
			"audio.speech, org.springframework.ai.audio.tts.TextToSpeechModel",
			"audio.transcription, org.springframework.ai.audio.transcription.TranscriptionModel",
			"moderation, org.springframework.ai.moderation.ModerationModel",
			"decision, org.springframework.ai.decision.DecisionModel" })
	void noneTurnsOneModelOffAndLeavesTheOthers(String model, Class<?> type) {
		this.openAi.withPropertyValues("spring.ai.model." + model + "=none").run((context) -> {
			assertThat(context).doesNotHaveBean(type);
			assertThat(context.getBeansOfType(org.springframework.ai.model.Model.class)).hasSize(5);
		});
	}

	@Test
	void theNameOfAnotherProviderTurnsOpenAiOffToo() {
		this.openAi.withPropertyValues("spring.ai.model.embedding=mistral", "spring.ai.model.image=stabilityai")
			.run((context) -> assertThat(context).doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(ImageModel.class)
				.hasSingleBean(TextToSpeechModel.class));
	}

	/**
	 * The sample turns off every model but the chat in {@code application.properties}.
	 * The decision model of Spring AI 2.1 needed a line of its own: with the five switches
	 * that the sample had for 2.0, the application had an {@code OpenAiDecisionModel} it
	 * did not use, with the OpenAI key of the chat, or an empty one.
	 */
	@Test
	void theSwitchesOfTheSampleLeaveNoModelOfOpenAiButTheChat() throws IOException {
		this.openAi.withPropertyValues(sampleSwitches()).run((context) -> {
			assertThat(context).doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(ImageModel.class)
				.doesNotHaveBean(TextToSpeechModel.class)
				.doesNotHaveBean(TranscriptionModel.class)
				.doesNotHaveBean(ModerationModel.class)
				.doesNotHaveBean(OpenAiDecisionModel.class);
		});
	}

	@Test
	void withoutTheSwitchOfTheDecisionModelTheApplicationHasOne() throws IOException {
		String[] switchesOf20 = Arrays.stream(sampleSwitches())
			.filter((line) -> !line.startsWith("spring.ai.model.decision"))
			.toArray(String[]::new);

		this.openAi.withPropertyValues(switchesOf20)
			.run((context) -> assertThat(context).hasSingleBean(OpenAiDecisionModel.class));
	}

	/**
	 * The {@code spring.ai.model.*} lines of the sample's {@code application.properties},
	 * but the chat.
	 */
	static String[] sampleSwitches() throws IOException {
		Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
		List<String> switches = new ArrayList<>();
		properties.stringPropertyNames()
			.stream()
			.filter((name) -> name.startsWith("spring.ai.model.") && !name.equals("spring.ai.model.chat"))
			.forEach((name) -> switches.add(name + "=" + properties.getProperty(name)));
		assertThat(switches).hasSize(6);
		return switches.toArray(String[]::new);
	}

	private final ApplicationContextRunner googleGenAi = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
				GoogleGenAiTextEmbeddingAutoConfiguration.class, GoogleGenAiImageConnectionAutoConfiguration.class,
				GoogleGenAiImageAutoConfiguration.class, GoogleGenAiTextToSpeechConnectionAutoConfiguration.class,
				GoogleGenAiTextToSpeechAutoConfiguration.class,
				GoogleGenAiTranscriptionConnectionAutoConfiguration.class,
				GoogleGenAiTranscriptionAutoConfiguration.class))
		.withPropertyValues("spring.ai.google.genai.api-key=test-key",
				"spring.ai.google.genai.embedding.api-key=test-key",
				"spring.ai.google.genai.transcription.api-key=test-key");

	@Test
	void withAKeyTheGoogleGenAiStartersMakeTheirModels() {
		this.googleGenAi.run((context) -> assertThat(context).hasSingleBean(GoogleGenAiTextEmbeddingModel.class)
			.hasSingleBean(GoogleGenAiImageModel.class)
			.hasSingleBean(GoogleGenAiTextToSpeechModel.class)
			.hasSingleBean(GoogleGenAiTranscriptionModel.class));
	}

	/**
	 * The text embedding models of Google GenAI and Vertex AI listen to
	 * {@code spring.ai.model.embedding.text}, not to {@code spring.ai.model.embedding},
	 * so the sample's {@code spring.ai.model.embedding=none} leaves the Google GenAI
	 * embedding model on. The other switches of the sample do cover Google GenAI.
	 */
	@Test
	void theSwitchesOfTheSampleLeaveTheGoogleGenAiEmbeddingModelOn() throws IOException {
		this.googleGenAi.withPropertyValues(sampleSwitches())
			.run((context) -> assertThat(context).hasSingleBean(GoogleGenAiTextEmbeddingModel.class)
				.doesNotHaveBean(GoogleGenAiImageModel.class)
				.doesNotHaveBean(GoogleGenAiTextToSpeechModel.class)
				.doesNotHaveBean(GoogleGenAiTranscriptionModel.class));
	}

	@Test
	void embeddingTextNoneTurnsTheGoogleGenAiEmbeddingModelOff() {
		this.googleGenAi.withPropertyValues("spring.ai.model.embedding.text=none")
			.run((context) -> assertThat(context).doesNotHaveBean(GoogleGenAiTextEmbeddingModel.class)
				.hasSingleBean(GoogleGenAiImageModel.class));
	}

	/**
	 * The connection of each Google GenAI model besides chat is made whatever the
	 * switches say, and it fails without a key or a project. An application that adds one
	 * of these starters does not start until it configures it, even with
	 * {@code spring.ai.model.*=none}: adding them to this sample stopped
	 * {@code SampleChatApplicationTests}, hence {@link TestOnlyStarters}.
	 */
	@Test
	void withoutAKeyTheGoogleGenAiStartersStopTheApplicationWhateverTheSwitchesSay() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
					GoogleGenAiTextEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.embedding=none", "spring.ai.model.embedding.text=none")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Google GenAI project-id must be set!"));
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiImageConnectionAutoConfiguration.class,
					GoogleGenAiImageAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.image=none")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageStartingWith("Incomplete Google GenAI configuration"));
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiTranscriptionConnectionAutoConfiguration.class,
					GoogleGenAiTranscriptionAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.audio.transcription=none")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageStartingWith("Incomplete Google GenAI configuration"));
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GoogleGenAiTextToSpeechConnectionAutoConfiguration.class,
					GoogleGenAiTextToSpeechAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.audio.speech=none")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Project ID must be provided for Vertex AI mode"));
	}

	/**
	 * The Vertex AI embedding models listen to {@code spring.ai.model.embedding.text} and
	 * {@code .multimodal}, but their connection is made whatever the switches say, and
	 * needs a project. With a project, they fail next to the Google GenAI chat starter:
	 * Maven takes {@code google-auth-library} 1.33.0 of {@code google-genai} 1.73.0, and
	 * {@code gax} 2.80.0 of {@code google-cloud-aiplatform} needs a class of 1.47.0.
	 */
	@Test
	void theVertexAiEmbeddingStarterNeedsAProjectWhateverTheSwitchesAndFailsNextToGoogleGenAi() {
		ApplicationContextRunner vertexAi = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(VertexAiEmbeddingConnectionAutoConfiguration.class,
					VertexAiTextEmbeddingAutoConfiguration.class, VertexAiMultiModalEmbeddingAutoConfiguration.class));

		vertexAi
			.withPropertyValues("spring.ai.model.embedding=none", "spring.ai.model.embedding.text=none",
					"spring.ai.model.embedding.multimodal=none")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Vertex AI project-id must be set!"));
		vertexAi
			.withPropertyValues("spring.ai.vertex.ai.embedding.project-id=p", "spring.ai.vertex.ai.embedding.location=l")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(ClassNotFoundException.class)
				.hasMessage("com.google.auth.mtls.CertificateSourceUnavailableException"));
	}

	private final ApplicationContextRunner mistral = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(MistralAiEmbeddingAutoConfiguration.class, MistralAiModerationAutoConfiguration.class,
					MistralAiOcrAutoConfiguration.class));

	@Test
	void theMistralModelsListenToTheEmbeddingAndModerationSwitchesAndOcrToItsOwn() {
		this.mistral.withPropertyValues("spring.ai.mistralai.api-key=test-key")
			.run((context) -> assertThat(context).hasSingleBean(MistralAiEmbeddingModel.class)
				.hasSingleBean(MistralAiModerationModel.class));
		this.mistral.withPropertyValues("spring.ai.mistralai.api-key=test-key", "spring.ai.model.embedding=none",
				"spring.ai.model.moderation=none")
			.run((context) -> assertThat(context).doesNotHaveBean(MistralAiEmbeddingModel.class)
				.doesNotHaveBean(MistralAiModerationModel.class));
	}

	/**
	 * The sample's switches turn off embedding and moderation of Mistral AI, but the OCR
	 * model, which no page of this area describes, listens to {@code spring.ai.model.ocr}
	 * and needs the key.
	 */
	@Test
	void withoutAKeyTheMistralStarterStopsTheApplicationEvenWithTheSwitchesOfTheSample() throws IOException {
		this.mistral.withPropertyValues(sampleSwitches())
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("API key must be set"));
		this.mistral.withPropertyValues(sampleSwitches())
			.withPropertyValues("spring.ai.model.ocr=none")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	private final ApplicationContextRunner ollamaAndOpenAi = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OllamaApiAutoConfiguration.class,
				OllamaEmbeddingAutoConfiguration.class, OllamaDecisionAutoConfiguration.class,
				OpenAiEmbeddingAutoConfiguration.class, OpenAiDecisionAutoConfiguration.class))
		.withPropertyValues("spring.ai.openai.api-key=test-key");

	@Test
	void ollamaAndOpenAiTogetherMakeTwoModelsOfEachUntilASwitchChoosesOne() {
		this.ollamaAndOpenAi.run((context) -> {
			assertThat(context.getBeansOfType(DecisionModel.class)).hasSize(2);
			assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(2);
		});
		this.ollamaAndOpenAi.withPropertyValues("spring.ai.model.decision=ollama", "spring.ai.model.embedding=openai")
			.run((context) -> {
				assertThat(context).getBean(DecisionModel.class).isInstanceOf(OllamaDecisionModel.class);
				assertThat(context).getBean(EmbeddingModel.class).isInstanceOf(OpenAiEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(OllamaEmbeddingModel.class);
			});
	}

	private final ApplicationContextRunner systemOne = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SystemOneDecisionAutoConfiguration.class));

	/**
	 * The page says that the auto-configured client is not created without the API key.
	 * The decision model is created anyway, and fails for want of the client, so the
	 * application does not start until it sets the key or turns the decision model off.
	 */
	@Test
	void withoutAKeyTheSystemOneStarterStopsTheApplicationUnlessTheDecisionSwitchIsOff() {
		this.systemOne.run((context) -> assertThat(context).hasFailed()
			.getFailure()
			.rootCause()
			.hasMessageContaining("No System One API key configured"));
		this.systemOne.withPropertyValues("spring.ai.model.decision=none")
			.run((context) -> assertThat(context).hasNotFailed().doesNotHaveBean(SystemOneDecisionModel.class));
		this.systemOne.withPropertyValues("spring.ai.systemone.api-key=test-key")
			.run((context) -> assertThat(context).hasSingleBean(SystemOneDecisionModel.class));
	}

	@Test
	void stabilityAiAndElevenLabsListenToTheImageAndSpeechSwitches() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(StabilityAiImageAutoConfiguration.class, ElevenLabsAutoConfiguration.class))
			.withPropertyValues("spring.ai.stabilityai.api-key=test-key", "spring.ai.elevenlabs.api-key=test-key");

		runner.run((context) -> assertThat(context).hasSingleBean(StabilityAiImageModel.class)
			.hasSingleBean(ElevenLabsTextToSpeechModel.class));
		runner.withPropertyValues("spring.ai.model.image=none", "spring.ai.model.audio.speech=openai")
			.run((context) -> assertThat(context).doesNotHaveBean(StabilityAiImageModel.class)
				.doesNotHaveBean(ElevenLabsTextToSpeechModel.class));
	}

}
