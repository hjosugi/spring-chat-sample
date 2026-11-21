package io.github.hjosugi.chatsample.providers.others;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The DeepSeek page: what {@link DeepSeekChatModel} sends to {@code /chat/completions},
 * thinking and reasoning effort, the reasoning parts and their replay in a tool loop,
 * chat prefix completion on the beta endpoint, and the {@code spring.ai.deepseek.*}
 * properties.
 */
class DeepSeekChatTests {

	private static final JsonMapper json = JsonMapper.shared();

	private static final String REASONED = "{\"role\":\"assistant\",\"reasoning_content\":\"9.8 has more tenths.\","
			+ "\"content\":\"9.8\"}";

	@Test
	void aCallSendsThinkingAndReasoningEffortAndTheReasoningBecomesAReasoningPart() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "stop", REASONED))) {
			ChatResponse response = model(deepseek)
				.call(new Prompt("9.11 or 9.8?", DeepSeekChatOptions.builder()
					.model("deepseek-v4-pro")
					.enableThinking()
					.reasoningEffortMax()
					.temperature(0.6)
					.maxTokens(100)
					.build()));

			Request request = deepseek.single();
			assertThat(request.path()).isEqualTo("/chat/completions");
			assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
			JsonNode body = request.body();
			assertThat(body.get("model").asString()).isEqualTo("deepseek-v4-pro");
			assertThat(body.get("thinking").toString()).isEqualTo("{\"type\":\"enabled\"}");
			assertThat(body.get("reasoning_effort").asString()).isEqualTo("max");
			assertThat(body.get("temperature").asDouble()).isEqualTo(0.6);
			assertThat(body.get("max_tokens").asInt()).isEqualTo(100);
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getParts()).containsExactly(ReasoningPart.of("9.8 has more tenths."), TextPart.of("9.8"));
		}
	}

	@Test
	void aStreamAggregatesToOneReasoningPartAndOneTextPart() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "stop", REASONED))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			new MessageAggregator().aggregate(model(deepseek).stream(new Prompt("9.11 or 9.8?")), aggregated::set)
				.blockLast();

			assertThat(aggregated.get().getResult().getOutput().getParts())
				.containsExactly(ReasoningPart.of("9.8 has more tenths."), TextPart.of("9.8"));
		}
	}

	@Test
	void aToolLoopInThinkingModeReplaysTheReasoningOfTheToolCallTurn() {
		try (StandIn deepseek = new StandIn((request) -> {
			JsonNode messages = request.body().get("messages");
			return "tool".equals(messages.get(messages.size() - 1).get("role").asString())
					? reply(request, "stop", "{\"role\":\"assistant\",\"content\":\"Shipped.\"}")
					: reply(request, "tool_calls", "{\"role\":\"assistant\",\"reasoning_content\":\"Look it up.\",\"content\":\"\","
							+ "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"orderStatus\","
							+ "\"arguments\":\"{\\\"orderId\\\":\\\"A-1\\\"}\"}}]}");
		})) {
			ChatClient client = ChatClient.create(model(deepseek));

			client.prompt("Where is A-1?").tools(new Shop()).call().content();
			client.prompt("Where is A-1?").tools(new Shop()).stream().content().collectList().block();

			for (Request second : List.of(deepseek.requests.get(1), deepseek.requests.get(3))) {
				JsonNode assistant = second.body().get("messages").get(1);
				assertThat(assistant.get("reasoning_content").asString()).isEqualTo("Look it up.");
				assertThat(assistant.get("tool_calls").get(0).get("id").asString()).isEqualTo("call_1");
			}
		}
	}

	@Test
	void anAssistantMessageFlaggedAsPrefixGoesToTheBetaEndpoint() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "stop",
				"{\"role\":\"assistant\",\"content\":\"def quick_sort(xs): ...\"}"))) {
			Message prefix = AssistantMessage.builder()
				.content("```python\n")
				.properties(Map.of(DeepSeekChatModel.PREFIX_METADATA_KEY, true))
				.build();

			model(deepseek).call(new Prompt(List.of(new UserMessage("Please write quick sort code"), prefix),
					DeepSeekChatOptions.builder().stop(List.of("```")).build()));

			Request request = deepseek.single();
			assertThat(request.path()).isEqualTo("/beta/chat/completions");
			JsonNode last = request.body().get("messages").get(1);
			assertThat(last.get("prefix").asBoolean()).isTrue();
			assertThat(last.get("content").asString()).isEqualTo("```python\n");
			assertThat(request.body().get("stop").toString()).isEqualTo("[\"```\"]");
		}
	}

	/**
	 * The prefix completion example of the page puts portable {@code ChatOptions} on the
	 * prompt; 2.1.0 casts them to {@code DeepSeekChatOptions} ({@code createRequest}) and
	 * fails before sending anything.
	 */
	@Test
	void thePrefixExampleOfThePageFailsWithAClassCastException() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "stop", "{\"role\":\"assistant\",\"content\":\"x\"}"))) {
			Message prefix = AssistantMessage.builder()
				.content("```python\n")
				.properties(Map.of(DeepSeekChatModel.PREFIX_METADATA_KEY, true))
				.build();

			assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> model(deepseek)
				.call(new Prompt(List.of(new UserMessage("Please write quick sort code"), prefix),
						ChatOptions.builder().stopSequences(List.of("```")).build())));
			assertThat(deepseek.requests).isEmpty();
		}
	}

	/**
	 * The comparison page says DeepSeek has native structured output. Its options do not
	 * implement {@code StructuredOutputChatOptions}, so the {@code ChatClient} falls back
	 * to format instructions in the prompt, and no response format is sent.
	 */
	@Test
	void structuredOutputIsNotNativeButAskedForInThePrompt() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "stop",
				"{\"role\":\"assistant\",\"content\":\"{\\\"actor\\\":\\\"Tom Hanks\\\",\\\"movies\\\":[\\\"Big\\\"]}\"}"))) {
			ActorFilms films = ChatClient.create(model(deepseek))
				.prompt("Filmography")
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.call()
				.entity(ActorFilms.class);

			JsonNode body = deepseek.single().body();
			assertThat(body.has("response_format")).isFalse();
			assertThat(body.get("messages").get(0).get("content").asString()).contains("Filmography", "JSON");
			assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big")));
		}
	}

	/**
	 * The 2.1 upgrade guide: a tool-call-only choice has no text part, the message is
	 * still a {@code DeepSeekAssistantMessage}, deprecated, and the prefix flag counts on
	 * the last message only.
	 */
	@Test
	@SuppressWarnings("removal")
	void aToolCallOnlyChoiceHasNoTextPartAndThePrefixFlagCountsOnTheLastMessageOnly() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "tool_calls", "{\"role\":\"assistant\",\"content\":\"\","
				+ "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"orderStatus\","
				+ "\"arguments\":\"{}\"}}]}"))) {
			DeepSeekChatModel model = model(deepseek);
			AssistantMessage flagged = AssistantMessage.builder()
				.content("Earlier")
				.properties(Map.of(DeepSeekChatModel.PREFIX_METADATA_KEY, true))
				.build();

			AssistantMessage message = model
				.call(new Prompt(List.of(new UserMessage("Hi"), flagged, new UserMessage("Where is A-1?"))))
				.getResult()
				.getOutput();

			assertThat(message).isInstanceOf(org.springframework.ai.deepseek.DeepSeekAssistantMessage.class);
			assertThat(message.getParts()).singleElement().isInstanceOf(org.springframework.ai.chat.messages.part.ToolCallPart.class);
			assertThat(message.getText()).isEmpty();
			assertThat(deepseek.single().path()).isEqualTo("/chat/completions");
			assertThat(deepseek.single().body().get("messages").get(1).has("prefix")).isFalse();
		}
	}

	@Test
	void thePropertiesReachTheRequestAndAnOptionOfARequestWinsOverThem() {
		try (StandIn deepseek = new StandIn((request) -> reply(request, "stop", "{\"role\":\"assistant\",\"content\":\"Hi\"}"))) {
			new ApplicationContextRunner()
				.withConfiguration(
						AutoConfigurations.of(DeepSeekChatAutoConfiguration.class, ToolCallingAutoConfiguration.class))
				.withPropertyValues("spring.ai.deepseek.base-url=" + deepseek.url(), "spring.ai.deepseek.api-key=test-key",
						"spring.ai.deepseek.chat.model=deepseek-v4-flash", "spring.ai.deepseek.chat.temperature=0.2",
						"spring.ai.deepseek.chat.thinking.type=disabled", "spring.ai.deepseek.chat.reasoning-effort=high",
						"spring.ai.deepseek.chat.completions-path=/v1/chat/completions")
				.run((context) -> {
					DeepSeekChatModel model = context.getBean(DeepSeekChatModel.class);
					model.call(new Prompt("Hello"));
					ChatClient.create(model).prompt("Hello").options(DeepSeekChatOptions.builder().temperature(0.9)).call().content();

					Request request = deepseek.requests.get(0);
					assertThat(request.path()).isEqualTo("/v1/chat/completions");
					JsonNode body = request.body();
					assertThat(body.get("model").asString()).isEqualTo("deepseek-v4-flash");
					assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
					assertThat(body.get("thinking").toString()).isEqualTo("{\"type\":\"disabled\"}");
					assertThat(body.get("reasoning_effort").asString()).isEqualTo("high");
					assertThat(deepseek.requests.get(1).body().get("temperature").asDouble()).isEqualTo(0.9);
				});
		}
	}

	static DeepSeekChatModel model(StandIn standIn) {
		return DeepSeekChatModel.builder()
			.deepSeekApi(DeepSeekApi.builder().baseUrl(standIn.url()).apiKey("test-key").build())
			.options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build())
			.build();
	}

	/**
	 * A chat completion of DeepSeek, whole or streamed: reasoning and content in two
	 * deltas each, the tool calls in a last delta.
	 */
	static Reply reply(Request request, String finishReason, String message) {
		String usage = "\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":15,\"total_tokens\":40}";
		if (!request.body().path("stream").asBoolean()) {
			return Reply.json("{\"id\":\"ds-1\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"deepseek-v4-flash\","
					+ "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finishReason + "\"}],"
					+ usage + "}");
		}
		JsonNode node = json.readTree(message);
		List<String> deltas = new ArrayList<>();
		for (String field : List.of("reasoning_content", "content")) {
			if (node.hasNonNull(field) && !node.get(field).asString().isEmpty()) {
				String text = node.get(field).asString();
				deltas.add("{\"role\":\"assistant\",\"" + field + "\":" + json.writeValueAsString(text.substring(0, text.length() / 2)) + "}");
				deltas.add("{\"" + field + "\":" + json.writeValueAsString(text.substring(text.length() / 2)) + "}");
			}
		}
		if (node.has("tool_calls")) {
			JsonNode call = node.get("tool_calls").get(0);
			deltas.add("{\"tool_calls\":[{\"index\":0,\"id\":" + call.get("id") + ",\"type\":\"function\",\"function\":{\"name\":"
					+ call.get("function").get("name") + ",\"arguments\":" + call.get("function").get("arguments") + "}}]}");
		}
		List<String> events = new ArrayList<>();
		for (int i = 0; i < deltas.size(); i++) {
			boolean last = i == deltas.size() - 1;
			events.add(StandIn.data("{\"id\":\"ds-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
					+ "\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":" + deltas.get(i)
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
