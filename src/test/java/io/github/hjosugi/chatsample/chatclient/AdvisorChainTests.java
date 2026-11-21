package io.github.hjosugi.chatsample.chatclient;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.ChatModelStreamAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advisor chain of a {@code ChatClient} call, as the pages "Advisors" and "Built-in
 * Advisors" describe it: what the chain holds, its order and the tool loop, the context
 * that travels with the request and the response, and the default orders of the
 * built-in advisors that this sample has on its class path.
 */
class AdvisorChainTests {

	private final List<String> events = new CopyOnWriteArrayList<>();

	@Test
	void theChainHasTheAdvisorsOfBuilderAndCallThenTheToolLoopAndEndsWithTheModel() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);
		ChatClient client = ChatClient.builder(model).defaultAdvisors(new Probe("default", 10, this.events)).build();

		client.prompt("Hi").advisors(probe, new Probe("request", 20, this.events)).call().content();
		List<String> callChain = List.copyOf(probe.chain());
		client.prompt("Hi").advisors(probe).stream().content().blockLast();

		assertThat(callChain).containsExactly("probe@" + Ordered.HIGHEST_PRECEDENCE,
				"Tool Calling Advisor@" + ToolCallingAdvisor.DEFAULT_ORDER, "default@10", "request@20",
				"call@" + Ordered.LOWEST_PRECEDENCE);
		assertThat(probe.chain()).last().isEqualTo("stream@" + Ordered.LOWEST_PRECEDENCE);
	}

	@Test
	void theLowestOrderRunsFirstOnTheWayDownAndLastOnTheWayUp() {
		ChatClient.create(ScriptedChatModel.answering("ok"))
			.prompt("Hi")
			.advisors(new Probe("lowest", Ordered.HIGHEST_PRECEDENCE + 1, this.events),
					new Probe("highest", Ordered.LOWEST_PRECEDENCE - 1, this.events))
			.call()
			.content();

		assertThat(this.events).containsExactly("lowest>", "highest>", "<highest", "<lowest");
	}

	@Test
	void anAdvisorBeforeTheToolLoopRunsOnceAndOneAfterItRunsForEveryRound() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}"));

		ChatClient.create(model)
			.prompt("What time is it?")
			.tools(new Clock())
			.advisors(new Probe("outside", ToolCallingAdvisor.DEFAULT_ORDER - 1, this.events),
					new Probe("inside", ToolCallingAdvisor.DEFAULT_ORDER + 1, this.events))
			.call()
			.content();

		assertThat(this.events).containsExactly("outside>", "inside>", "<inside", "inside>", "<inside", "<outside");
	}

	@Test
	void anAdvisorStoresSomethingInTheContextOnTheWayDownAndReadsItOnTheWayUp() {
		List<Object> seen = new CopyOnWriteArrayList<>();
		BaseAdvisor stamping = new Named("stamping", 0) {

			@Override
			public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
				return request.mutate().context("stamp", "from before").build();
			}

			@Override
			public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
				seen.add(response.context().get("stamp"));
				seen.add(response.context().get("tenant"));
				return response;
			}

		};

		ChatClientResponse response = ChatClient.create(ScriptedChatModel.answering("ok"))
			.prompt("Hi")
			.advisors((a) -> a.param("tenant", "shop-1").advisors(stamping))
			.call()
			.chatClientResponse();

		assertThat(seen).containsExactly("from before", "shop-1");
		assertThat(response.context()).containsEntry("stamp", "from before").containsEntry("tenant", "shop-1");
	}

	@Test
	void theContextCarriesTheOutputFormatOfEntity() {
		List<Object> formats = new CopyOnWriteArrayList<>();
		BaseAdvisor reader = new Named("reader", 0) {

			@Override
			public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
				formats.add(request.context().get("spring.ai.chat.client.output.format"));
				return request;
			}

		};

		ChatClient.create(ScriptedChatModel.answering("{\"name\":\"Tom\"}"))
			.prompt("Name someone")
			.advisors(reader)
			.call()
			.entity(Person.class);

		assertThat(formats).singleElement().asString().contains("Your response should be in JSON format.");
	}

	@Test
	void mutateMakesAChangedCopyOfTheRequestAndTheResponse() {
		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("Hi")).context("k", "v").build();
		ChatClientResponse response = ChatClientResponse.builder().context("k", "v").build();

		ChatClientRequest changedRequest = request.mutate().prompt(new Prompt("Hello")).build();
		ChatClientResponse changedResponse = response.mutate().context("k2", "v2").build();

		assertThat(request.prompt().getUserMessage().getText()).isEqualTo("Hi");
		assertThat(changedRequest.prompt().getUserMessage().getText()).isEqualTo("Hello");
		assertThat(changedRequest.context()).containsEntry("k", "v");
		assertThat(response.context()).doesNotContainKey("k2");
		assertThat(changedResponse.context()).containsEntry("k", "v").containsEntry("k2", "v2");
	}

	/**
	 * With a memory advisor after the tool loop, the loop forwards only the system message
	 * and the latest tool result, and leaves the rounds to the memory advisor. Without a
	 * system message, the forwarded prompt starts with an empty one.
	 */
	@Test
	void aMemoryAdvisorAfterTheToolLoopTakesOverTheHistoryOfTheRounds() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}"));
		ScriptedChatModel withoutMemory = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}"));

		ChatClient.create(model).prompt("What time is it?").tools(new Clock()).advisors(new Memory()).call().content();
		ChatClient.create(withoutMemory).prompt("What time is it?").tools(new Clock()).call().content();

		assertThat(model.prompts.get(1).getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.TOOL);
		assertThat(model.prompts.get(1).getInstructions().get(0)).isEqualTo(new SystemMessage(""));
		assertThat(withoutMemory.prompts.get(1).getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
	}

	@Test
	void theBuiltInAdvisorsHaveTheirDocumentedDefaultOrders() {
		ChatModel model = ScriptedChatModel.answering("ok");

		assertThat(new SimpleLoggerAdvisor().getOrder()).isZero();
		assertThat(SafeGuardAdvisor.builder().sensitiveWords(List.of("x")).build().getOrder()).isZero();
		assertThat(StructuredOutputValidationAdvisor.builder().outputType(Person.class).build().getOrder())
			.isEqualTo(Ordered.LOWEST_PRECEDENCE - 2000);
		assertThat(ToolCallingAdvisor.builder().build().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
		assertThat(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build().getOrder())
			.isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);
		assertThat(ChatModelCallAdvisor.builder().chatModel(model).build().getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
		assertThat(ChatModelStreamAdvisor.builder().chatModel(model).build().getOrder())
			.isEqualTo(Ordered.LOWEST_PRECEDENCE);
	}

	@Test
	void everyBuiltInAdvisorLetsYouChangeItsOrder() {
		assertThat(new SimpleLoggerAdvisor(5).getOrder()).isEqualTo(5);
		assertThat(SimpleLoggerAdvisor.builder().order(6).build().getOrder()).isEqualTo(6);
		assertThat(SafeGuardAdvisor.builder().sensitiveWords(List.of("x")).order(7).build().getOrder()).isEqualTo(7);
		assertThat(StructuredOutputValidationAdvisor.builder().outputType(Person.class).advisorOrder(8).build().getOrder())
			.isEqualTo(8);
		assertThat(ToolCallingAdvisor.builder().advisorOrder(9).build().getOrder()).isEqualTo(9);
		assertThat(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).order(10).build().getOrder())
			.isEqualTo(10);
	}

	/**
	 * The page "Chat Client API" says that the scheduler of the advisors' streaming calls
	 * can be set through the builder of each advisor class. Of the built-in advisors of
	 * {@code spring-ai-client-chat}, only the deprecated {@code MessageChatMemoryAdvisor}
	 * has such a builder method; a {@code BaseAdvisor} of your own overrides
	 * {@code getScheduler()}.
	 */
	@Test
	void onlyTheMessageChatMemoryAdvisorBuilderTakesAScheduler() {
		List<Class<?>> builders = List.of(SimpleLoggerAdvisor.Builder.class, SafeGuardAdvisor.Builder.class,
				StructuredOutputValidationAdvisor.Builder.class, ToolCallingAdvisor.Builder.class,
				MessageChatMemoryAdvisor.Builder.class);

		assertThat(builders).filteredOn((builder) -> Arrays.stream(builder.getMethods())
			.map(Method::getName)
			.anyMatch("scheduler"::equals)).containsExactly(MessageChatMemoryAdvisor.Builder.class);
	}

	record Person(String name) {
	}

	static class Clock {

		@Tool(description = "Tells the time")
		String time() {
			return "noon";
		}

	}

	/**
	 * A memory advisor that keeps no memory: the marker alone counts.
	 */
	static class Memory extends Named implements MemoryAdvisor {

		Memory() {
			super("memory", Ordered.HIGHEST_PRECEDENCE + 1000);
		}

	}

	/**
	 * A {@code BaseAdvisor} with a name and an order that changes nothing unless told to.
	 */
	static class Named implements BaseAdvisor {

		private final String name;

		private final int order;

		Named(String name, int order) {
			this.name = name;
			this.order = order;
		}

		@Override
		public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
			return request;
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
			return response;
		}

		@Override
		public String getName() {
			return this.name;
		}

		@Override
		public int getOrder() {
			return this.order;
		}

	}

}
