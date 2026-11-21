package io.github.hjosugi.chatsample.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.mcp.McpServers.Server;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.client.McpClient;
import io.modelcontextprotocol.modern.client.McpSyncClient;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.modern.client.transport.McpHttpRequestCustomizer;
import io.modelcontextprotocol.modern.server.McpSyncNotifier;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.annotation.McpClientCapability;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolListChanged;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpModernRequestContext;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.ai.mcp.modern.InMemorySyncToolRepository;
import org.springframework.ai.mcp.modern.McpTransportContexts;
import org.springframework.ai.mcp.modern.client.McpModernToolFilter;
import org.springframework.ai.mcp.modern.client.McpModernToolNamePrefixGenerator;
import org.springframework.ai.mcp.modern.client.SyncModernMcpChangeListener;
import org.springframework.ai.mcp.modern.client.AsyncModernMcpTransportContextProvider;
import org.springframework.ai.mcp.modern.client.SyncModernMcpClientScope;
import org.springframework.ai.mcp.modern.client.SyncModernMcpToolCallCustomizer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.endsWithToolResult;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

/**
 * The 2026-07-28 ("modern") protocol on both sides: a modern WebMVC server with
 * multi round-trip input, streamed progress, required capabilities, cache hints and
 * runtime changes, and the modern connections of the client starter with their names,
 * filters, client scopes, caller identity, handlers and listen streams.
 */
class ModernMcpTests {

	private static final String KIOSK = McpClients.STREAMABLE + "kiosk.";

	private static Server kiosk;

	@BeforeAll
	static void startServer() {
		kiosk = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.name=kiosk");
	}

	@AfterAll
	static void stopServer() {
		kiosk.close();
	}

	private static ApplicationContextRunner modernClient(Server server, String... properties) {
		return McpClients.httpClient()
			.withPropertyValues(KIOSK + "url=" + server.url(), KIOSK + "protocol=MODERN")
			.withPropertyValues(properties);
	}

	private static List<String> methods(Server server) {
		return server.bean(KioskServer.class).methods;
	}

	private static List<String> names(ToolCallback[] tools) {
		return java.util.Arrays.stream(tools).map((tool) -> tool.getToolDefinition().name()).toList();
	}

	// The client

	@Test
	void modernToolsAreNamedAfterTheirConnectionAndServedNextToLegacyTools() {
		try (Server legacy = McpServers.webMvc(Brewing.Beans.class, "spring.ai.mcp.server.protocol=STREAMABLE")) {
			modernClient(kiosk, McpClients.STREAMABLE + "brewing.url=" + legacy.url()).run((context) -> {
				ToolCallback[] tools = context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks();

				// The method with McpSyncRequestContext needs a session, and is skipped.
				assertThat(names(tools)).containsExactlyInAnyOrder("water", "kiosk_book", "kiosk_index",
						"kiosk_summarize", "kiosk_whoami", "kiosk_long_named_tool_for_the_kiosk");
				assertThat(McpClients.syncClients(context)).as("legacy clients").hasSize(1);
			});
		}
	}

