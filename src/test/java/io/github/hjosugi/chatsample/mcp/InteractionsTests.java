package io.github.hjosugi.chatsample.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.github.hjosugi.chatsample.mcp.McpServers.Stack;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.ai.mcp.annotation.McpPromptListChanged;
import org.springframework.ai.mcp.annotation.McpResourceListChanged;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolListChanged;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a server with sessions asks of its client while a tool runs, and what it tells it:
 * logging, progress, ping, elicitation, sampling and roots through
 * {@code McpSyncRequestContext}, and the changes of its lists. The client answers with
 * annotated handler methods, or with the handlers of a client customizer.
 */
class InteractionsTests {

	private static final String CONNECTION = McpClients.STREAMABLE + "barista.url=";

	static Stream<Arguments> statefulServers() {
		return Stream.of(Arguments.of(Stack.WEBMVC, "STREAMABLE"), Arguments.of(Stack.WEBFLUX, "STREAMABLE"),
				Arguments.of(Stack.WEBMVC, "SSE"), Arguments.of(Stack.WEBFLUX, "SSE"));
	}

	@ParameterizedTest(name = "{1} on {0}")
	@MethodSource("statefulServers")
	void aToolLogsReportsProgressPingsElicitsAndSamplesThroughTheAnnotatedHandlers(Stack stack, String protocol) {
		try (Server server = McpServers.start(stack, BaristaServer.class, "spring.ai.mcp.server.protocol=" + protocol)) {
			String connection = (protocol.equals("SSE") ? McpClients.SSE : McpClients.STREAMABLE) + "barista.url="
					+ server.url();
			McpClients.httpClient().withUserConfiguration(AnnotatedHandlers.class)
				.withPropertyValues(connection)
				.run((context) -> {
					AnnotatedHandlers handlers = context.getBean(AnnotatedHandlers.class);

					CallToolResult result = McpClients.syncClient(context)
						.callTool(CallToolRequest.builder("brew")
							.arguments(Map.of("drink", "latte"))
							.progressToken("brew-1")
							.build());

					assertThat(ServerProtocolsTests.text(result)).isEqualTo("latte, large, Enjoy it, no roots");
					assertThat(take(handlers.logs)).satisfies((log) -> {
						assertThat(log.level()).isEqualTo(LoggingLevel.INFO);
						assertThat(log.data()).isEqualTo("Brewing latte");
					});
					assertThat(take(handlers.progress)).satisfies((progress) -> {
						assertThat(progress.progressToken()).isEqualTo("brew-1");
						assertThat(progress.progress()).isEqualTo(0.5);
						assertThat(progress.total()).isEqualTo(1.0);
						assertThat(progress.message()).isEqualTo("Grinding");
					});
					assertThat(take(handlers.progress).message()).isEqualTo("Done");
					assertThat(handlers.elicitations).singleElement().satisfies((request) -> {
						assertThat(request.message()).isEqualTo("Which size?");
						assertThat(((ElicitFormRequest) request).requestedSchema()).extractingByKey("properties")
							.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
							.containsKey("size");
					});
					assertThat(ScriptedChatModel.lastText(handlers.model.lastPrompt()))
						.isEqualTo("Write a note for a latte");
					assertThat(handlers.othersLogs).isEmpty();
				});
		}
	}

