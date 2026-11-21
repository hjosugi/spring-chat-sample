package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Creating and calling a {@code ChatClient}, as the pages "Chat Client API" and
 * "Creating and Calling a ChatClient" show it: the auto-configured builder, the three
 * ways to start a prompt, and prompt templates.
 */
class ChatClientBasicsTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
		.withBean(ChatModel.class, () -> ScriptedChatModel.answering("Hello from the model"));

	@Test
	void theAutoConfiguredBuilderIsAPrototypeBoundToTheChatModel() {
		this.contextRunner.run((context) -> {
			ChatClient.Builder first = context.getBean(ChatClient.Builder.class);
			ChatClient.Builder second = context.getBean(ChatClient.Builder.class);
			assertThat(first).isNotSameAs(second);

			String answer = first.build().prompt().user("Hi").call().content();

			assertThat(answer).isEqualTo("Hello from the model");
			ScriptedChatModel model = (ScriptedChatModel) context.getBean(ChatModel.class);
			assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("Hi");
		});
	}

	@Test
	void theAutoConfiguredBuilderRunsTheToolsOfARequest() {
		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
			.withBean(ChatModel.class,
					() -> new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
							? ScriptedChatModel.text("It is " + ScriptedChatModel.lastText(prompt))
							: ScriptedChatModel.toolCall("time", "{}")))
			.run((context) -> {
				assertThat(context).hasSingleBean(ToolCallingAdvisor.Builder.class);

				String answer = context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("What time is it?")
					.tools(new Clock())
					.call()
					.content();

				assertThat(answer).isEqualTo("It is \"noon\"");
			});
	}

	@Test
	void promptWithoutArgumentsBuildsUpUserAndSystem() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model).prompt().system("Be brief").user("Hi").call().content();

		assertThat(model.lastPrompt().getInstructions()).extracting((message) -> message.getMessageType())
			.containsExactly(MessageType.SYSTEM, MessageType.USER);
		assertThat(model.lastPrompt().getSystemMessage().getText()).isEqualTo("Be brief");
		assertThat(model.lastPrompt().getUserMessage().getText()).isEqualTo("Hi");
	}

	@Test
	void promptWithAPromptPassesItsMessagesOn() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		Prompt prompt = new Prompt(List.of(new SystemMessage("Be brief"), new UserMessage("Hi")));

		ChatClient.create(model).prompt(prompt).call().content();

		assertThat(model.lastPrompt().getInstructions()).containsExactly(new SystemMessage("Be brief"),
				new UserMessage("Hi"));
	}

	@Test
	void promptWithAStringIsTheUsersText() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model).prompt("Hi").call().content();

		assertThat(model.lastPrompt().getInstructions()).containsExactly(new UserMessage("Hi"));
	}

	@Test
	void theUserTextIsATemplateWhoseParamsAreReplaced() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model)
			.prompt()
			.user((u) -> u.text("Tell me the names of 5 movies whose soundtrack was composed by {composer}")
				.param("composer", "John Williams"))
			.call()
			.content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt()))
			.isEqualTo("Tell me the names of 5 movies whose soundtrack was composed by John Williams");
	}

	@Test
	void aTextWithoutParamsIsNotRenderedSoItsBracesStay() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model).prompt().user("Reply with {\"answer\": 42}").call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("Reply with {\"answer\": 42}");
	}

	@Test
	void otherDelimitersLeaveJsonInTheTemplateAlone() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model)
			.prompt()
			.user((u) -> u.text("Tell me the names of 5 movies whose soundtrack was composed by <composer> as {\"movies\": []}")
				.param("composer", "John Williams"))
			.templateRenderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
			.call()
			.content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo(
				"Tell me the names of 5 movies whose soundtrack was composed by John Williams as {\"movies\": []}");
	}

	@Test
	void theNoOpRendererLeavesTheTemplateAsItIs() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model)
			.prompt()
			.user((u) -> u.text("Hello {name}").param("name", "Alice"))
			.templateRenderer(new NoOpTemplateRenderer())
			.call()
			.content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("Hello {name}");
	}

	@Test
	void aMissingTemplateVariableFailsBeforeTheModelIsAsked() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		assertThatIllegalStateException()
			.isThrownBy(() -> ChatClient.create(model)
				.prompt()
				.user((u) -> u.text("Hello {name} from {city}").param("name", "Alice"))
				.call()
				.content())
			.withMessageContaining("city");
		assertThat(model.prompts).isEmpty();
	}

	@Test
	void theRendererOfTheClientDoesNotReachTheAdvisors() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		List<String> seenByAdvisor = new CopyOnWriteArrayList<>();
		// An advisor that renders a template of its own, as the QuestionAnswerAdvisor does.
		var advisor = new RenderingAdvisor("Context: {context}", Map.of("context", "the shop"), seenByAdvisor);

		ChatClient.create(model)
			.prompt()
			.user((u) -> u.text("Question: <q>").param("q", "Where is A-1001?"))
			.templateRenderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
			.advisors(advisor)
			.call()
			.content();

		assertThat(seenByAdvisor).containsExactly("Context: the shop");
		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("Question: Where is A-1001?\nContext: the shop");
	}

	@Test
	void callAloneDoesNotAskTheModel() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.CallResponseSpec call = ChatClient.create(model).prompt("Hi").call();
		ChatClient.StreamResponseSpec stream = ChatClient.create(model).prompt("Hi").stream();

		assertThat(model.prompts).isEmpty();
		assertThat(call.content()).isEqualTo("ok");
		assertThat(model.prompts).hasSize(1);
		stream.content();
		assertThat(model.prompts).as("a flux that no one subscribed to").hasSize(1);
	}

	@Test
	void inAStreamTheToolsRunOnTheBoundedElasticScheduler() {
		ThreadRecordingClock clock = new ThreadRecordingClock();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is noon") : ScriptedChatModel.toolCall("time", "{}"));

		ChatClient.create(model).prompt("What time is it?").tools(clock).stream().content().blockLast();
		ChatClient.create(model).prompt("What time is it?").tools(clock).call().content();

		assertThat(clock.threads).hasSize(2);
		assertThat(clock.threads.get(0)).contains("boundedElastic");
		assertThat(clock.threads.get(1)).isEqualTo(Thread.currentThread().getName());
	}

	@Test
	void anEmptyPromptStringIsRejected() {
		ChatClient client = ChatClient.create(ScriptedChatModel.answering("ok"));

		assertThatIllegalArgumentException().isThrownBy(() -> client.prompt(""));
	}

	@Test
	void withoutAChatModelTheBuilderFailsWhenItIsAskedFor() {
		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
			.run((context) -> {
				// A prototype: the context starts, and fails only when someone injects one.
				assertThat(context).hasNotFailed();
				assertThatExceptionOfType(UnsatisfiedDependencyException.class)
					.isThrownBy(() -> context.getBean(ChatClient.Builder.class))
					.withMessageContaining(ChatModel.class.getName());
			});
	}

	@Test
	void theAutoConfiguredBuilderCanBeTurnedOff() {
		this.contextRunner.withPropertyValues("spring.ai.chat.client.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(ChatClient.Builder.class));
	}

	static class Clock {

		@Tool(description = "Tells the time")
		String time() {
			return "noon";
		}

	}

	static class ThreadRecordingClock {

		final List<String> threads = new CopyOnWriteArrayList<>();

		@Tool(description = "Tells the time")
		String time() {
			this.threads.add(Thread.currentThread().getName());
			return "noon";
		}

	}

	/**
	 * Adds a line rendered from a template of its own to the user message.
	 */
	record RenderingAdvisor(String template, Map<String, Object> variables, List<String> rendered)
			implements BaseAdvisor {

		@Override
		public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
			String line = PromptTemplate.builder()
				.template(this.template)
				.variables(this.variables)
				.build()
				.render();
			this.rendered.add(line);
			return request.mutate()
				.prompt(request.prompt()
					.augmentUserMessage(request.prompt().getUserMessage().getText() + "\n" + line))
				.build();
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
			return response;
		}

		@Override
		public int getOrder() {
			return 0;
		}

	}

}
