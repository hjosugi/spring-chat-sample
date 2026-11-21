package io.github.hjosugi.chatsample.providers.others;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.ai.mistralai.api.MistralAiApi.ChatCompletionRequest.ReasoningEffort;
import org.springframework.ai.mistralai.api.MistralAiApi.ChatCompletionRequest.ResponseFormat;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Mistral AI chat page, with the model module only: the starter is not on the
 * classpath (see the report). What {@link MistralAiChatModel} sends to
 * {@code /v1/chat/completions}, and the parts it makes of the content chunks of the
 * reply, thinking included.
 */
class MistralAiChatTests {

	private static final JsonMapper json = JsonMapper.shared();

	private static final String THINKING_REPLY = "{\"role\":\"assistant\",\"content\":["
			+ "{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"Divide by 8.\"}],\"signature\":\"sig-m\"},"
			+ "{\"type\":\"text\",\"text\":\"x = -3.75\"},{\"type\":\"reference\",\"reference_ids\":[1]}]}";

	@Test
	void aCallSendsTheOptionsWithTheKeyAsABearerTokenAndReadsTheReply() {
		try (StandIn mistral = new StandIn((request) -> reply(request, "stop", "{\"role\":\"assistant\",\"content\":\"Hi\"}"))) {
			ChatResponse response = model(mistral)
				.call(new Prompt("Hello", MistralAiChatOptions.builder()
					.model("mistral-small-2603")
					.temperature(0.3)
					.topP(0.9)
					.maxTokens(100)
					.safePrompt(true)
					.randomSeed(7)
					.stop(List.of("END"))
					.reasoningEffort(ReasoningEffort.HIGH)
					.build()));

			Request request = mistral.single();
			assertThat(request.path()).isEqualTo("/v1/chat/completions");
			assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
			JsonNode body = request.body();
			assertThat(body.get("model").asString()).isEqualTo("mistral-small-2603");
			assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
			assertThat(body.get("top_p").asDouble()).isEqualTo(0.9);
			assertThat(body.get("max_tokens").asInt()).isEqualTo(100);
			assertThat(body.get("safe_prompt").asBoolean()).isTrue();
			assertThat(body.get("random_seed").asInt()).isEqualTo(7);
			assertThat(body.get("stop").toString()).isEqualTo("[\"END\"]");
			assertThat(body.get("reasoning_effort").asString()).isEqualTo("high");
			assertThat(response.getResult().getOutput().getText()).isEqualTo("Hi");
			assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(40);
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void contentChunksBecomePartsInOrderWithTheThinkingAsAReasoningPart() {
		try (StandIn mistral = new StandIn((request) -> reply(request, "stop", THINKING_REPLY))) {
			AssistantMessage message = model(mistral).call(new Prompt("Solve 8x + 7 = -23")).getResult().getOutput();

			List<MessagePart> parts = message.getParts();
			assertThat(parts).hasSize(3);
			assertThat(parts.get(0)).isInstanceOfSatisfying(ReasoningPart.class, (reasoning) -> {
				assertThat(reasoning.text()).isEqualTo("Divide by 8.");
				assertThat(reasoning.payload().data()).isEqualTo("sig-m");
				assertThat(reasoning.attributes()).containsEntry(MessagePart.PROVIDER_ATTRIBUTE, "mistral_ai");
			});
			assertThat(parts.get(1)).isEqualTo(TextPart.of("x = -3.75"));
			assertThat(parts.get(2)).isInstanceOfSatisfying(UnknownPart.class,
					(reference) -> assertThat(reference.rawJson()).contains("reference_ids"));
			assertThat(message.getText()).isEqualTo("x = -3.75");
			assertThat(message.getMetadata()).containsEntry(MistralAiChatModel.THINKING_CONTENT_METADATA, "Divide by 8.");
		}
	}

	@Test
	void theThinkingIsReplayedAsAThinkingChunkInATurnThatGoesOn() {
		try (StandIn mistral = new StandIn((request) -> reply(request, "stop", THINKING_REPLY))) {
			MistralAiChatModel model = model(mistral);
			AssistantMessage first = model.call(new Prompt("Solve 8x + 7 = -23")).getResult().getOutput();

			model.call(new Prompt(List.of(new UserMessage("Solve 8x + 7 = -23"), first, new UserMessage("Why?"))));

			JsonNode content = mistral.last().body().get("messages").get(1).get("content");
			assertThat(content.get(0).get("type").asString()).isEqualTo("thinking");
			assertThat(content.get(0).get("signature").asString()).isEqualTo("sig-m");
			assertThat(content.get(1).get("text").asString()).isEqualTo("x = -3.75");
			assertThat(content.get(2).get("type").asString()).isEqualTo("reference");
		}
	}

	@Test
	void aStreamAggregatesItsThinkingAndTextDeltasIntoOnePartEach() {
		try (StandIn mistral = new StandIn((request) -> reply(request, "stop", THINKING_REPLY))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			new MessageAggregator().aggregate(model(mistral).stream(new Prompt("Solve")), aggregated::set).blockLast();

			assertThat(mistral.single().body().get("stream").asBoolean()).isTrue();
			List<MessagePart> parts = aggregated.get().getResult().getOutput().getParts();
			assertThat(parts.get(0)).isInstanceOfSatisfying(ReasoningPart.class,
					(reasoning) -> assertThat(reasoning.text()).isEqualTo("Divide by 8."));
			assertThat(parts.get(1)).isEqualTo(TextPart.of("x = -3.75"));
		}
	}

	@Test
	void theChatClientRunsTheToolsMistralAsksFor() {
		try (StandIn mistral = new StandIn((request) -> {
			JsonNode messages = request.body().get("messages");
			return "tool".equals(messages.get(messages.size() - 1).get("role").asString())
					? reply(request, "stop", "{\"role\":\"assistant\",\"content\":\"Shipped.\"}")
					: reply(request, "tool_calls", "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"abc123def\","
							+ "\"type\":\"function\",\"function\":{\"name\":\"orderStatus\",\"arguments\":\"{\\\"orderId\\\":\\\"A-1\\\"}\"}}]}");
		})) {
			String answer = ChatClient.create(model(mistral)).prompt("Where is A-1?").tools(new Shop()).call().content();

			assertThat(answer).isEqualTo("Shipped.");
			assertThat(mistral.requests.get(0).body().get("tools").get(0).get("function").get("name").asString())
				.isEqualTo("orderStatus");
			JsonNode toolMessage = mistral.requests.get(1).body().get("messages").get(2);
			assertThat(toolMessage.get("tool_call_id").asString()).isEqualTo("abc123def");
			assertThat(toolMessage.get("name").asString()).isEqualTo("orderStatus");
		}
	}

	@Test
	void structuredOutputAndJsonModeBecomeTheResponseFormat() {
		try (StandIn mistral = new StandIn((request) -> reply(request, "stop",
				"{\"role\":\"assistant\",\"content\":\"{\\\"actor\\\":\\\"Tom Hanks\\\",\\\"movies\\\":[\\\"Big\\\"]}\"}"))) {
			MistralAiChatModel model = model(mistral);

			ActorFilms films = ChatClient.create(model)
				.prompt("Filmography")
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.call()
				.entity(ActorFilms.class);
			model.call(new Prompt("JSON", MistralAiChatOptions.builder().responseFormat(ResponseFormat.jsonObject()).build()));
			model.call(new Prompt("Schema",
					MistralAiChatOptions.builder().responseFormat(ResponseFormat.jsonSchema(ActorFilms.class)).build()));

			JsonNode nativeFormat = mistral.requests.get(0).body().get("response_format");
			assertThat(nativeFormat.get("type").asString()).isEqualTo("json_schema");
			assertThat(nativeFormat.get("json_schema").get("schema").get("properties").has("movies")).isTrue();
			assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big")));
			assertThat(mistral.requests.get(1).body().get("response_format").toString()).isEqualTo("{\"type\":\"json_object\"}");
			assertThat(mistral.requests.get(2).body().get("response_format").get("type").asString()).isEqualTo("json_schema");
		}
	}

	@Test
	void anImageIsSentAsAnImageUrlChunk() {
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		try (StandIn mistral = new StandIn((request) -> reply(request, "stop", "{\"role\":\"assistant\",\"content\":\"A bowl\"}"))) {
			model(mistral).call(new Prompt(UserMessage.builder()
				.text("What is this?")
				.media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(png).build())
				.build()));

			JsonNode content = mistral.single().body().get("messages").get(0).get("content");
			assertThat(content.get(0).get("type").asString()).isEqualTo("text");
			assertThat(content.get(1).get("type").asString()).isEqualTo("image_url");
			assertThat(content.get(1).get("image_url").toString())
				.contains("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
		}
	}

	static MistralAiChatModel model(StandIn standIn) {
		return MistralAiChatModel.builder()
			.mistralAiApi(MistralAiApi.builder().baseUrl(standIn.url()).apiKey("test-key").build())
			.options(MistralAiChatOptions.builder().model("mistral-small-2603").build())
			.build();
	}

	/**
	 * A chat completion of Mistral, whole or streamed: a string content in two deltas, a
	 * list of chunks one chunk per delta, the tool calls in a last delta.
	 */
	static Reply reply(Request request, String finishReason, String message) {
		String usage = "\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":15,\"total_tokens\":40}";
		if (!request.body().path("stream").asBoolean()) {
			return Reply.json("{\"id\":\"cmpl-1\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"mistral-small-2603\","
					+ "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finishReason + "\"}],"
					+ usage + "}");
		}
		JsonNode node = json.readTree(message);
		List<String> deltas = new ArrayList<>();
		JsonNode content = node.get("content");
		if (content.isString() && !content.asString().isEmpty()) {
			String text = content.asString();
			deltas.add("{\"role\":\"assistant\",\"content\":" + json.writeValueAsString(text.substring(0, text.length() / 2)) + "}");
			deltas.add("{\"content\":" + json.writeValueAsString(text.substring(text.length() / 2)) + "}");
		}
		else if (content.isArray()) {
			for (JsonNode chunk : content) {
				deltas.add("{\"role\":\"assistant\",\"content\":[" + chunk + "]}");
			}
		}
		if (node.has("tool_calls")) {
			deltas.add("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":" + node.get("tool_calls") + "}");
		}
		List<String> events = new ArrayList<>();
		for (int i = 0; i < deltas.size(); i++) {
			boolean last = i == deltas.size() - 1;
			events.add(StandIn.data("{\"id\":\"cmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
					+ "\"model\":\"mistral-small-2603\",\"choices\":[{\"index\":0,\"delta\":" + deltas.get(i)
					+ ",\"finish_reason\":" + (last ? "\"" + finishReason + "\"" : "null") + "}]" + (last ? "," + usage : "")
					+ "}"));
		}
		events.add(StandIn.data("[DONE]"));
		return Reply.events(events);
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
