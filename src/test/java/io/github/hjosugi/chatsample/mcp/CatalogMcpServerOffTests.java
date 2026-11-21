package io.github.hjosugi.chatsample.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shop with its own properties: Spring AI's MCP server starter is on the classpath,
 * and still there is no MCP server and no new endpoint. {@code /mcp} is a path like any
 * other, behind the login.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.anthropic.api-key=test-key", "spring.ai.model.chat=anthropic",
				"spring.ai.anthropic.chat.model=claude-haiku-5-5" })
class CatalogMcpServerOffTests {

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ApplicationContext context;

	@Test
	void withoutTheSwitchThereIsNoMcpServer() {
		assertThat(this.context.getBeanNamesForType(McpSyncServer.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(McpStatelessSyncServer.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(RouterFunction.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(CatalogMcpServer.class)).isEmpty();
	}

	@Test
	void theMcpPathsAnswerAsAPathThatDoesNotExist() throws Exception {
		HttpResponse<String> nothing = post("/no-such-page");
		assertThat(nothing.statusCode()).isNotEqualTo(200);

		for (String path : new String[] { "/mcp", "/sse", "/mcp/message" }) {
			HttpResponse<String> response = post(path);
			assertThat(response.statusCode()).as(path).isEqualTo(nothing.statusCode());
			assertThat(location(response)).as(path).isEqualTo(location(nothing));
		}
	}

	private static String location(HttpResponse<String> response) {
		// Without the session id, which differs from one response to the next.
		return response.headers().firstValue("Location").orElse("").replaceAll(";jsessionid=.*", "");
	}

	private HttpResponse<String> post(String path) throws Exception {
		return HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

}
