package io.github.hjosugi.chatsample.chatclient;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientBuilderConfigurer;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Several chat clients and chat models in one application, as the page "Working with
 * Multiple Chat Models" shows it: clients from the prototype builder, clients for two
 * providers that keep the customizers and the observations, and two OpenAI-compatible
 * endpoints.
 */
class MultipleChatModelsTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class));

	@Test
	void eachInjectionOfThePrototypeBuilderMakesItsOwnClient() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		this.contextRunner.withBean(ChatModel.class, () -> model)
			.withUserConfiguration(SingleModelConfig.class)
			.run((context) -> {
				context.getBean("defaultChatClient", ChatClient.class).prompt("Hi").call().content();
				context.getBean("customChatClient", ChatClient.class).prompt("Hi").call().content();

				assertThat(model.prompts.get(0).getSystemMessage().getText()).isEmpty();
				assertThat(model.prompts.get(1).getSystemMessage().getText()).isEqualTo("You are a helpful assistant.");
			});
	}

	@Test
	void withTwoChatModelsTheAutoConfiguredBuilderIsAmbiguous() {
		this.contextRunner.withBean("one", ChatModel.class, () -> ScriptedChatModel.answering("one"))
			.withBean("two", ChatModel.class, () -> ScriptedChatModel.answering("two"))
			.run((context) -> assertThatExceptionOfType(UnsatisfiedDependencyException.class)
				.isThrownBy(() -> context.getBean(ChatClient.Builder.class))
				.havingRootCause()
				.isInstanceOf(NoUniqueBeanDefinitionException.class));
	}

	@Test
	void aPrimaryChatModelOrABuilderOfYourOwnEndsTheAmbiguity() {
		ScriptedChatModel primary = ScriptedChatModel.answering("primary");
		ScriptedChatModel other = ScriptedChatModel.answering("other");
		this.contextRunner.withBean("one", ChatModel.class, () -> primary, (definition) -> definition.setPrimary(true))
			.withBean("two", ChatModel.class, () -> other)
			.run((context) -> assertThat(context.getBean(ChatClient.Builder.class).build().prompt("Hi").call().content())
				.isEqualTo("primary"));
		this.contextRunner.withBean("one", ChatModel.class, () -> primary)
			.withBean("two", ChatModel.class, () -> other)
			.withBean(ChatClient.Builder.class, () -> ChatClient.builder(other))
			.run((context) -> assertThat(context.getBean(ChatClient.Builder.class).build().prompt("Hi").call().content())
				.isEqualTo("other"));
	}

	@Test
	void clientsForTwoProvidersKeepTheCustomizersAndTheObservations() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("From OpenAI")));
				StandIn anthropic = new StandIn((request) -> Reply.json(Providers.anthropicText("From Anthropic")))) {
			TestObservationRegistry observations = TestObservationRegistry.create();
			this.contextRunner.withBean(ObservationRegistry.class, () -> observations)
				.withBean(OpenAiChatModel.class, () -> Providers.openAi(openAi))
				.withBean(AnthropicChatModel.class, () -> Providers.anthropic(anthropic))
				.withBean(ChatClientBuilderCustomizer.class,
						() -> (builder) -> builder.defaultSystem("You work for a small shop."))
				.withUserConfiguration(ChatClientConfig.class)
				.run((context) -> {
					ChatClient primary = context.getBean(ChatClient.class);
					ChatClient anthropicClient = context.getBean("anthropicChatClient", ChatClient.class);

					assertThat(primary.prompt("What is the capital of France?").call().content()).isEqualTo("From OpenAI");
					assertThat(anthropicClient.prompt("What is the capital of France?").call().content())
						.isEqualTo("From Anthropic");

					assertThat(openAi.single().body().get("messages").get(0).get("content").asString())
						.isEqualTo("You work for a small shop.");
					assertThat(anthropic.single().body().get("system").toString()).contains("You work for a small shop.");
					assertThat(observations).hasNumberOfObservationsWithNameEqualTo("spring.ai.chat.client", 2);
				});
		}
	}

	@Test
	void aClientCreatedFromTheModelBypassesTheCustomizers() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		this.contextRunner.withBean(ChatModel.class, () -> model)
			.withBean(ChatClientBuilderCustomizer.class,
					() -> (builder) -> builder.defaultSystem("You work for a small shop."))
			.run((context) -> {
				context.getBean(ChatClient.Builder.class).build().prompt("Hi").call().content();
				ChatClient.create(model).prompt("Hi").call().content();

				assertThat(model.prompts.get(0).getSystemMessage().getText()).isEqualTo("You work for a small shop.");
				assertThat(model.prompts.get(1).getSystemMessage().getText()).isEmpty();
			});
	}

	@Test
	void twoOpenAiCompatibleEndpointsEachGetTheirOwnRequests() {
		try (StandIn groq = new StandIn((request) -> Reply.json(Providers.openAiText("Paris, says Groq")));
				StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("Paris, says OpenAI")))) {
			OpenAiChatModel groqModel = OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder()
					.baseUrl(groq.url() + "/openai/v1")
					.apiKey("groq-key")
					.model("openai/gpt-oss-120b")
					.build())
				.build();
			OpenAiChatModel openAiModel = OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder()
					.baseUrl(openAi.url() + "/v1")
					.apiKey("openai-key")
					.model("gpt-5.4-mini")
					.build())
				.build();

			String prompt = "What is the capital of France?";
			String groqResponse = ChatClient.builder(groqModel).build().prompt(prompt).call().content();
			String openAiResponse = ChatClient.builder(openAiModel).build().prompt(prompt).call().content();

			assertThat(groqResponse).isEqualTo("Paris, says Groq");
			assertThat(openAiResponse).isEqualTo("Paris, says OpenAI");
			assertThat(groq.single().path()).isEqualTo("/openai/v1/chat/completions");
			assertThat(groq.single().apiKey()).isEqualTo("groq-key");
			assertThat(groq.single().body().get("model").asString()).isEqualTo("openai/gpt-oss-120b");
			assertThat(openAi.single().path()).isEqualTo("/v1/chat/completions");
			assertThat(openAi.single().apiKey()).isEqualTo("openai-key");
			assertThat(openAi.single().body().get("model").asString()).isEqualTo("gpt-5.4-mini");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class SingleModelConfig {

		@Bean
		ChatClient defaultChatClient(ChatClient.Builder builder) {
			return builder.build();
		}

		@Bean
		ChatClient customChatClient(ChatClient.Builder builder) {
			return builder.defaultSystem("You are a helpful assistant.").build();
		}

	}

	/**
	 * The configuration of the page, as it is there.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ChatClientConfig {

		@Bean
		@Primary
		ChatClient openAiChatClient(OpenAiChatModel chatModel, ChatClientBuilderConfigurer configurer,
				ObjectProvider<ObservationRegistry> observationRegistry,
				ObjectProvider<ChatClientObservationConvention> chatClientObservationConvention,
				ObjectProvider<AdvisorObservationConvention> advisorObservationConvention,
				ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisorBuilder) {
			return buildChatClient(chatModel, configurer, observationRegistry, chatClientObservationConvention,
					advisorObservationConvention, toolCallingAdvisorBuilder);
		}

		@Bean
		ChatClient anthropicChatClient(AnthropicChatModel chatModel, ChatClientBuilderConfigurer configurer,
				ObjectProvider<ObservationRegistry> observationRegistry,
				ObjectProvider<ChatClientObservationConvention> chatClientObservationConvention,
				ObjectProvider<AdvisorObservationConvention> advisorObservationConvention,
				ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisorBuilder) {
			return buildChatClient(chatModel, configurer, observationRegistry, chatClientObservationConvention,
					advisorObservationConvention, toolCallingAdvisorBuilder);
		}

		private ChatClient buildChatClient(ChatModel chatModel, ChatClientBuilderConfigurer configurer,
				ObjectProvider<ObservationRegistry> observationRegistry,
				ObjectProvider<ChatClientObservationConvention> chatClientObservationConvention,
				ObjectProvider<AdvisorObservationConvention> advisorObservationConvention,
				ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisorBuilder) {
			ChatClient.Builder builder = ChatClient.builder(chatModel,
					observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
					chatClientObservationConvention.getIfUnique(), advisorObservationConvention.getIfUnique(),
					toolCallingAdvisorBuilder.getIfAvailable());
			return configurer.configure(builder).build();
		}

	}

}
