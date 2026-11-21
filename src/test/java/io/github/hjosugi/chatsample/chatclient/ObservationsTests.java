package io.github.hjosugi.chatsample.chatclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.client.observation.ChatClientCompletionObservationHandler;
import org.springframework.ai.chat.client.observation.ChatClientPromptContentObservationHandler;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelCompletionObservationHandler;
import org.springframework.ai.chat.observation.ChatModelPromptContentObservationHandler;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationAutoConfiguration;
import org.springframework.ai.model.observation.ErrorLoggingObservationHandler;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.observation.ToolCallingContentObservationFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observations of the chat client, its advisors, the chat model and the tools, as
 * the page "Observability" lists them with their keys, how they nest into the trace of a
 * call, and the properties that log the content which the observations leave out. The
 * chat model is OpenAI's, talking to a stand-in, as the scripted model records nothing.
 */
class ObservationsTests {

	private static final String TOOL_CALL = Providers.openAiCompletion("{\"role\":\"assistant\",\"content\":null,"
			+ "\"refusal\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"weather\","
			+ "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}", "tool_calls",
			"{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}");

	private static final String ANSWER = Providers.openAiCompletion(
			"{\"role\":\"assistant\",\"content\":\"Sunny in Paris\",\"refusal\":null}", "stop",
			"{\"prompt_tokens\":30,\"completion_tokens\":4,\"total_tokens\":34,\"prompt_tokens_details\":{\"cached_tokens\":12}}");

	private final TestObservationRegistry observations = TestObservationRegistry.create();

	private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

	ObservationsTests() {
		this.observations.observationConfig().observationHandler(new ObservationHandler<>() {

			@Override
			public void onStop(Observation.Context context) {
				ObservationsTests.this.stopped.add(context);
			}

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

		});
	}

