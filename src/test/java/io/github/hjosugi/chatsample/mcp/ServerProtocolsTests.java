package io.github.hjosugi.chatsample.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.hjosugi.chatsample.mcp.McpClients.Starter;
import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.github.hjosugi.chatsample.mcp.McpServers.Stack;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.modern.client.McpClient;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxSseServerTransportProvider;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same annotated beans on every protocol and web stack that the server pages name,
 * reached by the client of each starter: what the client lists, reads, gets, completes
 * and calls, and what the server says about itself.
 */
class ServerProtocolsTests {

	private static final String[] ABOUT = { "spring.ai.mcp.server.name=brewing", "spring.ai.mcp.server.version=2.0.0",
			"spring.ai.mcp.server.instructions=Ask about brewing" };

	static Stream<Arguments> legacyProtocols() {
		List<Arguments> arguments = new ArrayList<>();
		for (String protocol : List.of("SSE", "STREAMABLE", "STATELESS")) {
			for (Stack stack : Stack.values()) {
				for (Starter starter : Starter.values()) {
					arguments.add(Arguments.of(protocol, stack, starter));
				}
			}
		}
		return arguments.stream();
	}

	@ParameterizedTest(name = "{0} on {1}, client of {2}")
	@MethodSource("legacyProtocols")
	void aClientSeesAndUsesWhatTheServerOffers(String protocol, Stack stack, Starter starter) {
		try (Server server = McpServers.start(stack, Brewing.Beans.class, with(ABOUT, "spring.ai.mcp.server.protocol=" + protocol))) {
			String connection = (protocol.equals("SSE") ? McpClients.SSE : McpClients.STREAMABLE) + "brewing.url=" + server.url();
			McpClients.of(starter).withPropertyValues(connection).run((context) -> {
				McpSyncClient client = McpClients.syncClient(context);

				assertThat(client.getServerInfo().name()).isEqualTo("brewing");
				assertThat(client.getServerInfo().version()).isEqualTo("2.0.0");
				assertThat(client.getServerInstructions()).isEqualTo("Ask about brewing");
				assertBrewing(client);
				// A server without sessions cannot tell a client that a list changed.
				boolean listChanged = !protocol.equals("STATELESS");
				assertThat(client.getServerCapabilities().tools().listChanged()).isEqualTo(listChanged);
				assertThat(client.getServerCapabilities().prompts().listChanged()).isEqualTo(listChanged);
				assertThat(client.getServerCapabilities().resources().listChanged()).isEqualTo(listChanged);
			});
		}
	}

	@ParameterizedTest
	@EnumSource(Stack.class)
	void withoutTheProtocolPropertyAServerSpeaksTheDeprecatedSse(Stack stack) {
		try (Server server = McpServers.start(stack, Brewing.Beans.class)) {
			assertThat(server.context()
				.getBeanNamesForType((stack == Stack.WEBMVC) ? WebMvcSseServerTransportProvider.class
						: WebFluxSseServerTransportProvider.class)).hasSize(1);
			McpClients.httpClient().withPropertyValues(McpClients.SSE + "brewing.url=" + server.url()).run((context) -> {
				assertBrewing(McpClients.syncClient(context));
			});
		}
	}

