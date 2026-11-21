package io.github.hjosugi.chatsample.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.memory.Transcript;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool calling with a {@code ChatModel} and no {@code ChatClient}: the model returns the
 * calls without running them, request options replace the default options of the model
 * as a whole, and the application drives the loop, blocking or streaming.
 */
class ChatModelToolCallingTests {

	private static final String MESSAGE = """
			{"id":"msg_1","type":"message","role":"assistant","model":"claude-request",
			"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
			"usage":{"input_tokens":1,"output_tokens":1}}""";

	@Test
	void requestOptionsReplaceTheDefaultOptionsOfTheModelAndItsToolsAsAWhole() {
		try (StandIn anthropic = new StandIn((request) -> Reply.json(MESSAGE))) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder()
					.baseUrl(anthropic.url())
					.apiKey("test-key")
					.model("claude-default")
					.maxTokens(100)
					.toolCallbacks(ToolCallbacks.from(new Weather()))
					.build())
				.build();

			model.call(new Prompt("Without options"));
			JsonNode withDefaults = anthropic.last().body();
			model.call(new Prompt("With options", AnthropicChatOptions.builder()
				.model("claude-request")
				.maxTokens(10)
				.toolCallbacks(ToolCallbacks.from(new Clock()))
				.build()));
			JsonNode withRequestOptions = anthropic.last().body();

			assertThat(withDefaults.get("model").asString()).isEqualTo("claude-default");
			assertThat(toolNames(withDefaults)).containsExactly("weather");
			assertThat(withRequestOptions.get("model").asString()).isEqualTo("claude-request");
			assertThat(withRequestOptions.get("max_tokens").asInt()).isEqualTo(10);
			assertThat(toolNames(withRequestOptions)).containsExactly("time", "directTime");
			// The tools did not run: the model only offered them
			assertThat(anthropic.requests).hasSize(2);
		}
	}

	@Test
	void theDefaultToolsPlusOneAreBuiltFromTheDefaults() {
		try (StandIn anthropic = new StandIn((request) -> Reply.json(MESSAGE))) {
			AnthropicChatModel model = AnthropicChatModel.builder()
				.options(AnthropicChatOptions.builder()
					.baseUrl(anthropic.url())
					.apiKey("test-key")
					.model("claude-default")
					.maxTokens(100)
					.toolCallbacks(ToolCallbacks.from(new Weather()))
					.build())
				.build();
			AnthropicChatOptions defaults = (AnthropicChatOptions) model.getOptions();
			List<ToolCallback> tools = new ArrayList<>(defaults.getToolCallbacks());
			tools.addAll(List.of(ToolCallbacks.from(new Clock())));

			model.call(new Prompt("Both", defaults.mutate().toolCallbacks(tools).build()));

			assertThat(anthropic.last().body().get("model").asString()).isEqualTo("claude-default");
			assertThat(toolNames(anthropic.last().body())).containsExactly("weather", "time", "directTime");
		}
	}

	@Test
	void theApplicationDrivesTheBlockingLoopUntilTheModelAnswers() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("time", "{}");
		ToolCallingManager manager = ToolCallingManager.builder().build();
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(ToolCallbacks.from(new Clock()))
			.build();

		Prompt prompt = new Prompt("What time is it?", options);
		ChatResponse response = model.call(prompt);
		while (response.hasToolCalls()) {
			ToolExecutionResult result = manager.executeToolCalls(prompt, response);
			if (result.returnDirect()) {
				break;
			}
			prompt = new Prompt(result.conversationHistory(), options);
			response = model.call(prompt);
		}

		assertThat(response.getResult().getOutput().getText()).isEqualTo("\"noon\"");
		assertThat(Transcript.of(prompt)).containsExactly("user: What time is it?", "calls: time({})",
				"results: \"noon\"");
	}

	@Test
	void theApplicationAggregatesEachStreamedRoundBeforeItLooksForCalls() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("time", "{}");
		ToolCallingManager manager = ToolCallingManager.builder().build();
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(ToolCallbacks.from(new Clock()))
			.build();
		List<ChatResponse> forwarded = new CopyOnWriteArrayList<>();

		Prompt prompt = new Prompt("What time is it?", options);
		ChatResponse response;
		while (true) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			new MessageAggregator().aggregate(model.stream(prompt).doOnNext(forwarded::add), aggregated::set).blockLast();
			response = aggregated.get();
			if (!response.hasToolCalls()) {
				break;
			}
			ToolExecutionResult result = manager.executeToolCalls(prompt, response);
			if (result.returnDirect()) {
				break;
			}
			prompt = new Prompt(result.conversationHistory(), options);
		}

		assertThat(response.getResult().getOutput().getText()).isEqualTo("\"noon\"");
		assertThat(forwarded).anySatisfy((chunk) -> assertThat(chunk.hasToolCalls()).isTrue());
	}

	@Test
	void aRoundOfToolsThatAllReturnDirectIsTheAnswer() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("directTime", "{}");
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(ToolCallbacks.from(new Clock()))
			.build();
		Prompt prompt = new Prompt("What time is it?", options);

		ToolExecutionResult result = ToolCallingManager.builder().build().executeToolCalls(prompt, model.call(prompt));

		assertThat(result.returnDirect()).isTrue();
		assertThat(ToolExecutionResult.buildGenerations(result)).extracting((g) -> g.getOutput().getText())
			.containsExactly("\"noon\"");
	}

	private static List<String> toolNames(JsonNode body) {
		return body.path("tools").valueStream().map((tool) -> tool.get("name").asString()).toList();
	}

	static class Weather {

		@Tool(description = "The weather")
		String weather() {
			return "Sunny";
		}

	}

	static class Clock {

		@Tool(description = "The time")
		String time() {
			return "noon";
		}

		@Tool(description = "The time, as the answer", returnDirect = true)
		String directTime() {
			return "noon";
		}

	}

}
