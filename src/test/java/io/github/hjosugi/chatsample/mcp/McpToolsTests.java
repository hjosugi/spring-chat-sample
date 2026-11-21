package io.github.hjosugi.chatsample.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.hjosugi.chatsample.support.StandIn;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.endsWithToolResult;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The tools of an MCP server in a {@code ChatClient}, as the tutorial and "Using MCP
 * Tools" show them: the server's {@code @McpTool} methods and {@code ToolCallbackProvider}
 * beans, the client's {@code SyncMcpToolCallbackProvider}, and a model that asks for
 * them. The model is scripted, the server and the client are real.
 */
class McpToolsTests {

	private static Server server;

	@BeforeAll
	static void startServer() {
		server = McpServers.webMvc(WeatherServer.class, "spring.ai.mcp.server.protocol=STREAMABLE");
	}

	@AfterAll
	static void stopServer() {
		server.close();
	}

	private ApplicationContextRunner client() {
		return McpClients.httpClient().withPropertyValues(McpClients.STREAMABLE + "weather-server.url=" + server.url());
	}

	@Test
	void aChatClientCallsTheToolOfAnMcpServerThatTheModelAsksFor() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> endsWithToolResult(prompt)
				? text("It says: " + lastText(prompt)) : toolCall("getTemperature", "{\"city\": \"Paris\"}"));

		client().run((context) -> {
			ToolCallbackProvider mcpTools = context.getBean(SyncMcpToolCallbackProvider.class);

			String answer = ChatClient.builder(model)
				.build()
				.prompt("What's the weather like in Paris?")
				.tools(mcpTools)
				.call()
				.content();

			assertThat(answer).isEqualTo("It says: [{\"text\":\"Current temperature in Paris: 22°C\"}]");
			assertThat(((ToolCallingChatOptions) model.prompts.get(0).getOptions()).getToolCallbacks())
				.extracting((tool) -> tool.getToolDefinition().name())
				.containsExactlyInAnyOrder("getTemperature", "openingHours");
		});
	}

	@Test
	void theToolsCanBeTheDefaultToolsOfEveryRequest() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> endsWithToolResult(prompt)
				? text(lastText(prompt)) : toolCall("openingHours", "{}"));

		client().run((context) -> {
			ChatClient chatClient = ChatClient.builder(model)
				.defaultTools(context.getBean(SyncMcpToolCallbackProvider.class))
				.build();

			assertThat(chatClient.prompt("When do you open?").call().content()).contains("9 to 17");
			assertThat(chatClient.prompt("And tomorrow?").call().content()).contains("9 to 17");
		});
	}

	@Test
	void theProviderListsTheToolsWhenFirstUsedNotAtStartup() {
		client().withUserConfiguration(RequestLog.class).run((context) -> {
			List<String> requests = context.getBean(RequestLog.class).methods;
			assertThat(requests).as("at startup").contains("initialize").doesNotContain("tools/list");

			context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();

			assertThat(requests).containsOnlyOnce("tools/list");
		});
	}

	@Test
	void theToolsOfMcpServersCannotBeResolvedByName() {
		client().withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class)).run((context) -> {
			ToolCallbackResolver resolver = context.getBean(ToolCallbackResolver.class);

			assertThat(context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks()).isNotEmpty();
			assertThat(resolver.resolve("getTemperature")).isNull();
		});
	}

	@Test
	void theToolCallbackAutoConfigurationCanBeTurnedOff() {
		client().withPropertyValues("spring.ai.mcp.client.toolcallback.enabled=false").run((context) -> {
			assertThat(context).doesNotHaveBean(ToolCallbackProvider.class);
			assertThat(McpClients.syncClient(context).listTools().tools()).isNotEmpty();
		});
	}

	@Test
	void theAsyncClientTypeHasAnAsyncProvider() {
		client().withPropertyValues("spring.ai.mcp.client.type=ASYNC").run((context) -> {
			assertThat(context).doesNotHaveBean(SyncMcpToolCallbackProvider.class);
			ToolCallback[] tools = context.getBean(AsyncMcpToolCallbackProvider.class).getToolCallbacks();
			assertThat(tools).extracting((tool) -> tool.getToolDefinition().name())
				.containsExactlyInAnyOrder("getTemperature", "openingHours");
			assertThat(tools).filteredOn((tool) -> tool.getToolDefinition().name().equals("getTemperature"))
				.singleElement()
				.extracting((tool) -> tool.call("{\"city\": \"Oslo\"}"))
				.asString()
				.contains("Current temperature in Oslo: 22°C");
		});
	}

	@Test
	void localAndMcpToolsMixInOneRequest() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			if (!endsWithToolResult(prompt)) {
				return toolCall("getTemperature", "{\"city\": \"Rome\"}");
			}
			return lastText(prompt).contains("Rome") ? toolCall("beansInStock", "{}") : text("done");
		});

		client().run((context) -> {
			ChatClient.create(model)
				.prompt("Is it iced coffee weather, and do you have beans?")
				.tools(new LocalTools(), context.getBean(SyncMcpToolCallbackProvider.class))
				.call()
				.content();

			assertThat(model.lastPrompt().getInstructions()).filteredOn(ToolResponseMessage.class::isInstance)
				.extracting((message) -> ((ToolResponseMessage) message).getResponses().get(0).responseData())
				.satisfiesExactly(
						(mcp) -> assertThat(mcp).isEqualTo("[{\"text\":\"Current temperature in Rome: 22°C\"}]"),
						(local) -> assertThat(local).contains("3 kinds"));
		});
	}

	@Test
	void aLocalToolWithTheNameOfAnMcpToolFailsTheRequest() {
		client().run((context) -> {
			ChatClient.ChatClientRequestSpec request = ChatClient.create(ScriptedChatModel.answering("never"))
				.prompt("When do you open?")
				.tools(new LocalOpeningHours(), context.getBean(SyncMcpToolCallbackProvider.class));

			assertThatIllegalStateException().isThrownBy(() -> request.call().content())
				.withMessageContaining("openingHours");
		});
	}

	/**
	 * Outside the {@code ChatClient} nothing checks the names, and the first of two tools
	 * with one name runs.
	 */
	@Test
	void withoutTheChatClientTheFirstOfTwoToolsWithOneNameRunsWithoutAWord() {
		client().run((context) -> {
			ToolCallback[] mcpTools = context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();
			List<ToolCallback> merged = new java.util.ArrayList<>(List.of(MethodToolCallbackProvider.builder()
				.toolObjects(new LocalOpeningHours())
				.build()
				.getToolCallbacks()));
			merged.addAll(List.of(mcpTools));
			Prompt prompt = new Prompt("When do you open?", ToolCallingChatOptions.builder().toolCallbacks(merged).build());
			ChatResponse askingForTheTool = new ChatResponse(
					List.of(new Generation(toolCall("openingHours", "{}"))));

			ToolExecutionResult result = ToolCallingManager.builder().build().executeToolCalls(prompt, askingForTheTool);

			ToolResponseMessage response = (ToolResponseMessage) result.conversationHistory()
				.get(result.conversationHistory().size() - 1);
			assertThat(response.getResponses().get(0).responseData()).isEqualTo("\"around the clock\"");
			assertThatIllegalStateException().isThrownBy(() -> ToolCallingChatOptions.validateToolCallbacks(merged))
				.withMessageContaining("openingHours");
		});
	}

	/**
	 * The examples page ends with a controller that puts the MCP tools into a
	 * {@code ToolCallingChatOptions} and calls the {@code ChatModel} with it. Anthropic's
	 * model, talking to a stand-in, takes options that are not {@code AnthropicChatOptions}
	 * as empty ones ({@code AnthropicChatModel} lines 726-729): the tools, and the
	 * configured model, never reach the API. And a chat model of Spring AI 2.1 runs no
	 * tool, so a tool call that comes back is the controller's answer, and the MCP server
	 * is never called.
	 */
	@Test
	void theExamplesControllerSendsTheModelNoToolAndRunsNone() {
		try (StandIn anthropic = new StandIn((request) -> StandIn.Reply.json("{\"id\":\"msg_1\",\"type\":\"message\","
				+ "\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"tool_use\","
				+ "\"id\":\"toolu_1\",\"name\":\"getTemperature\",\"input\":{\"city\":\"Paris\"}}],"
				+ "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}"))) {
			client().withConfiguration(AutoConfigurations.of(AnthropicChatAutoConfiguration.class, ToolCallingAutoConfiguration.class))
				.withPropertyValues("spring.ai.anthropic.api-key=test-key", "spring.ai.anthropic.base-url=" + anthropic.url(),
						"spring.ai.anthropic.chat.model=claude-haiku-5-5", "spring.ai.anthropic.max-retries=0")
				.withUserConfiguration(RequestLog.class)
				.run((context) -> {
					ToolCallback[] mcpTools = context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();
					Prompt prompt = new Prompt("What's the weather in Paris?",
							ToolCallingChatOptions.builder().toolCallbacks(mcpTools).build());

					ChatResponse response = context.getBean(ChatModel.class).call(prompt);

					assertThat(response.getResult().getOutput().getToolCalls()).singleElement()
						.extracting(AssistantMessage.ToolCall::name)
						.isEqualTo("getTemperature");
					assertThat(response.getResult().getOutput().getText()).isNullOrEmpty();
					assertThat(anthropic.single().body().has("tools")).isFalse();
					assertThat(anthropic.single().body().get("model").asString()).isNotEqualTo("claude-haiku-5-5");
					assertThat(context.getBean(RequestLog.class).methods).doesNotContain("tools/call");
				});
		}
	}

	@Test
	void aToolCallbackProviderBeanOfTheServerIsExposedUnlessTheConverterIsOff() {
		try (Server converterOff = McpServers.webMvc(WeatherServer.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.tool-callback-converter=false")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "weather.url=" + converterOff.url())
				.run((context) -> assertThat(McpClients.syncClient(context).listTools().tools())
					.extracting(McpSchema.Tool::name)
					.containsExactly("getTemperature"));
		}
	}

	/**
	 * The page says the converter switch is about {@code ToolCallback} beans. On a
	 * stateless server it also takes away the {@code @McpTool} methods:
	 * {@code StatelessServerSpecificationFactoryAutoConfiguration} has the converter's
	 * condition.
	 */
	@Test
	void onAStatelessServerTurningTheConverterOffAlsoDropsTheAnnotatedTools() {
		try (Server stateless = McpServers.webMvc(WeatherServer.class, "spring.ai.mcp.server.protocol=STATELESS",
				"spring.ai.mcp.server.tool-callback-converter=false")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "weather.url=" + stateless.url())
				.run((context) -> assertThat(McpClients.syncClient(context).listTools().tools()).isEmpty());
		}
	}

	/**
	 * The server of the tutorial: an {@code @McpTool} method, and the {@code @Tool}
	 * methods of a {@code ToolCallbackProvider} bean.
	 */
	@Configuration(proxyBeanMethods = false)
	static class WeatherServer {

		@Bean
		WeatherService weatherService() {
			return new WeatherService();
		}

		@Bean
		ToolCallbackProvider openingTools() {
			return MethodToolCallbackProvider.builder().toolObjects(new OpeningHours()).build();
		}

	}

	public static class WeatherService {

		@McpTool(description = "Get current temperature for a location")
		public String getTemperature(@McpToolParam(description = "City name", required = true) String city) {
			return String.format("Current temperature in %s: 22°C", city);
		}

	}

	static class OpeningHours {

		@Tool(description = "Tells when the shop opens")
		String openingHours() {
			return "9 to 17";
		}

	}

	static class LocalOpeningHours {

		@Tool(description = "Tells when the shop opens, as the application thinks")
		String openingHours() {
			return "around the clock";
		}

	}

	static class LocalTools {

		@Tool(description = "Tells how many kinds of beans are in stock")
		String beansInStock() {
			return "3 kinds";
		}

	}

	/**
	 * The JSON-RPC methods the client sends, as an HTTP request customizer sees them.
	 */
	@Configuration(proxyBeanMethods = false)
	static class RequestLog {

		final List<String> methods = new CopyOnWriteArrayList<>();

		@Bean
		McpSyncHttpClientRequestCustomizer requestLog() {
			return (builder, method, endpoint, body, context) -> {
				if (body != null && body.contains("\"method\"")) {
					this.methods.add(body.replaceAll(".*\"method\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
				}
			};
		}

	}

}
