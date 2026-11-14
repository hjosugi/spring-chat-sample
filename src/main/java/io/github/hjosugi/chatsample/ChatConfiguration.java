package io.github.hjosugi.chatsample;

import java.util.Map;

import io.github.hjosugi.springchat.ChatPromptCustomizer;

import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

	private static final Map<String, String> KEYS = Map.of("anthropic", "ANTHROPIC_API_KEY", "openai",
			"OPENAI_API_KEY", "google-genai", "GEMINI_API_KEY");

	/**
	 * Spring AI starts without a key and fails at the first message; the sample stops at
	 * the start instead, and says what to set.
	 */
	ChatConfiguration(Environment environment) {
		String provider = environment.getProperty("spring.ai.model.chat");
		if (!KEYS.containsKey(provider)) {
			throw new InvalidConfigurationPropertyValueException("spring.ai.model.chat", provider,
					"Set the CHAT_PROVIDER environment variable to anthropic, openai or gemini.");
		}
		String property = "spring.ai." + provider.replace('-', '.') + ".api-key";
		if (!StringUtils.hasText(environment.getProperty(property))) {
			throw new InvalidConfigurationPropertyValueException(property, "",
					"The chat answers with %s: set the %s environment variable, or name its model \"fake\"."
						.formatted(provider, KEYS.get(provider)));
		}
	}

	/**
	 * Gives every chat prompt the order tools, for the user who asked. The system prompt
	 * and the conversation memory of spring-chat stay.
	 */
	@Bean
	ChatPromptCustomizer orderToolsForTheUser(OrderTools tools) {
		// SecurityConfiguration lets no one chat without signing in, so there is a principal.
		return (prompt, request) -> prompt.tools(tools)
			.toolContext(Map.of(OrderTools.USER, request.principal().getName()));
	}

}
