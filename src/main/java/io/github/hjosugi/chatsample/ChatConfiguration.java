package io.github.hjosugi.chatsample;

import java.util.Map;

import io.github.hjosugi.springchat.ChatPromptCustomizer;

import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

	/**
	 * Spring AI starts without a key and fails at the first message; the sample stops at
	 * the start instead, and says what to set.
	 */
	ChatConfiguration(@Value("${spring.ai.anthropic.api-key:}") String apiKey) {
		if (!StringUtils.hasText(apiKey)) {
			throw new InvalidConfigurationPropertyValueException("spring.ai.anthropic.api-key", apiKey,
					"The chat answers with Claude: set the ANTHROPIC_API_KEY environment variable.");
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

	/**
	 * Ends a stopped reply at Anthropic too, which Spring AI 2.0.2 does not (see
	 * {@link CancelOnCloseInterceptor}).
	 */
	@Bean
	AnthropicHttpClientBuilderCustomizer cancelStoppedReplies() {
		return (builder) -> builder.interceptor(new CancelOnCloseInterceptor());
	}

}
