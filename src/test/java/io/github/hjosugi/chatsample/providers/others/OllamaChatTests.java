package io.github.hjosugi.chatsample.providers.others;

import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ollama chat page: what {@link OllamaChatModel} sends to {@code /api/chat}, what it
 * makes of the reply, whole or as the newline-delimited JSON Ollama streams, thinking
 * included, and the {@code spring.ai.ollama.*} properties.
 */
class OllamaChatTests {

	private static final JsonMapper json = JsonMapper.shared();

	private static final String THINKING = "{\"role\":\"assistant\",\"thinking\":\"Three r's.\",\"content\":\"3\"}";

	@Test
	void aCallSendsTheModelTheMessagesAndTheOptionsAndReadsTheReply() {
		try (StandIn ollama = new StandIn((request) -> reply(request, THINKING))) {
			ChatResponse response = model(ollama)
				.call(new Prompt("How many r's in strawberry?", OllamaChatOptions.builder()
					.model("qwen3")
					.temperature(0.2)
					.topK(20)
					.topP(0.8)
					.numPredict(64)
					.numCtx(4096)
					.seed(42)
					.repeatPenalty(1.2)
					.stop(List.of("END"))
					.keepAlive("10m")
					.enableThinking()
					.build()));

			Request request = ollama.single();
			assertThat(request.path()).isEqualTo("/api/chat");
			JsonNode body = request.body();
			assertThat(body.get("model").asString()).isEqualTo("qwen3");
			assertThat(body.get("stream").asBoolean()).isFalse();
			assertThat(body.get("keep_alive").asString()).isEqualTo("10m");
			assertThat(body.get("think").asBoolean()).isTrue();
			JsonNode options = body.get("options");
			assertThat(options.get("temperature").asDouble()).isEqualTo(0.2);
			assertThat(options.get("top_k").asInt()).isEqualTo(20);
			assertThat(options.get("top_p").asDouble()).isEqualTo(0.8);
			assertThat(options.get("num_predict").asInt()).isEqualTo(64);
			assertThat(options.get("num_ctx").asInt()).isEqualTo(4096);
			assertThat(options.get("seed").asInt()).isEqualTo(42);
			assertThat(options.get("repeat_penalty").asDouble()).isEqualTo(1.2);
			assertThat(options.get("stop").toString()).isEqualTo("[\"END\"]");
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getParts()).containsExactly(ReasoningPart.of("Three r's."), TextPart.of("3"));
			assertThat(message.getText()).isEqualTo("3");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
			assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
			assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(15);
		}
	}

	@Test
	void theThinkOptionsAreABooleanOrALevel() {
		try (StandIn ollama = new StandIn((request) -> reply(request, "{\"role\":\"assistant\",\"content\":\"Hi\"}"))) {
			OllamaChatModel model = model(ollama);

			for (OllamaChatOptions options : List.of(OllamaChatOptions.builder().disableThinking().build(),
					OllamaChatOptions.builder().thinkLow().build(), OllamaChatOptions.builder().thinkHigh().build(),
					OllamaChatOptions.builder().model("llama3.2").build())) {
				model.call(new Prompt("Hi", options));
			}

			assertThat(ollama.requests).extracting((request) -> String.valueOf(request.body().get("think")))
				.containsExactly("false", "\"low\"", "\"high\"", "null");
		}
	}

	@Test
	void aStreamReadsTheNewlineDelimitedJsonAndAggregatesTheThinkingAndTheText() {
		try (StandIn ollama = new StandIn((request) -> reply(request, THINKING))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			List<ChatResponse> chunks = new MessageAggregator()
				.aggregate(model(ollama).stream(new Prompt("How many r's?")), aggregated::set)
				.collectList()
				.block();

			assertThat(ollama.single().body().get("stream").asBoolean()).isTrue();
			assertThat(chunks).hasSizeGreaterThanOrEqualTo(4);
			assertThat(aggregated.get().getResult().getOutput().getParts())
				.containsExactly(ReasoningPart.of("Three r's."), TextPart.of("3"));
		}
	}

	@Test
	void jsonModeAndASchemaBecomeTheFormat() {
		try (StandIn ollama = new StandIn((request) -> reply(request,
				"{\"role\":\"assistant\",\"content\":\"{\\\"actor\\\":\\\"Tom Hanks\\\",\\\"movies\\\":[\\\"Big\\\"]}\"}"))) {
			OllamaChatModel model = model(ollama);

			model.call(new Prompt("JSON please", OllamaChatOptions.builder().format("json").build()));
			ActorFilms films = ChatClient.create(model)
				.prompt("Filmography")
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.call()
				.entity(ActorFilms.class);

			assertThat(ollama.requests.get(0).body().get("format").asString()).isEqualTo("json");
			assertThat(ollama.requests.get(1).body().get("format").get("properties").has("movies")).isTrue();
			assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big")));
		}
	}

	@Test
	void theChatClientRunsTheToolsTheModelAsksFor() {
		try (StandIn ollama = new StandIn((request) -> {
			JsonNode messages = request.body().get("messages");
			return "tool".equals(messages.get(messages.size() - 1).get("role").asString())
					? reply(request, "{\"role\":\"assistant\",\"content\":\"Shipped.\"}")
					: reply(request, "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":"
							+ "{\"name\":\"orderStatus\",\"arguments\":{\"orderId\":\"A-1\"}}}]}");
		})) {
			String answer = ChatClient.create(model(ollama)).prompt("Where is A-1?").tools(new Shop()).call().content();

			assertThat(answer).isEqualTo("Shipped.");
			assertThat(ollama.requests.get(0).body().get("tools").get(0).get("function").get("name").asString())
				.isEqualTo("orderStatus");
			JsonNode messages = ollama.requests.get(1).body().get("messages");
			assertThat(messages.get(1).get("tool_calls").get(0).get("function").get("arguments").toString())
				.isEqualTo("{\"orderId\":\"A-1\"}");
			assertThat(messages.get(2).get("role").asString()).isEqualTo("tool");
		}
	}

	@Test
	void anImageGoesWithItsMessageAsBase64() {
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		try (StandIn ollama = new StandIn((request) -> reply(request, "{\"role\":\"assistant\",\"content\":\"A bowl\"}"))) {
			model(ollama).call(new Prompt(UserMessage.builder()
				.text("What is this?")
				.media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(png).build())
				.build()));

			JsonNode message = ollama.single().body().get("messages").get(0);
			assertThat(message.get("content").asString()).isEqualTo("What is this?");
			assertThat(message.get("images").toString()).isEqualTo("[\"" + Base64.getEncoder().encodeToString(png) + "\"]");
		}
	}

	@Test
	void thePropertiesReachTheRequestAndAnOptionOfARequestWinsOverThem() {
		try (StandIn ollama = new StandIn((request) -> reply(request, "{\"role\":\"assistant\",\"content\":\"Hi\"}"))) {
			new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(OllamaApiAutoConfiguration.class, OllamaChatAutoConfiguration.class,
						ToolCallingAutoConfiguration.class))
				.withPropertyValues("spring.ai.ollama.base-url=" + ollama.url(), "spring.ai.ollama.chat.model=qwen3",
						"spring.ai.ollama.chat.temperature=0.3", "spring.ai.ollama.chat.num-ctx=8192",
						"spring.ai.ollama.chat.keep-alive=1h", "spring.ai.ollama.chat.think=high",
						"spring.ai.ollama.chat.format=json")
				.run((context) -> {
					OllamaChatModel model = context.getBean(OllamaChatModel.class);
					model.call(new Prompt("Hello"));
					ChatClient.create(model).prompt("Hello").options(OllamaChatOptions.builder().temperature(0.9)).call().content();

					JsonNode body = ollama.requests.get(0).body();
					assertThat(body.get("model").asString()).isEqualTo("qwen3");
					assertThat(body.get("keep_alive").asString()).isEqualTo("1h");
					assertThat(body.get("think").asString()).isEqualTo("high");
					assertThat(body.get("format").asString()).isEqualTo("json");
					assertThat(body.get("options").get("temperature").asDouble()).isEqualTo(0.3);
					assertThat(body.get("options").get("num_ctx").asInt()).isEqualTo(8192);
					assertThat(ollama.requests.get(1).body().get("options").get("temperature").asDouble()).isEqualTo(0.9);
				});
		}
	}

	static OllamaChatModel model(StandIn standIn) {
		return OllamaChatModel.builder()
			.ollamaApi(OllamaApi.builder().baseUrl(standIn.url()).build())
			.options(OllamaChatOptions.builder().model("qwen3").build())
			.build();
	}

	/**
	 * The reply of {@code /api/chat}: one JSON object, or newline-delimited JSON with the
	 * thinking and the content in two pieces each and a last line that is done.
	 */
	static Reply reply(Request request, String message) {
		String done = ",\"done\":true,\"done_reason\":\"stop\",\"total_duration\":1000,\"prompt_eval_count\":25,"
				+ "\"eval_count\":15";
		if (!request.body().path("stream").asBoolean()) {
			return Reply.json("{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\",\"message\":" + message + done + "}");
		}
		JsonNode node = json.readTree(message);
		List<String> lines = new ArrayList<>();
		for (String field : List.of("thinking", "content")) {
			if (node.hasNonNull(field) && !node.get(field).asString().isEmpty()) {
				String text = node.get(field).asString();
				for (String half : List.of(text.substring(0, text.length() / 2), text.substring(text.length() / 2))) {
					if (!half.isEmpty()) {
						lines.add("{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\",\"message\":{\"role\":\"assistant\",\""
								+ field + "\":" + json.writeValueAsString(half) + (field.equals("thinking") ? ",\"content\":\"\"" : "")
								+ "},\"done\":false}\n");
					}
				}
			}
		}
		if (node.has("tool_calls")) {
			lines.add("{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\",\"message\":" + message + ",\"done\":false}\n");
		}
		lines.add("{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"\"}"
				+ done + "}\n");
		return new Reply(200, "application/x-ndjson", Map.of(), lines.stream().map(String::getBytes).toList());
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
