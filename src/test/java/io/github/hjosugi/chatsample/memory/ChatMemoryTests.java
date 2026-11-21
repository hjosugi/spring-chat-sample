package io.github.hjosugi.chatsample.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.H2ChatMemoryRepositoryDialect;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The deprecated chat memory API and its in-memory and JDBC repositories, as the pages
 * describe them: the window and its eviction, the memory advisor, and what each
 * repository keeps.
 */
@SuppressWarnings("removal")
class ChatMemoryTests {

	private final ApplicationContextRunner boot = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ChatMemoryAutoConfiguration.class));

	private final ApplicationContextRunner jdbc = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
				JdbcChatMemoryRepositoryAutoConfiguration.class, ChatMemoryAutoConfiguration.class))
		.withPropertyValues("spring.datasource.generate-unique-name=true");

	@Test
	void theAutoConfiguredMemoryIsAWindowOverAnInMemoryRepositoryUnlessAnotherOneIsThere() {
		this.boot.run((context) -> {
			assertThat(context.getBean(ChatMemory.class)).isInstanceOf(MessageWindowChatMemory.class);
			assertThat(context.getBean(ChatMemoryRepository.class)).isInstanceOf(InMemoryChatMemoryRepository.class);
		});
		this.jdbc.run((context) -> {
			assertThat(context.getBean(ChatMemoryRepository.class)).isInstanceOf(JdbcChatMemoryRepository.class);
			ChatMemory memory = context.getBean(ChatMemory.class);
			memory.add("c1", new UserMessage("Hello"));
			assertThat(context.getBean(JdbcTemplate.class)
				.queryForObject("SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY", Integer.class)).isEqualTo(1);
		});
	}

	@Test
	void eachConversationIdIsAConversationOfItsOwn() {
		ChatMemory memory = MessageWindowChatMemory.builder().build();

		memory.add("alice:1", new UserMessage("I am Alice"));
		memory.add("bob:1", new UserMessage("I am Bob"));
		memory.clear("bob:1");

		assertThat(Transcript.of(memory.get("alice:1"))).containsExactly("user: I am Alice");
		assertThat(memory.get("bob:1")).isEmpty();
	}

	@Test
	void theWindowKeeps20MessagesByDefaultAndTheSystemMessagesAlways() {
		ChatMemory memory = MessageWindowChatMemory.builder().build();
		memory.add("c1", new SystemMessage("Be brief."));
		for (int i = 1; i <= 15; i++) {
			memory.add("c1", List.of(new UserMessage("U" + i), new AssistantMessage("A" + i)));
		}

		List<String> kept = Transcript.of(memory.get("c1"));

		assertThat(kept).hasSizeLessThanOrEqualTo(20).startsWith("system: Be brief.").endsWith("user: U15", "assistant: A15");
		assertThat(kept.get(1)).startsWith("user: ");
	}

	@Test
	void evictionRemovesWholeTurnsSoTheWindowMayHoldFewerMessages() {
		ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(4).build();
		memory.add("c1", List.of(new UserMessage("U1"), new AssistantMessage("A1")));
		memory.add("c1", List.of(new UserMessage("U2"), toolCall(), toolResult(), new AssistantMessage("A2")));
		memory.add("c1", List.of(new UserMessage("U3"), new AssistantMessage("A3")));

		assertThat(Transcript.of(memory.get("c1"))).containsExactly("user: U3", "assistant: A3");
	}

	@Test
	void aTurnLargerThanTheWindowLeavesNoNonSystemMessage() {
		ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(3).build();
		memory.add("c1", new SystemMessage("Be brief."));
		memory.add("c1", List.of(new UserMessage("U1"), toolCall(), toolResult(), new AssistantMessage("A1")));

		assertThat(Transcript.of(memory.get("c1"))).containsExactly("system: Be brief.");
	}

	@Test
	void theMemoryAdvisorSendsTheHistoryAndNeedsAConversationId() {
		ScriptedChatModel model = ScriptedChatModel.answering("Yes");
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
			.build();

		client.prompt("Do I have license to code?").advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "007")).call().content();
		client.prompt("Really?").advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "007")).call().content();

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Do I have license to code?", "assistant: Yes",
				"user: Really?");
		assertThatIllegalArgumentException().isThrownBy(() -> client.prompt("Who?").call().content());
	}

	@Test
	void withAChatModelTheApplicationKeepsTheMemoryItself() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.text(
				prompt.getContents().contains("James Bond") ? "You are James Bond" : "I do not know"));
		ChatMemory memory = MessageWindowChatMemory.builder().build();

		memory.add("007", new UserMessage("My name is James Bond"));
		ChatResponse first = model.call(new Prompt(memory.get("007")));
		memory.add("007", first.getResult().getOutput());
		memory.add("007", new UserMessage("What is my name?"));
		ChatResponse second = model.call(new Prompt(memory.get("007")));

		assertThat(second.getResult().getOutput().getText()).isEqualTo("You are James Bond");
	}

	@Test
	void theInMemoryRepositoryKeepsToolMessages() {
		InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();

		repository.saveAll("c1", List.of(new UserMessage("U1"), toolCall(), toolResult(), new AssistantMessage("A1")));

		assertThat(Transcript.of(repository.findByConversationId("c1"))).containsExactly("user: U1", "calls: lookUp({})",
				"results: found", "assistant: A1");
	}

	@Test
	void theJdbcRepositoryKeepsTheOrderAndTheTimestampsAndDropsToolMessages() {
		this.jdbc.run((context) -> {
			JdbcChatMemoryRepository repository = context.getBean(JdbcChatMemoryRepository.class);
			repository.saveAll("c1", List.of(new UserMessage("U1"), toolCall(), toolResult(), new AssistantMessage("A1")));
			List<Message> first = repository.findByConversationId("c1");
			Instant created = (Instant) first.get(0).getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS);

			List<Message> more = new ArrayList<>(first);
			more.add(new UserMessage("U2"));
			repository.saveAll("c1", more);

			assertThat(Transcript.of(first)).containsExactly("user: U1", "assistant: A1");
			assertThat(Transcript.of(repository.findByConversationId("c1"))).containsExactly("user: U1", "assistant: A1",
					"user: U2");
			assertThat(created).isNotNull();
			assertThat(repository.findByConversationId("c1").get(0).getMetadata())
				.containsEntry(JdbcChatMemoryRepository.CONVERSATION_TS, created);
			assertThat(JdbcChatMemoryRepositoryDialect.from(context.getBean(javax.sql.DataSource.class)))
				.isInstanceOf(H2ChatMemoryRepositoryDialect.class);
		});
	}

	@Test
	void withInitializeSchemaNeverTheChatMemoryTableIsNotCreated() {
		this.jdbc.withPropertyValues("spring.ai.chat.memory.repository.jdbc.initialize-schema=never")
			.run((context) -> assertThat(context.getBean(JdbcTemplate.class)
				.queryForList("SELECT table_name FROM information_schema.tables", String.class))
				.doesNotContain("SPRING_AI_CHAT_MEMORY"));
	}

	@Test
	void theJdbcRepositoryInsideTheLoopLosesTheToolMessagesOfTheTurn() {
		this.jdbc.run((context) -> {
			ChatMemory memory = context.getBean(ChatMemory.class);
			ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
					? ScriptedChatModel.text("It is noon.") : ScriptedChatModel.toolCall("time", "{}"));
			ChatClient client = ChatClient.builder(model)
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory)
					.order(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER + 100)
					.build())
				.build();

			client.prompt("What time is it?")
				.tools(new Clock())
				.advisors((a) -> a.param(ChatMemory.CONVERSATION_ID, "c1"))
				.call()
				.content();

			assertThat(Transcript.of(memory.get("c1"))).containsExactly("user: What time is it?", "assistant: It is noon.");
		});
	}

	private static AssistantMessage toolCall() {
		return AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "lookUp", "{}")))
			.build();
	}

	private static ToolResponseMessage toolResult() {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c1", "lookUp", "found")))
			.build();
	}

	static class Clock {

		@org.springframework.ai.tool.annotation.Tool(description = "Tells the time")
		String time() {
			return "noon";
		}

	}

}
