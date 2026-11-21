package io.github.hjosugi.chatsample.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpClientCapability;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpIcon;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpProgressToken;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.MetaProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The server annotations and their special parameters, as a client sees them: what the
 * attributes publish, what the parameters receive, how exceptions come back, and which
 * methods each kind of server leaves out.
 */
class ServerAnnotationsTests {

	private static Server server;

	@BeforeAll
	static void startServer() {
		server = McpServers.webMvc(Annotated.class, "spring.ai.mcp.server.protocol=STREAMABLE");
	}

	@AfterAll
	static void stopServer() {
		server.close();
	}

	private static void withClient(ThrowingConsumer<McpSyncClient> test) {
		McpClients.httpClient()
			.withPropertyValues(McpClients.STREAMABLE + "annotated.url=" + server.url())
			.withUserConfiguration(Progress.class)
			.run((context) -> test.accept(McpClients.syncClient(context)));
	}

	private static McpSchema.Tool tool(McpSyncClient client, String name) {
		return client.listTools().tools().stream().filter((tool) -> tool.name().equals(name)).findFirst().orElseThrow();
	}

	@Test
	void theAttributesOfMcpToolArePublished() {
		withClient((client) -> {
			McpSchema.Tool area = tool(client, "calculate-area");

			assertThat(area.title()).isEqualTo("Rectangle Area Calculator");
			assertThat(area.annotations().title()).isEqualTo("Rectangle Area");
			assertThat(area.annotations().readOnlyHint()).isTrue();
			assertThat(area.annotations().destructiveHint()).isFalse();
			assertThat(area.annotations().idempotentHint()).isTrue();
			assertThat(area.annotations().openWorldHint()).isFalse();
			assertThat(area.outputSchema()).extractingByKey("properties")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsKeys("area", "unit");
			assertThat(area.meta()).containsEntry("version", "1.0").containsEntry("team", "platform");
			assertThat(area.icons()).singleElement().satisfies((icon) -> {
				assertThat(icon.src()).isEqualTo("https://shop.example/area.png");
				assertThat(icon.mimeType()).isEqualTo("image/png");
				assertThat(icon.sizes()).containsExactly("48x48");
			});
			assertThat(area.inputSchema()).containsEntry("required", List.of("width", "height"));
			assertThat(area.inputSchema()).extractingByKey("properties")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.extractingByKey("width")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("description", "Width");
			assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("calculate-area",
					Map.of("width", 2.0, "height", 3.0))))).isEqualTo("{\"area\":6.0,\"unit\":\"square units\"}");

