package io.github.hjosugi.chatsample.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.modern.client.McpClient;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.AsyncMcpToolCallback;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.aot.McpHints;
import org.springframework.ai.mcp.modern.InMemorySyncToolRepository;
import org.springframework.ai.mcp.modern.McpModernToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The utilities for programmatic clients and servers: the tool callbacks and providers
 * over MCP clients, {@code McpToolUtils}, {@code McpModernToolUtils} and the native image
 * hints.
 */
class UtilitiesTests {

	@Test
	void aToolCallbackAdaptsTheToolOfASyncOrAnAsyncClient() {
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient().withPropertyValues(McpClients.STREAMABLE + "brewing.url=" + server.url()).run((context) -> {
				McpSyncClient mcpClient = McpClients.syncClient(context);
				McpSchema.Tool mcpTool = mcpClient.listTools().tools().get(0);

				ToolCallback callback = SyncMcpToolCallback.builder().mcpClient(mcpClient).tool(mcpTool).build();
				ToolDefinition definition = callback.getToolDefinition();

				assertThat(definition.name()).isEqualTo("water");
				assertThat(definition.inputSchema()).contains("\"coffee\"");
				assertThat(callback.call("{\"coffee\": 10}")).isEqualTo("[{\"text\":\"160\"}]");

				ToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder().mcpClients(mcpClient).build();
				assertThat(provider.getToolCallbacks()).extracting((tool) -> tool.getToolDefinition().name())
					.containsExactly("water");
				assertThat(SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(mcpClient))).hasSize(1);
				assertThat(McpToolUtils.getToolCallbacksFromSyncClients(List.of(mcpClient))).hasSize(1);
				assertThat(buildProvider(List.of(mcpClient), Set.of("mcp-server")).getToolCallbacks()).hasSize(1);
				assertThat(buildProvider(List.of(mcpClient), Set.of("other")).getToolCallbacks()).isEmpty();
			});
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "brewing.url=" + server.url(), "spring.ai.mcp.client.type=ASYNC")
				.run((context) -> {
					McpAsyncClient mcpClient = McpClients.asyncClients(context).get(0);
					McpSchema.Tool mcpTool = mcpClient.listTools().block().tools().get(0);

					ToolCallback callback = AsyncMcpToolCallback.builder().mcpClient(mcpClient).tool(mcpTool).build();
					assertThat(callback.call("{\"coffee\": 10}")).isEqualTo("[{\"text\":\"160\"}]");
					assertThat(AsyncMcpToolCallbackProvider.builder().mcpClients(mcpClient).build().getToolCallbacks()).hasSize(1);
					assertThat(AsyncMcpToolCallbackProvider.asyncToolCallbacks(List.of(mcpClient)).collectList().block())
						.hasSize(1);
					assertThat(McpToolUtils.getToolCallbacksFromAsyncClients(List.of(mcpClient))).hasSize(1);
				});
		}
	}

	/**
	 * The page's selection of clients by the name of their server.
	 */
	private static ToolCallbackProvider buildProvider(List<McpSyncClient> mcpSyncClients, Set<String> allowedServerNames) {
		List<McpSyncClient> selected = mcpSyncClients.stream()
			.filter((c) -> allowedServerNames.contains(c.getServerInfo().name()))
			.toList();
		return SyncMcpToolCallbackProvider.builder().mcpClients(selected).build();
	}

	@Test
	void toolCallbacksBecomeTheToolSpecificationsOfALegacyServer() {
		try (Server server = McpServers.webMvc(Specifications.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.tool-callback-converter=false")) {
			McpClients.httpClient().withPropertyValues(McpClients.STREAMABLE + "specs.url=" + server.url()).run((context) -> {
				McpSyncClient client = McpClients.syncClient(context);

				assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("opening");
				assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("opening", Map.of()))))
					.isEqualTo("9 to 17");
			});
		}
		assertThat(McpToolUtils.toAsyncToolSpecifications(List.of(opening()))).singleElement()
			.extracting((specification) -> specification.tool().name())
			.isEqualTo("opening");
	}

	@Test
	void toolCallbacksBecomeTheToolsOfAModernServerAndSeeItsRequestContext() {
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=MODERN")) {
			ToolCallback context = FunctionToolCallback.<Void, String>builder("context", (Void input, ToolContext toolContext) -> {
				McpRequestContext requestContext = McpModernToolUtils.getMcpRequestContext(toolContext);
				return (requestContext != null) ? "modern " + requestContext.protocolVersion() : "none";
			}).description("Tells the request context").inputType(Void.class).build();
			ToolCallback logo = FunctionToolCallback.builder("logo", () -> "AQID").description("A logo").build();
			InMemorySyncToolRepository repository = server.bean(InMemorySyncToolRepository.class);

			repository.add(McpModernToolUtils.toSyncTool(context));
			repository.add(McpModernToolUtils.toSyncTool(logo, MimeTypeUtils.IMAGE_PNG));

			var client = McpClient.sync(HttpClientMcpTransport.builder(server.url() + "/mcp").build()).build();
			assertThat(((io.modelcontextprotocol.modern.McpSchema.TextContent) client.tool("context").call().content().get(0)).text())
				.isEqualTo("\"modern 2026-07-28\"");
			assertThat(client.tool("logo").call().content().get(0))
				.isInstanceOfSatisfying(io.modelcontextprotocol.modern.McpSchema.ImageContent.class,
						(image) -> assertThat(image.mimeType()).isEqualTo("image/png"));
		}
		assertThat(McpModernToolUtils.getMcpRequestContext(new ToolContext(Map.of()))).isNull();
		assertThat(McpModernToolUtils.toSyncTools(List.of(opening()))).hasSize(1);
		assertThat(McpModernToolUtils.toAsyncTools(List.of(opening()))).hasSize(1);
	}

	@Test
	void theNativeImageHintsRegisterTheSchemaClasses() {
		RuntimeHints hints = new RuntimeHints();

		new McpHints().registerHints(hints, getClass().getClassLoader());

		assertThat(hints.reflection().getTypeHint(TypeReference.of(McpSchema.CallToolRequest.class))).isNotNull();
		assertThat(hints.reflection().typeHints().count()).isGreaterThan(50);
	}

	static ToolCallback opening() {
		return FunctionToolCallback.builder("opening", () -> "9 to 17").description("Tells when the shop opens").build();
	}

	@Configuration(proxyBeanMethods = false)
	static class Specifications {

		@Bean
		List<McpServerFeatures.SyncToolSpecification> syncToolSpecs() {
			return McpToolUtils.toSyncToolSpecification(List.of(opening()));
		}

	}

}
