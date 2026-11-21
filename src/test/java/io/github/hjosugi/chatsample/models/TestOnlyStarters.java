package io.github.hjosugi.chatsample.models;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/**
 * Keeps the providers that only the tests of this package have out of every application
 * that Spring Boot starts with {@code @SpringBootApplication}, so that
 * {@code SampleChatApplicationTests} still starts the application as it ships.
 * <p>
 * The {@code pom.xml} adds the starters of Mistral AI, Ollama, Stability AI, ElevenLabs,
 * System One, Vertex AI and the four Google GenAI models besides chat in test scope. Some
 * of them stop an application that does not configure them, whatever
 * {@code spring.ai.model.*} says: see {@link ModelSwitchesTests}. The tests here build
 * their contexts with {@code ApplicationContextRunner} and name the auto-configurations
 * they want, which this filter does not see. A test of another package that wants one of
 * these auto-configurations through {@code @SpringBootApplication} has to drop it from
 * {@link #PACKAGES}.
 */
public class TestOnlyStarters implements AutoConfigurationImportFilter {

	static final List<String> PACKAGES = List.of("org.springframework.ai.model.mistralai.autoconfigure.",
			"org.springframework.ai.model.ollama.autoconfigure.",
			"org.springframework.ai.model.stabilityai.autoconfigure.",
			"org.springframework.ai.model.elevenlabs.autoconfigure.",
			"org.springframework.ai.model.systemone.autoconfigure.",
			"org.springframework.ai.model.vertexai.autoconfigure.",
			"org.springframework.ai.model.google.genai.autoconfigure.embedding.",
			"org.springframework.ai.model.google.genai.autoconfigure.image.",
			"org.springframework.ai.model.google.genai.autoconfigure.tts.",
			"org.springframework.ai.model.google.genai.autoconfigure.transcription.");

	@Override
	public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
		boolean[] matches = new boolean[autoConfigurationClasses.length];
		for (int i = 0; i < autoConfigurationClasses.length; i++) {
			String name = autoConfigurationClasses[i];
			// The array has holes where an earlier filter already dropped a class.
			matches[i] = name == null || PACKAGES.stream().noneMatch(name::startsWith);
		}
		return matches;
	}

}
