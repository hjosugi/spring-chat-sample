package io.github.hjosugi.chatsample.mcp;

import java.util.List;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;

import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientModernAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.ai.mcp.client.webflux.autoconfigure.SseWebFluxTransportAutoConfiguration;
import org.springframework.ai.mcp.client.webflux.autoconfigure.StreamableHttpWebFluxTransportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;

/**
 * The MCP client as the starters configure it. Each starter brings its own transports,
 * and putting both on one context gives every connection two clients, so a test takes
 * one of them.
 */
final class McpClients {

	static final String STREAMABLE = "spring.ai.mcp.client.streamable-http.connections.";

	static final String SSE = "spring.ai.mcp.client.sse.connections.";

	static final String STDIO = "spring.ai.mcp.client.stdio.connections.";

	private McpClients() {
	}

	/**
	 * {@code spring-ai-starter-mcp-client}: STDIO, and HTTP through the JDK
	 * {@code HttpClient}.
	 */
	@SuppressWarnings("removal")
	static ApplicationContextRunner httpClient() {
		return common().withConfiguration(AutoConfigurations.of(StreamableHttpHttpClientTransportAutoConfiguration.class,
				SseHttpClientTransportAutoConfiguration.class));
	}

	/**
	 * {@code spring-ai-starter-mcp-client-webflux}: STDIO, and HTTP through the WebFlux
	 * {@code WebClient}.
	 */
	@SuppressWarnings("removal")
	static ApplicationContextRunner webClient() {
		return common().withConfiguration(AutoConfigurations.of(StreamableHttpWebFluxTransportAutoConfiguration.class,
				SseWebFluxTransportAutoConfiguration.class));
	}

	/**
	 * The client of a starter.
	 */
	static ApplicationContextRunner of(Starter starter) {
		return (starter == Starter.HTTP_CLIENT) ? httpClient() : webClient();
	}

	private static ApplicationContextRunner common() {
		return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(McpClientAutoConfiguration.class,
				McpClientModernAutoConfiguration.class, McpToolCallbackAutoConfiguration.class,
				McpClientAnnotationScannerAutoConfiguration.class, StdioTransportAutoConfiguration.class));
	}

	/**
	 * The synchronous clients, one for each legacy connection.
	 */
	@SuppressWarnings("unchecked")
	static List<McpSyncClient> syncClients(ApplicationContext context) {
		return (List<McpSyncClient>) context.getBeanProvider(ResolvableType.forClassWithGenerics(List.class, McpSyncClient.class))
			.getObject();
	}

	/**
	 * The only synchronous client.
	 */
	static McpSyncClient syncClient(ApplicationContext context) {
		List<McpSyncClient> clients = syncClients(context);
		if (clients.size() != 1) {
			throw new IllegalStateException("Expected one client but there are " + clients.size());
		}
		return clients.get(0);
	}

	/**
	 * The asynchronous clients, one for each legacy connection.
	 */
	@SuppressWarnings("unchecked")
	static List<McpAsyncClient> asyncClients(ApplicationContext context) {
		return (List<McpAsyncClient>) context
			.getBeanProvider(ResolvableType.forClassWithGenerics(List.class, McpAsyncClient.class))
			.getObject();
	}

	/**
	 * The starter, which decides the HTTP client of the legacy transports.
	 */
	enum Starter {

		HTTP_CLIENT, WEB_CLIENT

	}

}
