package io.github.hjosugi.chatsample.mcp;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

/**
 * The common properties of the client starter, as the server sees their effect.
 */
class ClientPropertiesTests {

	private static Server server;

	@BeforeAll
	static void startServer() {
		server = McpServers.webMvc(Introspection.class, "spring.ai.mcp.server.protocol=STREAMABLE");
	}

	@AfterAll
	static void stopServer() {
		server.bean(Introspection.class).release.countDown();
		server.close();
	}

	private static ApplicationContextRunner client() {
		return McpClients.httpClient().withPropertyValues(McpClients.STREAMABLE + "shop.url=" + server.url());
	}

	@Test
	void theNameAndVersionOfTheClientReachTheServerWithTheConnectionsName() {
		client().withPropertyValues("spring.ai.mcp.client.name=my-mcp-client", "spring.ai.mcp.client.version=2.0.0")
			.run((context) -> assertThat(
					ServerProtocolsTests.text(McpClients.syncClient(context).callTool(new CallToolRequest("whoAmI", Map.of()))))
				.isEqualTo("my-mcp-client - shop 2.0.0 shop"));
		client().run((context) -> assertThat(
				ServerProtocolsTests.text(McpClients.syncClient(context).callTool(new CallToolRequest("whoAmI", Map.of()))))
			.isEqualTo("spring-ai-mcp-client - shop 1.0.0 shop"));
	}

	@Test
	void aClientThatIsNotInitializedAtStartupInitializesOnItsFirstRequest() {
		client().withPropertyValues("spring.ai.mcp.client.initialized=false").run((context) -> {
			McpSyncClient client = McpClients.syncClient(context);
			assertThat(client.isInitialized()).isFalse();

			assertThat(client.listTools().tools()).isNotEmpty();

			assertThat(client.isInitialized()).isTrue();
		});
		client().run((context) -> assertThat(McpClients.syncClient(context).isInitialized()).isTrue());
	}

	@Test
	void aDisabledClientHasNoClientsAndNoTools() {
		client().withPropertyValues("spring.ai.mcp.client.enabled=false").run((context) -> {
			assertThat(context).doesNotHaveBean("mcpSyncClients");
			assertThat(context).doesNotHaveBean(SyncMcpToolCallbackProvider.class);
		});
	}

	@Test
	void theAsyncTypeMakesAsyncClients() {
		client().withPropertyValues("spring.ai.mcp.client.type=ASYNC").run((context) -> {
			assertThat(context).doesNotHaveBean("mcpSyncClients");
			McpAsyncClient client = McpClients.asyncClients(context).get(0);
			assertThat(client.listTools().block().tools()).isNotEmpty();
			assertThat(context).hasSingleBean(AsyncMcpToolCallbackProvider.class);
		});
	}

	@Test
	void aRequestThatTakesLongerThanTheRequestTimeoutFails() {
		client().withPropertyValues("spring.ai.mcp.client.request-timeout=1s").run((context) -> {
			McpSyncClient client = McpClients.syncClient(context);

			assertThatException().isThrownBy(() -> client.callTool(new CallToolRequest("wait", Map.of())))
				.withStackTraceContaining("Timeout");
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class Introspection {

		final CountDownLatch release = new CountDownLatch(1);

		@Bean
		Tools tools() {
			return new Tools(this.release);
		}

	}

	public static class Tools {

		private final CountDownLatch release;

		Tools(CountDownLatch release) {
			this.release = release;
		}

		@McpTool(description = "Tells who the client is")
		public String whoAmI(McpSyncRequestContext context) {
			return context.clientInfo().name() + " " + context.clientInfo().version() + " " + context.clientInfo().title();
		}

		@McpTool(description = "Waits until the test is over")
		public String wait(McpSyncRequestContext context) throws InterruptedException {
			return this.release.await(10, TimeUnit.SECONDS) ? "released" : "waited";
		}

	}

}
