package io.github.hjosugi.chatsample.tools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.memory.Transcript;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@code ToolCallingAdvisor}: its builder options, its hooks, a subclass of it registered
 * in its place, return direct, advisors inside the loop, and turning it off.
 */
class ToolCallingAdvisorTests {

	private final ApplicationContextRunner boot = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class));

	@Test
	void theOrderMustLieBetweenTheHighestAndTheLowestPrecedence() {
		assertThat(ToolCallingAdvisor.builder().build().getOrder()).isEqualTo(BaseAdvisor.HIGHEST_PRECEDENCE + 300);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ToolCallingAdvisor.builder().advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE).build());
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ToolCallingAdvisor.builder().advisorOrder(BaseAdvisor.LOWEST_PRECEDENCE).build());
	}

	@Test
	void anEligibilityCheckerDecidesWhetherTheToolsRun() {
		Clock clock = new Clock();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("time", "{}");
		ToolExecutionEligibilityChecker never = (response) -> false;
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(never).build())
			.build();

		ChatResponse response = client.prompt("What time is it?").tools(clock).call().chatResponse();

		assertThat(response.hasToolCalls()).isTrue();
		assertThat(clock.calls).isZero();
	}

	@Test
	void withItsHistoryTheLoopForwardsTheTurnSoFarAndWithoutItTheNewestMessageOnly() {
		List<List<String>> withHistory = new CopyOnWriteArrayList<>();
		List<List<String>> withoutHistory = new CopyOnWriteArrayList<>();

		ChatClient.builder(DefiningToolsTests.callingOnce("time", "{}"))
			.defaultSystem("Be brief.")
			.defaultAdvisors(ToolCallingAdvisor.builder().build(), new Observer(withHistory))
			.build()
			.prompt("What time is it?")
			.tools(new Clock())
			.call()
			.content();
		ChatClient.builder(DefiningToolsTests.callingOnce("time", "{}"))
			.defaultSystem("Be brief.")
			.defaultAdvisors(ToolCallingAdvisor.builder().disableInternalConversationHistory().build(),
					new Observer(withoutHistory))
			.build()
			.prompt("What time is it?")
			.tools(new Clock())
			.call()
			.content();

		assertThat(withHistory.get(1)).containsExactly("system: Be brief.", "user: What time is it?", "calls: time({})",
				"results: \"noon\"");
		assertThat(withoutHistory.get(1)).containsExactly("system: Be brief.", "results: \"noon\"");
	}

	@Test
	void theCallHooksFireAroundEveryRound() {
		List<String> hooks = new CopyOnWriteArrayList<>();

		ChatClient.builder(DefiningToolsTests.callingOnce("time", "{}"))
			.defaultAdvisors(HookedAdvisor.builder().hooks(hooks).build())
			.build()
			.prompt("What time is it?")
			.tools(new Clock())
			.call()
			.content();

		assertThat(hooks).containsExactly("initialize", "before", "after", "next instructions", "before", "after",
				"finalize");
	}

	@Test
	void theStreamHooksFireAroundEveryRoundAndTheLastOneSeesOnlyWhatFollowsTheChunks() {
		List<String> hooks = new CopyOnWriteArrayList<>();
		List<ChatClientResponse> finalized = new CopyOnWriteArrayList<>();

		List<String> text = ChatClient.builder(DefiningToolsTests.callingOnce("time", "{}"))
			.defaultAdvisors(HookedAdvisor.builder().hooks(hooks).finalized(finalized).build())
			.build()
			.prompt("What time is it?")
			.tools(new Clock())
			.stream()
			.content()
			.collectList()
			.block();

		assertThat(hooks).containsExactly("initialize stream", "before stream", "after stream",
				"next instructions stream", "before stream", "after stream", "finalize stream");
		assertThat(text).containsExactly("\"noon\"");
		// The answer's chunks were passed on already; the round carried its usage, so
		// nothing follows them
		assertThat(finalized).isEmpty();
	}

	@Test
	void aBuilderThatCopiesItselfRegistersTheSubclassForEveryRequest() {
		List<String> hooks = new CopyOnWriteArrayList<>();
		ChatClient client = ChatClient
			.builder(DefiningToolsTests.callingOnce("time", "{}"), ObservationRegistry.NOOP, null, null,
					HookedAdvisor.builder().hooks(hooks))
			.build();

		client.prompt("What time is it?").tools(new Clock()).call().content();

		assertThat(hooks).contains("initialize", "finalize");
	}

	/**
	 * As the page warns: a builder without {@code newCopy()} and {@code copy()} of its own
	 * is copied as a plain {@code ToolCallingAdvisor.Builder}, so the advisor of every
	 * request is a plain {@code ToolCallingAdvisor}.
	 */
	@Test
	void aBuilderThatDoesNotCopyItselfRegistersAPlainToolCallingAdvisor() {
		List<String> hooks = new CopyOnWriteArrayList<>();
		ChatClient client = ChatClient
			.builder(DefiningToolsTests.callingOnce("time", "{}"), ObservationRegistry.NOOP, null, null,
					new CarelessBuilder(hooks))
			.build();

		assertThat(client.prompt("What time is it?").tools(new Clock()).call().content()).isEqualTo("\"noon\"");
		assertThat(hooks).isEmpty();
	}

	@Test
	void aToolCallingAdvisorBuilderBeanReplacesTheAutoConfiguredOne() {
		List<String> hooks = new CopyOnWriteArrayList<>();
		this.boot.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("time", "{}"))
			.withBean(ToolCallingAdvisor.Builder.class, () -> HookedAdvisor.builder().hooks(hooks))
			.run((context) -> {
				context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("What time is it?")
					.tools(new Clock())
					.call()
					.content();

				assertThat(hooks).contains("initialize", "finalize");
			});
	}

	@Test
	void anAdvisorInsideTheLoopSeesEveryRoundAndTheToolCallsTheCallerNeverSees() {
		List<ChatClientResponse> observed = new CopyOnWriteArrayList<>();
		ChatClient client = ChatClient.builder(DefiningToolsTests.callingOnce("time", "{}"))
			.defaultAdvisors(new ChunkObserver(observed))
			.build();

		List<ChatResponse> received = client.prompt("What time is it?")
			.tools(new Clock())
			.stream()
			.chatResponse()
			.collectList()
			.block();

		assertThat(observed).anySatisfy((chunk) -> assertThat(chunk.chatResponse().hasToolCalls()).isTrue());
		assertThat(received).noneSatisfy((chunk) -> assertThat(chunk.hasToolCalls()).isTrue());
	}

	@Test
	void returnDirectEndsTheLoopWithTheToolResultWhenEveryCalledToolReturnsDirect() {
		ScriptedChatModel model = DefiningToolsTests.callingOnce("directTime", "{}");

		ChatResponse response = ChatClient.create(model)
			.prompt("What time is it?")
			.tools(new Clock())
			.call()
			.chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo("\"noon\"");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(ToolExecutionResult.FINISH_REASON);
		assertThat(model.prompts).hasSize(1);
	}

	@Test
	void returnDirectIsIgnoredWhenAnotherToolOfTheRoundDoesNotReturnDirect() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon.")
				: org.springframework.ai.chat.messages.AssistantMessage.builder()
					.toolCalls(List.of(
							new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("a", "function",
									"directTime", "{}"),
							new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("b", "function", "time",
									"{}")))
					.build());

		String answer = ChatClient.create(model).prompt("What time is it?").tools(new Clock()).call().content();

		assertThat(answer).isEqualTo("It is noon.");
		assertThat(model.prompts).hasSize(2);
	}

	@Test
	void turnedOffForACallTheToolsAreOfferedButNotRun() {
		Clock clock = new Clock();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("time", "{}");

		ChatResponse response = ChatClient.create(model)
			.prompt("What day is tomorrow?")
			.tools(clock)
			.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
			.call()
			.chatResponse();

		assertThat(OfferedTools.names(model.lastPrompt())).contains("time");
		assertThat(response.hasToolCalls()).isTrue();
		assertThat(clock.calls).isZero();
	}

	@Test
	void turnedOffByAPropertyTheAutoConfiguredClientRunsNoTools() {
		Clock clock = new Clock();
		this.boot.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("time", "{}"))
			.withPropertyValues("spring.ai.chat.client.tool-calling.enabled=false")
			.run((context) -> {
				ChatResponse response = context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("What time is it?")
					.tools(clock)
					.call()
					.chatResponse();

				assertThat(response.hasToolCalls()).isTrue();
				assertThat(clock.calls).isZero();
			});
	}

	@Test
	void aPropertyMovesTheAutoRegisteredLoop() {
		List<List<String>> atDefaultOrder = new CopyOnWriteArrayList<>();
		List<List<String>> afterTheMove = new CopyOnWriteArrayList<>();
		this.boot.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("time", "{}")).run((context) -> {
			context.getBean(ChatClient.Builder.class)
				.defaultAdvisors(new Observer(atDefaultOrder))
				.build()
				.prompt("What time is it?")
				.tools(new Clock())
				.call()
				.content();
		});
		this.boot.withBean(ChatModel.class, () -> DefiningToolsTests.callingOnce("time", "{}"))
			.withPropertyValues("spring.ai.chat.client.tool-calling.advisor-order=" + (BaseAdvisor.HIGHEST_PRECEDENCE + 500))
			.run((context) -> {
				context.getBean(ChatClient.Builder.class)
					.defaultAdvisors(new Observer(afterTheMove))
					.build()
					.prompt("What time is it?")
					.tools(new Clock())
					.call()
					.content();
			});

		// The observer, at HIGHEST_PRECEDENCE + 400, is inside the loop at first, outside
		// it once the loop moved to + 500
		assertThat(atDefaultOrder).hasSize(2);
		assertThat(afterTheMove).hasSize(1);
	}

	static class Clock {

		int calls;

		@Tool(description = "Tells the time")
		String time() {
			this.calls++;
			return "noon";
		}

		@Tool(description = "Tells the time, as the answer", returnDirect = true)
		String directTime() {
			this.calls++;
			return "noon";
		}

	}

	/**
	 * Keeps the messages of every request that reaches it, at an order inside the loop.
	 */
	record Observer(List<List<String>> requests) implements CallAdvisor {

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			this.requests.add(Transcript.of(request.prompt()));
			return chain.nextCall(request);
		}

		@Override
		public String getName() {
			return "Observer";
		}

		@Override
		public int getOrder() {
			return BaseAdvisor.HIGHEST_PRECEDENCE + 400;
		}

	}

	/**
	 * Keeps every chunk that reaches it, inside the loop, as the page's
	 * {@code ToolCallObservingAdvisor} does.
	 */
	record ChunkObserver(List<ChatClientResponse> chunks) implements StreamAdvisor {

		@Override
		public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
			return chain.nextStream(request).doOnNext(this.chunks::add);
		}

		@Override
		public String getName() {
			return "ChunkObserver";
		}

		@Override
		public int getOrder() {
			return BaseAdvisor.HIGHEST_PRECEDENCE + 400;
		}

	}

	/**
	 * A subclass that notes which hook fired, with a builder that copies itself, as the
	 * page's subclass example does.
	 */
	static class HookedAdvisor extends ToolCallingAdvisor {

		private final List<String> hooks;

		private final List<ChatClientResponse> finalized;

		HookedAdvisor(Builder<?> builder) {
			super(builder.toolCallingManager(), builder.getToolExecutionEligibilityChecker(), builder.getAdvisorOrder(),
					builder.history());
			this.hooks = builder.hooks;
			this.finalized = builder.finalized;
		}

		public static Builder<?> builder() {
			return new Builder<>();
		}

		@Override
		protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
			this.hooks.add("initialize");
			return request;
		}

		@Override
		protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
			this.hooks.add("before");
			return request;
		}

		@Override
		protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
			this.hooks.add("after");
			return response;
		}

		@Override
		protected ChatClientResponse doFinalizeLoop(ChatClientResponse response, CallAdvisorChain chain) {
			this.hooks.add("finalize");
			return response;
		}

		@Override
		protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest request,
				ChatClientResponse response, ToolExecutionResult result) {
			this.hooks.add("next instructions");
			return super.doGetNextInstructionsForToolCall(request, response, result);
		}

		@Override
		protected ChatClientRequest doInitializeLoopStream(ChatClientRequest request, StreamAdvisorChain chain) {
			this.hooks.add("initialize stream");
			return request;
		}

		@Override
		protected ChatClientRequest doBeforeStream(ChatClientRequest request, StreamAdvisorChain chain) {
			this.hooks.add("before stream");
			return request;
		}

		@Override
		protected ChatClientResponse doAfterStream(ChatClientResponse response, StreamAdvisorChain chain) {
			this.hooks.add("after stream");
			return response;
		}

		@Override
		protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> responses,
				StreamAdvisorChain chain) {
			this.hooks.add("finalize stream");
			return responses.doOnNext(this.finalized::add);
		}

		@Override
		protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest request,
				ChatClientResponse response, ToolExecutionResult result) {
			this.hooks.add("next instructions stream");
			return super.doGetNextInstructionsForToolCallStream(request, response, result);
		}

		static class Builder<T extends Builder<T>> extends ToolCallingAdvisor.Builder<T> {

			private List<String> hooks = new CopyOnWriteArrayList<>();

			private List<ChatClientResponse> finalized = new CopyOnWriteArrayList<>();

			T hooks(List<String> hooks) {
				this.hooks = hooks;
				return self();
			}

			T finalized(List<ChatClientResponse> finalized) {
				this.finalized = finalized;
				return self();
			}

			ToolCallingManager toolCallingManager() {
				return getToolCallingManager();
			}

			boolean history() {
				return isConversationHistoryEnabled();
			}

			@Override
			public HookedAdvisor build() {
				return new HookedAdvisor(this);
			}

			@Override
			protected ToolCallingAdvisor.Builder<?> newCopy() {
				return new Builder<>();
			}

			@Override
			public ToolCallingAdvisor.Builder<?> copy() {
				Builder<?> copy = (Builder<?>) super.copy();
				copy.hooks = this.hooks;
				copy.finalized = this.finalized;
				return copy;
			}

		}

	}

	/**
	 * A builder of the hooked subclass that forgets to override {@code newCopy()} and
	 * {@code copy()}.
	 */
	static class CarelessBuilder extends ToolCallingAdvisor.Builder<CarelessBuilder> {

		private final List<String> hooks;

		CarelessBuilder(List<String> hooks) {
			this.hooks = hooks;
		}

		@Override
		public ToolCallingAdvisor build() {
			return HookedAdvisor.builder().hooks(this.hooks).build();
		}

	}

}
