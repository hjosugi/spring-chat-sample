package io.github.hjosugi.chatsample.providers.anthropic;

import java.util.ArrayList;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicCitationDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt caching page: where each {@link AnthropicCacheStrategy} puts its cache
 * breakpoints in the request, with which TTL, how many at most, and where the cache
 * tokens of the reply end up.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicPromptCachingTests {

	private static final List<Message> CONVERSATION = List.of(new SystemMessage("You are an expert assistant."),
			new UserMessage("What is machine learning?"), new AssistantMessage("Learning from data."),
			new UserMessage("Can you give me an example?"));

	@Test
	void systemOnlyCachesTheSystemPromptAndNothingElse() {
		JsonNode body = send(AnthropicCacheStrategy.SYSTEM_ONLY);

		assertThat(cached(body)).containsExactly("system[0]");
		assertThat(body.get("system").get(0).get("text").asString()).isEqualTo("You are an expert assistant.");
		assertThat(body.get("system").get(0).get("cache_control").toString())
			.isEqualTo("{\"type\":\"ephemeral\",\"ttl\":\"5m\"}");
	}

	@Test
	void toolsOnlyCachesTheLastToolDefinition() {
		JsonNode body = send(AnthropicCacheStrategy.TOOLS_ONLY);

		assertThat(cached(body)).containsExactly("tools[1]");
		assertThat(body.get("system").isString()).isTrue();
	}

	@Test
	void systemAndToolsCachesBoth() {
		assertThat(cached(send(AnthropicCacheStrategy.SYSTEM_AND_TOOLS))).containsExactly("system[0]", "tools[1]");
	}

	@Test
	void conversationHistoryAlsoCachesTheLastUserMessage() {
		JsonNode body = send(AnthropicCacheStrategy.CONVERSATION_HISTORY);

		assertThat(cached(body)).containsExactly("system[0]", "messages[2].content[0]", "tools[1]");
		assertThat(body.get("messages").get(0).get("content").isString()).isTrue();
	}

	@Test
	void noneSendsNoCacheControl() {
		JsonNode body = send(AnthropicCacheStrategy.NONE);

		assertThat(cached(body)).isEmpty();
		assertThat(body.get("system").asString()).isEqualTo("You are an expert assistant.");
	}

	@Test
	void theTtlAndTheMinimumLengthApplyPerMessageType() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Paris")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			model.call(new Prompt(CONVERSATION, options(AnthropicCacheOptions.builder()
				.strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
				.messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR))));
			model.call(new Prompt(CONVERSATION, options(AnthropicCacheOptions.builder()
				.strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
				.messageTypeMinContentLength(MessageType.SYSTEM, 100))));

			JsonNode oneHour = anthropic.requests.get(0).body();
			assertThat(oneHour.get("system").get(0).get("cache_control").get("ttl").asString()).isEqualTo("1h");
			assertThat(oneHour.get("messages").get(2).get("content").get(0).get("cache_control").get("ttl").asString())
				.isEqualTo("5m");
			assertThat(cached(anthropic.requests.get(1).body())).as("a system prompt shorter than 100").isEmpty();
		}
	}

	/**
	 * The migration page says so: tool definitions take the TTL of the system messages,
	 * whatever the strategy and the TTL of the other types.
	 */
	@Test
	void theToolDefinitionsTakeTheTtlOfTheSystemMessages() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Paris")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			model.call(new Prompt(CONVERSATION, options(AnthropicCacheOptions.builder()
				.strategy(AnthropicCacheStrategy.TOOLS_ONLY)
				.messageTypeTtl(MessageType.USER, AnthropicCacheTtl.ONE_HOUR))));
			model.call(new Prompt(CONVERSATION, options(AnthropicCacheOptions.builder()
				.strategy(AnthropicCacheStrategy.TOOLS_ONLY)
				.messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR))));

			assertThat(anthropic.requests).extracting(
					(request) -> request.body().get("tools").get(1).get("cache_control").get("ttl").asString())
				.containsExactly("5m", "1h");
		}
	}

	@Test
	void multiBlockSystemCachingCachesTheStaticPrefixOnly() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("News")))) {
			Claude.model(anthropic)
				.call(new Prompt(
						List.of(new SystemMessage("You are a knowledge base assistant."),
								new SystemMessage("Today is 2025-02-23."), new UserMessage("What is new?")),
						options(AnthropicCacheOptions.builder()
							.strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
							.multiBlockSystemCaching(true))));

			JsonNode system = anthropic.single().body().get("system");
			assertThat(system).hasSize(2);
			assertThat(system.get(0).has("cache_control")).isTrue();
			assertThat(system.get(1).has("cache_control")).isFalse();
			assertThat(system.get(1).get("text").asString()).isEqualTo("Today is 2025-02-23.");
		}
	}

	@Test
	void theLastToolResultIsCachedWithConversationHistoryWhenAsked() {
		List<Message> toolRound = List.of(new UserMessage("Where is A-1?"),
				AssistantMessage.builder()
					.toolCalls(List.of(new AssistantMessage.ToolCall("toolu_1", "function", "orderStatus",
							"{\"orderId\":\"A-1\"}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("toolu_1", "orderStatus", "shipped")))
					.build());
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Shipped.")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			model.call(new Prompt(toolRound, options(AnthropicCacheOptions.builder()
				.strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
				.cacheToolResults(true))));
			model.call(new Prompt(toolRound,
					options(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY))));
			model.call(new Prompt(toolRound, options(
					AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS).cacheToolResults(true))));

			assertThat(cached(anthropic.requests.get(0).body())).contains("messages[2].content[0]");
			assertThat(cached(anthropic.requests.get(1).body())).doesNotContain("messages[2].content[0]");
			assertThat(cached(anthropic.requests.get(2).body())).as("only with CONVERSATION_HISTORY")
				.doesNotContain("messages[2].content[0]");
		}
	}

	/**
	 * Four breakpoints at most, as both pages say. The migration page also promises a
	 * one-time {@code WARN} when one is dropped; 2.1.0 logs none, because every caller
	 * asks {@code CacheBreakpointTracker#allBreakpointsAreUsed} first and never calls
	 * {@code use()} a fifth time, which is where the warning is.
	 */
	@Test
	void aFifthBreakpointIsDroppedWithoutAWarning(CapturedOutput output) {
		List<Message> toolRound = List.of(new SystemMessage("You are a shop assistant."),
				new UserMessage("Where is A-1?"),
				AssistantMessage.builder()
					.toolCalls(List.of(new AssistantMessage.ToolCall("toolu_1", "function", "orderStatus",
							"{\"orderId\":\"A-1\"}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("toolu_1", "orderStatus", "shipped")))
					.build());
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Shipped.")))) {
			Claude.model(anthropic)
				.call(new Prompt(toolRound, AnthropicChatOptions.builder()
					.cacheOptions(AnthropicCacheOptions.builder()
						.strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
						.cacheToolResults(true)
						.build())
					.citationDocuments(AnthropicCitationDocument.builder().plainText("Orders ship in a day.").build())
					.toolCallbacks(ToolCallbacks.from(new Tools()))
					.build()));

			List<String> cached = cached(anthropic.single().body());
			assertThat(cached).containsExactly("system[0]", "messages[0].content[0]", "messages[0].content[1]",
					"messages[2].content[0]");
			assertThat(output).doesNotContain("cache breakpoint limit");
		}
	}

	@Test
	void theCacheTokensOfTheReplyAreInTheUsageOfACallAndAStream() {
		String usage = "\"input_tokens\":25,\"output_tokens\":15,\"cache_creation_input_tokens\":200,"
				+ "\"cache_read_input_tokens\":1000";
		try (StandIn anthropic = new StandIn((request) -> {
			Reply reply = Claude.reply(request, "end_turn", Claude.text("Paris"));
			return new Reply(reply.status(), reply.contentType(), reply.headers(), reply.pieces()
				.stream()
				.map((piece) -> new String(piece).replace("\"input_tokens\":25,\"output_tokens\":15", usage)
					.replace("\"input_tokens\":25,\"output_tokens\":1", usage)
					.getBytes())
				.toList());
		})) {
			AnthropicChatModel model = Claude.model(anthropic);

			Usage called = model.call(new Prompt("Capital of France?")).getMetadata().getUsage();
			List<ChatResponse> chunks = model.stream(new Prompt("Capital of France?")).collectList().block();
			Usage streamed = chunks.get(chunks.size() - 1).getMetadata().getUsage();

			com.anthropic.models.messages.Usage sdkUsage = (com.anthropic.models.messages.Usage) called.getNativeUsage();
			assertThat(sdkUsage.cacheCreationInputTokens()).hasValue(200L);
			assertThat(sdkUsage.cacheReadInputTokens()).hasValue(1000L);
			for (Usage each : List.of(called, streamed)) {
				assertThat(each.getCacheWriteInputTokens()).isEqualTo(200L);
				assertThat(each.getCacheReadInputTokens()).isEqualTo(1000L);
				assertThat(each.getPromptTokens()).as("uncached, written and read input").isEqualTo(1225);
			}
		}
	}

	/**
	 * The migration page says so: {@code NONE} on a request means "unset" when a
	 * {@code ChatClient} merges it into the defaults of the model; only a model whose
	 * default is {@link AnthropicCacheOptions#disabled()} sends no cache control.
	 */
	@Test
	void noneOnAChatClientRequestFallsBackToTheCachingOfTheModel() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Paris")))) {
			ChatClient client = ChatClient.create(Claude.model(anthropic, AnthropicChatOptions.builder()
				.cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.SYSTEM_ONLY).build())));

			client.prompt()
				.system("You are an expert assistant.")
				.user("Capital of France?")
				.options(AnthropicChatOptions.builder().cacheOptions(AnthropicCacheOptions.disabled()))
				.call()
				.content();

			assertThat(cached(anthropic.single().body())).containsExactly("system[0]");
		}
	}

	private static JsonNode send(AnthropicCacheStrategy strategy) {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Paris")))) {
			Claude.model(anthropic)
				.call(new Prompt(CONVERSATION, options(AnthropicCacheOptions.builder().strategy(strategy))));
			return anthropic.single().body();
		}
	}

	private static AnthropicChatOptions options(AnthropicCacheOptions.Builder cacheOptions) {
		return AnthropicChatOptions.builder()
			.cacheOptions(cacheOptions.build())
			.toolCallbacks(ToolCallbacks.from(new Tools()))
			.build();
	}

	/**
	 * Where the request has a cache breakpoint, such as {@code system[0]} or
	 * {@code messages[2].content[0]}, in request order.
	 */
	static List<String> cached(JsonNode body) {
		List<String> cached = new ArrayList<>();
		JsonNode system = body.path("system");
		for (int i = 0; system.isArray() && i < system.size(); i++) {
			if (system.get(i).has("cache_control")) {
				cached.add("system[" + i + "]");
			}
		}
		JsonNode messages = body.get("messages");
		for (int m = 0; m < messages.size(); m++) {
			JsonNode content = messages.get(m).get("content");
			for (int c = 0; content.isArray() && c < content.size(); c++) {
				if (content.get(c).has("cache_control")) {
					cached.add("messages[" + m + "].content[" + c + "]");
				}
			}
		}
		JsonNode tools = body.path("tools");
		for (int t = 0; tools.isArray() && t < tools.size(); t++) {
			if (tools.get(t).has("cache_control")) {
				cached.add("tools[" + t + "]");
			}
		}
		return cached;
	}

	static class Tools {

		@Tool(description = "Tells where an order is")
		String orderStatus(String orderId) {
			return "shipped";
		}

		@Tool(description = "Lists the orders")
		String myOrders() {
			return "A-1";
		}

	}

}