	@Test
	void aChatClientCallIsObservedWithItsKeys() {
		ChatClient client = ChatClient.create(ScriptedChatModel.answering("ok"), this.observations);

		client.prompt("Hi")
			.advisors(new SimpleLoggerAdvisor())
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "conversation-7"))
			.tools(new Weather())
			.call()
			.content();
		client.prompt("Hi").stream().content().blockLast();

		assertThat(this.observations).hasNumberOfObservationsWithNameEqualTo("spring.ai.chat.client", 2);
		assertThat(this.observations).hasObservationWithNameEqualTo("spring.ai.chat.client")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "framework")
			.hasLowCardinalityKeyValue("gen_ai.system", "spring_ai")
			.hasLowCardinalityKeyValue("spring.ai.kind", "chat_client")
			.hasLowCardinalityKeyValue("spring.ai.chat.client.stream", "false")
			.hasHighCardinalityKeyValue("spring.ai.chat.client.conversation.id", "conversation-7")
			.hasHighCardinalityKeyValueWithKey("spring.ai.chat.client.advisors")
			.hasHighCardinalityKeyValueWithKey("spring.ai.chat.client.tool.names");
		Observation.Context call = contexts("spring.ai.chat.client").get(0);
		assertThat(call.getHighCardinalityKeyValue("spring.ai.chat.client.advisors").getValue())
			.contains("SimpleLoggerAdvisor", "Tool Calling Advisor", "call");
		assertThat(call.getHighCardinalityKeyValue("spring.ai.chat.client.tool.names").getValue()).contains("weather");
		assertThat(contexts("spring.ai.chat.client").get(1).getLowCardinalityKeyValue("spring.ai.chat.client.stream")
			.getValue()).isEqualTo("true");
	}

	@Test
	void eachAdvisorIsObservedInsideTheOneBeforeIt() {
		ChatClient.create(ScriptedChatModel.answering("ok"), this.observations)
			.prompt("Hi")
			.advisors(new SimpleLoggerAdvisor())
			.call()
			.content();

		assertThat(this.observations).hasObservationWithNameEqualTo("spring.ai.advisor")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "framework")
			.hasLowCardinalityKeyValue("gen_ai.system", "spring_ai")
			.hasLowCardinalityKeyValue("spring.ai.kind", "advisor")
			.hasLowCardinalityKeyValueWithKey("spring.ai.advisor.name")
			.hasHighCardinalityKeyValueWithKey("spring.ai.advisor.order");
		// The advisors stop inside out: the outer ones last.
		List<Observation.Context> advisors = new ArrayList<>(contexts("spring.ai.advisor"));
		Collections.reverse(advisors);
		assertThat(advisors).extracting((context) -> context.getLowCardinalityKeyValue("spring.ai.advisor.name").getValue())
			.containsExactly("Tool Calling Advisor", "SimpleLoggerAdvisor", "call");
		assertThat(advisors.get(0).getParentObservation().getContextView().getName()).isEqualTo("spring.ai.chat.client");
		assertThat(name(advisors.get(1).getParentObservation())).isEqualTo("Tool Calling Advisor");
		assertThat(name(advisors.get(2).getParentObservation())).isEqualTo("SimpleLoggerAdvisor");
		assertThat(advisors.get(1).getHighCardinalityKeyValue("spring.ai.advisor.order").getValue()).isEqualTo("0");
	}

	@Test
	void theModelCallIsObservedInsideTheLastAdvisorWithItsKeys() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(ANSWER))) {
			OpenAiChatModel model = Providers.openAi(openAi, this.observations, (options) -> {
			});

			ChatClient.create(model, this.observations)
				.prompt("What is the weather in Paris?")
				.options(ChatOptions.builder().temperature(0.4).maxTokens(100).stopSequences(List.of("END")))
				.call()
				.content();

			assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
				.that()
				.hasLowCardinalityKeyValue("gen_ai.operation.name", "chat")
				.hasLowCardinalityKeyValue("gen_ai.system", "openai")
				.hasLowCardinalityKeyValue("gen_ai.request.model", Providers.OPENAI_MODEL)
				.hasLowCardinalityKeyValue("gen_ai.response.model", Providers.OPENAI_MODEL + "-2026-01-01")
				.hasHighCardinalityKeyValue("gen_ai.request.temperature", "0.4")
				.hasHighCardinalityKeyValue("gen_ai.request.max_tokens", "100")
				.hasHighCardinalityKeyValue("gen_ai.request.stop_sequences", "[\"END\"]")
				.hasHighCardinalityKeyValue("gen_ai.response.finish_reasons", "[\"STOP\"]")
				.hasHighCardinalityKeyValue("gen_ai.response.id", "chatcmpl-1")
				.hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "30")
				.hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "4")
				.hasHighCardinalityKeyValue("gen_ai.usage.total_tokens", "34")
				.hasHighCardinalityKeyValue("gen_ai.usage.cache_read.input_tokens", "12")
				.doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.request.stream");
			Observation.Context modelCall = contexts("gen_ai.client.operation").get(0);
			assertThat(name(modelCall.getParentObservation())).isEqualTo("call");
		}
	}

	@Test
	void aStreamedModelCallSaysSoAndNamesTheTools() {
		try (StandIn openAi = new StandIn((request) -> Reply.events(List.of(
				StandIn.data(ProviderFinishReasonTests.openAiChunk("{\"role\":\"assistant\",\"content\":\"Sunny\"}", null)),
				StandIn.data(ProviderFinishReasonTests.openAiChunk("{}", "stop")), StandIn.data("[DONE]"))))) {
			OpenAiChatModel model = Providers.openAi(openAi, this.observations, (options) -> {
			});

			ChatClient.create(model, this.observations)
				.prompt("What is the weather in Paris?")
				.tools(new Weather())
				.stream()
				.content()
				.blockLast();

			assertThat(this.observations).hasObservationWithNameEqualTo("gen_ai.client.operation")
				.that()
				.hasHighCardinalityKeyValue("gen_ai.request.stream", "true")
				.hasHighCardinalityKeyValue("spring.ai.model.request.tool.names", "[\"weather\"]");
		}
	}

	@Test
	void aToolCallIsObservedWithoutItsContentByDefault() {
		try (StandIn openAi = new StandIn(
				(request) -> request.text().contains("\"role\":\"tool\"") ? Reply.json(ANSWER) : Reply.json(TOOL_CALL))) {
			ChatClient.create(Providers.openAi(openAi, this.observations, (options) -> {
			}), this.observations).prompt("What is the weather in Paris?").tools(new Weather()).call().content();

			assertThat(this.observations).hasObservationWithNameEqualTo("spring.ai.tool")
				.that()
				.hasLowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
				.hasLowCardinalityKeyValue("gen_ai.system", "spring_ai")
				.hasLowCardinalityKeyValue("spring.ai.kind", "tool_call")
				.hasLowCardinalityKeyValue("spring.ai.tool.definition.name", "weather")
				.hasLowCardinalityKeyValue("spring.ai.tool.type", "function")
				.hasHighCardinalityKeyValue("spring.ai.tool.definition.description", "Tells the weather of a city")
				.hasHighCardinalityKeyValueWithKey("spring.ai.tool.definition.schema")
				.hasHighCardinalityKeyValue("spring.ai.tool.call.id", "call_1")
				.doesNotHaveHighCardinalityKeyValueWithKey("spring.ai.tool.call.arguments")
				.doesNotHaveHighCardinalityKeyValueWithKey("spring.ai.tool.call.result");
			// The advisors after the tool loop, and the model, run once for each round.
			assertThat(this.observations).hasNumberOfObservationsWithNameEqualTo("gen_ai.client.operation", 2);
		}
	}

	@Test
	void includeContentAddsTheArgumentsAndTheResultOfAToolCall() {
		this.contextRunner().withPropertyValues("spring.ai.tools.observations.include-content=true").run((context) -> {
			assertThat(context).hasSingleBean(ToolCallingContentObservationFilter.class);
			this.observations.observationConfig().observationFilter(context.getBean(ToolCallingContentObservationFilter.class));
			try (StandIn openAi = new StandIn(
					(request) -> request.text().contains("\"role\":\"tool\"") ? Reply.json(ANSWER) : Reply.json(TOOL_CALL))) {
				ChatClient.create(Providers.openAi(openAi, this.observations, (options) -> {
				}), this.observations).prompt("What is the weather in Paris?").tools(new Weather()).call().content();

				assertThat(this.observations).hasObservationWithNameEqualTo("spring.ai.tool")
					.that()
					.hasHighCardinalityKeyValue("spring.ai.tool.call.arguments", "{\"city\":\"Paris\"}")
					.hasHighCardinalityKeyValue("spring.ai.tool.call.result", "\"Sunny in Paris\"");
			}
		});
	}

	@Test
	void theObservationsCarryNoPromptOrCompletionByDefault() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(ANSWER))) {
			ChatClient.create(Providers.openAi(openAi, this.observations, (options) -> {
			}), this.observations).prompt("What is the weather in Paris?").call().content();

			assertThat(this.stopped).isNotEmpty().allSatisfy((context) -> {
				for (KeyValue keyValue : context.getAllKeyValues()) {
					assertThat(keyValue.getValue()).doesNotContain("What is the weather").doesNotContain("Sunny in Paris");
				}
			});
		}
	}

	@Test
	void thePropertiesLogThePromptAndTheCompletionOfTheChatClientAndTheModel() {
		this.contextRunner()
			.withPropertyValues("spring.ai.chat.client.observations.log-prompt=true",
					"spring.ai.chat.client.observations.log-completion=true", "spring.ai.chat.observations.log-prompt=true",
					"spring.ai.chat.observations.log-completion=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(ChatClientPromptContentObservationHandler.class)
					.hasSingleBean(ChatClientCompletionObservationHandler.class)
					.hasSingleBean(ChatModelPromptContentObservationHandler.class)
					.hasSingleBean(ChatModelCompletionObservationHandler.class);
				registerHandlers(context, this.observations);
				try (StandIn openAi = new StandIn((request) -> Reply.json(ANSWER));
						LogCapture log = new LogCapture("org.springframework.ai", Level.INFO)) {
					ChatClient.create(Providers.openAi(openAi, this.observations, (options) -> {
					}), this.observations).prompt("What is the weather in Paris?").call().content();

					assertThat(log.messages()).anySatisfy((message) -> assertThat(message)
						.startsWith("Chat Client Prompt Content:")
						.contains("What is the weather in Paris?"));
					assertThat(log.messages()).anySatisfy((message) -> assertThat(message)
						.startsWith("Chat Client Completion:")
						.contains("Sunny in Paris"));
					assertThat(log.messages()).anySatisfy((message) -> assertThat(message)
						.startsWith("Chat Model Prompt Content:")
						.contains("What is the weather in Paris?"));
					assertThat(log.messages()).anySatisfy((message) -> assertThat(message)
						.startsWith("Chat Model Completion:")
						.contains("Sunny in Paris"));
				}
			});
	}

	@Test
	void withoutThePropertiesNothingIsLogged() {
		this.contextRunner().run((context) -> {
			assertThat(context).doesNotHaveBean(ChatClientPromptContentObservationHandler.class)
				.doesNotHaveBean(ChatClientCompletionObservationHandler.class)
				.doesNotHaveBean(ChatModelPromptContentObservationHandler.class)
				.doesNotHaveBean(ChatModelCompletionObservationHandler.class)
				.doesNotHaveBean(ToolCallingContentObservationFilter.class);
		});
	}

	/**
	 * Not on the page: the logged prompt of the chat client keeps one message per role,
	 * the last one, so the earlier turns of a conversation are not in the log.
	 */
	@Test
	void theLoggedPromptOfTheChatClientKeepsOneMessagePerRole() {
		this.observations.observationConfig().observationHandler(new ChatClientPromptContentObservationHandler());
		try (LogCapture log = new LogCapture(ChatClientPromptContentObservationHandler.class, Level.INFO)) {
			ChatClient.create(ScriptedChatModel.answering("ok"), this.observations)
				.prompt()
				.messages(new UserMessage("First question"), new AssistantMessage("First answer"))
				.user("Second question")
				.call()
				.content();

			assertThat(log.messages()).singleElement()
				.asString()
				.contains("Second question", "First answer")
				.doesNotContain("First question");
		}
	}

	/**
	 * The page lists {@code include-error-logging} without a condition. Its handler is
	 * only made when a micrometer-tracing {@code Tracer} is there; without tracing the
	 * property does nothing.
	 */
	@Test
	void includeErrorLoggingNeedsTracing() {
		this.contextRunner()
			.withPropertyValues("spring.ai.chat.observations.include-error-logging=true")
			.run((context) -> assertThat(context).doesNotHaveBean(ErrorLoggingObservationHandler.class));
	}

	private ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class,
					ChatObservationAutoConfiguration.class))
			.withBean(ObservationRegistry.class, () -> this.observations)
			.withBean(ChatModel.class, () -> ScriptedChatModel.answering("ok"));
	}

	/**
	 * What Spring Boot does with the handlers of a context.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void registerHandlers(ApplicationContext context, ObservationRegistry registry) {
		Map<String, ObservationHandler> handlers = context.getBeansOfType(ObservationHandler.class);
		handlers.values().forEach((handler) -> registry.observationConfig().observationHandler(handler));
	}

	/**
	 * The observations of a name, in the order they stopped.
	 */
	private List<Observation.Context> contexts(String name) {
		return this.stopped.stream().filter((context) -> context.getName().equals(name)).toList();
	}

	private static String name(ObservationView parent) {
		return Optional.ofNullable(parent.getContextView().getLowCardinalityKeyValue("spring.ai.advisor.name"))
			.map(KeyValue::getValue)
			.orElse(parent.getContextView().getName());
	}

	static class Weather {

		@Tool(description = "Tells the weather of a city")
		String weather(String city) {
			return "Sunny in " + city;
		}

	}

}
