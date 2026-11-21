package io.github.hjosugi.chatsample.tools;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.hjosugi.chatsample.memory.Transcript;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.observation.ToolCallingContentObservationFilter;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The tool specification reference: definitions and their JSON schema, the tool context,
 * result conversion, exceptions, tool call limits, tool resolution, observations, and
 * the behavior the page lists to know.
 */
@ExtendWith(OutputCaptureExtension.class)
class ToolSpecificationTests {

	private static final JsonMapper json = JsonMapper.shared();

	private final ApplicationContextRunner boot = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class));

	@Test
	void aToolDefinitionIsANameADescriptionAndAnInputSchema() {
		ToolDefinition built = ToolDefinition.builder()
			.name("currentWeather")
			.description("Get the weather in location")
			.inputSchema("{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}}}")
			.build();
		Method method = ReflectionUtils.findMethod(Annotated.class, "weather", String.class);

		assertThat(built.name()).isEqualTo("currentWeather");
		ToolDefinition fromMethod = ToolDefinitions.from(method);
		assertThat(fromMethod.name()).isEqualTo("current_weather");
		assertThat(fromMethod.description()).isEqualTo("The weather of a city");
		assertThat(json.readTree(fromMethod.inputSchema()).at("/properties/city/type").asString()).isEqualTo("string");
	}

	@Test
	void parameterDescriptionsComeFromSpringAiJacksonOrSwaggerAndSpringAiWins() {
		JsonNode schema = schemaOf(new Described(), "describe");

		assertThat(schema.at("/properties/a/description").asString()).isEqualTo("Spring AI");
		assertThat(schema.at("/properties/b/description").asString()).isEqualTo("Jackson");
		assertThat(schema.at("/properties/c/description").asString()).isEqualTo("Swagger");
		assertThat(schema.at("/properties/d/description").asString()).isEqualTo("Spring AI wins");
	}

	@Test
	void aParameterIsRequiredUnlessOneOfFourAnnotationsSaysOtherwise() {
		JsonNode schema = schemaOf(new Optionals(), "optionals");

		assertThat(schema.get("required").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("plain",
				"swaggerAuto");
	}

	@Test
	void theToolContextReachesTheToolButNotTheSchemaAndRuntimeValuesWin() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("customer", "{\"id\":42}");
		ChatClient client = ChatClient.builder(model)
			.defaultToolContext(Map.of("tenantId", "default", "region", "eu"))
			.build();

		String answer = client.prompt("Tell me about customer 42")
			.tools(new CustomerTools())
			.toolContext(Map.of("tenantId", "acme"))
			.call()
			.content();

		assertThat(answer).isEqualTo("\"Customer 42 of acme in eu\"");
		assertThat(json.readTree(OfferedTools.definition(model.prompts.get(0), "customer").inputSchema())
			.get("properties")
			.propertyNames()).containsExactly("id");
	}

	/**
	 * Not on the page: a {@code String} result that Jackson can read as JSON is passed
	 * on as it is, and Jackson reads {@code 42 orders} as the number 42 and ignores the
	 * rest, so that result reaches the model unquoted while {@code Orders: 42} is quoted.
	 */
	@Test
	void resultsBecomeJsonExceptForVoidAndImagesOrWhatAConverterMakesOfThem() {
		Results results = new Results();
		assertThat(answerTo("startsWithANumber", results)).isEqualTo("42 orders");
		assertThat(answerTo("startsWithAWord", results)).isEqualTo("\"Orders: 42\"");

		assertThat(answerTo("nothing", results)).isEqualTo("\"Done\"");
		assertThat(answerTo("order", results)).isEqualTo("{\"id\":\"A-1\",\"total\":3}");
		assertThat(answerTo("converted", results)).isEqualTo("converted: A-1");
		JsonNode image = json.readTree(answerTo("image", results));
		assertThat(image.get("mimeType").asString()).isEqualTo("image/png");
		assertThat(image.get("data").asString()).isNotBlank();
		ToolCallback withConverter = FunctionToolCallback.builder("time", () -> "noon")
			.description("The time")
			.toolCallResultConverter((result, type) -> "It is " + result)
			.build();
		assertThat(answerTo("time", withConverter)).isEqualTo("It is noon");
	}

	@Test
	void aToolMethodThatReturnsAFunctionIsIgnoredWithAWarning(CapturedOutput output) {
		ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(new Functional()).build().getToolCallbacks();

		assertThat(callbacks).extracting((tool) -> tool.getToolDefinition().name()).containsExactly("plain");
		assertThat(output).contains("supplier");
	}

	@Test
	void aRuntimeExceptionGoesToTheModelAndACheckedOneToTheCaller() {
		Failing failing = new Failing();

		assertThat(answerTo("unchecked", failing)).isEqualTo("The order system is down");
		assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> answerTo("checked", failing));
	}

	@Test
	void withThrowExceptionOnErrorEveryToolErrorGoesToTheCaller() {
		this.boot.withPropertyValues("spring.ai.tools.throw-exception-on-error=true")
			.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("unchecked", "{}"))
			.run((context) -> assertThatExceptionOfType(ToolExecutionException.class)
				.isThrownBy(() -> context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("Go")
					.tools(new Failing())
					.call()
					.content()));
	}

	@Test
	void aToolCallbackOfYourOwnReportsFailureWithAToolExecutionException() {
		ToolCallback own = new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder().name("remote").description("A remote tool").inputSchema("{}").build();
			}

			@Override
			public String call(String toolInput) {
				throw new ToolExecutionException(getToolDefinition(), new IllegalStateException("Remote said no"));
			}

		};

		assertThat(answerTo("remote", own)).isEqualTo("Remote said no");
	}

	@Test
	void aBreachedLimitEndsTheLoopWithOneGenerationThatKeepsWhatRan() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> AssistantMessage.builder()
			.toolCalls(List.of(call("1", "search"), call("2", "search"), call("3", "search")))
			.build());
		Counter counter = new Counter();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder()
				.toolCallingManager(ToolCallingManager.builder().maxCallsPerTool("search", 2).build())
				.build())
			.build();

		ChatResponse response = client.prompt("Search three times").tools(counter).call().chatResponse();

		assertThat(response.getResults()).hasSize(1);
		assertThat(response.getResult().getMetadata().getFinishReason())
			.isEqualTo(ToolCallLimitExceededException.FINISH_REASON);
		assertThat((List<?>) response.getResult().getMetadata().get(ToolCallLimitExceededException.METADATA_PARTIAL_TOOL_RESPONSES))
			.hasSize(2);
		assertThat(counter.calls).isEqualTo(2);
	}

	@Test
	void withReturnErrorResponseTheModelLearnsOfTheLimitAndTheTurnGoesOn() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Stopped searching.")
				: AssistantMessage.builder().toolCalls(List.of(call("1", "search"), call("2", "search"))).build());
		Counter counter = new Counter();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder()
				.toolCallingManager(ToolCallingManager.builder()
					.maxCallsPerTool("search", 1)
					.onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
					.build())
				.build())
			.build();

		assertThat(client.prompt("Search twice").tools(counter).call().content()).isEqualTo("Stopped searching.");
		assertThat(counter.calls).isEqualTo(1);
		assertThat(Transcript.line(model.lastPrompt().getInstructions().get(2))).startsWith("results: \"found\", ");
	}

	@Test
	void excludedToolsAndTheTotalLimitAreCountedTooAndThePropertiesSetThem() {
		ChatResponse three = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.toolCalls(List.of(call("1", "search"), call("2", "search"), call("3", "search")))
			.build())));
		Prompt prompt = new Prompt(List.of(),
				ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(new Counter())).build());

		assertThat(ToolCallingManager.builder().maxCallsPerTool(1).excludeToolFromLimit("search").build()
			.executeToolCalls(prompt, three)
			.conversationHistory()).hasSize(2);
		assertThatExceptionOfType(ToolCallLimitExceededException.class)
			.isThrownBy(() -> ToolCallingManager.builder().excludeToolFromLimit("search").maxTotalToolCalls(2).build()
				.executeToolCalls(prompt, three))
			.satisfies((ex) -> assertThat(ex.getToolName()).isNull());
		this.boot.withPropertyValues("spring.ai.tools.limits.max-calls-per-tool.search=2")
			.run((context) -> assertThatExceptionOfType(ToolCallLimitExceededException.class)
				.isThrownBy(() -> context.getBean(ToolCallingManager.class).executeToolCalls(prompt, three))
				.satisfies((ex) -> assertThat(ex.getLimit()).isEqualTo(2)));
		this.boot.withPropertyValues("spring.ai.tools.limits.max-total-tool-calls=-1",
				"spring.ai.tools.limits.max-calls-per-tool-default=-1")
			.run((context) -> assertThat(
					context.getBean(ToolCallingManager.class).executeToolCalls(prompt, three).conversationHistory())
				.hasSize(2));
	}

	@Test
	@SuppressWarnings("removal")
	void theCountsStartAtTheLastUserMessageSoReplayedHistoryDoesNotCount() {
		ChatMemory memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("search", "{}");
		Counter counter = new Counter();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).order(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER + 100).build(),
					org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder()
						.toolCallingManager(ToolCallingManager.builder().maxCallsPerTool("search", 1).build())
						.disableInternalConversationHistory()
						.build())
			.build();

		client.prompt("Search").tools(counter).advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c")).call().content();
		String second = client.prompt("Search again")
			.tools(counter)
			.advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c"))
			.call()
			.content();

		assertThat(second).isEqualTo("\"found\"");
		assertThat(counter.calls).isEqualTo(2);
	}

	@Test
	void aToolThatIsNotAttachedRunsOnlyWithTheResolutionFallback() {
		ToolCallback bean = FunctionToolCallback.builder("currentWeather", () -> "Sunny").description("The weather").build();
		this.boot.withBean("currentWeather", ToolCallback.class, () -> bean)
			.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("currentWeather", "{}"))
			.run((context) -> {
				assertThat(context.getBean(ToolCallbackResolver.class).resolve("currentWeather")).isSameAs(bean);
				assertThatIllegalStateException()
					.isThrownBy(() -> context.getBean(ChatClient.Builder.class).build().prompt("Weather?").call().content())
					.withMessage("No ToolCallback found for tool name: currentWeather");
			});
		this.boot.withBean("currentWeather", ToolCallback.class, () -> bean)
			.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("currentWeather", "{}"))
			.withPropertyValues("spring.ai.tools.resolution.fallback.enabled=true")
			.run((context) -> assertThat(
					context.getBean(ChatClient.Builder.class).build().prompt("Weather?").call().content())
				.isEqualTo("\"Sunny\""));
	}

	@Test
	void everyToolCallIsObservedAsSpringAiTool() {
		List<String> observed = new CopyOnWriteArrayList<>();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<>() {

			@Override
			public void onStop(Observation.Context context) {
				observed.add(context.getName() + " " + context.getContextualName());
			}

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

		});
		ToolCallingManager manager = ToolCallingManager.builder().observationRegistry(registry).build();

		manager.executeToolCalls(
				new Prompt(List.of(), ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(new Counter())).build()),
				new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call("1", "search"))).build()))));

		assertThat(observed).singleElement().asString().startsWith("spring.ai.tool ").contains("search");
		this.boot.run((context) -> assertThat(context).doesNotHaveBean(ToolCallingContentObservationFilter.class));
		this.boot.withPropertyValues("spring.ai.tools.observations.include-content=true")
			.run((context) -> assertThat(context).hasSingleBean(ToolCallingContentObservationFilter.class));
	}

	@Test
	void aMissingToolContextFailsAToolThatTakesOne() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("customer", "{\"id\":42}");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChatClient.create(model).prompt("Customer 42?").tools(new CustomerTools()).call().content())
			.withMessage("ToolContext is required by the method as an argument");
	}

	@Test
	void twoToolsOfTheSameNameFailInAChatClientAndTheManagerTakesTheFirstOtherwise() {
		ToolCallback first = FunctionToolCallback.builder("time", () -> "first").description("The time").build();
		ToolCallback second = FunctionToolCallback.builder("time", () -> "second").description("The time").build();

		assertThatIllegalStateException().isThrownBy(
				() -> ChatClient.create(ScriptedChatModel.answering("ok")).prompt("hi").tools(first, second).call().content());
		ToolCallingManager manager = ToolCallingManager.builder().build();
		String result = manager
			.executeToolCalls(new Prompt(List.of(), ToolCallingChatOptions.builder().toolCallbacks(first, second).build()),
					new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call("1", "time"))).build()))))
			.conversationHistory()
			.stream()
			.map(Transcript::line)
			.reduce((a, b) -> b)
			.orElseThrow();
		assertThat(result).isEqualTo("results: \"first\"");
	}

	@Test
	void theToolsOfAChatClientReplaceTheToolCallbacksOfItsOptions() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ToolCallback fromOptions = FunctionToolCallback.builder("fromOptions", () -> "x").description("x").build();

		ChatClient.create(model)
			.prompt("hi")
			.options(ToolCallingChatOptions.builder().toolCallbacks(fromOptions))
			.tools(new Counter())
			.call()
			.content();

		assertThat(OfferedTools.names(model.lastPrompt())).containsExactly("search");
	}

	private static String answerTo(String tool, Object... tools) {
		return ChatClient.create(DefiningToolsTests.callingOnce(tool, "{}")).prompt("Go").tools(tools).call().content();
	}

	private static JsonNode schemaOf(Object tools, String name) {
		return json.readTree(List.of(ToolCallbacks.from(tools))
			.stream()
			.filter((tool) -> tool.getToolDefinition().name().equals(name))
			.findFirst()
			.orElseThrow()
			.getToolDefinition()
			.inputSchema());
	}

	private static AssistantMessage.ToolCall call(String id, String name) {
		return new AssistantMessage.ToolCall(id, "function", name, "{}");
	}

	static class Annotated {

		@Tool(name = "current_weather", description = "The weather of a city")
		String weather(String city) {
			return "Sunny";
		}

	}

	static class Described {

		@Tool(description = "Describes")
		String describe(@ToolParam(description = "Spring AI") String a, @JsonPropertyDescription("Jackson") String b,
				@Schema(description = "Swagger") String c,
				@ToolParam(description = "Spring AI wins") @JsonPropertyDescription("Jackson loses") String d) {
			return "ok";
		}

	}

	static class Optionals {

		@Tool(description = "Optionals")
		String optionals(String plain, @ToolParam(required = false) String toolParam,
				@JsonProperty(required = false) String jackson, @JsonProperty String jacksonDefault,
				@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String swagger,
				@Schema(description = "auto") String swaggerAuto, @Nullable String nullable) {
			return "ok";
		}

	}

	static class CustomerTools {

		@Tool(description = "Retrieve customer information")
		String customer(Long id, ToolContext toolContext) {
			return "Customer " + id + " of " + toolContext.getContext().get("tenantId") + " in "
					+ toolContext.getContext().get("region");
		}

	}

	static class Results {

		@Tool(description = "Does nothing")
		void nothing() {
		}

		@Tool(description = "Starts with a number")
		String startsWithANumber() {
			return "42 orders";
		}

		@Tool(description = "Starts with a word")
		String startsWithAWord() {
			return "Orders: 42";
		}

		@Tool(description = "An order")
		Order order() {
			return new Order("A-1", 3);
		}

		@Tool(description = "Converted", resultConverter = Prefixing.class)
		Order converted() {
			return new Order("A-1", 3);
		}

		@Tool(description = "An image")
		RenderedImage image() {
			return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		}

	}

	record Order(String id, int total) {
	}

	public static class Prefixing implements ToolCallResultConverter {

		@Override
		public String convert(@Nullable Object result, @Nullable Type returnType) {
			return "converted: " + ((Order) result).id();
		}

	}

	static class Functional {

		@Tool(description = "Plain")
		String plain() {
			return "ok";
		}

		@Tool(description = "A supplier")
		Supplier<String> supplier() {
			return () -> "never";
		}

	}

	static class Failing {

		@Tool(description = "Fails unchecked")
		String unchecked() {
			throw new IllegalStateException("The order system is down");
		}

		@Tool(description = "Fails checked")
		String checked() throws Exception {
			throw new Exception("checked");
		}

	}

	static class Counter {

		int calls;

		@Tool(description = "Searches")
		String search() {
			this.calls++;
			return "found";
		}

	}

}
