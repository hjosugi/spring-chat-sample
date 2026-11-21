package io.github.hjosugi.chatsample.tools;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three ways to define a tool, {@code @Tool}, {@code MethodToolCallback} and
 * {@code FunctionToolCallback}, and how tools reach a {@code ChatClient}: what the model
 * is offered, and what runs when it calls a tool.
 */
class DefiningToolsTests {

	private static final JsonMapper json = JsonMapper.shared();

	@Test
	void aToolMethodMayBePrivateOrStatic() {
		HiddenTools tools = new HiddenTools();

		assertThat(answerTo("secret", "{}", tools)).isEqualTo("\"private\"");
		assertThat(answerTo("shared", "{}", tools)).isEqualTo("\"static\"");
	}

	@Test
	void aToolIsNamedAndDescribedAfterItsMethodUnlessTheAnnotationSaysOtherwise() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model).prompt("hi").tools(new NamedTools()).call().content();

		Prompt prompt = model.lastPrompt();
		assertThat(OfferedTools.names(prompt)).containsExactlyInAnyOrder("lookUpOrder", "find_customer");
		assertThat(OfferedTools.definition(prompt, "lookUpOrder").description()).isEqualTo("lookUpOrder");
		assertThat(OfferedTools.definition(prompt, "find_customer").description())
			.isEqualTo("Finds a customer by name");
	}

	@Test
	void parametersAreRequiredUnlessToolParamOrNullableSaysOtherwise() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model).prompt("hi").tools(new WeatherTools()).call().content();

		JsonNode schema = json.readTree(OfferedTools.definition(model.lastPrompt(), "getWeather").inputSchema());
		assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrder("city", "at", "unit");
		assertThat(schema.get("required").valueStream().map(JsonNode::asString)).containsExactly("city");
		assertThat(schema.at("/properties/city/description").asString()).isEqualTo("City name");
		assertThat(schema.at("/properties/at/description").asString()).isEqualTo("Time in ISO-8601 format");
	}

	@Test
	void aMethodToolCallbackMakesAToolOfAMethodAtRuntime() {
		Method method = ReflectionUtils.findMethod(PlainWeather.class, "weatherIn", String.class);
		MethodToolCallback callback = MethodToolCallback.builder()
			.toolDefinition(ToolDefinitions.builder(method).description("Get the current weather for a city").build())
			.toolMethod(method)
			.toolObject(new PlainWeather())
			.build();

		assertThat(callback.getToolDefinition().name()).isEqualTo("weatherIn");
		assertThat(callback.getToolDefinition().description()).isEqualTo("Get the current weather for a city");
		assertThat(answerTo("weatherIn", "{\"city\":\"Lisbon\"}", callback)).isEqualTo("\"Sunny in Lisbon\"");
	}

	/**
	 * The reference says the default result converter answers {@code "Done"} for a
	 * {@code void} method; a {@code Consumer} tool returns nothing either, but its result
	 * reaches the model as {@code null}: {@code FunctionToolCallback} passes no return type
	 * to the converter.
	 */
	@Test
	void aFunctionToolCallbackRunsAFunctionASupplierAConsumerOrABiFunction() {
		List<String> consumed = new CopyOnWriteArrayList<>();
		Function<City, String> function = (city) -> "Sunny in " + city.name();
		Supplier<String> supplier = () -> "noon";
		Consumer<City> consumer = (city) -> consumed.add(city.name());
		ToolCallback withFunction = FunctionToolCallback.builder("weather", function)
			.description("The weather in a city")
			.inputType(City.class)
			.build();
		ToolCallback withSupplier = FunctionToolCallback.builder("time", supplier).description("The time").build();
		ToolCallback withConsumer = FunctionToolCallback.builder("visit", consumer)
			.description("Records a visit")
			.inputType(City.class)
			.build();
		ToolCallback withContext = FunctionToolCallback
			.builder("greet", (City city, ToolContext context) -> "Hello " + context.getContext().get("user") + " in "
					+ city.name())
			.description("Greets the user")
			.inputType(City.class)
			.build();

		assertThat(answerTo("weather", "{\"name\":\"Oslo\"}", withFunction)).isEqualTo("\"Sunny in Oslo\"");
		assertThat(answerTo("time", "{}", withSupplier)).isEqualTo("\"noon\"");
		// Unlike a void @Tool method, which answers "Done", a consumer answers null
		assertThat(answerTo("visit", "{\"name\":\"Rome\"}", withConsumer)).isEqualTo("null");
		assertThat(consumed).containsExactly("Rome");
		ScriptedChatModel model = callingOnce("greet", "{\"name\":\"Paris\"}");
		assertThat(ChatClient.create(model)
			.prompt("hi")
			.tools(withContext)
			.toolContext(Map.of("user", "alice"))
			.call()
			.content()).isEqualTo("\"Hello alice in Paris\"");
	}

	@Test
	void perCallToolsAddToTheDefaultTools() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultTools(new HiddenTools()).build();

		client.prompt("hi").tools(new NamedTools()).call().content();

		assertThat(OfferedTools.names(model.lastPrompt())).containsExactlyInAnyOrder("secret", "shared", "lookUpOrder",
				"find_customer");
	}

	@Test
	void toolsTakeAnnotatedObjectsCallbacksProvidersArraysAndCollectionsAtOnce() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ToolCallback time = FunctionToolCallback.builder("time", () -> "noon").description("The time").build();
		ToolCallbackProvider provider = () -> ToolCallbacks.from(new NamedTools());

		ChatClient.create(model)
			.prompt("hi")
			.tools(new HiddenTools(), time, provider, ToolCallbacks.from(new WeatherTools()),
					List.of(FunctionToolCallback.builder("date", () -> "today").description("The date").build()))
			.call()
			.content();

		assertThat(OfferedTools.names(model.lastPrompt())).containsExactlyInAnyOrder("secret", "shared", "time",
				"lookUpOrder", "find_customer", "getWeather", "date");
	}

	@Test
	void theToolsOfARequestReplaceTheToolsOfTheChatModelsOptions() {
		ScriptedChatModel script = ScriptedChatModel.answering("ok");
		ChatModel withDefaultTools = new ChatModel() {

			@Override
			public ChatResponse call(Prompt prompt) {
				return script.call(prompt);
			}

			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(new HiddenTools())).build();
			}

		};

		ChatClient.create(withDefaultTools).prompt("hi").call().content();
		assertThat(OfferedTools.names(script.lastPrompt())).containsExactlyInAnyOrder("secret", "shared");

		ChatClient.create(withDefaultTools).prompt("hi").tools(new NamedTools()).call().content();
		assertThat(OfferedTools.names(script.lastPrompt())).containsExactlyInAnyOrder("lookUpOrder", "find_customer");
	}

	/**
	 * A model that calls the tool once, then answers with what the tool returned.
	 */
	static ScriptedChatModel callingOnce(String tool, String arguments) {
		return new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text(ScriptedChatModel.lastText(prompt)) : ScriptedChatModel.toolCall(tool, arguments));
	}

	private static String answerTo(String tool, String arguments, Object... tools) {
		return ChatClient.create(callingOnce(tool, arguments)).prompt("Go").tools(tools).call().content();
	}

	static class HiddenTools {

		@Tool(description = "A secret")
		private String secret() {
			return "private";
		}

		@Tool(description = "Shared by all")
		static String shared() {
			return "static";
		}

	}

	static class NamedTools {

		@Tool
		String lookUpOrder(String id) {
			return id;
		}

		@Tool(name = "find_customer", description = "Finds a customer by name")
		String findCustomer(String name) {
			return name;
		}

	}

	static class WeatherTools {

		@Tool(description = "Get the weather for a city at a specific time")
		String getWeather(@ToolParam(description = "City name") String city,
				@ToolParam(description = "Time in ISO-8601 format", required = false) String at,
				@Nullable String unit) {
			return "Sunny";
		}

	}

	static class PlainWeather {

		String weatherIn(String city) {
			return "Sunny in " + city;
		}

	}

	record City(String name) {
	}

}