	@Test
	void theServerAsksForInputAndTheElicitationMethodAnswersWithTheToolContextOfTheCall() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> endsWithToolResult(prompt) ? text(lastText(prompt))
				: toolCall("kiosk_book", "{\"guests\": 4}"));

		modernClient(kiosk).withUserConfiguration(ModernHandlers.class).run((context) -> {
			String answer = ChatClient.create(model)
				.prompt("A table for four, please")
				.tools(context.getBean(SyncMcpToolCallbackProvider.class))
				.toolContext(Map.of("user", "alice"))
				.call()
				.content();

			assertThat(answer).isEqualTo("[{\"text\":\"Booked\"}]");
			assertThat(context.getBean(ModernHandlers.class).elicitations).containsExactly("alice: Book a table for 4?");
		});
	}

	@Test
	void theToolContextGoesToTheServerInTheMetaOfTheCall() {
		modernClient(kiosk).run((context) -> {
			ScriptedChatModel model = new ScriptedChatModel((prompt) -> endsWithToolResult(prompt)
					? text(lastText(prompt)) : toolCall("kiosk_whoami", "{}"));

			String answer = ChatClient.create(model)
				.prompt("Who am I?")
				.tools(context.getBean(SyncMcpToolCallbackProvider.class))
				.toolContext(Map.of("user", "bob"))
				.call()
				.content();

			assertThat(answer).isEqualTo("[{\"text\":\"2026-07-28 null bob\"}]");
		});
	}

	@Test
	void aToolCallCustomizerRunsAfterTheAnnotatedHandlersAndCanReplaceThem() {
		modernClient(kiosk).withUserConfiguration(ModernHandlers.class)
			.withBean(SyncModernMcpToolCallCustomizer.class,
					() -> (connection, toolSpec, toolContext) -> connection.name().equals("kiosk")
							? toolSpec.formElicitation((request) -> ElicitResult.decline()) : toolSpec)
			.run((context) -> {
				assertThat(tool(context, "kiosk_book").call("{\"guests\": 2}")).isEqualTo("[{\"text\":\"Not booked\"}]");
				assertThat(context.getBean(ModernHandlers.class).elicitations).isEmpty();
			});
	}

	@Test
	void theAsyncClientTypeTakesTheCallerFromItsTransportContextProvider() {
		modernClient(kiosk, "spring.ai.mcp.client.type=ASYNC")
			.withBean(AsyncModernMcpTransportContextProvider.class,
					() -> (connection) -> McpTransportContext.create(Map.of("user", "carol")))
			.withBean(McpHttpRequestCustomizer.class, () -> McpHttpRequestCustomizer
				.fromSync((request, headers, context) -> headers.set("X-User", String.valueOf(context.get("user")))))
			.run((context) -> {
				ToolCallback[] tools = context.getBean(org.springframework.ai.mcp.AsyncMcpToolCallbackProvider.class)
					.getToolCallbacks();
				assertThat(find(tools, "kiosk_whoami").call("{}")).isEqualTo("[{\"text\":\"2026-07-28 carol null\"}]");
			});
	}

	@Test
	void theProgressOfAModernToolReachesTheProgressMethod() {
		modernClient(kiosk).withUserConfiguration(ModernHandlers.class).run((context) -> {
			ToolCallback index = tool(context, "kiosk_index");

			assertThat(index.call("{\"paths\": [\"beans\", \"cups\"]}")).isEqualTo("[{\"text\":\"Indexed 2 paths\"}]");

			BlockingQueue<ProgressNotification> progress = context.getBean(ModernHandlers.class).progress;
			assertThat(InteractionsTests.take(progress)).satisfies((first) -> {
				assertThat(first.progress()).isEqualTo(1.0);
				assertThat(first.total()).isEqualTo(2.0);
				assertThat(first.message()).isEqualTo("beans");
			});
			assertThat(InteractionsTests.take(progress).message()).isEqualTo("cups");
		});
	}

	/**
	 * An {@code @McpSampling} method serves no modern connection, so the client does not
	 * declare sampling, and the server refuses a tool that requires it before the tool
	 * runs.
	 */
	@Test
	void aToolThatRequiresSamplingIsRefusedForAModernClientEvenWithASamplingMethod() {
		modernClient(kiosk).withUserConfiguration(ModernHandlers.class).run((context) -> {
			assertThatException().isThrownBy(() -> tool(context, "kiosk_summarize").call("{}"))
				.withMessageContaining("Missing required client capability");
			assertThat(kiosk.bean(Kiosk.class).summarized).isFalse();
		});
	}

	@Test
	void aPerRequestConnectionListsTheToolsForEveryRequestUnlessTheListingIsPublic() {
		try (Server privateListing = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.modern.list-ttl=1m");
				Server publicListing = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN",
						"spring.ai.mcp.server.modern.list-ttl=1m", "spring.ai.mcp.server.modern.list-cache-scope=PUBLIC")) {
			modernClient(kiosk).run((context) -> listTwice(context));
			modernClient(privateListing).run((context) -> listTwice(context));
			modernClient(privateListing, KIOSK + "client-scope=SHARED").run((context) -> listTwice(context));
			modernClient(publicListing).run((context) -> listTwice(context));

			assertThat(methods(kiosk)).filteredOn("tools/list"::equals).hasSizeGreaterThanOrEqualTo(2);
			assertThat(methods(privateListing)).filteredOn("tools/list"::equals).as("2 per request, 1 shared").hasSize(3);
			assertThat(methods(publicListing)).filteredOn("tools/list"::equals).hasSize(1);
		}
	}

	@Test
	void aCustomScopeKeepsAClientAndItsPrivateListingForEachUser() {
		try (Server server = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.modern.list-ttl=1m")) {
			modernClient(server, KIOSK + "client-scope=CUSTOM").withUserConfiguration(PerUserScope.class).run((context) -> {
				AtomicReference<String> user = context.getBean(PerUserScope.class).currentUser;
				SyncMcpToolCallbackProvider provider = context.getBean(SyncMcpToolCallbackProvider.class);

				user.set("alice");
				provider.getToolCallbacks();
				provider.getToolCallbacks();
				user.set("bob");
				provider.getToolCallbacks();

				assertThat(methods(server)).filteredOn("tools/list"::equals).hasSize(2);
			});
			modernClient(server, KIOSK + "client-scope=CUSTOM").run((context) -> assertThat(context).hasFailed());
		}
	}

	@Test
	void theCallerReachesTheServerThroughTheTransportContextAndARequestCustomizer() {
		modernClient(kiosk).withUserConfiguration(CallerIdentity.class).run((context) -> {
			AtomicReference<String> user = context.getBean(CallerIdentity.class).currentUser;
			SyncMcpToolCallbackProvider provider = context.getBean(SyncMcpToolCallbackProvider.class);

			user.set("alice");
			ToolCallback forAlice = find(provider.getToolCallbacks(), "kiosk_whoami");
			user.set("bob");
			ToolCallback forBob = find(provider.getToolCallbacks(), "kiosk_whoami");

			// Each request's callbacks keep the client, and so the caller, of their request.
			assertThat(forBob.call("{}")).contains(" bob ");
			assertThat(forAlice.call("{}")).contains(" alice ");
		});
	}

	@Test
	void aModernToolFilterAndNameGeneratorDecideWhatTheToolsAreCalled() {
		modernClient(kiosk).withUserConfiguration(ModernFilterAndNames.class).run((context) -> {
			assertThat(names(context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks()))
				.containsExactlyInAnyOrder("book", "index", "whoami", "long-named-tool-for-the-kiosk");
		});
		modernClient(kiosk).withBean(McpToolFilter.class, () -> (connection, tool) -> true)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("McpModernToolFilter"));
	}

	@Test
	void aServerThatCannotBeReachedLeavesTheToolsOfTheOthersUsable() {
		modernClient(kiosk, McpClients.STREAMABLE + "gone.url=http://localhost:1",
				McpClients.STREAMABLE + "gone.protocol=MODERN")
			.run((context) -> assertThat(names(context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks()))
				.contains("kiosk_book")
				.noneMatch((name) -> name.startsWith("gone_")));
	}

	@Test
	void thePathOfTheUrlIsKeptAndTheEndpointAppended() {
		try (Server server = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.modern.mcp-endpoint=/api/mcp")) {
			McpClients.httpClient()
				.withPropertyValues(KIOSK + "url=" + server.url() + "/api", KIOSK + "protocol=MODERN")
				.run((context) -> assertThat(context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks())
					.isNotEmpty());
		}
	}

	@Test
	void aListeningConnectionHearsWhenTheServerAddsATool() {
		try (Server server = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN")) {
			modernClient(server, KIOSK + "listen=true").withUserConfiguration(ChangeHandlers.class).run((context) -> {
				ChangeHandlers handlers = context.getBean(ChangeHandlers.class);
				handlers.established.get(10, TimeUnit.SECONDS);

				server.bean(InMemorySyncToolRepository.class)
					.add(McpSyncTool.of(McpSchema.Tool.builder("late", Map.of("type", "object")).description("Added at runtime").build(),
							(ctx, request) -> McpSyncResponse
								.result(CallToolResult.builder().addContent(TextContent.builder("late").build()).build())));

				assertThat(InteractionsTests.take(handlers.toolLists)).contains("late");
				assertThat(InteractionsTests.take(handlers.events)).contains("ToolsListChanged");
			});
		}
	}

	@Test
	void resolvingModernToolsOnANonBlockingThreadFails() {
		modernClient(kiosk).run((context) -> {
			SyncMcpToolCallbackProvider provider = context.getBean(SyncMcpToolCallbackProvider.class);

			assertThatException()
				.isThrownBy(() -> Mono.fromCallable(provider::getToolCallbacks).subscribeOn(Schedulers.parallel()).block())
				.withMessageContaining("kiosk");
		});
	}

	// The server

	@Test
	void discoverAndListsCarryTheConfiguredCacheHints() {
		try (Server server = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.modern.discover-ttl=5m", "spring.ai.mcp.server.modern.discover-cache-scope=PUBLIC",
				"spring.ai.mcp.server.modern.list-ttl=1m", "spring.ai.mcp.server.modern.list-cache-scope=PUBLIC")) {
			McpSyncClient client = client(server);

			assertThat(client.discover().ttlMs()).isEqualTo(300_000L);
			assertThat(client.discover().cacheScope()).isEqualTo(CacheScope.PUBLIC);
			assertThat(client.tools().list().ttlMs()).isEqualTo(60_000L);
			assertThat(client.tools().list().cacheScope()).isEqualTo(CacheScope.PUBLIC);
		}
		McpSyncClient client = client(kiosk);
		assertThat(client.discover().ttlMs()).isZero();
		assertThat(client.tools().list().cacheScope()).isEqualTo(CacheScope.PRIVATE);
	}

	@Test
	void aResourceCarriesTheCacheHintsOfItsAnnotation() {
		McpSchema.ReadResourceResult products = client(kiosk).resource("catalog://products").read();

		assertThat(products.ttlMs()).isEqualTo(60_000L);
		assertThat(products.cacheScope()).isEqualTo(CacheScope.PUBLIC);
	}

	@Test
	void aClientWithoutAFormElicitationHandlerIsRefusedTheToolThatAsksForOne() {
		McpSyncClient withHandler = McpClient.sync(HttpClientMcpTransport.builder(kiosk.url() + "/mcp").build())
			.formElicitation((request) -> ElicitResult.decline())
			.build();

		assertThat(((TextContent) withHandler.tool("book").arguments(Map.of("guests", 2)).call().content().get(0)).text())
			.isEqualTo("Not booked");
		assertThatException().isThrownBy(() -> client(kiosk).tool("book").arguments(Map.of("guests", 2)).call())
			.withMessageContaining("Missing required client capability");
	}

	@Test
	void aToolAddedToTheRepositoryAtRuntimeIsListedAndCalled() {
		try (Server server = McpServers.webMvc(KioskServer.class, "spring.ai.mcp.server.protocol=MODERN")) {
			server.bean(InMemorySyncToolRepository.class)
				.add(McpSyncTool.of(McpSchema.Tool.builder("late", Map.of("type", "object")).description("Added at runtime").build(),
						(ctx, request) -> McpSyncResponse
							.result(CallToolResult.builder().addContent(TextContent.builder("late").build()).build())));

			McpSyncClient client = client(server);
			assertThat(client.tools().list().tools()).extracting(McpSchema.Tool::name).contains("late");
			assertThat(((TextContent) client.tool("late").call().content().get(0)).text()).isEqualTo("late");
		}
	}

	@Test
	void aRequestWhoseHeadersDisagreeWithItsBodyIsRejected() {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\",\"params\":{\"_meta\":"
				+ "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";

		var response = RawMcp.post(kiosk.url() + "/mcp", body,
				Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list"));

		assertThat(response.body()).contains("-32020");
	}

	@Test
	void aRepositoryOfTheApplicationShowsEachCallerOnlyWhatItMaySee() {
		try (Server server = McpServers.webMvc(PerCallerTools.class, "spring.ai.mcp.server.protocol=MODERN")) {
			McpSyncClient customer = client(server);
			McpSyncClient admin = McpClient
				.sync(HttpClientMcpTransport.builder(server.url() + "/mcp")
					.requestCustomizer(McpHttpRequestCustomizer.fromSync((request, headers, context) -> headers.set("X-Role", "admin")))
					.build())
				.build();

			assertThat(customer.tools().list().tools()).extracting(McpSchema.Tool::name).containsExactly("menu");
			assertThat(admin.tools().list().tools()).extracting(McpSchema.Tool::name)
				.containsExactlyInAnyOrder("menu", "admin_reset");
			// The client calls only what it has seen listed; with the admin's definition the
			// server answers as for a tool that does not exist.
			assertThatException().isThrownBy(() -> customer.tool("admin_reset").call())
				.withMessageContaining("isn't listed by the server");
			McpSchema.Tool reset = admin.tools().list().tools().stream()
				.filter((tool) -> tool.name().equals("admin_reset"))
				.findFirst()
				.orElseThrow();
			assertThatException().isThrownBy(() -> customer.tool(reset).call()).withMessageContaining("Unknown tool");
		}
	}

	@Test
	void theAsyncServerTypeServesReactiveAndBlockingMethodsAndRejectsAFlux() {
		try (Server server = McpServers.webMvc(AsyncTools.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.type=ASYNC")) {
			McpSyncClient client = client(server);

			assertThat(((TextContent) client.tool("reactive").call().content().get(0)).text()).isEqualTo("from a Mono");
			assertThat(((TextContent) client.tool("blocking").call().content().get(0)).text()).isEqualTo("blocking");
		}
		assertThatException().isThrownBy(() -> McpServers.webMvc(FluxTool.class, "spring.ai.mcp.server.protocol=MODERN",
				"spring.ai.mcp.server.type=ASYNC"));
	}

	@Test
	void aCompletionForAPromptThatDoesNotExistFailsTheStartup() {
		assertThatException().isThrownBy(
				() -> McpServers.webMvc(OrphanCompletion.class, "spring.ai.mcp.server.protocol=MODERN"));
	}

	private static McpSyncClient client(Server server) {
		return McpClient.sync(HttpClientMcpTransport.builder(server.url() + "/mcp").build()).build();
	}

	private static void listTwice(org.springframework.context.ApplicationContext context) {
		SyncMcpToolCallbackProvider provider = context.getBean(SyncMcpToolCallbackProvider.class);
		provider.getToolCallbacks();
		provider.getToolCallbacks();
	}

	private static ToolCallback tool(org.springframework.context.ApplicationContext context, String name) {
		return find(context.getBean(SyncMcpToolCallbackProvider.class).getToolCallbacks(), name);
	}

	private static ToolCallback find(ToolCallback[] tools, String name) {
		return java.util.Arrays.stream(tools)
			.filter((tool) -> tool.getToolDefinition().name().equals(name))
			.findFirst()
			.orElseThrow();
	}

	/**
	 * The modern server, which records the {@code Mcp-Method} header of each request and
	 * puts the {@code X-User} header into the transport context.
	 */
	@Configuration(proxyBeanMethods = false)
	static class KioskServer {

		final List<String> methods = new CopyOnWriteArrayList<>();

		@Bean
		Kiosk kiosk() {
			return new Kiosk();
		}

		@Bean
		McpTransportContextExtractor<ServerRequest> recorder() {
			return (request) -> {
				this.methods.add(String.valueOf(request.headers().firstHeader("Mcp-Method")));
				return McpTransportContexts.fromHeaders(List.of("X-User"), request.headers().asHttpHeaders()::get);
			};
		}

	}

	public static class Kiosk {

		final AtomicBoolean summarized = new AtomicBoolean();

		@McpTool(name = "book", description = "Books a table")
		public CallToolOutcome book(McpModernRequestContext ctx, @McpToolParam(description = "guests") int guests) {
			ElicitResult confirmation = ctx.inputResponse("confirm", ElicitResult.class);
			if (confirmation == null) {
				return InputRequiredResult.builder()
					.elicit("confirm", ElicitFormRequest.builder("Book a table for " + guests + "?",
							Map.of("type", "object", "properties", Map.of("ok", Map.of("type", "boolean")))).build())
					.requestState("guests=" + guests)
					.build();
			}
			boolean booked = confirmation.action() == ElicitResult.Action.ACCEPT
					&& Boolean.TRUE.equals(confirmation.content().get("ok"));
			return CallToolResult.builder().addContent(TextContent.builder(booked ? "Booked" : "Not booked").build()).build();
		}

		@McpTool(name = "index", description = "Indexes the given paths")
		public String index(McpSyncNotifier notifier, @McpToolParam(description = "paths") List<String> paths) {
			for (int i = 0; i < paths.size(); i++) {
				notifier.progress(i + 1, (double) paths.size(), paths.get(i));
			}
			return "Indexed " + paths.size() + " paths";
		}

		@McpTool(name = "summarize", description = "Asks the client's model for a summary",
				requiredCapabilities = McpClientCapability.SAMPLING)
		public CallToolOutcome summarize(McpModernRequestContext ctx) {
			this.summarized.set(true);
			return CallToolResult.builder().addContent(TextContent.builder("summary").build()).build();
		}

		@McpTool(name = "whoami", description = "Tells who calls")
		public String whoami(McpModernRequestContext ctx) {
			return ctx.protocolVersion() + " " + McpTransportContexts.header(ctx.transportContext(), "x-user") + " "
					+ ctx.requestMeta().get("user");
		}

		@McpTool(name = "long-named-tool-for-the-kiosk", description = "Has a long name with hyphens")
		public String longName() {
			return "long";
		}

		@McpTool(name = "legacy-context", description = "Needs a session")
		public String legacyContext(McpSyncRequestContext context) {
			return "never";
		}

		@McpResource(uri = "catalog://products", name = "products", ttlMs = 60_000, cacheScope = CacheScope.PUBLIC)
		public String products() {
			return "grinder, kettle";
		}

	}

	/**
	 * Handler methods that serve the modern connection: elicitation with the tool context
	 * of the call, progress, and a sampling method that the modern client does not call.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ModernHandlers {

		final List<String> elicitations = new CopyOnWriteArrayList<>();

		final BlockingQueue<ProgressNotification> progress = new LinkedBlockingQueue<>();

		@McpElicitation(clients = "kiosk")
		public ElicitResult confirm(ElicitFormRequest request, ToolContext toolContext) {
			this.elicitations.add(toolContext.getContext().get("user") + ": " + request.message());
			return ElicitResult.accept(Map.of("ok", true));
		}

		@McpProgress(clients = "kiosk")
		public void progress(ProgressNotification notification) {
			this.progress.add(notification);
		}

		@McpSampling(clients = "kiosk")
		public io.modelcontextprotocol.spec.McpSchema.CreateMessageResult sample(
				io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest request) {
			throw new IllegalStateException("A modern connection has no sampling");
		}

	}

	/**
	 * The page's per-user scope.
	 */
	@Configuration(proxyBeanMethods = false)
	static class PerUserScope {

		final AtomicReference<String> currentUser = new AtomicReference<>("nobody");

		@Bean
		SyncModernMcpClientScope perUserClientScope() {
			Map<String, io.modelcontextprotocol.modern.client.McpSyncClient> clients = new ConcurrentHashMap<>();
			return (connection, clientFactory) -> clients.computeIfAbsent(connection.name() + "/" + this.currentUser.get(),
					(key) -> clientFactory.get());
		}

	}

	/**
	 * The page's caller customizer and request customizer.
	 */
	@Configuration(proxyBeanMethods = false)
	static class CallerIdentity {

		final AtomicReference<String> currentUser = new AtomicReference<>("nobody");

		@Bean
		McpClientCustomizer<McpClient.SyncBuilder> callerCustomizer() {
			return (connectionName, builder) -> {
				McpTransportContext caller = McpTransportContext.create(Map.of("user", this.currentUser.get()));
				builder.transportContextProvider(() -> caller);
			};
		}

		@Bean
		McpHttpRequestCustomizer userHeader() {
			return McpHttpRequestCustomizer
				.fromSync((request, headers, context) -> headers.set("X-User", String.valueOf(context.get("user"))));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ModernFilterAndNames {

		@Bean
		McpModernToolFilter modernToolFilter() {
			return (connection, tool) -> !(connection.name().equals("kiosk") && tool.name().equals("summarize"));
		}

		@Bean
		McpModernToolNamePrefixGenerator modernToolNames() {
			return McpModernToolNamePrefixGenerator.noPrefix();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ChangeHandlers {

		final CompletableFuture<Void> established = new CompletableFuture<>();

		final BlockingQueue<List<String>> toolLists = new LinkedBlockingQueue<>();

		final BlockingQueue<String> events = new LinkedBlockingQueue<>();

		@McpToolListChanged(clients = "kiosk")
		public void toolsChanged(List<McpSchema.Tool> tools) {
			this.toolLists.add(tools.stream().map(McpSchema.Tool::name).toList());
		}

		@Bean
		SyncModernMcpChangeListener toolChangeLogger() {
			return new SyncModernMcpChangeListener() {

				@Override
				public void changed(org.springframework.ai.mcp.modern.client.McpModernConnectionInfo connection,
						io.modelcontextprotocol.modern.client.SubscriptionEvent event) {
					ChangeHandlers.this.events.add(String.valueOf(event));
				}

				@Override
				public void streamEstablished(org.springframework.ai.mcp.modern.client.McpModernConnectionInfo connection) {
					ChangeHandlers.this.established.complete(null);
				}

			};
		}

	}

	/**
	 * The page's repository that hides the {@code admin_} tools from callers without the
	 * admin role.
	 */
	@Configuration(proxyBeanMethods = false)
	static class PerCallerTools {

		@Bean
		McpTransportContextExtractor<ServerRequest> roleHeaderExtractor() {
			return (request) -> McpTransportContexts.fromHeaders(List.of("X-Role"), request.headers().asHttpHeaders()::get);
		}

		@Bean
		io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository toolRepository(
				org.springframework.beans.factory.ObjectProvider<List<McpSyncTool>> tools,
				io.modelcontextprotocol.modern.server.feature.McpChangeFeed changes) {
			var inMemory = InMemorySyncToolRepository.builder()
				.tools(tools.stream().flatMap(List::stream).toList())
				.changeBroadcaster(changes)
				.build();
			return new io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository() {

				@Override
				public io.modelcontextprotocol.modern.server.feature.ToolsPage list(
						io.modelcontextprotocol.modern.server.McpRequestContext ctx, String cursor) {
					var page = inMemory.list(ctx, cursor);
					List<McpSchema.Tool> allowed = page.tools().stream().filter((tool) -> allowed(ctx, tool)).toList();
					return new io.modelcontextprotocol.modern.server.feature.ToolsPage(allowed, page.nextCursor(),
							page.ttlMs(), CacheScope.PRIVATE);
				}

				@Override
				public McpSyncTool find(io.modelcontextprotocol.modern.server.McpRequestContext ctx, String name) {
					McpSyncTool tool = inMemory.find(ctx, name);
					return (tool != null && allowed(ctx, tool.definition())) ? tool : null;
				}

				private boolean allowed(io.modelcontextprotocol.modern.server.McpRequestContext ctx, McpSchema.Tool tool) {
					return "admin".equals(McpTransportContexts.header(ctx, "x-role")) || !tool.name().startsWith("admin_");
				}

			};
		}

		@Bean
		AdminTools adminTools() {
			return new AdminTools();
		}

	}

	public static class AdminTools {

		@McpTool(name = "menu", description = "Shows the menu")
		public String menu() {
			return "coffee";
		}

		@McpTool(name = "admin_reset", description = "Resets the kiosk")
		public String reset() {
			return "reset";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AsyncTools {

		@Bean
		Reactive reactive() {
			return new Reactive();
		}

	}

	public static class Reactive {

		@McpTool(name = "reactive", description = "Answers with a Mono")
		public Mono<String> reactive() {
			return Mono.just("from a Mono");
		}

		@McpTool(name = "blocking", description = "Answers at once")
		public String blocking() {
			return "blocking";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class FluxTool {

		@Bean
		Streaming streaming() {
			return new Streaming();
		}

	}

	public static class Streaming {

		@McpTool(name = "stream", description = "Answers with a Flux")
		public reactor.core.publisher.Flux<String> stream() {
			return reactor.core.publisher.Flux.just("a", "b");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OrphanCompletion {

		@Bean
		Orphan orphan() {
			return new Orphan();
		}

	}

	public static class Orphan {

		@org.springframework.ai.mcp.annotation.McpComplete(prompt = "no-such-prompt")
		public List<String> complete(String prefix) {
			return List.of();
		}

	}

}
