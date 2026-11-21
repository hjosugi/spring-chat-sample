package io.github.hjosugi.chatsample.mcp;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.github.hjosugi.chatsample.mcp.McpServers.Stack;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.customizer.McpServerCustomizer;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

/**
 * The server pages' properties, beans and customizers: capabilities, endpoints, the
 * low-level specification beans, {@code ToolCallback} beans with their tool context and
 * MIME types, the customizers of the server and its transport, session limits, and what
 * the transports do with the {@code Origin} of a request.
 */
class ServerConfigurationTests {

	@Test
	void capabilitiesThatAreOffAreNeitherAnnouncedNorServed() {
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.capabilities.tool=false", "spring.ai.mcp.server.capabilities.prompt=false",
				"spring.ai.mcp.server.tool-change-notification=false")) {
			client(server).run((context) -> {
				McpSyncClient client = McpClients.syncClient(context);
				McpSchema.ServerCapabilities capabilities = client.getServerCapabilities();

				assertThat(capabilities.tools()).isNull();
				assertThat(capabilities.prompts()).isNull();
				assertThat(capabilities.resources()).isNotNull();
				assertThat(capabilities.completions()).isNotNull();
				assertThatException().isThrownBy(client::listTools);
				assertThat(client.listResources().resources()).isNotEmpty();
			});
		}
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.tool-change-notification=false")) {
			client(server).run((context) -> assertThat(McpClients.syncClient(context).getServerCapabilities().tools().listChanged())
				.isFalse());
		}
	}

	@Test
	void theEndpointsOfTheTransportsAreProperties() {
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "brewing.url=" + server.url(),
						McpClients.STREAMABLE + "brewing.endpoint=/api/mcp")
				.run((context) -> ServerProtocolsTests.assertBrewing(McpClients.syncClient(context)));
		}
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.sse-endpoint=/events", "spring.ai.mcp.server.sse-message-endpoint=/mcp/messages")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.SSE + "brewing.url=" + server.url(), McpClients.SSE + "brewing.sse-endpoint=/events")
				.run((context) -> ServerProtocolsTests.assertBrewing(McpClients.syncClient(context)));
		}
	}

	@Test
	void theLowLevelSpecificationBeansAreServed() {
		try (Server server = McpServers.webMvc(Specifications.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			client(server).run((context) -> {
				McpSyncClient client = McpClients.syncClient(context);

				assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("spec-tool", Map.of()))))
					.isEqualTo("from a specification");
				assertThat(client.readResource(new ReadResourceRequest("system://info")).contents()).singleElement()
					.satisfies((contents) -> {
						assertThat(contents.mimeType()).isEqualTo("application/json");
						assertThat(((TextResourceContents) contents).text()).isEqualTo("{\"os\":\"test\"}");
					});
				assertThat(client.getPrompt(new GetPromptRequest("greeting", Map.of())).messages()).singleElement()
					.extracting((message) -> ((TextContent) message.content()).text())
					.isEqualTo("Hello friend! How can I assist you today?");
				assertThat(client
					.completeCompletion(new CompleteRequest(new McpSchema.PromptReference("greeting"),
							new CompleteRequest.CompleteArgument("name", "py")))
					.completion()).satisfies((completion) -> {
						assertThat(completion.values()).containsExactly("python", "pytorch", "pyside");
						assertThat(completion.total()).isEqualTo(10);
						assertThat(completion.hasMore()).isTrue();
					});
			});
		}
		try (Server server = McpServers.webMvc(StatelessSpecifications.class, "spring.ai.mcp.server.protocol=STATELESS")) {
			client(server).run((context) -> assertThat(ServerProtocolsTests
				.text(McpClients.syncClient(context).callTool(new CallToolRequest("stateless-spec-tool", Map.of()))))
				.isEqualTo("stateless"));
		}
	}

	/**
	 * Of two tools with one name the first is served, as the page says. First means first
	 * in {@code ToolCallbackUtils.aggregateToolCallbacks}: the {@code List<ToolCallback>}
	 * beans come before the single {@code ToolCallback} beans, whatever their order.
	 * <p>
	 * {@code tool-response-mime-type} makes the result of a tool image content, as the
	 * page says, but the data is the text that the callback returns: a {@code String}
	 * comes JSON-quoted, so an image needs a result converter that leaves it alone.
	 */
	@Test
	void toolCallbackBeansAreToolsAndAListBeanComesBeforeASingleOneOfTheSameName() {
		try (Server server = McpServers.webMvc(ToolCallbacks.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.tool-response-mime-type.logo=image/png")) {
			client(server).withUserConfiguration(LogCollector.class).run((context) -> {
				McpSyncClient client = McpClients.syncClient(context);

				assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
					.containsExactlyInAnyOrder("special", "logo", "notify");
				assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("special", Map.of()))))
					.isEqualTo("\"second\"");
				CallToolResult logo = client.callTool(new CallToolRequest("logo", Map.of()));
				assertThat(logo.content()).singleElement().isInstanceOfSatisfying(ImageContent.class, (image) -> {
					assertThat(image.mimeType()).isEqualTo("image/png");
					// The callback's result as Spring AI's default converter makes it: a JSON
					// string, quotes included, which is no longer valid base64.
					assertThat(image.data()).isEqualTo("\"" + Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }) + "\"");
				});

				// The tool context of a ToolCallback holds the exchange of the session.
				client.callTool(new CallToolRequest("notify", Map.of()));
				assertThat(InteractionsTests.take(context.getBean(LogCollector.class).logs).data())
					.isEqualTo("sent through the exchange");
			});
		}
	}

	@ParameterizedTest
	@EnumSource(Stack.class)
	void aTransportCustomizerPutsARequestHeaderIntoTheTransportContext(Stack stack) {
		try (Server server = McpServers.start(stack, Authorization.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			client(server).withUserConfiguration(BearerToken.class).run((context) -> assertThat(ServerProtocolsTests
				.text(McpClients.syncClient(context).callTool(new CallToolRequest("whoAmI", Map.of()))))
				.isEqualTo("Bearer alice-token"));
		}
	}

	/**
	 * The page says a customizer must name the builder type exactly, for example
	 * {@code SyncSpecification<?>} rather than {@code StreamableSyncSpecification}.
	 */
	@Test
	void aServerCustomizerAppliesOnlyWhenItNamesTheBuilderTypeExactly() {
		try (Server server = McpServers.webMvc(ServerCustomizers.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.instructions=from the properties")) {
			client(server).run((context) -> assertThat(McpClients.syncClient(context).getServerInstructions())
				.isEqualTo("from the exact customizer"));
		}
	}

	@Test
	void aStreamableServerAnswersInitializeBeyondItsSessionLimitWith503() {
		try (Server server = McpServers.webMvc(OneSession.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			assertThat(RawMcp.post(server.url() + "/mcp", RawMcp.INITIALIZE, Map.of()).statusCode()).isEqualTo(200);
			assertThat(RawMcp.post(server.url() + "/mcp", RawMcp.INITIALIZE, Map.of()).statusCode()).isEqualTo(503);
		}
	}

	/**
	 * The legacy transports take a request from any web page; the modern one refuses every
	 * request with an {@code Origin}, unless a validator bean says otherwise.
	 */
	@Test
	void onlyTheModernTransportChecksTheOriginOfARequest() {
		Map<String, String> fromAPage = Map.of("Origin", "http://attacker.example");
		for (String protocol : List.of("STREAMABLE", "STATELESS")) {
			try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=" + protocol)) {
				assertThat(RawMcp.post(server.url() + "/mcp", RawMcp.INITIALIZE, fromAPage).statusCode()).as(protocol)
					.isEqualTo(200);
			}
		}
		for (Stack stack : Stack.values()) {
			try (Server server = McpServers.start(stack, Brewing.Beans.class, "spring.ai.mcp.server.protocol=MODERN")) {
				assertThat(RawMcp.post(server.url() + "/mcp", "{}", fromAPage).statusCode()).as(stack.name())
					.isEqualTo(403);
			}
		}
		try (Server server = McpServers.webMvc(AnyOrigin.class, "spring.ai.mcp.server.protocol=MODERN")) {
			assertThat(RawMcp.post(server.url() + "/mcp", "{}", fromAPage).statusCode()).isNotEqualTo(403);
		}
	}

	/**
	 * The WebMVC and WebFlux server auto-configurations do not look at the type of the web
	 * application, so with both starters on the classpath a server has two transports.
	 */
	@Test
	void theWebMvcAndWebFluxServerStartersCannotBeCombined() {
		SpringApplicationBuilder both = new SpringApplicationBuilder(McpServers.WebMvcStack.class,
				McpServers.WebFluxStack.class, Brewing.Beans.class)
			.web(WebApplicationType.SERVLET)
			.properties("spring.config.name=mcp-test-server", "server.port=0", "spring.ai.mcp.server.protocol=STREAMABLE");

		assertThatException().isThrownBy(both::run)
			.havingRootCause()
			.isInstanceOf(NoUniqueBeanDefinitionException.class)
			.withMessageContaining("webMvcStreamableServerTransportProvider")
			.withMessageContaining("webFluxStreamableServerTransportProvider");
	}

	private static org.springframework.boot.test.context.runner.ApplicationContextRunner client(Server server) {
		return McpClients.httpClient().withPropertyValues(McpClients.STREAMABLE + "server.url=" + server.url());
	}

	/**
	 * The specification beans of the server pages' examples.
	 */
	@Configuration(proxyBeanMethods = false)
	static class Specifications {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> myTools() {
			return List.of(McpServerFeatures.SyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder("spec-tool").description("A tool").inputSchema(Map.of("type", "object")).build())
				.callHandler((exchange, request) -> CallToolResult.builder().addTextContent("from a specification").build())
				.build());
		}

		@Bean
		List<McpServerFeatures.SyncResourceSpecification> myResources() {
			var systemInfoResource = McpSchema.Resource.builder("system://info", "system-info").build();
			return List.of(new McpServerFeatures.SyncResourceSpecification(systemInfoResource,
					(exchange, request) -> McpSchema.ReadResourceResult
						.builder(List.of(TextResourceContents.builder(request.uri(), "{\"os\":\"test\"}")
							.mimeType("application/json")
							.build()))
						.build()));
		}

		@Bean
		List<McpServerFeatures.SyncPromptSpecification> myPrompts() {
			var prompt = McpSchema.Prompt.builder("greeting")
				.description("A friendly greeting prompt")
				.arguments(List.of(McpSchema.PromptArgument.builder("name").description("The name to greet").required(true).build()))
				.build();
			return List.of(new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, getPromptRequest) -> {
				String nameArgument = (String) getPromptRequest.arguments().get("name");
				if (nameArgument == null) {
					nameArgument = "friend";
				}
				var userMessage = PromptMessage
					.builder(Role.USER, TextContent.builder("Hello " + nameArgument + "! How can I assist you today?").build())
					.build();
				return McpSchema.GetPromptResult.builder(List.of(userMessage)).description("A personalized greeting message").build();
			}));
		}

		@Bean
		List<McpServerFeatures.SyncCompletionSpecification> myCompletions() {
			return List.of(new McpServerFeatures.SyncCompletionSpecification(
					McpSchema.PromptReference.builder("greeting").title("Completes names").build(),
					(exchange, request) -> new McpSchema.CompleteResult(
							new McpSchema.CompleteResult.CompleteCompletion(List.of("python", "pytorch", "pyside"), 10, true))));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class StatelessSpecifications {

		@Bean
		List<McpStatelessServerFeatures.SyncToolSpecification> myTools() {
			return List.of(McpStatelessServerFeatures.SyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder("stateless-spec-tool").description("A tool").inputSchema(Map.of("type", "object")).build())
				.callHandler((transportContext, request) -> CallToolResult.builder().addTextContent("stateless").build())
				.build());
		}

	}

	/**
	 * {@code ToolCallback} beans: a single one, a list, and a provider, two of them with one
	 * name.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ToolCallbacks {

		@Bean
		ToolCallback special() {
			return FunctionToolCallback.builder("special", () -> "first").description("The first").build();
		}

		@Bean
		List<ToolCallback> moreTools() {
			return List.of(FunctionToolCallback.builder("special", () -> "second").description("The second").build(),
					FunctionToolCallback.builder("logo", () -> Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }))
						.description("The shop's logo")
						.build());
		}

		@Bean
		ToolCallbackProvider notifier() {
			return MethodToolCallbackProvider.builder().toolObjects(new Notifier()).build();
		}

	}

	static class Notifier {

		@Tool(description = "Logs a message to the client")
		String notify(ToolContext toolContext) {
			McpSyncServerExchange exchange = McpToolUtils.getMcpExchange(toolContext).orElseThrow();
			exchange.loggingNotification(LoggingMessageNotification.builder(LoggingLevel.INFO, "sent through the exchange")
				.logger("notifier")
				.build());
			return "sent";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class LogCollector {

		final BlockingQueue<LoggingMessageNotification> logs = new LinkedBlockingQueue<>();

		@McpLogging(clients = "server")
		public void log(LoggingMessageNotification notification) {
			this.logs.add(notification);
		}

	}

	/**
	 * The transport customizers of the page's example, which copy the
	 * {@code Authorization} header into the transport context.
	 */
	@Configuration(proxyBeanMethods = false)
	static class Authorization {

		@Bean
		McpServerCustomizer<WebMvcStreamableServerTransportProvider.Builder> webMvcTransportCustomizer() {
			return (builder) -> builder.contextExtractor((serverRequest) -> context(serverRequest.headers().firstHeader("Authorization")));
		}

		@Bean
		McpServerCustomizer<WebFluxStreamableServerTransportProvider.Builder> webFluxTransportCustomizer() {
			return (builder) -> builder.contextExtractor((serverRequest) -> context(serverRequest.headers().firstHeader("Authorization")));
		}

		private static McpTransportContext context(String authorization) {
			return (authorization != null) ? McpTransportContext.create(Map.of("authorization", authorization))
					: McpTransportContext.EMPTY;
		}

		@Bean
		WhoAmI whoAmI() {
			return new WhoAmI();
		}

	}

	public static class WhoAmI {

		@McpTool(description = "Tells the Authorization header of the request")
		public String whoAmI(McpSyncRequestContext requestContext) {
			return String.valueOf(requestContext.transportContext().get("authorization"));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class BearerToken {

		@Bean
		io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer bearerToken() {
			return (builder, method, endpoint, body, context) -> builder.header("Authorization", "Bearer alice-token");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ServerCustomizers {

		@Bean
		McpServerCustomizer<McpServer.SyncSpecification<?>> exact() {
			return (spec) -> spec.instructions("from the exact customizer");
		}

		@Bean
		@org.springframework.core.annotation.Order(1)
		McpServerCustomizer<McpServer.StreamableSyncSpecification> streamable() {
			return (spec) -> spec.instructions("from the streamable customizer");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OneSession {

		@Bean
		McpServerCustomizer<WebMvcStreamableServerTransportProvider.Builder> sessionLimits() {
			return (builder) -> builder.maxSessions(1).sessionIdleTimeout(java.time.Duration.ofMinutes(30));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AnyOrigin {

		@Bean
		io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator anyOrigin() {
			return io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator.NOOP;
		}

	}

}
