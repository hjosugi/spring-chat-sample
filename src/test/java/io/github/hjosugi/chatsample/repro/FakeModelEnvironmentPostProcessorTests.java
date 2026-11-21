package io.github.hjosugi.chatsample.repro;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which settings the stand-in changes, for which combination of a chat and an embedding
 * model: a real provider must keep its own.
 */
class FakeModelEnvironmentPostProcessorTests {

	@Test
	void aRealChatAndRealEmbeddingsAreLeftAlone() {
		ConfigurableEnvironment environment = environment("anthropic", "spring.ai.anthropic.api-key", "a-key",
				"spring.ai.model.embedding", "openai");

		process(environment);

		assertThat(environment.getProperty("fake-model.url")).isNull();
		assertThat(environment.getProperty("spring.ai.anthropic.base-url")).isNull();
		assertThat(environment.getProperty("spring.ai.openai.base-url")).isNull();
	}

	@Test
	void aModelNamedFakeSendsTheChatToTheStandIn() {
		ConfigurableEnvironment environment = environment("openai", "spring.ai.openai.chat.model", "fake");

		process(environment);

		assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("http://localhost:18089");
		assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("fake");
		assertThat(environment.getProperty("fake-model.chat", Boolean.class)).isTrue();
		assertThat(environment.getProperty("fake-model.embedding")).isNull();
	}

	@Test
	void fakeEmbeddingsGoToTheStandInAsOpenAisAndTheRealChatStaysAtItsProvider() {
		ConfigurableEnvironment environment = environment("anthropic", "spring.ai.anthropic.api-key", "a-key",
				"spring.ai.model.embedding", "fake");

		process(environment);

		assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
		assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("http://localhost:18089");
		assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("fake");
		assertThat(environment.getProperty("fake-model.embedding", Boolean.class)).isTrue();
		assertThat(environment.getProperty("fake-model.chat")).isNull();
		assertThat(environment.getProperty("spring.ai.anthropic.base-url")).isNull();
		assertThat(environment.getProperty("spring.ai.anthropic.api-key")).isEqualTo("a-key");
		assertThat(environment.getProperty("agent.guidelines.similarity-threshold")).isEqualTo("0.1");
	}

	@Test
	void fakeEmbeddingsLeaveTheKeyOfARealOpenAiChatSoThatTheApplicationCanStopAtTheStart() {
		ConfigurableEnvironment environment = environment("openai", "spring.ai.openai.api-key", "a-key",
				"spring.ai.model.embedding", "fake");

		process(environment);

		assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("a-key");
		assertThat(environment.getProperty("spring.ai.openai.base-url")).isNull();
		assertThat(environment.getProperty("fake-model.embedding", Boolean.class)).isTrue();
	}

	@Test
	void theThresholdOfTheEnvironmentWinsOverTheOneOfTheStandIn() {
		ConfigurableEnvironment environment = environment("anthropic", "spring.ai.model.embedding", "fake");
		// What AGENT_GUIDELINES_SIMILARITY_THRESHOLD is, in the environment of the process.
		environment.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new MapPropertySource(
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					Map.of("agent.guidelines.similarity-threshold", "0.4")));

		process(environment);

		assertThat(environment.getProperty("agent.guidelines.similarity-threshold")).isEqualTo("0.4");
	}

	private static ConfigurableEnvironment environment(String provider, String... properties) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("spring.ai.model.chat", provider);
		source.put("fake-model.port", "18089");
		for (int i = 0; i < properties.length; i += 2) {
			source.put(properties[i], properties[i + 1]);
		}
		// A StandardEnvironment, which has the sources that the stand-in's defaults go between.
		ConfigurableEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", source));
		return environment;
	}

	private static void process(ConfigurableEnvironment environment) {
		new FakeModelEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
	}

}