	@ParameterizedTest
	@EnumSource(Stack.class)
	void aModernClientSeesAndUsesWhatAModernServerOffers(Stack stack) {
		try (Server server = McpServers.start(stack, Brewing.Beans.class, with(ABOUT, "spring.ai.mcp.server.protocol=MODERN"))) {
			var client = McpClient.sync(HttpClientMcpTransport.builder(server.url() + "/mcp").build()).build();

			var discovered = client.discover();
			// server/discover has no field for the server's name: it travels in _meta.
			assertThat(discovered.meta()).containsEntry("io.modelcontextprotocol/serverInfo",
					Map.of("name", "brewing", "version", "2.0.0"));
			assertThat(discovered.instructions()).isEqualTo("Ask about brewing");
			assertThat(discovered.supportedVersions()).containsExactly("2026-07-28");
			assertThat(client.tools().list().tools()).extracting((tool) -> tool.name()).containsExactly("water");
			assertThat(client.tool("water").arguments(Map.of("coffee", 20)).call().content())
				.singleElement()
				.extracting((content) -> ((io.modelcontextprotocol.modern.McpSchema.TextContent) content).text())
				.isEqualTo("320");
			assertThat(client.resources().list().resources()).extracting((resource) -> resource.uri())
				.containsExactly("shop://hours");
			assertThat(client.resourceTemplates().list().resourceTemplates())
				.extracting((template) -> template.uriTemplate())
				.containsExactly("recipe://{method}");
			assertThat(client.resource("recipe://espresso").read().contents()).singleElement()
				.extracting((contents) -> ((io.modelcontextprotocol.modern.McpSchema.TextResourceContents) contents).text())
				.isEqualTo("18 g in, 36 g out, 28 seconds");
			assertThat(client.prompts().list().prompts()).extracting((prompt) -> prompt.name())
				.containsExactly("brew-guide");
			assertThat(client.prompt("brew-guide").arguments(Map.of("method", "espresso")).get().messages())
				.singleElement()
				.extracting((message) -> ((io.modelcontextprotocol.modern.McpSchema.TextContent) message.content()).text())
				.isEqualTo("Explain how to brew espresso");
			assertThat(client
				.completion(new io.modelcontextprotocol.modern.McpSchema.PromptReference("brew-guide"), "method", "e")
				.complete()
				.completion()
				.values()).containsExactly("espresso");
			assertThat(client
				.completion(new io.modelcontextprotocol.modern.McpSchema.ResourceReference("recipe://{method}"),
						"method", "f")
				.complete()
				.completion()
				.values()).containsExactly("french-press");

			// The client of either starter speaks it on a connection that says so.
			for (Starter starter : Starter.values()) {
				McpClients.of(starter)
					.withPropertyValues(McpClients.STREAMABLE + "brewing.url=" + server.url(),
							McpClients.STREAMABLE + "brewing.protocol=MODERN")
					.run((context) -> {
						ToolCallback[] tools = context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();
						assertThat(tools).extracting((tool) -> tool.getToolDefinition().name())
							.containsExactly("brewing_water");
						assertThat(tools[0].call("{\"coffee\": 20, \"ratio\": 15}")).isEqualTo("[{\"text\":\"300\"}]");
					});
			}
		}
	}

	@Test
	void aLegacyClientCannotTalkToAModernServer() {
		try (Server server = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=MODERN")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "brewing.url=" + server.url())
				.run((context) -> assertThat(context).hasFailed());
		}
	}

	static void assertBrewing(McpSyncClient client) {
		assertThat(client.listTools().tools()).filteredOn((tool) -> tool.name().equals("water")).singleElement().satisfies((tool) -> {
			assertThat(tool.name()).isEqualTo("water");
			assertThat(tool.description()).isEqualTo("Grams of water for grams of coffee at a brew ratio");
			assertThat(tool.inputSchema()).containsEntry("required", List.of("coffee"));
		});
		assertThat(text(client.callTool(new CallToolRequest("water", Map.of("coffee", 20))))).isEqualTo("320");
		assertThat(client.listResources().resources()).extracting(McpSchema.Resource::uri).containsExactly("shop://hours");
		assertThat(client.listResourceTemplates().resourceTemplates()).extracting(McpSchema.ResourceTemplate::uriTemplate)
			.containsExactly("recipe://{method}");
		assertThat(client.readResource(new ReadResourceRequest("recipe://espresso")).contents()).singleElement()
			.extracting((contents) -> ((TextResourceContents) contents).text())
			.isEqualTo("18 g in, 36 g out, 28 seconds");
		assertThat(client.listPrompts().prompts()).singleElement().satisfies((prompt) -> {
			assertThat(prompt.name()).isEqualTo("brew-guide");
			assertThat(prompt.arguments()).singleElement().satisfies((argument) -> {
				assertThat(argument.name()).isEqualTo("method");
				assertThat(argument.required()).isTrue();
			});
		});
		assertThat(client.getPrompt(new GetPromptRequest("brew-guide", Map.of("method", "espresso"))).messages())
			.singleElement()
			.extracting((message) -> ((TextContent) message.content()).text())
			.isEqualTo("Explain how to brew espresso");
		assertThat(client
			.completeCompletion(new CompleteRequest(new PromptReference("brew-guide"),
					new CompleteRequest.CompleteArgument("method", "e")))
			.completion()
			.values()).containsExactly("espresso");
		assertThat(client
			.completeCompletion(new CompleteRequest(new ResourceReference("recipe://{method}"),
					new CompleteRequest.CompleteArgument("method", "f")))
			.completion()
			.values()).containsExactly("french-press");
	}

	static String text(McpSchema.CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	static String[] with(String[] properties, String... more) {
		return Stream.concat(Stream.of(properties), Stream.of(more)).toArray(String[]::new);
	}

}
