package io.github.hjosugi.chatsample.chatclient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model that answers with whole {@link ChatResponse}s, for what the
 * {@code ScriptedChatModel} cannot say: a finish reason of its own, a category, usage
 * that a test chooses. It keeps the prompts it was asked.
 */
final class CannedChatModel implements ChatModel {

	final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	private final Function<Prompt, ChatResponse> script;

	CannedChatModel(Function<Prompt, ChatResponse> script) {
		this.script = script;
	}

	/**
	 * A model that gives these responses one after the other, the last one again when
	 * they run out.
	 */
	static CannedChatModel answering(ChatResponse... responses) {
		AtomicInteger next = new AtomicInteger();
		return new CannedChatModel(
				(prompt) -> responses[Math.min(next.getAndIncrement(), responses.length - 1)]);
	}

	/**
	 * A response of one generation.
	 */
	static ChatResponse response(String text, String finishReason) {
		return response(text, finishReason, null, 10, 5);
	}

	/**
	 * A response of one generation, with a category and usage.
	 */
	static ChatResponse response(String text, String finishReason, FinishReasonCategory category,
			int promptTokens, int completionTokens) {
		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
			.finishReason(finishReason)
			.finishReasonCategory(category)
			.build();
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)),
				ChatResponseMetadata.builder()
					.id("canned")
					.model("canned")
					.usage(new DefaultUsage(promptTokens, completionTokens))
					.build());
	}

	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().build();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.prompts.add(prompt);
		return this.script.apply(prompt);
	}

	/**
	 * The response as one chunk.
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.defer(() -> {
			this.prompts.add(prompt);
			return Flux.fromIterable(new ArrayList<>(List.of(this.script.apply(prompt))));
		});
	}

	Prompt lastPrompt() {
		return this.prompts.get(this.prompts.size() - 1);
	}

}
