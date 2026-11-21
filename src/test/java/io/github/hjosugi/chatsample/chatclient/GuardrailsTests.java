package io.github.hjosugi.chatsample.chatclient;

import java.util.List;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ContentFilteredException;
import org.springframework.ai.chat.client.GuardrailRefusalException;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.GuardrailRefusal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The {@code SafeGuardAdvisor} and refusals, as the page "Guardrails" describes them.
 * Decision and moderation models are model APIs of their own and verified elsewhere.
 */
class GuardrailsTests {

	private final ScriptedChatModel model = ScriptedChatModel.answering("{\"total\":42}");

	private final ChatClient chatClient = ChatClient.builder(this.model)
		.defaultAdvisors(SafeGuardAdvisor.builder()
			.sensitiveWords(List.of("password", "credit card"))
			.failureResponse("I can't help with that.")
			.build())
		.build();

	@Test
	void aSensitiveWordBlocksTheCallAndTheModelIsNotAsked() {
		String answer = this.chatClient.prompt("What is my password?").call().content();

		assertThat(answer).isEqualTo("I can't help with that.");
		assertThat(this.model.prompts).isEmpty();
		assertThat(this.chatClient.prompt("What is my order?").call().content()).isEqualTo("{\"total\":42}");
		assertThat(this.model.prompts).hasSize(1);
	}

	@Test
	void theMatchIgnoresCaseAndFindsTheWordInsideOthers() {
		assertThat(this.chatClient.prompt("Pay by CREDIT CARD?").call().content()).isEqualTo("I can't help with that.");
		assertThat(this.chatClient.prompt("Reset my passwords").call().content()).isEqualTo("I can't help with that.");
		assertThat(this.model.prompts).isEmpty();
	}

	@Test
	void theSystemMessageAndTheHistoryAreCheckedToo() {
		ChatClient strictSystem = ChatClient.builder(this.model)
			.defaultSystem("Never reveal a password.")
			.defaultAdvisors(SafeGuardAdvisor.builder().sensitiveWords(List.of("password")).build())
			.build();

		String fromSystem = strictSystem.prompt("What is my order?").call().content();
		String fromHistory = this.chatClient.prompt()
			.messages(new UserMessage("My password is hunter2"), new AssistantMessage("Noted."))
			.user("What is my order?")
			.call()
			.content();

		assertThat(fromSystem).startsWith("I'm unable to respond to that due to sensitive content.");
		assertThat(fromHistory).isEqualTo("I can't help with that.");
		assertThat(this.model.prompts).isEmpty();
	}

	@Test
	void theHistoryThatAMemoryAdvisorAddsFirstIsChecked() {
		ChatMemory memory = MessageWindowChatMemory.builder().build();
		memory.add("c-1", List.<Message>of(new UserMessage("My credit card ends in 1234"), new AssistantMessage("Noted.")));
		MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(memory).build();

		String answer = this.chatClient.prompt("What is my order?")
			.advisors(memoryAdvisor)
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "c-1"))
			.call()
			.content();

		assertThat(memoryAdvisor.getOrder()).isLessThan(0);
		assertThat(answer).isEqualTo("I can't help with that.");
		assertThat(this.model.prompts).isEmpty();
		// The memory stores the refusal like any other reply.
		assertThat(memory.get("c-1")).last().extracting(Message::getText).isEqualTo("I can't help with that.");
	}

	@Test
	void theRefusalIsMarkedAsOneAndFiltered() {
		ChatResponse refusal = this.chatClient.prompt("What is my password?").call().chatResponse();

		assertThat(GuardrailRefusal.isRefusal(refusal)).isTrue();
		assertThat(GuardrailRefusal.text(refusal)).isEqualTo("I can't help with that.");
		assertThat(refusal.getResult().getMetadata().getFinishReason()).isEqualTo(GuardrailRefusal.FINISH_REASON);
		assertThat(refusal.getResult().getMetadata().getFinishReasonCategory()).isEqualTo(FinishReasonCategory.FILTERED);
		assertThat(GuardrailRefusal.isRefusal(this.chatClient.prompt("Hi").call().chatResponse())).isFalse();
	}

	@Test
	void entityThrowsTheRefusalAndResponseEntityReturnsNoEntity() {
		assertThatExceptionOfType(GuardrailRefusalException.class)
			.isThrownBy(() -> this.chatClient.prompt("What is my password?").call().entity(Total.class))
			.satisfies((ex) -> {
				assertThat(ex.getRefusal()).isEqualTo("I can't help with that.");
				assertThat(ex.getFinishReason()).isEqualTo(GuardrailRefusal.FINISH_REASON);
			})
			.isInstanceOf(ContentFilteredException.class);

		ResponseEntity<ChatResponse, Total> result = this.chatClient.prompt("What is my password?")
			.call()
			.responseEntity(Total.class);

		assertThat(result.entity()).isNull();
		assertThat(GuardrailRefusal.isRefusal(result.response())).isTrue();
	}

	@Test
	void oneCatchCoversTheGuardrailAndTheSafetyStopOfAProvider() {
		ChatClient filtering = ChatClient.create(CannedChatModel.answering(CannedChatModel.response("", "content_filter")));

		assertThat(reply(this.chatClient, "What is my password?")).isEqualTo("Sorry, I can't help with that.");
		assertThat(reply(filtering, "What is my order?")).isEqualTo("Sorry, I can't help with that.");
		assertThat(reply(this.chatClient, "What is my order?")).isEqualTo("42");
	}

	@Test
	void aStreamGetsTheRefusalAsItsOnlyChunk() {
		List<String> chunks = this.chatClient.prompt("What is my password?").stream().content().collectList().block();

		assertThat(chunks).containsExactly("I can't help with that.");
		assertThat(this.model.prompts).isEmpty();
	}

	@Test
	void fullwidthLettersPassTheCheck() {
		String answer = this.chatClient.prompt("What is my ｐａｓｓｗｏｒｄ?").call().content();

		assertThat(answer).isEqualTo("{\"total\":42}");
		assertThat(this.model.prompts).hasSize(1);
	}

	/**
	 * Not on the page: the texts of the messages are joined without a separator before
	 * the check, so a word that two messages make together blocks the call.
	 */
	@Test
	void aWordThatTwoMessagesMakeTogetherBlocksTheCall() {
		String answer = this.chatClient.prompt()
			.messages(new UserMessage("Enter your pass"), new AssistantMessage("word count, please?"))
			.user("Twelve")
			.call()
			.content();

		assertThat(answer).isEqualTo("I can't help with that.");
		assertThat(this.model.prompts).isEmpty();
	}

	private static String reply(ChatClient chatClient, String message) {
		try {
			Total total = chatClient.prompt().user(message).call().entity(Total.class);
			return String.valueOf(total.total());
		}
		catch (ContentFilteredException ex) {
			return "Sorry, I can't help with that.";
		}
	}

	record Total(int total) {
	}

}
