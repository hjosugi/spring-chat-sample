package io.github.hjosugi.chatsample.providers.anthropic;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.models.messages.Metadata;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicServiceTier;
import org.springframework.ai.anthropic.metadata.AnthropicRateLimit;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The Anthropic chat page: what Spring AI's {@link AnthropicChatModel} sends to the
 * Messages API for each option, and what it makes of the reply, called and streamed.
 * The model talks to a {@link StandIn} through the official Anthropic SDK.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicChatTests {

	@Test
	void aCallSendsTheConversationWithTheKeyAsABearerTokenAndReadsTheReply() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Five pirates.")))) {
			AnthropicChatModel model = Claude.model(anthropic, AnthropicChatOptions.builder().model(Claude.MODEL));

			ChatResponse response = model.call(new Prompt(List.of(new SystemMessage("Be brief."),
					new SystemMessage("Answer in English."), new UserMessage("Name five pirates."))));

			Request request = anthropic.single();
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.path()).isEqualTo("/v1/messages");
			assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
			assertThat(request.header("x-api-key")).isNull();
			assertThat(request.header("anthropic-version")).isNotBlank();
			JsonNode body = request.body();
			assertThat(body.get("model").asString()).isEqualTo(Claude.MODEL);
			assertThat(body.get("max_tokens").asInt()).as("the default of maxTokens").isEqualTo(4096);
			assertThat(body.get("system").asString()).isEqualTo("Be brief.\n\nAnswer in English.");
			assertThat(body.get("messages").toString())
				.isEqualTo("[{\"content\":\"Name five pirates.\",\"role\":\"user\"}]");
			assertThat(body.has("stream")).isFalse();
			assertThat(response.getResult().getOutput().getText()).isEqualTo("Five pirates.");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
			assertThat(response.getMetadata().getId()).isEqualTo("msg_1");
			assertThat(response.getMetadata().getModel()).isEqualTo(Claude.MODEL);
			assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
			assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(15);
		}
	}

	@Test
	void aStreamDeliversTheTextInPiecesAndEndsWithTheReasonAndTheUsage() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Five pirates.")))) {
			List<ChatResponse> chunks = Claude.model(anthropic)
				.stream(new Prompt("Name five pirates."))
				.collectList()
				.block();

			assertThat(anthropic.single().body().get("stream").asBoolean()).isTrue();
			assertThat(chunks).extracting((chunk) -> chunk.getResult().getOutput().getText())
				.containsExactly("Five p", "irates.", "");
			ChatResponse last = chunks.get(chunks.size() - 1);
			assertThat(last.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
			assertThat(last.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
			assertThat(last.getMetadata().getUsage().getCompletionTokens()).isEqualTo(15);
			assertThat(chunks.get(0).getResult().getOutput().getParts()).singleElement()
				.satisfies((part) -> assertThat(StreamingParts.isPartial(part)).isTrue());
		}
	}

	@Test
	@SuppressWarnings({ "deprecation", "removal" })
	void temperatureTopPAndTopKAreDroppedWithAWarning(CapturedOutput output) {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic, AnthropicChatOptions.builder().temperature(0.2));

			model.call(new Prompt("Hello",
					model.getOptions().mutate().temperature(0.7).topP(0.9).topK(40).build()));

			JsonNode body = anthropic.single().body();
			assertThat(body.has("temperature")).isFalse();
			assertThat(body.has("top_p")).isFalse();
			assertThat(body.has("top_k")).isFalse();
			assertThat(output).contains("does not support temperature, topP or topK; ignoring temperature=0.2")
				.contains("ignoring temperature=0.7, topP=0.9, topK=40");
		}
	}

	/**
	 * The migration page says so: an option set on the prompt does not inherit the unset
	 * ones from the model, unlike Spring AI 1.
	 */
	@Test
	void theOptionsOfAPromptReplaceTheDefaultsOfTheModelWithoutMerging() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic,
					AnthropicChatOptions.builder().model(Claude.MODEL).stopSequences(List.of("END")));

			model.call(new Prompt("Hello", AnthropicChatOptions.builder().maxTokens(2048).build()));
			model.call(new Prompt("Hello", model.getOptions().mutate().maxTokens(2048).build()));

			JsonNode partial = anthropic.requests.get(0).body();
			assertThat(partial.get("model").asString()).isEqualTo(AnthropicChatOptions.DEFAULT_MODEL);
			assertThat(partial.has("stop_sequences")).isFalse();
			JsonNode full = anthropic.requests.get(1).body();
			assertThat(full.get("model").asString()).isEqualTo(Claude.MODEL);
			assertThat(full.get("max_tokens").asInt()).isEqualTo(2048);
			assertThat(full.get("stop_sequences").toString()).isEqualTo("[\"END\"]");
		}
	}

	@Test
	void aChatClientMergesTheOptionsOfARequestIntoTheDefaultsOfTheModel() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic,
					AnthropicChatOptions.builder().model(Claude.MODEL).stopSequences(List.of("END")));
			ChatClient client = ChatClient.create(model);

			client.prompt("Hello").options(AnthropicChatOptions.builder().maxTokens(2048)).call().content();
			client.prompt("Hello").options(ChatOptions.builder().model("claude-opus-5-5")).call().content();

			JsonNode anthropicOptions = anthropic.requests.get(0).body();
			assertThat(anthropicOptions.get("model").asString()).isEqualTo(Claude.MODEL);
			assertThat(anthropicOptions.get("max_tokens").asInt()).isEqualTo(2048);
			assertThat(anthropicOptions.get("stop_sequences").toString()).isEqualTo("[\"END\"]");
			JsonNode portableOptions = anthropic.requests.get(1).body();
			assertThat(portableOptions.get("model").asString()).isEqualTo("claude-opus-5-5");
			assertThat(portableOptions.get("stop_sequences").toString()).isEqualTo("[\"END\"]");
		}
	}

	/**
	 * The page says a portable {@code ChatOptions} can take the place of
	 * {@code AnthropicChatOptions}. On a prompt given to the model itself, 2.1.0 drops
	 * it: {@code resolveAnthropicOptions} keeps only {@code AnthropicChatOptions} and
	 * falls back to the defaults of {@code AnthropicChatOptions}, not even those of the
	 * model. Through a {@code ChatClient} it works (see above).
	 */
	@Test
	void portableChatOptionsOnAPromptToTheModelAreIgnored() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic, AnthropicChatOptions.builder().model(Claude.MODEL));

			model.call(new Prompt("Hello", ChatOptions.builder().model("claude-opus-5-5").maxTokens(100).build()));

			JsonNode body = anthropic.single().body();
			assertThat(body.get("model").asString()).isEqualTo(AnthropicChatOptions.DEFAULT_MODEL);
			assertThat(body.get("max_tokens").asInt()).isEqualTo(4096);
		}
	}

	@Test
	void theChatOptionsReachTheRequest() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			model.call(new Prompt("Hello",
					AnthropicChatOptions.builder()
						.model("claude-sonnet-5-5")
						.maxTokens(2048)
						.stopSequences(List.of("END", "STOP"))
						.metadata(Metadata.builder().userId("alice").build())
						.inferenceGeo("eu")
						.serviceTier(AnthropicServiceTier.STANDARD_ONLY)
						.build()));
			model.call(new Prompt("Hello", AnthropicChatOptions.builder().serviceTier(AnthropicServiceTier.AUTO).build()));

			JsonNode body = anthropic.requests.get(0).body();
			assertThat(body.get("model").asString()).isEqualTo("claude-sonnet-5-5");
			assertThat(body.get("max_tokens").asInt()).isEqualTo(2048);
			assertThat(body.get("stop_sequences").toString()).isEqualTo("[\"END\",\"STOP\"]");
			assertThat(body.get("metadata").get("user_id").asString()).isEqualTo("alice");
			assertThat(body.get("inference_geo").asString()).isEqualTo("eu");
			assertThat(body.get("service_tier").asString()).isEqualTo("standard_only");
			assertThat(anthropic.requests.get(1).body().get("service_tier").asString()).isEqualTo("auto");
		}
	}

	@Test
	void toolChoiceAndDisableParallelToolUseReachTheRequest() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			ChatClient client = ChatClient.create(Claude.model(anthropic));

			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(AnthropicChatOptions.builder().toolChoice(ToolChoice.ofAny(ToolChoiceAny.builder().build())))
				.call()
				.content();
			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(AnthropicChatOptions.builder()
					.toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name("orderStatus").build()))
					.disableParallelToolUse(true))
				.call()
				.content();
			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(AnthropicChatOptions.builder().toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build())))
				.call()
				.content();
			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(AnthropicChatOptions.builder().disableParallelToolUse(true))
				.call()
				.content();

			assertThat(anthropic.requests.get(0).body().get("tool_choice").toString())
				.isEqualTo("{\"type\":\"any\"}");
			assertThat(anthropic.requests.get(1).body().get("tool_choice").toString())
				.isEqualTo("{\"name\":\"orderStatus\",\"type\":\"tool\",\"disable_parallel_tool_use\":true}");
			assertThat(anthropic.requests.get(2).body().get("tool_choice").toString())
				.isEqualTo("{\"type\":\"none\"}");
			assertThat(anthropic.requests.get(3).body().get("tool_choice").toString())
				.isEqualTo("{\"type\":\"auto\",\"disable_parallel_tool_use\":true}");
			JsonNode tool = anthropic.requests.get(0).body().get("tools").get(0);
			assertThat(tool.get("name").asString()).isEqualTo("orderStatus");
			assertThat(tool.get("description").asString()).isEqualTo("Tells where an order is");
			assertThat(tool.get("input_schema").get("properties").has("orderId")).isTrue();
			assertThat(tool.get("input_schema").get("required").toString()).isEqualTo("[\"orderId\"]");
		}
	}

	@Test
	void theThinkingConfigurationsReachTheRequest() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic);
			List<AnthropicChatOptions> options = List.of(
					AnthropicChatOptions.builder().maxTokens(16000).thinkingEnabled(10000L).build(),
					AnthropicChatOptions.builder()
						.maxTokens(16000)
						.thinkingEnabled(10000L, ThinkingConfigEnabled.Display.OMITTED)
						.build(),
					AnthropicChatOptions.builder().thinkingAdaptive().build(),
					AnthropicChatOptions.builder().thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED).build(),
					AnthropicChatOptions.builder().thinkingDisabled().build(),
					AnthropicChatOptions.builder()
						.thinking(ThinkingConfigParam
							.ofEnabled(ThinkingConfigEnabled.builder().budgetTokens(2048L).build()))
						.build());

			options.forEach((option) -> model.call(new Prompt("Is 7 prime?", option)));

			assertThat(anthropic.requests).extracting((request) -> request.body().get("thinking").toString())
				.containsExactly("{\"budget_tokens\":10000,\"type\":\"enabled\"}",
						"{\"budget_tokens\":10000,\"type\":\"enabled\",\"display\":\"omitted\"}",
						"{\"type\":\"adaptive\"}", "{\"type\":\"adaptive\",\"display\":\"summarized\"}",
						"{\"type\":\"disabled\"}", "{\"budget_tokens\":2048,\"type\":\"enabled\"}");
		}
	}

	@Test
	void anOutputSchemaAndAnEffortBecomeTheOutputConfig() {
		String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},"
				+ "\"required\":[\"name\"],\"additionalProperties\":false}";
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("{\"name\":\"France\"}")))) {
			AnthropicChatModel model = Claude.model(anthropic);
			AnthropicChatOptions both = AnthropicChatOptions.builder()
				.outputSchema(schema)
				.effort(OutputConfig.Effort.HIGH)
				.build();

			ChatResponse response = model.call(new Prompt("Tell me about France.", both));
			model.call(new Prompt("Capital of France?",
					AnthropicChatOptions.builder().effort(OutputConfig.Effort.LOW).build()));

			JsonNode outputConfig = anthropic.requests.get(0).body().get("output_config");
			assertThat(outputConfig.get("effort").asString()).isEqualTo("high");
			assertThat(outputConfig.get("format").get("type").asString()).isEqualTo("json_schema");
			assertThat(outputConfig.get("format").get("schema").toString()).isEqualTo(schema);
			assertThat(anthropic.requests.get(1).body().get("output_config").toString())
				.isEqualTo("{\"effort\":\"low\"}");
			assertThat(both.getOutputSchema()).as("StructuredOutputChatOptions#getOutputSchema").isEqualTo(schema);
			assertThat(response.getResult().getOutput().getText()).isEqualTo("{\"name\":\"France\"}");
		}
	}

	@Test
	void perRequestHeadersGoWithOneRequestAndCustomHeadersWithEvery() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic,
					AnthropicChatOptions.builder().customHeaders(Map.of("X-Client", "shop")));

			model.call(new Prompt("Hello", model.getOptions()
				.mutate()
				.httpHeaders(Map.of("X-Request-Id", "req-12345"))
				.build()));
			model.call(new Prompt("Hello again"));

			assertThat(anthropic.requests.get(0).header("X-Request-Id")).isEqualTo("req-12345");
			assertThat(anthropic.requests.get(1).header("X-Request-Id")).isNull();
			assertThat(anthropic.requests).extracting((request) -> request.header("X-Client"))
				.containsExactly("shop", "shop");
		}
	}

	@Test
	void theRateLimitHeadersBecomeAnAnthropicRateLimitOnACallAndAStream() {
		String reset = Instant.now().plusSeconds(60).toString();
		try (StandIn anthropic = new StandIn((request) -> Claude.reply(request, "end_turn", Claude.text("Hi"))
			.header("anthropic-ratelimit-requests-limit", "50")
			.header("anthropic-ratelimit-requests-remaining", "49")
			.header("anthropic-ratelimit-requests-reset", reset)
			.header("anthropic-ratelimit-tokens-limit", "1000")
			.header("anthropic-ratelimit-tokens-remaining", "900")
			.header("anthropic-ratelimit-input-tokens-limit", "800")
			.header("anthropic-ratelimit-output-tokens-remaining", "150"))) {
			AnthropicChatModel model = Claude.model(anthropic);

			ChatResponse called = model.call(new Prompt("Hello"));
			List<ChatResponse> streamed = model.stream(new Prompt("Hello")).collectList().block();

			for (ChatResponse response : List.of(called, streamed.get(streamed.size() - 1))) {
				assertThat(response.getMetadata().getRateLimit()).isInstanceOfSatisfying(AnthropicRateLimit.class,
						(rateLimit) -> {
							assertThat(rateLimit.getRequestsLimit()).isEqualTo(50L);
							assertThat(rateLimit.getRequestsRemaining()).isEqualTo(49L);
							assertThat(rateLimit.getRequestsReset()).isPositive();
							assertThat(rateLimit.getTokensLimit()).isEqualTo(1000L);
							assertThat(rateLimit.getTokensRemaining()).isEqualTo(900L);
							assertThat(rateLimit.getInputTokensLimit()).isEqualTo(800L);
							assertThat(rateLimit.getOutputTokensRemaining()).isEqualTo(150L);
						});
			}
		}
	}

	@Test
	void withoutRateLimitHeadersTheRateLimitIsEmpty() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			assertThat(Claude.model(anthropic).call(new Prompt("Hello")).getMetadata().getRateLimit())
				.isInstanceOf(EmptyRateLimit.class);
		}
	}

	@Test
	void theRawMessageIsInTheMetadataOfACallButNotOfAStream() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			ChatResponse called = model.call(new Prompt("Hello"));
			List<ChatResponse> streamed = model.stream(new Prompt("Hello")).collectList().block();

			Object raw = called.getMetadata().get("anthropic-response");
			assertThat(raw).isInstanceOfSatisfying(com.anthropic.models.messages.Message.class,
					(message) -> assertThat(message.stopReason()).hasValueSatisfying(
							(reason) -> assertThat(reason.asString()).isEqualTo("end_turn")));
			assertThat(streamed).allSatisfy(
					(chunk) -> assertThat(chunk.getMetadata().containsKey("anthropic-response")).isFalse());
		}
	}

	@Test
	void theChatClientRunsTheToolsClaudeAsksForInACallAndInAStream() {
		try (StandIn anthropic = new StandIn((request) -> (toolResult(request) != null)
				? Claude.reply(request, "end_turn", Claude.text("Order " + toolResult(request)))
				: Claude.reply(request, "tool_use", Claude.toolUse("toolu_1", "orderStatus", "{\"orderId\":\"A-1\"}")))) {
			ChatClient client = ChatClient.create(Claude.model(anthropic));

			String called = client.prompt("Where is A-1?").tools(new Shop()).call().content();
			String streamed = String.join("",
					client.prompt("Where is A-1?").tools(new Shop()).stream().content().collectList().block());

			assertThat(called).isEqualTo("Order \"A-1 shipped\"");
			assertThat(streamed).isEqualTo("Order \"A-1 shipped\"");
			assertThat(anthropic.requests).hasSize(4);
			for (Request second : List.of(anthropic.requests.get(1), anthropic.requests.get(3))) {
				JsonNode messages = Claude.messages(second);
				assertThat(messages).hasSize(3);
				assertThat(Claude.content(messages.get(1)).toString()).isEqualTo(
						"[{\"id\":\"toolu_1\",\"input\":{\"orderId\":\"A-1\"},\"name\":\"orderStatus\",\"type\":\"tool_use\"}]");
				JsonNode result = Claude.content(messages.get(2)).get(0);
				assertThat(result.get("type").asString()).isEqualTo("tool_result");
				assertThat(result.get("tool_use_id").asString()).isEqualTo("toolu_1");
			}
		}
	}

	@Test
	void aThinkingReplyHoldsItsReasoningPartsBeforeTheTextInBlockOrder() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.thinking("Primes...", "sig-1"),
				Claude.redactedThinking("opaque"), Claude.text("Yes.")))) {
			AssistantMessage message = Claude.model(anthropic)
				.call(new Prompt("Infinitely many primes 4k+3?",
						AnthropicChatOptions.builder().maxTokens(16000).thinkingEnabled(10000L).build()))
				.getResult()
				.getOutput();

			assertThat(message.getParts()).hasSize(3);
			assertThat(message.getParts().get(0)).isEqualTo(new ReasoningPart("Primes...", null,
					new OpaquePayload("anthropic", AnthropicChatModel.PAYLOAD_SIGNATURE, "sig-1"), Map.of()));
			assertThat(message.getParts().get(1)).isInstanceOfSatisfying(ReasoningPart.class, (redacted) -> {
				assertThat(redacted.text()).isNull();
				assertThat(redacted.payload()).isEqualTo(
						new OpaquePayload("anthropic", AnthropicChatModel.PAYLOAD_REDACTED_THINKING, "opaque"));
			});
			assertThat(message.getParts().get(2)).isEqualTo(TextPart.of("Yes."));
			assertThat(message.getReasoning()).hasSize(2);
			assertThat(message.getText()).isEqualTo("Yes.");
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void aStreamedThinkingReplyComesInPartialPartsAndAggregatesToTheSameMessage() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.thinking("Primes...", "sig-1"),
				Claude.redactedThinking("opaque"), Claude.text("Yes.")))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			List<ChatResponse> chunks = new MessageAggregator()
				.aggregate(Claude.model(anthropic)
					.stream(new Prompt("Infinitely many primes 4k+3?",
							AnthropicChatOptions.builder().maxTokens(16000).thinkingEnabled(10000L).build())),
						aggregated::set)
				.collectList()
				.block();

			List<MessagePart> streamedParts = chunks.stream()
				.flatMap((chunk) -> chunk.getResult().getOutput().getParts().stream())
				.toList();
			assertThat(streamedParts.get(0)).isInstanceOfSatisfying(ReasoningPart.class, (delta) -> {
				assertThat(delta.text()).isEqualTo("Prim");
				assertThat(StreamingParts.isPartial(delta)).isTrue();
				assertThat(StreamingParts.partIndex(delta)).isZero();
			});
			assertThat(streamedParts.get(2)).isInstanceOfSatisfying(ReasoningPart.class, (signature) -> {
				assertThat(signature.text()).isEmpty();
				assertThat(signature.payload().data()).isEqualTo("sig-1");
			});
			assertThat(streamedParts.get(3)).isInstanceOfSatisfying(ReasoningPart.class,
					(redacted) -> assertThat(StreamingParts.isPartial(redacted)).isFalse());
			ChatResponse firstThinking = chunks.get(0);
			assertThat(firstThinking.getResult().getOutput().getText()).isEmpty();
			assertThat(firstThinking.getResult().getOutput().getMetadata())
				.containsEntry(AnthropicChatModel.THINKING_METADATA_KEY, true)
				.containsEntry(AnthropicChatModel.THINKING_TEXT_METADATA_KEY, "Prim");

			AssistantMessage message = aggregated.get().getResult().getOutput();
			assertThat(message.getParts()).containsExactly(
					new ReasoningPart("Primes...", null,
							new OpaquePayload("anthropic", AnthropicChatModel.PAYLOAD_SIGNATURE, "sig-1"), Map.of()),
					new ReasoningPart(null, null,
							new OpaquePayload("anthropic", AnthropicChatModel.PAYLOAD_REDACTED_THINKING, "opaque"),
							Map.of()),
					TextPart.of("Yes."));
		}
	}

	@Test
	void signedThinkingIsReplayedBeforeTheToolUseItLedToInACallAndInAStream() {
		try (StandIn anthropic = new StandIn((request) -> (toolResult(request) != null)
				? Claude.reply(request, "end_turn", Claude.text("Shipped."))
				: Claude.reply(request, "tool_use", Claude.thinking("Look it up.", "sig-1"),
						Claude.redactedThinking("opaque"),
						Claude.toolUse("toolu_1", "orderStatus", "{\"orderId\":\"A-1\"}")))) {
			ChatClient client = ChatClient.builder(Claude.model(anthropic,
					AnthropicChatOptions.builder().maxTokens(16000).thinkingEnabled(10000L)))
				.defaultTools(new Shop())
				.build();

			client.prompt("Where is A-1?").call().content();
			client.prompt("Where is A-1?").stream().content().collectList().block();

			for (Request second : List.of(anthropic.requests.get(1), anthropic.requests.get(3))) {
				JsonNode assistant = Claude.content(Claude.messages(second).get(1));
				assertThat(assistant).hasSize(3);
				assertThat(assistant.get(0).toString())
					.isEqualTo("{\"signature\":\"sig-1\",\"thinking\":\"Look it up.\",\"type\":\"thinking\"}");
				assertThat(assistant.get(1).toString()).isEqualTo("{\"data\":\"opaque\",\"type\":\"redacted_thinking\"}");
				assertThat(assistant.get(2).get("type").asString()).isEqualTo("tool_use");
			}
		}
	}

	@Test
	void aToolLoopWithThinkingButWithoutTheThinkingBlockIsWarnedAbout(CapturedOutput output) {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Shipped.")))) {
			AssistantMessage withoutThinking = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("toolu_1", "function", "orderStatus",
						"{\"orderId\":\"A-1\"}")))
				.build();
			ToolResponseMessage result = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("toolu_1", "orderStatus", "A-1 shipped")))
				.build();

			Claude.model(anthropic)
				.call(new Prompt(List.of(new UserMessage("Where is A-1?"), withoutThinking, result),
						AnthropicChatOptions.builder().maxTokens(16000).thinkingEnabled(10000L).build()));

			assertThat(Claude.content(Claude.messages(anthropic.single()).get(1))).hasSize(1);
			assertThat(output).contains("Extended thinking is enabled but the assistant turn awaiting tool results "
					+ "has no Anthropic thinking block to replay");
		}
	}

	@Test
	void imagesAndPdfsBecomeContentBlocksInTheOrderOfTheParts() {
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		byte[] pdf = "%PDF-1.4".getBytes();
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Two charts.")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			model.call(new Prompt(UserMessage.builder()
				.part(TextPart.of("The first chart:"))
				.part(MediaPart.of(new Media(MimeTypeUtils.IMAGE_PNG, new org.springframework.core.io.ByteArrayResource(png))))
				.part(TextPart.of("The report:"))
				.part(MediaPart.of(Media.builder().mimeType(new MimeType("application", "pdf")).data(pdf).build()))
				.part(TextPart.of("Which grows more?"))
				.build()));
			model.call(new Prompt(UserMessage.builder()
				.text("Compare these.")
				.media(List.of(Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data(png).build(),
						Media.builder().mimeType(MimeTypeUtils.IMAGE_GIF).data(png).build()))
				.build()));

			JsonNode interleaved = Claude.lastContent(anthropic.requests.get(0));
			assertThat(interleaved).extracting((block) -> block.get("type").asString())
				.containsExactly("text", "image", "text", "document", "text");
			assertThat(interleaved.get(1).get("source").toString()).isEqualTo("{\"data\":\""
					+ Base64.getEncoder().encodeToString(png) + "\",\"media_type\":\"image/png\",\"type\":\"base64\"}");
			assertThat(interleaved.get(3).get("source").toString()).isEqualTo("{\"data\":\""
					+ Base64.getEncoder().encodeToString(pdf) + "\",\"media_type\":\"application/pdf\",\"type\":\"base64\"}");
			JsonNode textThenMedia = Claude.lastContent(anthropic.requests.get(1));
			assertThat(textThenMedia).extracting((block) -> block.get("type").asString())
				.containsExactly("text", "image", "image");
			assertThat(textThenMedia.get(1).get("source").get("media_type").asString()).isEqualTo("image/jpeg");
			assertThat(textThenMedia.get(2).get("source").get("media_type").asString()).isEqualTo("image/gif");
		}
	}

	@Test
	void anHttpsImageOrPdfIsSentAsAUrl() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("A bowl.")))) {
			Claude.model(anthropic)
				.call(new Prompt(UserMessage.builder()
					.text("What is this?")
					.media(new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/bowl.png")),
							new Media(new MimeType("application", "pdf"), URI.create("https://example.com/a.pdf")))
					.build()));

			JsonNode content = Claude.lastContent(anthropic.single());
			assertThat(content.get(1).get("source").toString())
				.isEqualTo("{\"type\":\"url\",\"url\":\"https://example.com/bowl.png\"}");
			assertThat(content.get(2).get("source").toString())
				.isEqualTo("{\"type\":\"url\",\"url\":\"https://example.com/a.pdf\"}");
		}
	}

	@Test
	void mediaOtherThanImagesAndPdfsIsRefusedBeforeTheRequest() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("?")))) {
			UserMessage audio = UserMessage.builder()
				.text("What is said?")
				.media(Media.builder().mimeType(new MimeType("audio", "mp3")).data(new byte[] { 1 }).build())
				.build();

			assertThatIllegalArgumentException().isThrownBy(() -> Claude.model(anthropic).call(new Prompt(audio)))
				.withMessageContaining("Unsupported media type: audio/mp3");
			assertThat(anthropic.requests).isEmpty();
		}
	}

	@Test
	void stopReasonsHaveACategory() {
		Map<String, FinishReasonCategory> categories = new LinkedHashMap<>();
		categories.put("end_turn", FinishReasonCategory.COMPLETED);
		categories.put("stop_sequence", FinishReasonCategory.COMPLETED);
		categories.put("max_tokens", FinishReasonCategory.TRUNCATED);
		categories.put("tool_use", FinishReasonCategory.TOOL_CALL);
		categories.put("refusal", FinishReasonCategory.FILTERED);
		categories.put("pause_turn", FinishReasonCategory.OTHER);
		try (StandIn anthropic = new StandIn((request) -> Claude.reply(request,
				Claude.messages(request).get(0).get("content").asString(), Claude.text("Hi")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			categories.forEach((reason, category) -> {
				assertThat(model.call(new Prompt(reason)).getResult().getMetadata().getFinishReasonCategory()).as(reason)
					.isEqualTo(category);
				ChatResponse last = model.stream(new Prompt(reason)).blockLast();
				assertThat(last.getResult().getMetadata().getFinishReasonCategory()).as(reason).isEqualTo(category);
			});
		}
	}

	@Test
	void maxRetriesRetriesAFailedRequest() {
		AtomicInteger attempts = new AtomicInteger();
		try (StandIn anthropic = new StandIn((request) -> (attempts.incrementAndGet() == 1)
				? Reply.json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}")
					.header("retry-after-ms", "1")
				: Claude.reply(request, "end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder().baseUrl(anthropic.url()).apiKey("test-key").maxRetries(1).build())
				.build();

			assertThat(model.call(new Prompt("Hello")).getResult().getOutput().getText()).isEqualTo("Hi");
			assertThat(anthropic.requests).hasSize(2);
		}
	}

	/**
	 * MiniMax and other Anthropic-compatible services are reached with a base URL that
	 * has a path, which the SDK keeps.
	 */
	@Test
	void aBaseUrlWithAPathReachesAnAnthropicCompatibleEndpoint() {
		try (StandIn minimax = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder()
					.baseUrl(minimax.url() + "/anthropic")
					.apiKey("test-key")
					.model("MiniMax-M3")
					.build())
				.build();

			model.call(new Prompt("Hello"));

			assertThat(minimax.single().path()).isEqualTo("/anthropic/v1/messages");
			assertThat(minimax.single().body().get("model").asString()).isEqualTo("MiniMax-M3");
		}
	}

	@Test
	void anHttpClientCustomizerIsCalledForBothClientsAndItsInterceptorsSeeEveryRequest() {
		AtomicInteger calls = new AtomicInteger();
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Hi")))) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder().baseUrl(anthropic.url()).apiKey("test-key").build())
				.httpClientBuilderCustomizer((builder) -> {
					calls.incrementAndGet();
					builder.interceptor((chain) -> chain
						.proceed(chain.request().newBuilder().header("X-Intercepted", "yes").build()));
				})
				.build();

			model.call(new Prompt("Hello"));
			model.stream(new Prompt("Hello")).blockLast();

			assertThat(calls).as("once for the sync client, once for the async one").hasValue(2);
			assertThat(anthropic.requests).extracting((request) -> request.header("X-Intercepted"))
				.containsExactly("yes", "yes");
		}
	}

	/**
	 * The result of the newest tool call in a request, or {@code null}.
	 */
	static String toolResult(Request request) {
		List<String> results = new ArrayList<>();
		for (JsonNode block : Claude.lastContent(request)) {
			if ("tool_result".equals(block.path("type").asString())) {
				JsonNode content = block.get("content");
				results.add(content.isString() ? content.asString() : content.get(0).get("text").asString());
			}
		}
		return results.isEmpty() ? null : results.get(results.size() - 1);
	}

	static class Shop {

		@Tool(description = "Tells where an order is")
		String orderStatus(@ToolParam(description = "The order id") String orderId) {
			return orderId + " shipped";
		}

	}

}
