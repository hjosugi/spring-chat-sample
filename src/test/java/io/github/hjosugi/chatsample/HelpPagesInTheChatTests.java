package io.github.hjosugi.chatsample;

import java.util.List;

import io.github.hjosugi.chatsample.rag.BagOfWordsEmbeddingModel;
import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application with an embedding model, as {@code EMBEDDING_PROVIDER=openai} starts it:
 * the shop's help pages are embedded at the start, and the sections close to a question
 * go to the chat model with it. Spring AI's own clients talk to two stand-ins: OpenAI's
 * embeddings, with a bag of words for vectors, and Anthropic's messages.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key=test-key",
				"spring.ai.anthropic.max-retries=0", "spring.ai.anthropic.chat.model=claude-haiku-5-5",
				"spring.ai.model.embedding=openai", "spring.ai.openai.api-key=test-key",
				"spring.ai.openai.max-retries=0",
				// For the bag of words, which is close to a text only by the words they share.
				// The threshold of the application is for a real model.
				"shop.help.similarity-threshold=0.15" })
class HelpPagesInTheChatTests {

	private static final StandIn openAi = new StandIn(BagOfWordsEmbeddingModel::openAiEmbeddings);

	private static final FakeAnthropic anthropic = anthropic();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private SessionService sessions;

	private static FakeAnthropic anthropic() {
		try {
			return new FakeAnthropic((body) -> {
				String result = FakeAnthropic.toolResult(body);
				if (result != null) {
					return FakeAnthropic.text("Found: ", result);
				}
				// The question, and not what the help pages add to it: they speak of orders too.
				return body.toString().contains("What did I order?") ? FakeAnthropic.toolUse("myOrders", "{}")
						: FakeAnthropic.text("As the help pages say.");
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void standIns(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.anthropic.base-url", anthropic::url);
		registry.add("spring.ai.openai.base-url", openAi::url);
	}

	@AfterAll
	static void stop() {
		anthropic.stop();
		openAi.close();
	}

	@BeforeEach
	void forget() {
		anthropic.reset();
	}

	@Test
	void theHelpPagesWereEmbeddedWhenTheApplicationStarted() {
		List<String> embedded = openAi.requests.stream()
			.flatMap((request) -> request.body().get("input").valueStream())
			.map(JsonNode::asString)
			.toList();

		assertThat(embedded).anySatisfy((text) -> assertThat(text).contains("title: Refunds", "5 working days"));
		assertThat(openAi.requests).allSatisfy(
				(request) -> assertThat(request.body().get("model").asString()).isEqualTo("text-embedding-3-small"));
	}

	@Test
	void aQuestionThatTheHelpPagesAnswerReachesTheModelWithTheirSections() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		String reply = alice.chat("When does my refund arrive?");

		assertThat(reply).contains("As the help pages say.", "event:done").doesNotContain("event:error");
		String asked = lastUserText(anthropic.requests.get(0));
		assertThat(asked).startsWith("When does my refund arrive?")
			.contains("help pages", "The refund goes back to the original payment method within 5 working days");
		// And the tools are still there, for a question about an order.
		assertThat(anthropic.requests.get(0).get("tools").toString()).contains("myOrders");
	}

	@Test
	void theConversationKeepsTheQuestionAsTheUserWroteIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("How long does the warranty last?");

		List<Message> conversation = conversationThatStartsWith("alice", "How long does the warranty last?");

		assertThat(conversation).hasSize(2);
		assertThat(conversation.get(1).getText()).isEqualTo("As the help pages say.");
		assertThat(lastUserText(anthropic.requests.get(0))).contains("How long does the warranty last?", "---------");
	}

	@Test
	void aQuestionThatNoSectionIsCloseToGoesToTheModelAsItIs() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		alice.chat("Hello!");

		assertThat(lastUserText(anthropic.requests.get(0))).isEqualTo("Hello!");
	}

	@Test
	void aQuestionAboutAnOrderIsStillAnsweredWithTheTools() throws Exception {
		Browser bob = Browser.signIn(this.port, "bob");

		String reply = bob.chat("What did I order?");

		assertThat(reply).contains("B-2001", "Kettle", "event:done").doesNotContain("event:error");
		// The second request of the turn brings the tool's result, and the question with it.
		assertThat(anthropic.requests).hasSize(2);
		assertThat(anthropic.requests.get(1).toString()).contains("tool_result", "What did I order?");
	}

	/**
	 * The conversation of a user that began with these very words. The chat labels a
	 * session with who started it, and keeps the messages as they were written.
	 */
	private List<Message> conversationThatStartsWith(String user, String question) {
		List<List<Message>> conversations = this.sessions.findByMetadata(SessionAttributes.PRINCIPAL_NAME, user)
			.stream()
			.map((session) -> this.sessions.getMessages(session.id()))
			.filter((messages) -> !messages.isEmpty() && question.equals(messages.get(0).getText()))
			.toList();
		assertThat(conversations).as("the conversations of %s that start with \"%s\"", user, question).hasSize(1);
		return conversations.get(0);
	}

	private static String lastUserText(JsonNode request) {
		JsonNode messages = request.get("messages");
		for (int i = messages.size() - 1; i >= 0; i--) {
			JsonNode message = messages.get(i);
			if ("user".equals(message.get("role").asString())) {
				JsonNode content = message.get("content");
				if (content.isString()) {
					return content.asString();
				}
				for (JsonNode block : content) {
					if ("text".equals(block.path("type").asString())) {
						return block.get("text").asString();
					}
				}
			}
		}
		throw new AssertionError("No text of the user in " + request);
	}

}
