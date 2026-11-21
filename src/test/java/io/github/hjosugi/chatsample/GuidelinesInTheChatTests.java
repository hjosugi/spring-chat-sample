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
 * the coding guidelines are embedded at the start, and the sections close to a question
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
				"agent.guidelines.similarity-threshold=0.15" })
class GuidelinesInTheChatTests {

	private static final StandIn openAi = new StandIn(BagOfWordsEmbeddingModel::openAiEmbeddings);

	private static final FakeAnthropic anthropic = anthropic();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private SessionService sessions;

	@Autowired
	private Workspace workspace;

	private static FakeAnthropic anthropic() {
		try {
			return new FakeAnthropic((body) -> {
				String result = FakeAnthropic.toolResult(body);
				if (result != null) {
					return FakeAnthropic.text("Found: ", result);
				}
				// The question, and not what the guidelines add to it.
				return body.toString().contains("List the files") ? FakeAnthropic.toolUse("listFiles", "{\"path\": \".\"}")
						: FakeAnthropic.text("As the guidelines say.");
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void standIns(DynamicPropertyRegistry registry) throws java.io.IOException {
		registry.add("spring.ai.anthropic.base-url", anthropic::url);
		registry.add("spring.ai.openai.base-url", openAi::url);
		java.nio.file.Path workspace = java.nio.file.Files.createTempDirectory("agent-workspace");
		registry.add("agent.workspace", workspace::toString);
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
	void theGuidelinesWereEmbeddedWhenTheApplicationStarted() {
		List<String> embedded = openAi.requests.stream()
			.flatMap((request) -> request.body().get("input").valueStream())
			.map(JsonNode::asString)
			.toList();

		assertThat(embedded).anySatisfy(
				(text) -> assertThat(text).contains("title: Names", "A test's name is a sentence about the behavior"));
		assertThat(openAi.requests).allSatisfy(
				(request) -> assertThat(request.body().get("model").asString()).isEqualTo("text-embedding-3-small"));
	}

	@Test
	void aQuestionThatTheGuidelinesAnswerReachesTheModelWithTheirSections() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		String reply = alice.chat("How should I name a test?");

		assertThat(reply).contains("As the guidelines say.", "event:done").doesNotContain("event:error");
		String asked = lastUserText(anthropic.requests.get(0));
		assertThat(asked).startsWith("How should I name a test?")
			.contains("coding guidelines", "A test's name is a sentence about the behavior");
		// And the tools are still there, for a question about a file.
		assertThat(anthropic.requests.get(0).get("tools").toString()).contains("listFiles");
	}

	@Test
	void theConversationKeepsTheQuestionAsTheUserWroteIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		alice.chat("Can a public method return null?");

		List<Message> conversation = conversationThatStartsWith("alice", "Can a public method return null?");

		assertThat(conversation).hasSize(2);
		assertThat(conversation.get(1).getText()).isEqualTo("As the guidelines say.");
		assertThat(lastUserText(anthropic.requests.get(0))).contains("Can a public method return null?", "---------");
	}

	@Test
	void aQuestionThatNoSectionIsCloseToGoesToTheModelAsItIs() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");

		alice.chat("Hello!");

		assertThat(lastUserText(anthropic.requests.get(0))).isEqualTo("Hello!");
	}

	@Test
	void aQuestionAboutFilesIsStillAnsweredWithTheToolsWhenTheGuidelinesComeWithIt() throws Exception {
		java.nio.file.Files.writeString(this.workspace.of("bob").resolve("bobs.txt"), "mine");
		Browser bob = Browser.signIn(this.port, "bob");

		String reply = bob.chat("List the files and tell me how to name a test");

		assertThat(reply).contains("bobs.txt", "event:done").doesNotContain("event:error");
		// The second request of the turn brings the tool's result, and the question with the
		// sections that were close to it: the advisor searches once for each round.
		assertThat(anthropic.requests).hasSize(2);
		assertThat(anthropic.requests.get(0).toString()).contains("A test's name is a sentence about the behavior");
		assertThat(anthropic.requests.get(1).toString()).contains("tool_result", "name a test",
				"A test's name is a sentence about the behavior");
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
