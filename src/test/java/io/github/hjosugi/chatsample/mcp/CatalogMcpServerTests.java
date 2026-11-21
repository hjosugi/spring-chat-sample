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
 * The shop with {@code shop.mcp.enabled=true}: Spring AI's MCP client reaches the
 * catalog without signing in, and nothing but the catalog. The rest of the shop still
 * wants a login.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "shop.mcp.enabled=true", "spring.ai.anthropic.api-key=test-key",
				"spring.ai.model.chat=anthropic", "spring.ai.anthropic.chat.model=claude-haiku-5-5",
				// The tests also carry the WebFlux server, whose auto-configuration does not
				// look at the web application type: with both, the server has two transports.
				"spring.autoconfigure.exclude=org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration" })
class CatalogMcpServerTests {

	@Value("${local.server.port}")
	private int port;

	@Test
	void anMcpClientReadsTheCatalogWithoutSigningIn() {
		client().run((context) -> {
			McpSyncClient catalog = McpClients.syncClient(context);

			assertThat(catalog.getServerInfo().name()).isEqualTo("shop-catalog");
			assertThat(catalog.listTools().tools()).extracting(McpSchema.Tool::name)
				.containsExactlyInAnyOrder("products", "product");
			assertThat(catalog.listTools().tools()).allSatisfy(
					(tool) -> assertThat(tool.annotations().readOnlyHint()).as(tool.name() + " only reads").isTrue());
			assertThat(ServerProtocolsTests.text(catalog.callTool(new CallToolRequest("products", Map.of()))))
				.contains("\"id\":\"grinder\"", "\"price\":89.00", "\"inStock\":false");
			assertThat(ServerProtocolsTests.text(catalog.callTool(new CallToolRequest("product", Map.of("id", "kettle")))))
				.isEqualTo("Gooseneck kettle (kettle): 59.00 EUR, out of stock");
			assertThat(catalog.readResource(new ReadResourceRequest("catalog://products/dripper")).contents())
				.singleElement()
				.satisfies((contents) -> {
					assertThat(contents.mimeType()).isEqualTo("application/json");
					assertThat(((TextResourceContents) contents).text())
						.isEqualTo("{\"id\":\"dripper\",\"name\":\"Ceramic dripper\",\"price\":24.00,\"inStock\":true}");
				});
			assertThat(catalog
				.completeCompletion(new CompleteRequest(new ResourceReference("catalog://products/{id}"),
						new CompleteRequest.CompleteArgument("id", "g")))
				.completion()
				.values()).containsExactly("grinder");
		});
	}

	@Test
	void theCatalogServesNoToolOfTheApplicationAndNoOrder() {
		client().run((context) -> {
			McpSyncClient catalog = McpClients.syncClient(context);

			// OrderBook below is a ToolCallbackProvider bean, which the server would expose by default.
			assertThat(catalog.listTools().tools()).extracting(McpSchema.Tool::name).doesNotContain("myOrders");
			assertThat(ServerProtocolsTests.text(catalog.callTool(new CallToolRequest("products", Map.of()))))
				.doesNotContain("A-1001", "B-2001");
			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> catalog.callTool(new CallToolRequest("myOrders", Map.of())))
				.satisfies((error) -> assertThat(error.getJsonRpcError().data()).isEqualTo("Tool not found: myOrders"));
		});
	}

	@Test
	void aBrowserPageCannotCallTheCatalog() throws Exception {
		assertThat(post(Map.of()).statusCode()).isEqualTo(200);
		assertThat(post(Map.of("Origin", "http://localhost:" + this.port)).statusCode()).isEqualTo(403);
		assertThat(post(Map.of("Origin", "http://attacker.example")).statusCode()).isEqualTo(403);
	}

	@Test
	void theRestOfTheShopStillWantsALogin() throws Exception {
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
			.withPropertyValues(McpClients.STREAMABLE + "shop.url=http://localhost:" + this.port);
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
	static class OrderBookAsAToolCallbackProvider {

		@Bean
		ToolCallbackProvider orderBook() {
			return MethodToolCallbackProvider.builder().toolObjects(new OrderBook()).build();
		}

	}

	static class OrderBook {

		@Tool(description = "Lists every order of the shop")
		String myOrders() {
			return "A-1001, B-2001";
		}

	}

}
