package io.github.hjosugi.chatsample.repro;

import java.io.IOException;
import java.time.Duration;

import com.google.genai.Client;
import io.github.hjosugi.chatsample.guidelines.GuidelinesAutoConfiguration;
import com.google.genai.types.HttpOptions;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ObjectUtils;

/**
 * With a model named {@code fake}, or {@code EMBEDDING_PROVIDER=fake}, the application
 * talks to a {@link SlowModel} of its own and needs no key. It answers the questions that
 * {@link AgentScript} knows, with the coding tools and the guidelines, and a long
 * reply, 300 words, one every 100 ms, to a question that asks for one.
 *
 * @see FakeModelEnvironmentPostProcessor
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("fake-model.url")
class FakeModelConfiguration {

	/**
	 * The guidelines are embedded when their bean is made, which is before this
	 * configuration's own beans, so they wait for the stand-in.
	 */
	@Bean
	static BeanFactoryPostProcessor guidelinesWaitForTheStandIn() {
		return (beanFactory) -> {
			if (beanFactory.containsBeanDefinition(GuidelinesAutoConfiguration.BEAN_NAME)) {
				BeanDefinition pages = beanFactory.getBeanDefinition(GuidelinesAutoConfiguration.BEAN_NAME);
				pages.setDependsOn(ObjectUtils.addObjectToArray(pages.getDependsOn(), "slowModel"));
			}
		};
	}

	@Bean(destroyMethod = "close")
	SlowModel slowModel(Environment environment) throws IOException {
		return new SlowModel(environment.getRequiredProperty("fake-model.port", Integer.class), 300,
				Duration.ofMillis(100), true);
	}

	/**
	 * Spring AI has no property for the URL of Gemini, so the client is made here. It is
	 * made before {@code @Value} placeholders are resolved, hence the environment.
	 */
	@Bean
	@ConditionalOnBooleanProperty("fake-model.chat")
	@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
	Client fakeGeminiClient(Environment environment) {
		String url = environment.getRequiredProperty("fake-model.url");
		return Client.builder().apiKey("fake").httpOptions(HttpOptions.builder().baseUrl(url).build()).build();
	}

}
