package io.github.hjosugi.chatsample.repro;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Sends the chat to a stand-in when the model of the chosen provider is named
 * {@code fake}: {@code ANTHROPIC_MODEL=fake}, {@code OPENAI_MODEL=fake} or
 * {@code GEMINI_MODEL=fake}. A key that is {@code fake} does the same. Spring AI's own
 * client stays, and talks to the {@link SlowModel} of {@link FakeModelConfiguration}.
 * <p>
 * {@code EMBEDDING_PROVIDER=fake} sends the embeddings of the guidelines to it too, as
 * OpenAI's. Both use the settings of OpenAI, so a real OpenAI chat cannot go with it.
 * <p>
 * Also takes {@code gemini} as the provider, which Spring AI calls {@code google-genai}.
 */
class FakeModelEnvironmentPostProcessor implements EnvironmentPostProcessor {

	private static final String FAKE = "fake";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Map<String, Object> properties = new LinkedHashMap<>();
		String provider = environment.getProperty("spring.ai.model.chat", "anthropic");
		if (provider.equals("gemini")) {
			provider = "google-genai";
			properties.put("spring.ai.model.chat", provider);
		}
		String prefix = "spring.ai." + provider.replace('-', '.');
		boolean fakeChat = FAKE.equalsIgnoreCase(environment.getProperty(prefix + ".chat.model"))
				|| FAKE.equalsIgnoreCase(environment.getProperty(prefix + ".api-key"));
		boolean fakeEmbedding = FAKE.equalsIgnoreCase(environment.getProperty("spring.ai.model.embedding"));
		String url = "http://localhost:" + environment.getProperty("fake-model.port");
		if (fakeChat) {
			properties.put("fake-model.chat", true);
			properties.put("fake-model.url", url);
			// Gemini has no such property: FakeModelConfiguration makes its client.
			properties.put(prefix + ".base-url", url);
			properties.put(prefix + ".api-key", FAKE);
		}
		Map<String, Object> defaults = new LinkedHashMap<>();
		if (fakeEmbedding) {
			// Embedded as OpenAI's. A real OpenAI chat would be sent to the stand-in as well:
			// ChatConfiguration stops the application then, and says so.
			properties.put("fake-model.url", url);
			properties.put("fake-model.embedding", true);
			properties.put("spring.ai.model.embedding", "openai");
			if (fakeChat || !provider.equals("openai")) {
				properties.put("spring.ai.openai.base-url", url);
				properties.put("spring.ai.openai.api-key", FAKE);
			}
			// A bag of words is never as close to a question as a model's vectors are. Below
			// the environment, so that AGENT_GUIDELINES_SIMILARITY_THRESHOLD still says otherwise.
			defaults.put("agent.guidelines.similarity-threshold", "0.1");
		}
		if (!properties.isEmpty()) {
			environment.getPropertySources().addFirst(new MapPropertySource("fake-model", properties));
		}
		if (!defaults.isEmpty()) {
			environment.getPropertySources()
				.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new MapPropertySource("fake-model-defaults", defaults));
		}
	}

}
