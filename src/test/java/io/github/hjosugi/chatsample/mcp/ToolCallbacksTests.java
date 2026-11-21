package io.github.hjosugi.chatsample.mcp;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpProgressToken;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The tool callbacks of the legacy connections: which tools a filter lets through, what
 * they are called when two servers have one, and what a server gets of the tool context.
 */
class ToolCallbacksTests {

	private static Server server;

	@BeforeAll
	static void startServer() {
		server = McpServers.webMvc(Tools.class, "spring.ai.mcp.server.protocol=STREAMABLE",
				"spring.ai.mcp.server.name=brewing", "spring.ai.mcp.server.version=2.0.0");
	}

	@AfterAll
	static void stopServer() {
		server.close();
	}

	private static ApplicationContextRunner twoConnections() {
		return McpClients.httpClient()
			.withPropertyValues(McpClients.STREAMABLE + "first.url=" + server.url(),
					McpClients.STREAMABLE + "second.url=" + server.url());
	}

	private static ApplicationContextRunner oneConnection() {
		return McpClients.httpClient().withPropertyValues(McpClients.STREAMABLE + "brewing.url=" + server.url());
	}

	private static ToolCallback[] tools(org.springframework.context.ApplicationContext context) {
		return context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();
	}

	private static java.util.List<String> names(ToolCallback[] tools) {
		return java.util.Arrays.stream(tools).map((tool) -> tool.getToolDefinition().name()).toList();
	}

	/**
	 * The page's example names a second {@code search} {@code alt_1_search}. The counter
	 * is one for all the tools that are renamed, not one for each name, so the names
	 * depend on the order in which the servers list their tools.
	 */
	@Test
	void theSecondToolOfOneNameGetsACounterPrefixAndHyphensBecomeUnderscores() {
		twoConnections().run((context) -> assertThat(names(tools(context))).containsExactly("echo_meta",
				"experimental_tool", "my_special_tool", "alt_1_echo_meta", "alt_2_experimental_tool",
				"alt_3_my_special_tool"));
	}

	@Test
	void withoutPrefixesTwoServersWithOneToolNameFail() {
		twoConnections().withBean(McpToolNamePrefixGenerator.class, McpToolNamePrefixGenerator::noPrefix)
			.run((context) -> assertThatIllegalStateException().isThrownBy(() -> tools(context))
				.withMessageContaining("Multiple tools with the same name").withMessageContaining("echo-meta"));
	}

	@Test
	void aPrefixGeneratorOfTheApplicationNamesTheTools() {
		oneConnection()
			.withBean(McpToolNamePrefixGenerator.class,
					() -> (connectionInfo, tool) -> connectionInfo.initializeResult().serverInfo().name() + "_v"
							+ connectionInfo.initializeResult().serverInfo().version().replace(".", "_") + "_" + tool.name())
			.run((context) -> assertThat(names(tools(context))).contains("brewing_v2_0_0_echo-meta"));
	}

	/**
	 * The page's filter compares {@code clientInfo().name()} with a connection name. The
	 * auto-configured client is named {@code <spring.ai.mcp.client.name> - <connection>},
	 * and its title is the connection's name.
	 */
	@Test
	void aToolFilterSeesTheConnectionAndTheTool() {
		twoConnections()
			.withBean(McpToolFilter.class, () -> (connectionInfo, tool) -> {
				if (connectionInfo.clientInfo().title().equals("second")) {
					return connectionInfo.clientInfo().name().equals("spring-ai-mcp-client - second")
							&& tool.name().equals("echo-meta");
				}
				return tool.description() == null || !tool.description().contains("experimental");
			})
			.run((context) -> assertThat(names(tools(context))).containsExactlyInAnyOrder("echo_meta", "my_special_tool",
					"alt_1_echo_meta"));
	}

	@Test
	void theToolContextGoesToTheServerAsMetaWithoutTheNullValues() {
		oneConnection().run((context) -> {
			Map<String, Object> toolContext = new HashMap<>(Map.of("user", "alice", "progressToken", "my-progress-token"));
			toolContext.put("nothing", null);

			assertThat(echoMeta(context, toolContext))
				.isEqualTo("[{\"text\":\"token=my-progress-token meta={progressToken=my-progress-token, user=alice}\"}]");
		});
	}

	@Test
	void theNoOpConverterSendsNothingAndAConverterOfTheApplicationWhatItLikes() {
		oneConnection().withBean(ToolContextToMcpMetaConverter.class, ToolContextToMcpMetaConverter::noOp)
			.run((context) -> assertThat(echoMeta(context, Map.of("user", "alice")))
				.isEqualTo("[{\"text\":\"token=null meta={}\"}]"));
		oneConnection().withBean(ToolContextToMcpMetaConverter.class, () -> (toolContext) -> {
			Map<String, Object> metadata = new HashMap<>();
			toolContext.getContext().forEach((key, value) -> metadata.put("app_" + key, value));
			metadata.put("source", "spring-ai");
			return metadata;
		})
			.run((context) -> assertThat(echoMeta(context, Map.of("user", "alice")))
				.isEqualTo("[{\"text\":\"token=null meta={app_user=alice, source=spring-ai}\"}]"));
	}

	private static String echoMeta(org.springframework.context.ApplicationContext context, Map<String, Object> toolContext) {
		ToolCallback echo = java.util.Arrays.stream(tools(context))
			.filter((tool) -> tool.getToolDefinition().name().equals("echo_meta"))
			.findFirst()
			.orElseThrow();
		return echo.call("{}", new ToolContext(toolContext));
	}

	@Configuration(proxyBeanMethods = false)
	static class Tools {

		@Bean
		MetaEcho metaEcho() {
			return new MetaEcho();
		}

	}

	public static class MetaEcho {

		@McpTool(name = "echo-meta", description = "Tells the _meta of the call")
		public String echoMeta(@McpProgressToken String progressToken, McpMeta meta) {
			return "token=" + progressToken + " meta=" + new TreeMap<>((meta.meta() != null) ? meta.meta() : Map.of());
		}

		@McpTool(name = "my-special-tool", description = "Has a hyphen in its name")
		public String special() {
			return "special";
		}

		@McpTool(name = "experimental_tool", description = "Something experimental")
		public String experimental() {
			return "maybe";
		}

	}

}
