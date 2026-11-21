package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.UsageAccumulator;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Advisors that run the rest of the chain more than once, as the page "Recursive
 * Advisors" shows them: the retry advisor of the page with its usage, which advisors
 * repeat, the chain that is used up, a stream, and the tool loop that returns a result
 * directly.
 */
class RecursiveAdvisorTests {

	private final List<String> events = new CopyOnWriteArrayList<>();

	@Test
	void theRetryAdvisorAsksAgainForAnEmptyAnswerAndSumsTheUsage() {
		AtomicInteger calls = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text((calls.incrementAndGet() < 3) ? "" : "Finally an answer"));

		ChatResponse response = ChatClient.create(model)
			.prompt("one two")
			.advisors(new RetryOnEmptyAnswerAdvisor(5))
			.call()
			.chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo("Finally an answer");
		assertThat(model.prompts).hasSize(3);
		// Each round: two prompt tokens; only the last answer has completion tokens.
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(6);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(3);
	}

	@Test
	void theRetryAdvisorStopsAfterItsMaximum() {
		ScriptedChatModel model = ScriptedChatModel.answering("");

		ChatClient.create(model).prompt("Hi").advisors(new RetryOnEmptyAnswerAdvisor(2)).call().content();

		assertThat(model.prompts).hasSize(3);
	}

	@Test
	void theAdvisorsBeforeARecursiveAdvisorRunOnceAndThoseAfterItEveryRound() {
		ScriptedChatModel model = ScriptedChatModel.answering("");

		ChatClient.create(model)
			.prompt("Hi")
			.advisors(new Probe("before", 0, this.events), new RetryOnEmptyAnswerAdvisor(1),
					new Probe("after", Ordered.LOWEST_PRECEDENCE - 500, this.events))
			.call()
			.content();

		assertThat(this.events).containsExactly("before>", "after>", "<after", "after>", "<after", "<before");
	}

	@Test
	void aChainThatHasRunCannotRunAgain() {
		CallAdvisor twice = new CallAdvisor() {

			@Override
			public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
				chain.nextCall(request);
				return chain.nextCall(request);
			}

			@Override
			public String getName() {
				return "twice";
			}

			@Override
			public int getOrder() {
				return 0;
			}

		};

		assertThatIllegalStateException()
			.isThrownBy(() -> ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").advisors(twice).call().content())
			.withMessageContaining("No CallAdvisors available");
	}

	@Test
	void aStreamingAdvisorRunsTheRestOfTheChainAgainThroughACopy() {
		ScriptedChatModel model = ScriptedChatModel.answering("Two words");

		List<String> chunks = ChatClient.create(model)
			.prompt("Hi")
			.advisors(new StreamTwice())
			.stream()
			.content()
			.collectList()
			.block();

		assertThat(model.prompts).hasSize(2);
		assertThat(chunks).containsExactly("Two ", "words", "Two ", "words");
	}

	@Test
	void theToolLoopEndsWithAResultThatGoesDirectlyToTheCaller() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.toolCall("secretCode", "{}"));

		String answer = ChatClient.create(model).prompt("What is the code?").tools(new Codes()).call().content();

		assertThat(answer).isEqualTo("4711");
		assertThat(model.prompts).hasSize(1);
	}

	/**
	 * The advisor of the page.
	 */
	static class RetryOnEmptyAnswerAdvisor implements CallAdvisor {

		private final int maxRetries;

		RetryOnEmptyAnswerAdvisor(int maxRetries) {
			this.maxRetries = maxRetries;
		}

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			UsageAccumulator usage = new UsageAccumulator();
			ChatClientResponse response = chain.copy(this).nextCall(request);
			usage.addRoundResponse(response.chatResponse());

			for (int retry = 0; retry < this.maxRetries && isEmpty(response); retry++) {
				response = chain.copy(this).nextCall(request);
				usage.addRoundResponse(response.chatResponse());
			}
			return usage.applyAccumulatedUsage(response);
		}

		private boolean isEmpty(ChatClientResponse response) {
			ChatResponse chatResponse = response.chatResponse();
			return chatResponse == null || chatResponse.getResult() == null
					|| !StringUtils.hasText(chatResponse.getResult().getOutput().getText());
		}

		@Override
		public String getName() {
			return "RetryOnEmptyAnswerAdvisor";
		}

		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE - 1000;
		}

	}

	static class StreamTwice implements StreamAdvisor {

		@Override
		public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
			return chain.copy(this).nextStream(request).concatWith(Flux.defer(() -> chain.copy(this).nextStream(request)));
		}

		@Override
		public String getName() {
			return "StreamTwice";
		}

		@Override
		public int getOrder() {
			return 0;
		}

	}

	static class Codes {

		@Tool(description = "The secret code", returnDirect = true)
		String secretCode() {
			return "4711";
		}

	}

}
