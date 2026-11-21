package io.github.hjosugi.chatsample.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.client.transport.StdioMcpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedModernClientMcpTransport;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STDIO, where the client starts the server as a child process: a Spring Boot STDIO
 * server of the test classpath, reached by the client of the starter configured with
 * properties and with a Claude Desktop JSON file, by the SDK client of the page's
 * programmatic example, and on the 2026-07-28 protocol through a transport bean.
 */
class StdioTests {

	private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

	private static List<String> serverArguments(String... more) {
		List<String> arguments = new ArrayList<>(List.of("-Xmx96m", "-XX:TieredStopAtLevel=1", "-cp",
				System.getProperty("java.class.path"), StdioServerApplication.class.getName()));
		arguments.addAll(List.of(more));
		return arguments;
	}

	@Test
	void theClientStartsAStdioServerConfiguredWithPropertiesAndGivesItsEnvironment() {
		List<String> properties = new ArrayList<>(List.of(McpClients.STDIO + "brewing.command=" + JAVA,
				McpClients.STDIO + "brewing.env.SHOP_GREETING=hello"));
		List<String> arguments = serverArguments();
		for (int i = 0; i < arguments.size(); i++) {
			properties.add(McpClients.STDIO + "brewing.args[" + i + "]=" + arguments.get(i));
		}

		McpClients.httpClient().withPropertyValues(properties.toArray(String[]::new)).run((context) -> {
			McpSyncClient client = McpClients.syncClient(context);

			assertThat(client.getServerInfo().name()).isEqualTo("brewing-stdio");
			ServerProtocolsTests.assertBrewing(client);
			assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("env", Map.of("name", "SHOP_GREETING")))))
				.isEqualTo("hello");
		});
	}

	@Test
	void theClientStartsTheServersOfAClaudeDesktopFile(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("mcp-servers.json");
		Files.writeString(file, JsonMapper.shared()
			.writeValueAsString(Map.of("mcpServers", Map.of("brewing", Map.of("command", JAVA, "args", serverArguments(),
					"env", Map.of("SHOP_GREETING", "from the file"))))));

		McpClients.httpClient()
			.withPropertyValues("spring.ai.mcp.client.stdio.servers-configuration=" + file.toUri())
			.run((context) -> {
				McpSyncClient client = McpClients.syncClient(context);

				assertThat(client.getClientInfo().name()).isEqualTo("spring-ai-mcp-client - brewing");
				assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("env", Map.of("name", "SHOP_GREETING")))))
					.isEqualTo("from the file");
			});
	}

	@Test
	void theProgrammaticClientOfThePageStartsAStdioServer() {
		ServerParameters parameters = ServerParameters.builder(JAVA).args(serverArguments()).build();

		McpSyncClient client = McpClient.sync(new StdioClientTransport(parameters, McpJsonDefaults.getMapper()))
			.requestTimeout(Duration.ofSeconds(10))
			.build();
		try {
			client.initialize();
			ServerProtocolsTests.assertBrewing(client);
		}
		finally {
			client.close();
		}
	}

	@Test
	void aModernStdioServerIsReachedThroughATransportBean() {
		McpClients.httpClient().withUserConfiguration(ModernStdio.class).run((context) -> {
			ToolCallback[] tools = context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();

			assertThat(tools).extracting((tool) -> tool.getToolDefinition().name()).containsExactlyInAnyOrder("files_water",
					"files_env");
			assertThat(McpClients.syncClients(context)).isEmpty();
			assertThat(java.util.Arrays.stream(tools)
				.filter((tool) -> tool.getToolDefinition().name().equals("files_water"))
				.findFirst()
				.orElseThrow()
				.call("{\"coffee\": 10}")).isEqualTo("[{\"text\":\"160\"}]");
		});
	}

	/**
	 * The page's example: a transport that spawns the server when the bean is created,
	 * and a list that names it.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ModernStdio {

		@Bean
		StdioMcpClientTransport filesTransport() {
			ServerParameters parameters = ServerParameters.builder(JAVA)
				.args(serverArguments("--spring.ai.mcp.server.protocol=MODERN"))
				.build();
			return StdioMcpClientTransport.builder(parameters).start();
		}

		@Bean
		List<NamedModernClientMcpTransport> modernStdioTransports(StdioMcpClientTransport filesTransport) {
			return List.of(new NamedModernClientMcpTransport("files", filesTransport));
		}

	}

}
