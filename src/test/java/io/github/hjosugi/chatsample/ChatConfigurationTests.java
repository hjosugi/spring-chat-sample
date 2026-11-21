package io.github.hjosugi.chatsample;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the application refuses to start with, and what it says then: Spring AI itself
 * would start, and fail at the first message.
 */
class ChatConfigurationTests {

	@Test
	void aProviderWithItsKeyStarts() {
		assertThatCode(() -> new ChatConfiguration(environment("anthropic", "spring.ai.anthropic.api-key", "a-key")))
			.doesNotThrowAnyException();
		assertThatCode(() -> new ChatConfiguration(environment("google-genai", "spring.ai.google.genai.api-key", "a-key")))
			.doesNotThrowAnyException();
	}

	@Test
	void aProviderThatTheSampleDoesNotKnowIsRefused() {
		assertThatThrownBy(() -> new ChatConfiguration(environment("mistral", "spring.ai.mistralai.api-key", "a-key")))
			.isInstanceOf(InvalidConfigurationPropertyValueException.class)
			.hasMessageContaining("CHAT_PROVIDER");
	}

	@Test
	void aProviderWithoutItsKeyIsRefusedAndToldWhichKeyToSet() {
		assertThatThrownBy(() -> new ChatConfiguration(environment("openai", "spring.ai.openai.api-key", "")))
			.isInstanceOf(InvalidConfigurationPropertyValueException.class)
			.hasMessageContaining("OPENAI_API_KEY");
	}

	@Test
	void theHelpPagesNeedAKeyOfOpenAiWhoeverAnswers() {
		MockEnvironment claudeAlone = environment("anthropic", "spring.ai.anthropic.api-key", "a-key")
			.withProperty("spring.ai.model.embedding", "openai");
		MockEnvironment claudeAndOpenAi = environment("anthropic", "spring.ai.anthropic.api-key", "a-key")
			.withProperty("spring.ai.model.embedding", "openai")
			.withProperty("spring.ai.openai.api-key", "another-key");

		assertThatThrownBy(() -> new ChatConfiguration(claudeAlone))
			.isInstanceOf(InvalidConfigurationPropertyValueException.class)
			.hasMessageContaining("OPENAI_API_KEY");
		assertThatCode(() -> new ChatConfiguration(claudeAndOpenAi)).doesNotThrowAnyException();
	}

	@Test
	void aModelNamedFakeEmbedsNoHelpPages() {
		// What FakeModelEnvironmentPostProcessor leaves for OPENAI_MODEL=fake.
		MockEnvironment fake = environment("openai", "spring.ai.openai.api-key", "fake")
			.withProperty("spring.ai.model.embedding", "openai");

		assertThatThrownBy(() -> new ChatConfiguration(fake))
			.isInstanceOf(InvalidConfigurationPropertyValueException.class)
			.hasMessageContaining("fake");
	}

	@Test
	void anEmbeddingProviderThatTheSampleDoesNotKnowIsRefused() {
		MockEnvironment environment = environment("anthropic", "spring.ai.anthropic.api-key", "a-key")
			.withProperty("spring.ai.model.embedding", "ollama");

		assertThatThrownBy(() -> new ChatConfiguration(environment))
			.isInstanceOf(InvalidConfigurationPropertyValueException.class)
			.hasMessageContaining("EMBEDDING_PROVIDER");
	}

	private static MockEnvironment environment(String provider, String keyProperty, String key) {
		return new MockEnvironment().withProperty("spring.ai.model.chat", provider).withProperty(keyProperty, key);
	}

}