	@Test
	void theHandlersOfAClientCustomizerAnswerAsWell() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(CONNECTION + server.url(), "spring.ai.mcp.client.annotation-scanner.enabled=false")
				.withUserConfiguration(CustomizerHandlers.class)
				.run((context) -> {
					CustomizerHandlers handlers = context.getBean(CustomizerHandlers.class);

					CallToolResult result = McpClients.syncClient(context)
						.callTool(CallToolRequest.builder("brew")
							.arguments(Map.of("drink", "flat white"))
							.progressToken("brew-2")
							.build());

					assertThat(ServerProtocolsTests.text(result))
						.isEqualTo("flat white, small, From the customizer, file:///srv/customized");
					assertThat(take(handlers.logs).data()).isEqualTo("Brewing flat white");
					assertThat(take(handlers.progress).progressToken()).isEqualTo("brew-2");
				});
		}
	}

	@Test
	void theIndividualParametersOfALoggingAndAProgressHandler() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(McpClients.STREAMABLE + "params.url=" + server.url())
				.withUserConfiguration(ParameterHandlers.class)
				.run((context) -> {
					ParameterHandlers handlers = context.getBean(ParameterHandlers.class);

					McpClients.syncClient(context)
						.callTool(CallToolRequest.builder("brew")
							.arguments(Map.of("drink", "mocha"))
							.progressToken("brew-3")
							.build());

					assertThat(take(handlers.logs)).isEqualTo("INFO server Brewing mocha");
					assertThat(take(handlers.progress)).isEqualTo("0.5 brew-3 1.0");
				});
		}
	}

	@Test
	void aSyncClientCallsOnlyTheSyncHandlersAndAnAsyncClientOnlyTheAsyncOnes() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			for (String type : List.of("SYNC", "ASYNC")) {
				McpClients.httpClient()
					.withPropertyValues(CONNECTION + server.url(), "spring.ai.mcp.client.type=" + type)
					.withUserConfiguration(SyncAndAsyncHandlers.class)
					.run((context) -> {
						SyncAndAsyncHandlers handlers = context.getBean(SyncAndAsyncHandlers.class);
						CallToolRequest brew = new CallToolRequest("brew", Map.of("drink", "cortado"));
						if (type.equals("SYNC")) {
							McpClients.syncClient(context).callTool(brew);
						}
						else {
							McpClients.asyncClients(context).get(0).callTool(brew).block();
						}

						assertThat(take(handlers.calls)).isEqualTo(type);
						assertThat(handlers.calls).isEmpty();
					});
			}
		}
	}

	@Test
	void theClientHearsWhenTheServerChangesItsTools() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(CONNECTION + server.url())
				.withUserConfiguration(AnnotatedHandlers.class)
				.run((context) -> {
					AnnotatedHandlers handlers = context.getBean(AnnotatedHandlers.class);
					McpSyncServer mcpServer = server.bean(McpSyncServer.class);
					org.springframework.ai.mcp.SyncMcpToolCallbackProvider provider = context
						.getBean(org.springframework.ai.mcp.SyncMcpToolCallbackProvider.class);
					// The provider keeps this listing until the server reports a change.
					assertThat(provider.getToolCallbacks()).hasSize(1);

					mcpServer.addTool(McpServerFeatures.SyncToolSpecification.builder()
						.tool(McpSchema.Tool.builder("descale").description("Descales the machine").inputSchema(Map.of("type", "object")).build())
						.callHandler((exchange, request) -> CallToolResult.builder().addTextContent("Descaled").build())
						.build());
					assertThat(take(handlers.toolLists)).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("brew", "descale");
					assertThat(provider.getToolCallbacks()).extracting((tool) -> tool.getToolDefinition().name())
						.containsExactlyInAnyOrder("brew", "descale");

					mcpServer.addPrompt(new McpServerFeatures.SyncPromptSpecification(
							McpSchema.Prompt.builder("tasting").description("Tasting notes").build(),
							(exchange, request) -> McpSchema.GetPromptResult.builder(List.of()).build()));
					assertThat(take(handlers.promptLists)).extracting(McpSchema.Prompt::name).containsExactly("tasting");

					mcpServer.addResource(new McpServerFeatures.SyncResourceSpecification(
							McpSchema.Resource.builder("shop://menu", "menu").build(),
							(exchange, request) -> McpSchema.ReadResourceResult.builder(List.of()).build()));
					assertThat(take(handlers.resourceLists)).extracting(McpSchema.Resource::uri).containsExactly("shop://menu");
				});
		}
	}

	/**
	 * The page's customizer example sets roots with {@code spec.roots(...)}. With the
	 * annotation scanner on, as it is by default, the auto-configuration has already set
	 * the client's capabilities to those of its handler methods, which have no roots: the
	 * client does not declare its roots, and the server cannot ask for them.
	 */
	@Test
	void withTheAnnotationScannerOnTheRootsOfACustomizerAreNotDeclared() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(CONNECTION + server.url())
				.withUserConfiguration(AnnotatedHandlers.class, Roots.class)
				.run((context) -> {
					McpSyncClient client = McpClients.syncClient(context);

					assertThat(context.getBean(Roots.class).customized).containsExactly("barista");
					assertThat(client.getClientCapabilities().roots()).isNull();
					assertThat(ServerProtocolsTests.text(client.callTool(new CallToolRequest("brew", Map.of("drink", "latte")))))
						.endsWith("no roots");
				});
		}
	}

	@Test
	void theRootsOfACustomizerAreDeclaredWhenItAlsoSetsTheCapabilities() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(CONNECTION + server.url())
				.withUserConfiguration(AnnotatedHandlers.class, Roots.class, Capabilities.class)
				.run((context) -> assertThat(ServerProtocolsTests
					.text(McpClients.syncClient(context).callTool(new CallToolRequest("brew", Map.of("drink", "latte")))))
					.isEqualTo("latte, large, Enjoy it, file:///srv/shop"));
		}
	}

	@Test
	void theServerHearsWhenTheRootsOfTheClientChange() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(CONNECTION + server.url())
				.withUserConfiguration(Roots.class, Capabilities.class)
				.run((context) -> {
					McpClients.syncClient(context).addRoot(new Root("file:///srv/beans", "beans"));

					assertThat(take(server.bean(BaristaServer.class).rootChanges))
						.containsExactlyInAnyOrder("file:///srv/shop", "file:///srv/beans");
				});
		}
	}

	/**
	 * The page says {@code root-change-notification} turns root change notifications on
	 * for all clients, and it is {@code true} by default. The property is bound and read
	 * nowhere: a client with roots does not say that it reports their changes.
	 */
	@Test
	void theRootChangeNotificationPropertyChangesNothing() {
		try (Server server = McpServers.webMvc(BaristaServer.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			McpClients.httpClient()
				.withPropertyValues(CONNECTION + server.url(), "spring.ai.mcp.client.annotation-scanner.enabled=false",
						"spring.ai.mcp.client.root-change-notification=true")
				.withUserConfiguration(Roots.class)
				.run((context) -> {
					McpSyncClient client = McpClients.syncClient(context);
					assertThat(client.getClientCapabilities().roots().listChanged()).isFalse();

					client.addRoot(new Root("file:///srv/beans", "beans"));
					client.ping();

					assertThat(server.bean(BaristaServer.class).rootChanges).isEmpty();
				});
		}
	}

	static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
		T next = queue.poll(10, TimeUnit.SECONDS);
		assertThat(next).as("nothing arrived").isNotNull();
		return next;
	}

	/**
	 * A server with sessions, whose tool needs them, and which hears when the roots of a
	 * client change.
	 */
	@Configuration(proxyBeanMethods = false)
	static class BaristaServer {

		final BlockingQueue<List<String>> rootChanges = new LinkedBlockingQueue<>();

		@Bean
		Barista barista() {
			return new Barista();
		}

		@Bean
		BiConsumer<McpSyncServerExchange, List<Root>> rootsChangeHandler() {
			return (exchange, roots) -> this.rootChanges.add(roots.stream().map(Root::uri).toList());
		}

	}

	public static class Barista {

		@McpTool(name = "brew", description = "Brews a drink and asks the client what it needs on the way")
		public String brew(McpSyncRequestContext context, @McpToolParam(description = "The drink") String drink) {
			context.info("Brewing " + drink);
			context.progress((progress) -> progress.progress(0.5).total(1.0).message("Grinding"));
			context.ping();
			String size = "regular";
			if (context.elicitEnabled()) {
				StructuredElicitResult<Size> answer = context.elicit((elicit) -> elicit.message("Which size?"),
						Size.class);
				size = (answer.action() == ElicitResult.Action.ACCEPT) ? answer.structuredContent().size() : "declined";
			}
			String note = context.sampleEnabled()
					? ((TextContent) context.sample("Write a note for a " + drink).content()).text() : "no note";
			String roots = context.rootsEnabled()
					? context.roots().roots().stream().map(Root::uri).collect(Collectors.joining(" ")) : "no roots";
			context.progress((progress) -> progress.progress(1.0).total(1.0).message("Done"));
			return String.join(", ", drink, size, note, roots);
		}

		public record Size(String size) {
		}

	}

	/**
	 * The client's answers, as annotated methods for the connection named
	 * {@code barista}. Sampling asks a chat model, as the examples do.
	 */
	@Configuration(proxyBeanMethods = false)
	static class AnnotatedHandlers {

		final ScriptedChatModel model = ScriptedChatModel.answering("Enjoy it");

		final BlockingQueue<LoggingMessageNotification> logs = new LinkedBlockingQueue<>();

		final List<LoggingMessageNotification> othersLogs = new CopyOnWriteArrayList<>();

		final BlockingQueue<ProgressNotification> progress = new LinkedBlockingQueue<>();

		final List<ElicitRequest> elicitations = new CopyOnWriteArrayList<>();

		final BlockingQueue<List<McpSchema.Tool>> toolLists = new LinkedBlockingQueue<>();

		final BlockingQueue<List<McpSchema.Prompt>> promptLists = new LinkedBlockingQueue<>();

		final BlockingQueue<List<McpSchema.Resource>> resourceLists = new LinkedBlockingQueue<>();

		@McpLogging(clients = "barista")
		public void log(LoggingMessageNotification notification) {
			this.logs.add(notification);
		}

		@McpLogging(clients = "someone-else")
		public void othersLog(LoggingMessageNotification notification) {
			this.othersLogs.add(notification);
		}

		@McpProgress(clients = "barista")
		public void progress(ProgressNotification notification) {
			this.progress.add(notification);
		}

		@McpSampling(clients = "barista")
		public CreateMessageResult sample(CreateMessageRequest request) {
			String question = ((TextContent) request.messages().get(0).content()).text();
			String answer = ChatClient.create(this.model).prompt(question).call().content();
			return CreateMessageResult.builder(Role.ASSISTANT, answer, "scripted").build();
		}

		@McpElicitation(clients = "barista")
		public ElicitResult elicit(ElicitRequest request) {
			this.elicitations.add(request);
			return (request instanceof ElicitFormRequest) ? new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("size", "large"))
					: new ElicitResult(ElicitResult.Action.DECLINE, null);
		}

		@McpToolListChanged(clients = "barista")
		public void toolsChanged(List<McpSchema.Tool> tools) {
			this.toolLists.add(tools);
		}

		@McpPromptListChanged(clients = "barista")
		public void promptsChanged(List<McpSchema.Prompt> prompts) {
			this.promptLists.add(prompts);
		}

		@McpResourceListChanged(clients = "barista")
		public void resourcesChanged(List<McpSchema.Resource> resources) {
			this.resourceLists.add(resources);
		}

	}

	/**
	 * The roots of the client, set by a client customizer, which also tells which
	 * connections it customized.
	 */
	@Configuration(proxyBeanMethods = false)
	static class Roots {

		final List<String> customized = new CopyOnWriteArrayList<>();

		@Bean
		McpClientCustomizer<McpClient.SyncSpec> roots() {
			return (connection, spec) -> {
				this.customized.add(connection);
				spec.roots(new Root("file:///srv/shop", "shop"));
			};
		}

	}

	/**
	 * Capabilities that declare the roots next to the handler methods' sampling and
	 * elicitation.
	 */
	@Configuration(proxyBeanMethods = false)
	static class Capabilities {

		@Bean
		McpClientCustomizer<McpClient.SyncSpec> capabilities() {
			return (connection, spec) -> spec
				.capabilities(McpSchema.ClientCapabilities.builder().roots(true).sampling().elicitation().build());
		}

	}

	/**
	 * The handlers of the page's client customizer example, instead of annotated methods.
	 */
	@Configuration(proxyBeanMethods = false)
	static class CustomizerHandlers {

		final BlockingQueue<LoggingMessageNotification> logs = new LinkedBlockingQueue<>();

		final BlockingQueue<ProgressNotification> progress = new LinkedBlockingQueue<>();

		@Bean
		McpClientCustomizer<McpClient.SyncSpec> handlers() {
			return (connection, spec) -> spec.roots(new Root("file:///srv/customized", "customized"))
				.sampling((request) -> CreateMessageResult.builder(Role.ASSISTANT, "From the customizer", "none").build())
				.elicitation((request) -> new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("size", "small")))
				.progressConsumer(this.progress::add)
				.loggingConsumer(this.logs::add);
		}

	}

	/**
	 * Handlers that take the parts of a notification instead of the notification.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ParameterHandlers {

		final BlockingQueue<String> logs = new LinkedBlockingQueue<>();

		final BlockingQueue<String> progress = new LinkedBlockingQueue<>();

		@McpLogging(clients = "params")
		public void log(LoggingLevel level, String logger, String data) {
			this.logs.add(level + " " + logger + " " + data);
		}

		@McpProgress(clients = "params")
		public void progress(double progress, String progressToken, String total) {
			this.progress.add(progress + " " + progressToken + " " + total);
		}

	}

	/**
	 * A synchronous and a reactive logging method for the same connection.
	 */
	@Configuration(proxyBeanMethods = false)
	static class SyncAndAsyncHandlers {

		final BlockingQueue<String> calls = new LinkedBlockingQueue<>();

		@McpLogging(clients = "barista")
		public void sync(LoggingMessageNotification notification) {
			this.calls.add("SYNC");
		}

		@McpLogging(clients = "barista")
		public Mono<Void> async(LoggingMessageNotification notification) {
			return Mono.fromRunnable(() -> this.calls.add("ASYNC"));
		}

	}

}