			// The name defaults to the method's, and so does the title.
			assertThat(tool(client, "simpleAdd").title()).isEqualTo("simpleAdd");
			// The parameter carries the keyword that the 2026-07-28 revision defines.
			assertThat(tool(client, "track").inputSchema()).extractingByKey("properties")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.extractingByKey("parcel")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("x-mcp-header", "parcel");
		});
	}

	/**
	 * The page lists {@code title} and {@code annotations} (audience, priority, last
	 * modified) among the attributes of {@code @McpResource}. No resource provider of
	 * 2.1.0 copies them into the resource ({@code SyncMcpResourceProvider} lines 71-77,
	 * {@code SyncModernMcpResourceProvider} lines 75-81); only the unused
	 * {@code ResourceAdapter} does.
	 */
	@Test
	void mcpResourcePublishesItsUriNameMimeTypeSizeAndMetaButNotItsTitleOrAnnotations() {
		withClient((client) -> {
			McpSchema.Resource menu = client.listResources().resources().get(0);
			assertThat(menu.uri()).isEqualTo("shop://menu");
			assertThat(menu.name()).isEqualTo("menu");
			assertThat(menu.description()).isEqualTo("What the shop serves");
			assertThat(menu.mimeType()).isEqualTo("text/markdown");
			assertThat(menu.size()).isEqualTo(42L);
			assertThat(menu.meta()).containsEntry("version", "1.0");
			assertThat(menu.title()).isNull();
			assertThat(menu.annotations()).isNull();
		});
	}

	@Test
	void theAttributesOfMcpPromptArePublished() {
		withClient((client) -> {

			McpSchema.Prompt prompt = client.listPrompts().prompts().stream()
				.filter((candidate) -> candidate.name().equals("personalized-message"))
				.findFirst()
				.orElseThrow();
			assertThat(prompt.arguments()).extracting(McpSchema.PromptArgument::name, McpSchema.PromptArgument::title,
					McpSchema.PromptArgument::required)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("name", "Your name", true),
						org.assertj.core.groups.Tuple.tuple("age", null, false));
			assertThat(text(client.getPrompt(new GetPromptRequest("personalized-message", Map.of("name", "Ann")))))
				.isEqualTo("Hello, Ann!");
			assertThat(text(client.getPrompt(new GetPromptRequest("personalized-message", Map.of("name", "Ann", "age", "30")))))
				.isEqualTo("Hello, Ann! At 30 years old");
		});
	}

	@Test
	void aCompletionTakesThePrefixTheArgumentOrAnswersWithACompleteResult() {
		withClient((client) -> {
			assertThat(complete(client, "travel-planner", "city", "Ro").values()).containsExactly("Rome");
			assertThat(complete(client, "travel-planner", "country", "It").values()).containsExactly("Italy");
			CompleteResult.CompleteCompletion code = complete(client, "code-completion", "code", "pr");
			assertThat(code.values()).containsExactly("print", "println");
			assertThat(code.total()).isEqualTo(5);
			assertThat(code.hasMore()).isTrue();
		});
	}

	@Test
	void aRuntimeExceptionIsAnErrorResultAndACheckedOneFailsTheCall() {
		withClient((client) -> {
			CallToolResult divided = client.callTool(new CallToolRequest("divide", Map.of("a", 1, "b", 0)));
			assertThat(divided.isError()).isTrue();
			assertThat(ServerProtocolsTests.text(divided)).contains("Cannot divide by zero");

			// What the exception says does not leave the server.
			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> client.callTool(new CallToolRequest("read-file", Map.of("path", "/nowhere"))))
				.satisfies((error) -> {
					assertThat(error.getJsonRpcError().code()).isEqualTo(-32603);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Internal error");
				});
		});
	}

	@Test
	void theSpecialParametersReceiveTheRequestAndAreNotInTheSchema() {
		withClient((client) -> {
			assertThat(tool(client, "context-tool").inputSchema()).extractingByKey("properties")
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsOnlyKeys("input");

			String context = ServerProtocolsTests.text(client.callTool(CallToolRequest.builder("context-tool")
				.arguments(Map.of("input", "x"))
				// The builder puts the progress token into the map it is given.
				.meta(new java.util.HashMap<>(Map.of("userId", "alice")))
				.progressToken("token-1")
				.build()));
			assertThat(context).isEqualTo("input=x, token=token-1, meta=alice, ctx-token=token-1, ctx-meta=alice, "
					+ "client=spring-ai-mcp-client - annotated, session=true, transport=true, request=context-tool");

			String withoutMeta = ServerProtocolsTests
				.text(client.callTool(new CallToolRequest("context-tool", Map.of("input", "y"))));
			// McpMeta is empty without a _meta, the context's requestMeta() is null.
			assertThat(withoutMeta).startsWith("input=y, token=null, meta=null,").contains("ctx-meta=no map");

			String dynamic = ServerProtocolsTests.text(client.callTool(new CallToolRequest("hybrid-tool",
					Map.of("operation", "grind", "priority", 2, "beans", "arabica"))));
			assertThat(dynamic).isEqualTo("Operation: grind (Priority: 2) with 1 additional parameters");
		});
	}

	@Test
	void aToolSendsProgressThroughTheExchangeForTheTokenItWasGiven() {
		McpClients.httpClient()
			.withPropertyValues(McpClients.STREAMABLE + "annotated.url=" + server.url())
			.withUserConfiguration(Progress.class)
			.run((context) -> {
				McpClients.syncClient(context)
					.callTool(CallToolRequest.builder("long-operation")
						.arguments(Map.of("operation", "roast"))
						.progressToken("roast-2")
						.build());
				Progress progress = context.getBean(Progress.class);
				assertThat(InteractionsTests.take(progress.notifications).message()).isEqualTo("Starting roast");
				assertThat(InteractionsTests.take(progress.notifications).message()).isEqualTo("Operation roast completed");
			});
	}

	@Test
	void resourcesAndPromptsReceiveTheMetaOfTheRequest() {
		withClient((client) -> {
			ReadResourceResult secure = client
				.readResource(new ReadResourceRequest("secure-data://42", Map.of("accessLevel", "admin")));
			assertThat(((TextResourceContents) secure.contents().get(0)).text()).isEqualTo("secret 42");
			ReadResourceResult denied = client.readResource(new ReadResourceRequest("secure-data://42"));
			assertThat(((TextResourceContents) denied.contents().get(0)).text()).isEqualTo("Access denied");

			assertThat(text(client.getPrompt(new GetPromptRequest("localized-prompt", Map.of("topic", "beans"),
					Map.of("language", "fr"))))).isEqualTo("beans in fr");
		});
	}

	@Test
	void aSyncServerLeavesOutReactiveMethodsAndAnAsyncServerTheOthers() {
		withClient((client) -> assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
			.contains("sync-tool")
			.doesNotContain("async-tool"));
		try (Server async = McpServers.webMvc(Annotated.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.type=ASYNC")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "async.url=" + async.url())
				.run((context) -> {
					McpSyncClient client = McpClients.syncClient(context);
					assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("async-tool");
					assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("async-tool", Map.of("input", "x")))))
						.isEqualTo("Processed: x");
				});
		}
	}

	@Test
	void aStatelessServerLeavesOutTheMethodsThatNeedASession() {
		try (Server stateless = McpServers.webMvc(Annotated.class, "spring.ai.mcp.server.protocol=STATELESS")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "stateless.url=" + stateless.url())
				.run((context) -> {
					McpSyncClient client = McpClients.syncClient(context);
					assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
						.contains("stateless-tool", "simpleAdd", "divide")
						.doesNotContain("context-tool", "long-operation", "async-tool");
					assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("stateless-tool", Map.of("input", "x")))))
						.isEqualTo("Processed in stateless mode: x (context: true)");
				});
		}
	}

	private static CompleteResult.CompleteCompletion complete(McpSyncClient client, String prompt, String argument,
			String value) {
		return client
			.completeCompletion(new CompleteRequest(new PromptReference(prompt), new CompleteRequest.CompleteArgument(argument, value)))
			.completion();
	}

	private static String text(GetPromptResult result) {
		return ((TextContent) result.messages().get(0).content()).text();
	}

	@FunctionalInterface
	interface ThrowingConsumer<T> {

		void accept(T value) throws Exception;

	}

	@Configuration(proxyBeanMethods = false)
	static class Annotated {

		@Bean
		AnnotatedMethods annotatedMethods() {
			return new AnnotatedMethods();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Progress {

		final BlockingQueue<ProgressNotification> notifications = new LinkedBlockingQueue<>();

		@org.springframework.ai.mcp.annotation.McpProgress(clients = "annotated")
		public void progress(ProgressNotification notification) {
			this.notifications.add(notification);
		}

	}

	public static class VersionMetaProvider implements MetaProvider {

		@Override
		public Map<String, Object> getMeta() {
			return Map.of("version", "1.0", "team", "platform");
		}

	}

	public record AreaResult(double area, String unit) {
	}

	/**
	 * The pages' examples, more or less as they are.
	 */
	public static class AnnotatedMethods {

		@McpTool(name = "calculate-area", description = "Calculate the area of a rectangle",
				title = "Rectangle Area Calculator", generateOutputSchema = true,
				annotations = @McpTool.McpAnnotations(title = "Rectangle Area", readOnlyHint = true,
						destructiveHint = false, idempotentHint = true, openWorldHint = false),
				metaProvider = VersionMetaProvider.class,
				icons = @McpIcon(src = "https://shop.example/area.png", mimeType = "image/png", sizes = "48x48"),
				requiredCapabilities = McpClientCapability.ROOTS)
		public AreaResult calculateRectangleArea(@McpToolParam(description = "Width", required = true) double width,
				@McpToolParam(description = "Height", required = true) double height) {
			return new AreaResult(width * height, "square units");
		}

		@McpTool(description = "Simple addition")
		public int simpleAdd(@McpToolParam(description = "First number") int a,
				@McpToolParam(description = "Second number") int b) {
			return a + b;
		}

		@McpTool(name = "track", description = "Tracks a parcel")
		public String track(@McpToolParam(description = "The parcel", header = true) String parcel) {
			return "on its way";
		}

		@McpTool(name = "divide", description = "Divide two numbers")
		public double divide(@McpToolParam(description = "a") int a, @McpToolParam(description = "b") int b) {
			if (b == 0) {
				throw new IllegalArgumentException("Cannot divide by zero");
			}
			return (double) a / b;
		}

		@McpTool(name = "read-file", description = "Read a file from disk")
		public String readFile(@McpToolParam(description = "Path", required = true) String path) throws IOException {
			throw new IOException("No file at " + path);
		}

		@McpTool(name = "context-tool", description = "Reads the special parameters")
		public String contextTool(McpSyncRequestContext context, @McpProgressToken String progressToken, McpMeta meta,
				@McpToolParam(description = "Input", required = true) String input) {
			return "input=" + input + ", token=" + progressToken + ", meta=" + meta.get("userId") + ", ctx-token="
					+ context.request().progressToken() + ", ctx-meta="
					+ ((context.requestMeta() != null) ? context.requestMeta().get("userId") : "no map")
					+ ", client=" + context.clientInfo().name() + ", session=" + (context.sessionId() != null)
					+ ", transport=" + (context.transportContext() != null) + ", request="
					+ ((CallToolRequest) context.request()).name();
		}

		@McpTool(name = "hybrid-tool", description = "Tool with typed and dynamic parameters")
		public String processHybrid(@McpToolParam(description = "Operation", required = true) String operation,
				@McpToolParam(description = "Priority", required = false) Integer priority, CallToolRequest request) {
			String result = "Operation: " + operation;
			if (priority != null) {
				result += " (Priority: " + priority + ")";
			}
			Map<String, Object> additionalArgs = new java.util.HashMap<>(request.arguments());
			additionalArgs.remove("operation");
			additionalArgs.remove("priority");
			if (!additionalArgs.isEmpty()) {
				result += " with " + additionalArgs.size() + " additional parameters";
			}
			return result;
		}

		@McpTool(name = "long-operation", description = "Long-running operation with progress")
		public String performLongOperation(@McpProgressToken String progressToken,
				@McpToolParam(description = "Operation name", required = true) String operation,
				McpSyncServerExchange exchange) {
			if (progressToken != null) {
				exchange.progressNotification(
						ProgressNotification.builder(progressToken, 0.0).total(1.0).message("Starting " + operation).build());
				exchange.progressNotification(ProgressNotification.builder(progressToken, 1.0)
					.total(1.0)
					.message("Operation " + operation + " completed")
					.build());
			}
			return "Operation " + operation + " completed";
		}

		@McpTool(name = "stateless-tool", description = "Stateless with transport context")
		public String statelessTool(McpTransportContext context,
				@McpToolParam(description = "Input", required = true) String input) {
			return "Processed in stateless mode: " + input + " (context: " + (context != null) + ")";
		}

		@McpTool(name = "sync-tool", description = "Synchronous tool")
		public String syncTool(@McpToolParam(description = "Input") String input) {
			return "Processed: " + input;
		}

		@McpTool(name = "async-tool", description = "Async tool")
		public Mono<String> asyncTool(@McpToolParam(description = "Input") String input) {
			return Mono.just("Processed: " + input);
		}

		@McpResource(uri = "shop://menu", name = "menu", title = "The menu", description = "What the shop serves",
				mimeType = "text/markdown", size = 42, metaProvider = VersionMetaProvider.class,
				annotations = @McpResource.McpAnnotations(audience = { Role.USER, Role.ASSISTANT }, priority = 1.0,
						lastModified = "2026-11-01T09:00:00Z"),
				ttlMs = 60_000)
		public String menu() {
			return "# Coffee";
		}

		@McpResource(uri = "secure-data://{id}", name = "Secure Data")
		public ReadResourceResult getSecureData(String id, McpMeta meta) {
			String text = "admin".equals(meta.get("accessLevel")) ? "secret " + id : "Access denied";
			return ReadResourceResult
				.builder(List.of(TextResourceContents.builder("secure-data://" + id, text).mimeType("text/plain").build()))
				.build();
		}

		@McpPrompt(name = "personalized-message", description = "Generate a personalized message")
		public GetPromptResult personalizedMessage(@McpArg(name = "name", title = "Your name", required = true) String name,
				@McpArg(name = "age", required = false) Integer age) {
			String message = "Hello, " + name + "!" + ((age != null) ? " At " + age + " years old" : "");
			return GetPromptResult
				.builder(List.of(new PromptMessage(Role.ASSISTANT, TextContent.builder(message).build())))
				.description("Personalized Message")
				.build();
		}

		@McpPrompt(name = "localized-prompt", description = "Localized prompt generation")
		public GetPromptResult localizedPrompt(@McpArg(name = "topic", required = true) String topic, McpMeta meta) {
			return GetPromptResult
				.builder(List.of(new PromptMessage(Role.ASSISTANT, TextContent.builder(topic + " in " + meta.get("language")).build())))
				.build();
		}

		@McpPrompt(name = "travel-planner", description = "Plans a trip")
		public GetPromptResult travelPlanner(@McpArg(name = "city") String city, @McpArg(name = "country") String country) {
			return GetPromptResult.builder(List.of()).build();
		}

		@McpComplete(prompt = "travel-planner")
		public List<String> completeTravelDestination(CompleteRequest.CompleteArgument argument) {
			List<String> candidates = "city".equals(argument.name()) ? List.of("Rome", "Oslo") : List.of("Italy", "Norway");
			return candidates.stream().filter((candidate) -> candidate.startsWith(argument.value())).toList();
		}

		@McpPrompt(name = "code-completion", description = "Completes code")
		public GetPromptResult codeCompletion(@McpArg(name = "code") String code) {
			return GetPromptResult.builder(List.of()).build();
		}

		@McpComplete(prompt = "code-completion")
		public CompleteResult completeCode(String prefix) {
			List<String> completions = List.of("print", "println");
			return new CompleteResult(new CompleteResult.CompleteCompletion(completions, 5, true));
		}

	}

}
