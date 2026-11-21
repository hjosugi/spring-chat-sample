package io.github.hjosugi.chatsample.memory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A scripted model whose streamed reply to some prompts never ends: it sends the text and
 * then waits, as a provider does while it writes a long reply, until the stream is
 * cancelled. Stopping such a reply is the only way it ends, so a test sees exactly what a
 * stopped reply leaves behind.
 */
final class UnfinishedReplies implements ChatModel {

	private final ScriptedChatModel script;

	private final Predicate<Prompt> unfinished;

	/**
	 * Counts down when the stream of an unfinished reply is cancelled.
	 */
	final CountDownLatch cancelled = new CountDownLatch(1);

	/**
	 * @param script the replies
	 * @param unfinished the prompts whose reply never ends
	 */
	UnfinishedReplies(ScriptedChatModel script, Predicate<Prompt> unfinished) {
		this.script = script;
		this.unfinished = unfinished;
	}

	List<Prompt> prompts() {
		return this.script.prompts;
	}

	@Override
	public ChatOptions getOptions() {
		return this.script.getOptions();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		return this.script.call(prompt);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		if (!this.unfinished.test(prompt)) {
			return this.script.stream(prompt);
		}
		// The last chunk, with the finish reason, is the end of the reply: it never comes
		return this.script.stream(prompt)
			.filter((chunk) -> chunk.getResult().getMetadata().getFinishReason() == null)
			.concatWith(Flux.never())
			.doOnCancel(this.cancelled::countDown);
	}

}
