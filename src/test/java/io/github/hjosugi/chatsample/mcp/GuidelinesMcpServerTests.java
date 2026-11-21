package io.github.hjosugi.chatsample.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The agent with {@code agent.mcp.enabled=true}: Spring AI's MCP client reaches the
 * coding guidelines without signing in, and nothing but the guidelines. The rest of the
 * agent, its workspaces and its tools, still wants a login.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "agent.mcp.enabled=true", "spring.ai.anthropic.api-key=test-key",
				"spring.ai.model.chat=anthropic", "spring.ai.anthropic.chat.model=claude-haiku-5-5",
				// The tests also carry the WebFlux server, whose auto-configuration does not
				// look at the web application type: with both, the server has two transports.
				"spring.autoconfigure.exclude=org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration" })
class GuidelinesMcpServerTests {

	@Value("${local.server.port}")
	private int port;

	@Test
	void anMcpClientReadsTheGuidelinesWithoutSigningIn() {
		client().run((context) -> {
			McpSyncClient guidelines = McpClients.syncClient(context);

			assertThat(guidelines.getServerInfo().name()).isEqualTo("coding-guidelines");
			assertThat(guidelines.listTools().tools()).extracting(McpSchema.Tool::name)
				.containsExactlyInAnyOrder("topics", "guideline");
			assertThat(guidelines.listTools().tools()).allSatisfy(
					(tool) -> assertThat(tool.annotations().readOnlyHint()).as(tool.name() + " only reads").isTrue());
			assertThat(ServerProtocolsTests.text(guidelines.callTool(new CallToolRequest("topics", Map.of()))))
				.contains("\"id\":\"java\"", "\"title\":\"Testing\"", "\"id\":\"git\"");
			assertThat(ServerProtocolsTests.text(guidelines.callTool(new CallToolRequest("guideline", Map.of("id", "git")))))
				.startsWith("# Git").contains("Write the subject in the imperative");
			assertThat(guidelines.readResource(new ReadResourceRequest("guidelines://topics/testing")).contents())
				.singleElement()
				.satisfies((contents) -> {
					assertThat(contents.mimeType()).isEqualTo("text/markdown");
					assertThat(((TextResourceContents) contents).text()).startsWith("# Testing")
						.contains("A test's name is a sentence about the behavior");
				});
			assertThat(guidelines
				.completeCompletion(new CompleteRequest(new ResourceReference("guidelines://topics/{id}"),
						new CompleteRequest.CompleteArgument("id", "j")))
				.completion()
				.values()).containsExactly("java");
		});
	}

	@Test
	void theGuidelinesServeNoToolOfTheApplicationAndNoFile() {
		client().run((context) -> {
			McpSyncClient guidelines = McpClients.syncClient(context);

			// AgentTools below is a ToolCallbackProvider bean, which the server would expose by default.
			assertThat(guidelines.listTools().tools()).extracting(McpSchema.Tool::name).doesNotContain("readFile");
			assertThat(ServerProtocolsTests.text(guidelines.callTool(new CallToolRequest("topics", Map.of()))))
				.doesNotContain("secret");
			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> guidelines.callTool(new CallToolRequest("readFile", Map.of("path", "notes.txt"))))
				.satisfies((error) -> assertThat(error.getJsonRpcError().data()).isEqualTo("Tool not found: readFile"));
		});
	}

	@Test
	void aBrowserPageCannotCallTheGuidelines() throws Exception {
		assertThat(post(Map.of()).statusCode()).isEqualTo(200);
		assertThat(post(Map.of("Origin", "http://localhost:" + this.port)).statusCode()).isEqualTo(403);
		assertThat(post(Map.of("Origin", "http://attacker.example")).statusCode()).isEqualTo(403);
	}

	@Test
	void theRestOfTheAgentStillWantsALogin() throws Exception {
		HttpResponse<String> home = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/")).build(),
					HttpResponse.BodyHandlers.ofString());

		assertThat(home.statusCode()).isEqualTo(302);
		assertThat(home.headers().firstValue("Location")).get().asString().endsWith("/login");
		assertThat(home.headers().firstValue("Set-Cookie")).isPresent();
		assertThat(post(Map.of()).headers().firstValue("Set-Cookie")).as("the MCP endpoint keeps no session").isEmpty();
	}

	private org.springframework.boot.test.context.runner.ApplicationContextRunner client() {
		return McpClients.httpClient()
			.withPropertyValues(McpClients.STREAMABLE + "agent.url=http://localhost:" + this.port);
	}

	private HttpResponse<String> post(Map<String, String> headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
		headers.forEach(request::header);
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AgentToolsAsAToolCallbackProvider {

		@Bean
		ToolCallbackProvider agentTools() {
			return MethodToolCallbackProvider.builder().toolObjects(new AgentTools()).build();
		}

	}

	static class AgentTools {

		@Tool(description = "Reads a file of a workspace")
		String readFile(String path) {
			return "secret of " + path;
		}

	}

}
