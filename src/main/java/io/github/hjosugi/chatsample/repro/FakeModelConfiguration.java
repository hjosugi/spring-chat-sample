package io.github.hjosugi.chatsample.repro;

import java.io.IOException;
import java.time.Duration;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * With a model named {@code fake}, the application talks to a {@link SlowModel} of its
 * own and needs no key: every reply is 300 words, one every 100 ms.
 *
 * @see FakeModelEnvironmentPostProcessor
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("fake-model.url")
class FakeModelConfiguration {

	@Bean(destroyMethod = "close")
	SlowModel slowModel(Environment environment) throws IOException {
		return new SlowModel(environment.getRequiredProperty("fake-model.port", Integer.class), 300,
				Duration.ofMillis(100));
	}

	/**
	 * Spring AI has no property for the URL of Gemini, so the client is made here. It is
	 * made before {@code @Value} placeholders are resolved, hence the environment.
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
	Client fakeGeminiClient(Environment environment) {
		String url = environment.getRequiredProperty("fake-model.url");
		return Client.builder().apiKey("fake").httpOptions(HttpOptions.builder().baseUrl(url).build()).build();
	}

}
