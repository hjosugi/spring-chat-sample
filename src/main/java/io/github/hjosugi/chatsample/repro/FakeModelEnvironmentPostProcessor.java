package io.github.hjosugi.chatsample.repro;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Sends the chat to a stand-in when the model of the chosen provider is named
 * {@code fake}: {@code ANTHROPIC_MODEL=fake}, {@code OPENAI_MODEL=fake} or
 * {@code GEMINI_MODEL=fake}. A key that is {@code fake} does the same. Spring AI's own
 * client stays, and talks to the {@link SlowModel} of {@link FakeModelConfiguration}.
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
		if (FAKE.equalsIgnoreCase(environment.getProperty(prefix + ".chat.model"))
				|| FAKE.equalsIgnoreCase(environment.getProperty(prefix + ".api-key"))) {
			String url = "http://localhost:" + environment.getProperty("fake-model.port");
			properties.put("fake-model.url", url);
			// Gemini has no such property: FakeModelConfiguration makes its client.
			properties.put(prefix + ".base-url", url);
			properties.put(prefix + ".api-key", FAKE);
		}
		if (!properties.isEmpty()) {
			environment.getPropertySources().addFirst(new MapPropertySource("fake-model", properties));
		}
	}

}
