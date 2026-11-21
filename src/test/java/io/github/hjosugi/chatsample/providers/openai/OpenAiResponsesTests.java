package io.github.hjosugi.chatsample.providers.openai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.HostedTool;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesException;
import org.springframework.ai.openai.responses.OpenAiResponsesMetadata;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.MimeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The Responses API page: what {@link OpenAiResponsesChatModel} sends to
 * {@code /v1/responses}, the parts it makes of the output items, and how it replays them.
 */
class OpenAiResponsesTests {

	@Test
	void aCallIsStatelessAsksForTheEncryptedReasoningAndReadsTheMessage() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", Gpt.message("msg_1", "Rome.")))) {
			ChatResponse response = Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder().model("gpt-5.4-mini"))
				.call(new Prompt(List.of(new SystemMessage("Be brief."), new SystemMessage("Answer in English."),
						new UserMessage("Plan a trip."))));

			Request request = openai.single();
			assertThat(request.path()).isEqualTo("/v1/responses");
			assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
			JsonNode body = request.body();
			assertThat(body.get("model").asString()).isEqualTo("gpt-5.4-mini");
			assertThat(body.get("store").asBoolean()).isFalse();
			assertThat(body.get("include").toString()).isEqualTo("[\"reasoning.encrypted_content\"]");
			assertThat(body.get("instructions").asString()).as("the first system message").isEqualTo("Be brief.");
			assertThat(body.get("input").toString())
				.isEqualTo("[{\"content\":\"Answer in English.\",\"role\":\"developer\",\"type\":\"message\"},"
						+ "{\"content\":\"Plan a trip.\",\"role\":\"user\",\"type\":\"message\"}]");
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getText()).isEqualTo("Rome.");
			assertThat(message.getParts()).singleElement().isInstanceOfSatisfying(TextPart.class,
					(part) -> assertThat(part.attributes()).containsEntry(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "msg_1")
						.containsEntry(OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE, "completed"));
			assertThat(message.getMetadata()).as("nothing but the type every message has").containsOnlyKeys("messageType");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
			assertThat((String) response.getResult().getMetadata().get(OpenAiResponsesMetadata.STATUS))
				.isEqualTo("completed");
			assertThat(response.getMetadata().getId()).isEqualTo("resp_1");
			assertThat((Long) response.getMetadata().get(OpenAiResponsesMetadata.CREATED_AT)).isEqualTo(1700000000L);
			assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(40);
		}
	}

	@Test
	void theResponsesOptionsReachTheRequest() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", Gpt.message("msg_1", "Hi")))) {
			Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder())
				.call(new Prompt("Hello", OpenAiResponsesChatOptions.builder()
					.model("gpt-5.4")
					.maxOutputTokens(1024)
					.temperature(0.3)
					.topP(0.8)
					.reasoningEffort("medium")
					.reasoningSummary("auto")
					.verbosity("low")
					.parallelToolCalls(false)
					.maxToolCalls(3)
					.toolChoice("none")
					.include(List.of("web_search_call.results"))
					.truncation("auto")
					.serviceTier("priority")
					.promptCacheKey("orders")
					.safetyIdentifier("user-42")
					.metadata(Map.of("shop", "alice"))
					.extraBody(Map.of("custom_flag", true))
					.build()));

			JsonNode body = openai.single().body();
			assertThat(body.get("model").asString()).isEqualTo("gpt-5.4");
			assertThat(body.get("max_output_tokens").asInt()).isEqualTo(1024);
			assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
			assertThat(body.get("top_p").asDouble()).isEqualTo(0.8);
			assertThat(body.get("reasoning").toString()).isEqualTo("{\"effort\":\"medium\",\"summary\":\"auto\"}");
			assertThat(body.get("text").toString()).isEqualTo("{\"verbosity\":\"low\"}");
			assertThat(body.get("parallel_tool_calls").asBoolean()).isFalse();
			assertThat(body.get("max_tool_calls").asInt()).isEqualTo(3);
			assertThat(body.get("tool_choice").asString()).isEqualTo("none");
			assertThat(body.get("include").toString())
				.isEqualTo("[\"reasoning.encrypted_content\",\"web_search_call.results\"]");
			assertThat(body.get("truncation").asString()).isEqualTo("auto");
			assertThat(body.get("service_tier").asString()).isEqualTo("priority");
			assertThat(body.get("prompt_cache_key").asString()).isEqualTo("orders");
			assertThat(body.get("safety_identifier").asString()).isEqualTo("user-42");
			assertThat(body.get("metadata").toString()).isEqualTo("{\"shop\":\"alice\"}");
			assertThat(body.get("custom_flag").asBoolean()).isTrue();
			assertThat(body.get("store").asBoolean()).isFalse();
		}
	}

	/**
	 * The migration section says so: options of {@code OpenAiChatOptions} merge like any
	 * other provider's, keeping the portable fields only.
	 */
	@Test
	void chatCompletionsOptionsKeepOnlyTheirPortableFields() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", Gpt.message("msg_1", "Hi")))) {
			Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder().model("gpt-5.4-mini"))
				.call(new Prompt("Hello", OpenAiChatOptions.builder()
					.model("gpt-5.4")
					.temperature(0.5)
					.reasoningEffort("high")
					.stop(List.of("END"))
					.build()));

			JsonNode body = openai.single().body();
			assertThat(body.get("model").asString()).isEqualTo("gpt-5.4");
			assertThat(body.get("temperature").asDouble()).isEqualTo(0.5);
			assertThat(body.has("reasoning")).isFalse();
			assertThat(body.has("stop")).isFalse();
		}
	}

	@Test
	void reasoningIsAReasoningPartWithItsEncryptedContentAndItsSummaryInTheMetadata() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed",
				Gpt.reasoning("rs_1", "Weighing Rome and Florence.", "enc-1"), Gpt.message("msg_1", "Rome.")))) {
			ChatResponse response = Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder())
				.call(new Prompt("Plan a trip.", OpenAiResponsesChatOptions.builder().reasoningEffort("medium").build()));

			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getParts()).hasSize(2);
			assertThat(message.getParts().get(0)).isInstanceOfSatisfying(ReasoningPart.class, (reasoning) -> {
				assertThat(reasoning.summary()).isEqualTo("Weighing Rome and Florence.");
				assertThat(reasoning.payload())
					.isEqualTo(new OpaquePayload(OpenAiResponsesMetadata.PROVIDER, "encrypted_content", "enc-1"));
				assertThat(reasoning.attributes()).containsEntry(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_1");
			});
			assertThat(message.getText()).isEqualTo("Rome.");
			assertThat((String) response.getResult().getMetadata().get(OpenAiResponsesMetadata.REASONING_CONTENT))
				.isEqualTo("Weighing Rome and Florence.");
			com.openai.models.responses.ResponseUsage usage = (com.openai.models.responses.ResponseUsage) response
				.getMetadata()
				.getUsage()
				.getNativeUsage();
			assertThat(usage.outputTokensDetails().reasoningTokens()).isEqualTo(7L);
		}
	}

	@Test
	void aToolLoopReplaysTheReasoningAndTheFunctionCallAsTheyCameInACallAndInAStream() {
		try (StandIn openai = new StandIn((request) -> lastInputType(request).equals("function_call_output")
				? Gpt.response(request, "completed", Gpt.message("msg_2", "Shipped."))
				: Gpt.response(request, "completed", Gpt.reasoning("rs_1", "Look it up.", "enc-1"),
						Gpt.functionCall("fc_1", "call_1", "orderStatus", "{\"orderId\":\"A-1\"}")))) {
			ChatClient client = ChatClient.create(Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()));

			String called = client.prompt("Where is A-1?").tools(new Shop()).call().content();
			String streamed = String.join("",
					client.prompt("Where is A-1?").tools(new Shop()).stream().content().collectList().block());

			assertThat(called).isEqualTo("Shipped.");
			assertThat(streamed).isEqualTo("Shipped.");
			JsonNode tool = openai.requests.get(0).body().get("tools").get(0);
			assertThat(tool.get("type").asString()).isEqualTo("function");
			assertThat(tool.get("name").asString()).isEqualTo("orderStatus");
			assertThat(tool.get("strict").asBoolean()).as("non-strict unless set").isFalse();
			for (Request second : List.of(openai.requests.get(1), openai.requests.get(3))) {
				JsonNode input = second.body().get("input");
				assertThat(input).extracting((item) -> item.path("type").asString())
					.containsExactly("message", "reasoning", "function_call", "function_call_output");
				assertThat(input.get(1).get("id").asString()).isEqualTo("rs_1");
				assertThat(input.get(1).get("encrypted_content").asString()).isEqualTo("enc-1");
				assertThat(input.get(2).get("call_id").asString()).isEqualTo("call_1");
				assertThat(input.get(2).get("id").asString()).isEqualTo("fc_1");
				assertThat(input.get(2).get("arguments").asString()).isEqualTo("{\"orderId\":\"A-1\"}");
				assertThat(input.get(3).get("call_id").asString()).isEqualTo("call_1");
				assertThat(input.get(3).get("output").asString()).isEqualTo("\"A-1 shipped\"");
			}
		}
	}

	@Test
	void reasoningOfAnotherProviderIsNotReplayed() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", Gpt.message("msg_1", "Hi")))) {
			AssistantMessage fromClaude = AssistantMessage.builder()
				.part(new ReasoningPart("Hmm.", null, new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
				.part(TextPart.of("Rome."))
				.build();

			Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder())
				.call(new Prompt(List.of(new UserMessage("Where?"), fromClaude, new UserMessage("Why?"))));

			assertThat(openai.single().body().get("input")).extracting((item) -> item.path("type").asString())
				.doesNotContain("reasoning");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void hostedToolsAreSentAndTheirCallsAreUnknownPartsNotToolCalls() {
		String webSearchCall = "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\","
				+ "\"action\":{\"type\":\"search\",\"query\":\"spring ai\"}}";
		String cited = "{\"type\":\"message\",\"id\":\"msg_1\",\"status\":\"completed\",\"role\":\"assistant\","
				+ "\"content\":[{\"type\":\"output_text\",\"text\":\"Spring AI 2.1\",\"annotations\":[{\"type\":\"url_citation\","
				+ "\"start_index\":0,\"end_index\":9,\"url\":\"https://spring.io\",\"title\":\"Spring\"}]}]}";
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", webSearchCall, cited))) {
			ChatResponse response = Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder())
				.call(new Prompt("Latest Spring AI?", OpenAiResponsesChatOptions.builder()
					.hostedTools(new HostedTool.WebSearch("high", List.of("spring.io")), HostedTool.FileSearch.of("vs_abc123"),
							HostedTool.CodeInterpreter.of(), HostedTool.Mcp.of("my-server", "https://mcp.example.com"),
							HostedTool.ImageGeneration.of(), new HostedTool.Raw(Map.of("type", "local_shell")))
					.build()));

			JsonNode tools = openai.single().body().get("tools");
			assertThat(tools).extracting((tool) -> tool.get("type").asString())
				.containsExactly("web_search", "file_search", "code_interpreter", "mcp", "image_generation", "local_shell");
			assertThat(tools.get(0).get("search_context_size").asString()).isEqualTo("high");
			assertThat(tools.get(1).get("vector_store_ids").toString()).isEqualTo("[\"vs_abc123\"]");
			assertThat(tools.get(3).get("server_label").asString()).isEqualTo("my-server");
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getToolCalls()).isEmpty();
			assertThat(message.getParts().get(0)).isInstanceOfSatisfying(UnknownPart.class, (part) -> {
				assertThat(part.provider()).isEqualTo(OpenAiResponsesMetadata.PROVIDER);
				assertThat(part.kind()).isEqualTo("web_search_call");
				assertThat(part.rawJson()).contains("\"query\":\"spring ai\"");
			});
			assertThat((List<Map<String, Object>>) response.getResult().getMetadata().get(OpenAiResponsesMetadata.HOSTED_TOOL_CALLS))
				.containsExactly(Map.of("type", "web_search_call", "id", "ws_1", "status", "completed"));
			assertThat((List<Map<String, Object>>) response.getResult().getMetadata().get(OpenAiResponsesMetadata.ANNOTATIONS))
				.singleElement()
				.satisfies((annotation) -> assertThat(annotation).containsEntry("url", "https://spring.io"));
		}
	}

	@Test
	void anMcpToolThatAlwaysAsksForApprovalIsRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new HostedTool.Mcp("my-server", "https://mcp.example.com", null, null, null, "always"))
			.withMessageContaining("requireApproval=always is not supported");
	}

	@Test
	void audioInputIsRefusedWithAClearMessage() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed", Gpt.message("msg_1", "Hi")))) {
			UserMessage audio = UserMessage.builder()
				.text("What is said?")
				.media(Media.builder().mimeType(new MimeType("audio", "mp3")).data(new byte[] { 1 }).build())
				.build();

			assertThatIllegalArgumentException()
				.isThrownBy(() -> Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()).call(new Prompt(audio)))
				.withMessageContaining("Audio input is not supported by the OpenAI Responses API");
			assertThat(openai.requests).isEmpty();
		}
	}

	@Test
	void anIncompleteResponseEndsWithLengthAndItsReason() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "incomplete", Gpt.message("msg_1", "Ro")))) {
			ChatResponse response = Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()).call(new Prompt("Hi"));

			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("LENGTH");
			assertThat((String) response.getResult().getMetadata().get(OpenAiResponsesMetadata.INCOMPLETE_REASON))
				.isEqualTo("max_output_tokens");
		}
	}

	@Test
	void aFailedResponseRaisesAnException() {
		try (StandIn openai = new StandIn((request) -> Reply.json(Gpt.response("failed")
			.replace("\"error\":null", "\"error\":{\"code\":\"server_error\",\"message\":\"Something broke\"}")))) {
			assertThatExceptionOfType(OpenAiResponsesException.class)
				.isThrownBy(() -> Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()).call(new Prompt("Hi")))
				.withMessageContaining("Something broke");
		}
	}

	@Test
	void aStreamDeliversTheTextInPiecesAndAggregatesToTheSameParts() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed",
				Gpt.reasoning("rs_1", "Weighing.", "enc-1"), Gpt.message("msg_1", "Rome is best.")))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			List<ChatResponse> chunks = new MessageAggregator()
				.aggregate(Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()).stream(new Prompt("Where?")),
						aggregated::set)
				.collectList()
				.block();

			assertThat(openai.single().body().get("stream").asBoolean()).isTrue();
			assertThat(chunks).extracting((chunk) -> chunk.getResult().getOutput().getText()).contains("Rome i", "s best.");
			AssistantMessage message = aggregated.get().getResult().getOutput();
			assertThat(message.getText()).isEqualTo("Rome is best.");
			assertThat(message.getReasoning()).singleElement()
				.satisfies((reasoning) -> assertThat(reasoning.payload().data()).isEqualTo("enc-1"));
			assertThat(aggregated.get().getMetadata().getId()).isEqualTo("resp_1");
			assertThat(aggregated.get().getMetadata().containsKey(OpenAiResponsesMetadata.STATUS))
				.as("the openai.responses.* keys do not survive aggregation")
				.isFalse();
		}
	}

	@Test
	void nativeStructuredOutputBecomesTheTextFormat() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed",
				Gpt.message("msg_1", "{\"actor\":\"Tom Hanks\",\"movies\":[\"Big\"]}")))) {
			ActorFilms films = ChatClient.create(Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()))
				.prompt("Generate the filmography for a random actor.")
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.call()
				.entity(ActorFilms.class);

			JsonNode format = openai.single().body().get("text").get("format");
			assertThat(format.get("type").asString()).isEqualTo("json_schema");
			assertThat(format.get("name").asString()).isEqualTo("json_schema");
			assertThat(format.get("strict").asBoolean()).isTrue();
			assertThat(format.get("schema").get("properties").has("movies")).isTrue();
			assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big")));
		}
	}

	@Test
	void theToolCallsOfAStreamArePartialPartsUntilTheItemIsDone() {
		try (StandIn openai = new StandIn((request) -> Gpt.response(request, "completed",
				Gpt.functionCall("fc_1", "call_1", "orderStatus", "{\"orderId\":\"A-1\"}")))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			new MessageAggregator()
				.aggregate(Gpt.responsesModel(openai, OpenAiResponsesChatOptions.builder()).stream(new Prompt("Where?")),
						aggregated::set)
				.blockLast();

			assertThat(aggregated.get().getResult().getOutput().getParts()).singleElement()
				.isInstanceOfSatisfying(ToolCallPart.class,
						(part) -> assertThat(part.toolCall().id()).isEqualTo("call_1"));
			assertThat(aggregated.get().getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		}
	}

	private static String lastInputType(Request request) {
		JsonNode input = request.body().get("input");
		return input.get(input.size() - 1).path("type").asString();
	}

	record ActorFilms(String actor, List<String> movies) {
	}

	static class Shop {

		@Tool(description = "Tells where an order is")
		String orderStatus(String orderId) {
			return orderId + " shipped";
		}

	}

}
