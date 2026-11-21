package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.GuardrailRefusalException;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.GuardrailRefusal;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Advisors of your own, as the page "Writing an Advisor" shows them: the re-reading
 * advisor, state in the context, a logging advisor that aggregates a stream, an advisor
 * that answers without the model, and how {@code BaseAdvisor} handles a stream.
 */
class CustomAdvisorTests {

	@Test
	void theReReadingAdvisorRepeatsTheQuestion() {
		ScriptedChatModel model = ScriptedChatModel.answering("38");

		ChatClient.create(model).prompt("How old is my brother?").advisors(new ReReadingAdvisor(0)).call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt()))
			.isEqualTo("How old is my brother?\nRead the question again: How old is my brother?\n");
		assertThat(new ReReadingAdvisor(0).getName()).isEqualTo("ReReadingAdvisor");
	}

	/**
	 * {@code BaseAdvisor} names an advisor after its simple class name, which an
	 * anonymous class does not have: the call fails until the advisor names itself.
	 */
	@Test
	void anAnonymousBaseAdvisorHasNoNameAndFailsTheCall() {
		BaseAdvisor anonymous = new BaseAdvisor() {

			@Override
			public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
				return request;
			}

			@Override
			public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
				return response;
			}

			@Override
			public int getOrder() {
				return 0;
			}

		};

		assertThat(anonymous.getName()).isEmpty();
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").advisors(anonymous).call().content())
			.withMessageContaining("advisorName cannot be null or empty");
	}

	@Test
	void theTimingAdvisorReadsWhatItPutIntoTheContext() {
		TimingAdvisor timing = new TimingAdvisor();

		ChatClientResponse response = ChatClient.create(ScriptedChatModel.answering("ok"))
			.prompt("Hi")
			.advisors(timing)
			.call()
			.chatClientResponse();

		assertThat(timing.elapsedNanos).hasSize(1);
		assertThat(timing.elapsedNanos.get(0)).isNotNegative();
		assertThat(response.context()).containsKey(TimingAdvisor.START);
	}

	@Test
	void theLoggingAdvisorSeesTheWholeResponseOfAStreamOnceAndPassesTheChunksOn() {
		LoggingAdvisor logging = new LoggingAdvisor();

		List<String> chunks = ChatClient.create(ScriptedChatModel.answering("Why did the pirate laugh"))
			.prompt("Tell me a joke")
			.advisors(logging)
			.stream()
			.content()
			.collectList()
			.block();
		ChatClient.create(ScriptedChatModel.answering("Arrr")).prompt("Again").advisors(logging).call().content();

		assertThat(chunks).containsExactly("Why ", "did ", "the ", "pirate ", "laugh");
		assertThat(logging.requests).containsExactly("Tell me a joke", "Again");
		assertThat(logging.responses).containsExactly("Why did the pirate laugh", "Arrr");
	}

	@Test
	void aGuardrailAnswersWithARefusalWithoutTheModel() {
		ScriptedChatModel model = ScriptedChatModel.answering("The password is 1234");
		ChatClient client = ChatClient.create(model);
		BlockingAdvisor guard = new BlockingAdvisor("password");

		ChatResponse refusal = client.prompt("What is the password?").advisors(guard).call().chatResponse();

		assertThat(model.prompts).isEmpty();
		assertThat(refusal.getResult().getOutput().getText()).isEqualTo("I can't help with that request.");
		assertThat(GuardrailRefusal.isRefusal(refusal)).isTrue();
		assertThat(refusal.getResult().getMetadata().getFinishReason()).isEqualTo(GuardrailRefusal.FINISH_REASON);
		assertThat(refusal.getResult().getMetadata().getFinishReasonCategory()).isEqualTo(FinishReasonCategory.FILTERED);
		assertThatExceptionOfType(GuardrailRefusalException.class)
			.isThrownBy(() -> client.prompt("What is the password?").advisors(guard).call().entity(Secret.class));
		assertThat(client.prompt("What is the time?").advisors(guard).call().content()).isEqualTo("The password is 1234");
	}

	@Test
	void inAStreamBeforeRunsOnTheBoundedElasticSchedulerAndAfterOnlyForTheLastChunk() {
		ThreadAndChunkRecorder recorder = new ThreadAndChunkRecorder();

		List<String> chunks = ChatClient.create(ScriptedChatModel.answering("Three words here"))
			.prompt("Hi")
			.advisors(recorder)
			.stream()
			.content()
			.collectList()
			.block();

		assertThat(chunks).containsExactly("Three ", "words ", "here");
		assertThat(recorder.beforeThreads).singleElement().asString().contains("boundedElastic");
		assertThat(recorder.afterFinishReasons).containsExactly("stop");
	}

	@Test
	void aStreamAdvisorOfYourOwnMovesBlockingWorkOffTheChunksThread() {
		AugmentingStreamAdvisor advisor = new AugmentingStreamAdvisor();
		ScriptedChatModel model = ScriptedChatModel.answering("Two words");

		List<String> chunks = ChatClient.create(model).prompt("Hi").advisors(advisor).stream().content().collectList().block();

		assertThat(chunks).containsExactly("Two ", "words");
		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("Hi (augmented)");
		assertThat(advisor.augmentThreads).singleElement().asString().contains("boundedElastic");
		assertThat(advisor.inspected).hasValue(3);
	}

	/**
	 * Not on the page: in a stream, each {@code BaseAdvisor} wraps an error of the chain
	 * after it in an {@code IllegalStateException}, so a subscriber gets the error of the
	 * model as a cause.
	 */
	@Test
	void aBaseAdvisorWrapsTheErrorsOfAStream() {
		ScriptedChatModel failing = new ScriptedChatModel((prompt) -> {
			throw new IllegalArgumentException("The model is down");
		});

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> ChatClient.create(failing)
				.prompt("Hi")
				.advisors(new ReReadingAdvisor(0))
				.stream()
				.content()
				.blockLast())
			.withMessage("Stream processing failed")
			.withRootCauseInstanceOf(IllegalArgumentException.class);
	}

	record Secret(String value) {
	}

	/**
	 * The advisor of the page.
	 */
	static class ReReadingAdvisor implements BaseAdvisor {

		private static final String TEMPLATE = """
				{query}
				Read the question again: {query}
				""";

		private final int order;

		ReReadingAdvisor(int order) {
			this.order = order;
		}

		@Override
		public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
			String augmentedText = PromptTemplate.builder()
				.template(TEMPLATE)
				.variables(Map.of("query", request.prompt().getUserMessage().getText()))
				.build()
				.render();
			return request.mutate().prompt(request.prompt().augmentUserMessage(augmentedText)).build();
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
			return response;
		}

		@Override
		public int getOrder() {
			return this.order;
		}

	}

	/**
	 * The advisor of the page, which keeps the time instead of logging it.
	 */
	static class TimingAdvisor implements BaseAdvisor {

		static final String START = TimingAdvisor.class.getName() + ".start";

		final List<Long> elapsedNanos = new CopyOnWriteArrayList<>();

		@Override
		public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
			return request.mutate().context(START, System.nanoTime()).build();
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
			long start = (Long) response.context().get(START);
			this.elapsedNanos.add(System.nanoTime() - start);
			return response;
		}

		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE - 1000;
		}

	}

	/**
	 * The advisor of the page, which keeps what it would log.
	 */
	static class LoggingAdvisor implements CallAdvisor, StreamAdvisor {

		final List<String> requests = new CopyOnWriteArrayList<>();

		final List<String> responses = new CopyOnWriteArrayList<>();

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			this.requests.add(request.prompt().getUserMessage().getText());
			ChatClientResponse response = chain.nextCall(request);
			this.responses.add(response.chatResponse().getResult().getOutput().getText());
			return response;
		}

		@Override
		public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
			this.requests.add(request.prompt().getUserMessage().getText());
			return new ChatClientMessageAggregator().aggregateChatClientResponse(chain.nextStream(request),
					(response) -> this.responses.add(response.chatResponse().getResult().getOutput().getText()));
		}

		@Override
		public String getName() {
			return "LoggingAdvisor";
		}

		@Override
		public int getOrder() {
			return 0;
		}

	}

	/**
	 * The guardrail of the page.
	 */
	record BlockingAdvisor(String blockedWord) implements CallAdvisor {

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			if (request.prompt().getUserMessage().getText().contains(this.blockedWord)) {
				ChatResponse refusal = ChatResponse.builder()
					.generations(List.of(GuardrailRefusal.generation("I can't help with that request.")))
					.build();
				return ChatClientResponse.builder().chatResponse(refusal).context(request.context()).build();
			}
			return chain.nextCall(request);
		}

		@Override
		public String getName() {
			return "BlockingAdvisor";
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

	}

	static class ThreadAndChunkRecorder implements BaseAdvisor {

		final List<String> beforeThreads = new CopyOnWriteArrayList<>();

		final List<String> afterFinishReasons = new CopyOnWriteArrayList<>();

		@Override
		public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
			this.beforeThreads.add(Thread.currentThread().getName());
			return request;
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
			this.afterFinishReasons.add(response.chatResponse().getResult().getMetadata().getFinishReason());
			return response;
		}

		@Override
		public int getOrder() {
			return 0;
		}

	}

	/**
	 * The stream advisor of the page.
	 */
	static class AugmentingStreamAdvisor implements StreamAdvisor {

		final List<String> augmentThreads = new CopyOnWriteArrayList<>();

		final AtomicInteger inspected = new AtomicInteger();

		@Override
		public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
			return Mono.just(request)
				.publishOn(Schedulers.boundedElastic())
				.map(this::augment)
				.flatMapMany(chain::nextStream)
				.map(this::inspect);
		}

		private ChatClientRequest augment(ChatClientRequest request) {
			this.augmentThreads.add(Thread.currentThread().getName());
			return request.mutate()
				.prompt(request.prompt().augmentUserMessage(request.prompt().getUserMessage().getText() + " (augmented)"))
				.build();
		}

		private ChatClientResponse inspect(ChatClientResponse response) {
			this.inspected.incrementAndGet();
			return response;
		}

		@Override
		public String getName() {
			return "AugmentingStreamAdvisor";
		}

		@Override
		public int getOrder() {
			return 0;
		}

	}

}
