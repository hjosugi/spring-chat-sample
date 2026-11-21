package io.github.hjosugi.chatsample.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model that answers from a script and keeps the prompts it was asked, for what
 * does not depend on a provider: advisors, memory, tools, output converters. What does
 * depend on one goes through the provider's own client and a {@link StandIn}.
 * <p>
 * Like the chat models of Spring AI 2, it runs no tool: it returns the calls, and the
 * {@code ChatClient} runs them and asks again.
 */
public final class ScriptedChatModel implements ChatModel {

	private static final AtomicInteger toolCallIds = new AtomicInteger();

	private final Function<Prompt, AssistantMessage> script;

	/**
	 * The prompts so far, oldest first.
	 */
	public final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	/**
	 * @param script the reply to a prompt, as {@link #text} and {@link #toolCall} make
	 * them
	 */
	public ScriptedChatModel(Function<Prompt, AssistantMessage> script) {
		this.script = script;
	}

	/**
	 * A model that always answers the same.
	 */
	public static ScriptedChatModel answering(String text) {
		return new ScriptedChatModel((prompt) -> text(text));
	}

	/**
	 * A reply of text.
	 */
	public static AssistantMessage text(String text) {
		return new AssistantMessage(text);
	}

	/**
	 * A reply that asks to call a tool.
	 * @param name the tool
	 * @param arguments its arguments, as JSON
	 */
	public static AssistantMessage toolCall(String name, String arguments) {
		return AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_" + toolCallIds.incrementAndGet(), "function", name,
					arguments)))
			.build();
	}

	/**
	 * The text of the newest message of a prompt: the user's, or a tool's result.
	 */
	public static String lastText(Prompt prompt) {
		Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
		if (last instanceof ToolResponseMessage results) {
			return results.getResponses().get(results.getResponses().size() - 1).responseData();
		}
		return last.getText();
	}

	/**
	 * Whether the newest message of a prompt is the result of a tool.
	 */
	public static boolean endsWithToolResult(Prompt prompt) {
		return prompt.getInstructions().get(prompt.getInstructions().size() - 1).getMessageType() == MessageType.TOOL;
	}

	/**
	 * The newest prompt.
	 */
	public Prompt lastPrompt() {
		return this.prompts.get(this.prompts.size() - 1);
	}

	/**
	 * Options that can hold tools. With the plain {@code ChatOptions} that a
	 * {@link ChatModel} has unless it says otherwise, the {@code ChatClient} leaves the
	 * tools of a request out of the prompt, without a word.
	 */
	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().build();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.prompts.add(prompt);
		AssistantMessage reply = this.script.apply(prompt);
		return response(reply, finishReason(reply), prompt);
	}

	/**
	 * The text a word at a time, then a last chunk with the tool calls, the finish
	 * reason and the usage.
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.defer(() -> {
			this.prompts.add(prompt);
			AssistantMessage reply = this.script.apply(prompt);
			List<ChatResponse> chunks = new ArrayList<>();
			String text = reply.getText();
			if (text != null && !text.isEmpty()) {
				for (String word : text.split("(?<= )")) {
					chunks.add(response(new AssistantMessage(word), null, prompt));
				}
			}
			AssistantMessage end = AssistantMessage.builder().content("").toolCalls(reply.getToolCalls()).build();
			chunks.add(response(end, finishReason(reply), prompt));
			return Flux.fromIterable(chunks);
		});
	}

	private static String finishReason(AssistantMessage reply) {
		return reply.hasToolCalls() ? "tool_calls" : "stop";
	}

	private static ChatResponse response(AssistantMessage message, String finishReason, Prompt prompt) {
		ChatGenerationMetadata generation = ChatGenerationMetadata.builder().finishReason(finishReason).build();
		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().id("scripted").model("scripted");
		if (finishReason != null) {
			// A token per word, which is all a test needs of them.
			metadata.usage(new DefaultUsage(words(prompt.getContents()), words(message.getText())));
		}
		return new ChatResponse(List.of(new Generation(message, generation)), metadata.build());
	}

	private static int words(String text) {
		return (text == null || text.isBlank()) ? 0 : text.strip().split("\\s+").length;
	}

}
