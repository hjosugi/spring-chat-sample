package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Advisors configured on a {@code ChatClient}, as the page "Advisors and Memory in
 * ChatClient" shows it: the {@code AdvisorSpec}, the order of the chain, the
 * {@code SimpleLoggerAdvisor}, and the auto-registered {@code ToolCallingAdvisor} with
 * its switches. Chat memory is verified by the tools and memory area.
 */
class ChatClientAdvisorsTests {

	private final List<String> events = new CopyOnWriteArrayList<>();

	@Test
	void theAdvisorSpecAddsParamsAndAdvisors() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		Probe first = new Probe("first", 1, this.events);
		Probe second = new Probe("second", 2, this.events);
		Probe third = new Probe("third", 3, this.events);

		ChatClientResponse response = ChatClient.create(model)
			.prompt("Hi")
			.advisors((a) -> a.param("k", "v")
				.params(Map.of("tenant", "shop-1"))
				.advisors(first)
				.advisors(List.of(second, third)))
			.call()
			.chatClientResponse();

		assertThat(response.context()).containsEntry("k", "v").containsEntry("tenant", "shop-1");
		assertThat(this.events).containsExactly("first>", "second>", "third>", "<third", "<second", "<first");
	}

	@Test
	void theChainRunsByOrderNotByRegistration() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model)
			.prompt("Hi")
			.advisors(new Probe("late", 100, this.events), new Probe("early", -100, this.events),
					new Probe("middle", 0, this.events))
			.call()
			.content();

		assertThat(this.events).containsExactly("early>", "middle>", "late>", "<late", "<middle", "<early");
	}

	@Test
	void theSimpleLoggerAdvisorLogsTheRequestAndTheResponseAtDebug() {
		try (LogCapture log = new LogCapture(SimpleLoggerAdvisor.class, Level.DEBUG)) {
			ChatResponse response = ChatClient.create(ScriptedChatModel.answering("Why did the pirate laugh"))
				.prompt()
				.advisors(new SimpleLoggerAdvisor())
				.user("Tell me a joke?")
				.call()
				.chatResponse();

			assertThat(response.getResult().getOutput().getText()).isEqualTo("Why did the pirate laugh");
			assertThat(log.messages()).hasSize(2);
			assertThat(log.messages().get(0)).startsWith("request: ").contains("Tell me a joke?");
			assertThat(log.messages().get(1)).startsWith("response: ").contains("Why did the pirate laugh");
		}
	}

	@Test
	void theSimpleLoggerAdvisorSaysNothingAboveDebug() {
		try (LogCapture log = new LogCapture(SimpleLoggerAdvisor.class, Level.INFO)) {
			ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").advisors(new SimpleLoggerAdvisor()).call().content();

			assertThat(log.messages()).isEmpty();
		}
	}

	@Test
	void atItsDefaultOrderTheSimpleLoggerAdvisorLogsEveryRoundOfTheToolLoop() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}"));
		try (LogCapture log = new LogCapture(SimpleLoggerAdvisor.class, Level.DEBUG)) {
			ChatClient.create(model).prompt("What time is it?").tools(new Clock()).advisors(new SimpleLoggerAdvisor()).call().content();

			assertThat(new SimpleLoggerAdvisor().getOrder()).isZero();
			assertThat(log.messages()).filteredOn((message) -> message.startsWith("request: ")).hasSize(2);
			assertThat(log.messages()).filteredOn((message) -> message.startsWith("response: ")).hasSize(2);
		}
	}

	@Test
	void beforeTheToolLoopTheSimpleLoggerAdvisorLogsOnce() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}"));
		try (LogCapture log = new LogCapture(SimpleLoggerAdvisor.class, Level.DEBUG)) {
			ChatClient.create(model)
				.prompt("What time is it?")
				.tools(new Clock())
				.advisors(new SimpleLoggerAdvisor(Ordered.HIGHEST_PRECEDENCE))
				.call()
				.content();

			assertThat(log.messages()).filteredOn((message) -> message.startsWith("request: ")).hasSize(1);
		}
	}

	@Test
	void theSimpleLoggerAdvisorWritesWhatItsFunctionsMakeOfTheRequestAndTheResponse() {
		SimpleLoggerAdvisor customLogger = new SimpleLoggerAdvisor(
				(request) -> "Custom request: " + request.prompt().getUserMessage().getText(),
				(response) -> "Custom response: " + response.getResult().getOutput().getText(), 0);
		try (LogCapture log = new LogCapture(SimpleLoggerAdvisor.class, Level.DEBUG)) {
			ChatClient.create(ScriptedChatModel.answering("Arrr")).prompt("Hi").advisors(customLogger).call().content();

			assertThat(log.messages()).containsExactly("request: Custom request: Hi", "response: Custom response: Arrr");
		}
	}

	@Test
	void theToolCallingAdvisorIsInEveryChainAtHighestPrecedencePlus300() {
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);

		ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").advisors(probe).call().content();

		assertThat(ToolCallingAdvisor.DEFAULT_ORDER).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
		assertThat(probe.chain()).containsExactly("probe@" + Ordered.HIGHEST_PRECEDENCE,
				"Tool Calling Advisor@" + ToolCallingAdvisor.DEFAULT_ORDER, "call@" + Ordered.LOWEST_PRECEDENCE);
	}

	@Test
	void turnedOffForACallTheToolsAreSentButNotRun() {
		Clock clock = new Clock();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.toolCall("time", "{}"));
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);

		ChatResponse response = ChatClient.create(model)
			.prompt("What day is tomorrow?")
			.tools(clock)
			.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
			.advisors(probe)
			.call()
			.chatResponse();

		assertThat(response.hasToolCalls()).isTrue();
		assertThat(model.prompts).hasSize(1);
		assertThat(((ToolCallingChatOptions) model.lastPrompt().getOptions()).getToolCallbacks()).hasSize(1);
		assertThat(clock.calls).hasValue(0);
		assertThat(probe.chain()).noneMatch((advisor) -> advisor.startsWith("Tool Calling Advisor"));
	}

	@Test
	void turnedOffByPropertyTheToolsAreSentButNotRun() {
		Clock clock = new Clock();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.toolCall("time", "{}"));
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
			.withBean(ChatModel.class, () -> model)
			.withPropertyValues("spring.ai.chat.client.tool-calling.enabled=false")
			.run((context) -> {
				ChatResponse response = context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("What day is tomorrow?")
					.tools(clock)
					.call()
					.chatResponse();

				assertThat(response.hasToolCalls()).isTrue();
				assertThat(model.prompts).hasSize(1);
				assertThat(((ToolCallingChatOptions) model.lastPrompt().getOptions()).getToolCallbacks()).hasSize(1);
				assertThat(clock.calls).hasValue(0);
			});
	}

	@Test
	void thePropertyMovesTheToolCallingAdvisorInTheChain() {
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
			.withBean(ChatModel.class, () -> ScriptedChatModel.answering("ok"))
			.withPropertyValues("spring.ai.chat.client.tool-calling.advisor-order=0")
			.run((context) -> {
				context.getBean(ChatClient.Builder.class).build().prompt("Hi").advisors(probe).call().content();

				assertThat(probe.chain()).contains("Tool Calling Advisor@0");
			});
	}

	@Test
	void aToolCallingAdvisorBuilderBeanOfYourOwnTakesPrecedence() {
		CountingToolCallingManager manager = new CountingToolCallingManager();
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
			.withBean(ChatModel.class,
					() -> new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
							? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}")))
			.withBean(ToolCallingAdvisor.Builder.class, () -> ToolCallingAdvisor.builder()
				.toolCallingManager(manager)
				.advisorOrder(Ordered.LOWEST_PRECEDENCE - 1))
			.run((context) -> {
				String answer = context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("What time is it?")
					.tools(new Clock())
					.advisors(probe)
					.call()
					.content();

				assertThat(answer).isEqualTo("It is noon");
				assertThat(manager.executions).hasValue(1);
				assertThat(probe.chain()).contains("Tool Calling Advisor@" + (Ordered.LOWEST_PRECEDENCE - 1));
			});
	}

	@Test
	void withoutSpringBootTheBuilderTakesAToolCallingAdvisorBuilder() {
		CountingToolCallingManager manager = new CountingToolCallingManager();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Tomorrow is Sunday") : ScriptedChatModel.toolCall("time", "{}"));
		ChatClient chatClient = ChatClient
			.builder(model, ObservationRegistry.NOOP, null, null, ToolCallingAdvisor.builder().toolCallingManager(manager))
			.build();

		String answer = chatClient.prompt("What day is tomorrow?").tools(new Clock()).call().content();

		assertThat(answer).isEqualTo("Tomorrow is Sunday");
		assertThat(manager.executions).hasValue(1);
	}

	@Test
	void anAdvisorThatIsAToolAdvisorReplacesTheAutoRegisteredOne() {
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);
		ToolCallingAdvisor own = ToolCallingAdvisor.builder().advisorOrder(Ordered.HIGHEST_PRECEDENCE + 500).build();

		ChatClient.create(ScriptedChatModel.answering("ok")).prompt("Hi").advisors(probe, own).call().content();

		assertThat(probe.chain()).filteredOn((advisor) -> advisor.startsWith("Tool Calling Advisor"))
			.containsExactly("Tool Calling Advisor@" + (Ordered.HIGHEST_PRECEDENCE + 500));
	}

	@Test
	void twoToolAdvisorsInOneChainAreRefused() {
		ChatClient.ChatClientRequestSpec request = ChatClient.create(ScriptedChatModel.answering("ok"))
			.prompt("Hi")
			.advisors(ToolCallingAdvisor.builder().build(), ToolCallingAdvisor.builder().advisorOrder(0).build());

		assertThatIllegalStateException().isThrownBy(request::call).withMessageContaining("At most one ToolAdvisor");
	}

	static class Clock {

		final AtomicInteger calls = new AtomicInteger();

		@Tool(description = "Tells the time")
		String time() {
			this.calls.incrementAndGet();
			return "noon";
		}

	}

	/**
	 * The default manager, counting the tool executions it was asked for.
	 */
	static class CountingToolCallingManager implements ToolCallingManager {

		final AtomicInteger executions = new AtomicInteger();

		private final ToolCallingManager delegate = ToolCallingManager.builder().build();

		@Override
		public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
			return this.delegate.resolveToolDefinitions(chatOptions);
		}

		@Override
		public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
			this.executions.incrementAndGet();
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

	}

}
