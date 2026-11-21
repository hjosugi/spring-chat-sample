package io.github.hjosugi.chatsample.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventQuery;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.session.tool.SessionRecallTools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Recall: a keyword search over the whole log of a session, for code and, through the
 * {@code conversation_search} tool, for the model.
 */
class SessionRecallTests {

	private static final JsonMapper json = JsonMapper.shared();

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@BeforeEach
	void storeAConversationThatWasCompacted() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		this.sessions.appendMessage("s1", new UserMessage("Where is my INVOICE?"));
		this.sessions.appendMessage("s1", new AssistantMessage("The invoice was sent on Monday."));
		this.sessions.appendMessage("s1", new UserMessage("I want a refund for the invoice"));
		this.sessions.appendMessage("s1", AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "refund", "{}")))
			.build());
		this.sessions.appendMessage("s1", ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c1", "refund", "refund 100% approved")))
			.build());
		this.sessions.appendMessage("s1", new AssistantMessage("Your refund is approved."));
		this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder()
			.lastTurns(1)
			.summarizer((request) -> List.of(new UserMessage("Summarize"), new AssistantMessage("An invoice was sent.")))
			.build());
		this.sessions.create(CreateSessionRequest.builder().id("s2").build());
		this.sessions.appendMessage("s2", new UserMessage("Another invoice"));
	}

	@Test
	void aSearchFindsCoveredMessagesIgnoringCaseButNoSummaryNoToolResultAndNoOtherSession() {
		List<SessionEvent> found = this.sessions.searchEvents("s1", EventQuery.keyword("invoice"));

		assertThat(texts(found)).containsExactly("Where is my INVOICE?", "The invoice was sent on Monday.",
				"I want a refund for the invoice");
		assertThat(texts(this.sessions.searchEvents("s1", EventQuery.keyword("approved")))).containsExactly(
				"Your refund is approved.");
		assertThat(this.sessions.searchEvents("s1", EventQuery.keyword("100%"))).isEmpty();
	}

	@Test
	void theQueryMatchesAnyOrAllKeywordsOfSomeTypesSinceAnInstantAPageAtATime() {
		assertThat(texts(this.sessions.searchEvents("s1",
				EventQuery.builder().keywords("invoice", "refund").matchMode(EventQuery.MatchMode.ALL).build())))
			.containsExactly("I want a refund for the invoice");
		assertThat(texts(this.sessions.searchEvents("s1",
				EventQuery.builder().keywords("invoice").messageTypes(Set.of(MessageType.ASSISTANT)).build())))
			.containsExactly("The invoice was sent on Monday.");
		assertThat(this.sessions.searchEvents("s1",
				EventQuery.builder().keywords("invoice").since(Instant.now().plusSeconds(60)).build()))
			.isEmpty();
		assertThat(texts(this.sessions.searchEvents("s1",
				EventQuery.builder().keywords("invoice").page(1).pageSize(2).build())))
			.containsExactly("I want a refund for the invoice");
		assertThat(texts(this.sessions.searchEvents("s1", EventQuery.keyword("in_oice")))).isEmpty();
	}

	@Test
	void aQueryOutsideTheBoundsIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventQuery.builder().keywords("x").pageSize(101).build());
		assertThatIllegalArgumentException().isThrownBy(() -> EventQuery.keyword("x".repeat(201)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> EventQuery.builder()
				.keywords(java.util.stream.IntStream.range(0, 21).mapToObj((i) -> "k" + i).toList())
				.build());
		assertThatIllegalArgumentException().isThrownBy(() -> EventQuery.builder().keywords("x").page(100).build());
	}

	@Test
	void theToolSearchesTheSessionOfTheToolContextAndAnswersWithAPageOfJson() {
		ScriptedChatModel model = callingSearch("{\"query\":\"invoice\"}");
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultTools(SessionRecallTools.builder(this.sessions).pageSize(2).build())
			.build();

		String result = client.prompt("What did we say about invoices?")
			.advisors(SessionAdvisors.session("s1"))
			.toolContext(Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, "s1"))
			.call()
			.content();

		JsonNode page = json.readTree(result);
		assertThat(page.get("results").valueStream().map((match) -> match.get("text").asString()))
			.containsExactly("Where is my INVOICE?", "The invoice was sent on Monday.");
		assertThat(page.get("results").get(0).get("type").asString()).isEqualTo("user");
		assertThat(page.get("nextPage").asInt()).isEqualTo(1);
		assertThat(OfferedToolNames.of(model)).contains("conversation_search");
	}

	@Test
	void theToolSaysWhenNothingIsFoundAndWhenThePagesAreOver() {
		assertThat(search("{\"query\":\"pizza\"}", Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, "s1")))
			.isEqualTo("No results found.");
		assertThat(search("{\"query\":\"invoice\",\"page\":5}", Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, "s1")))
			.isEqualTo("No more results.");
		assertThat(search("{\"query\":\"invoice\",\"match\":\"some\"}",
				Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, "s1")))
			.isNotBlank()
			.doesNotStartWith("{");
	}

	@Test
	void aLongMessageComesBackAsAnExcerptAroundTheKeyword() {
		this.sessions.appendMessage("s1", new UserMessage("x".repeat(300) + " needle " + "y".repeat(300)));
		ScriptedChatModel model = callingSearch("{\"query\":\"needle\"}");
		ChatClient client = ChatClient.builder(model)
			.defaultTools(SessionRecallTools.builder(this.sessions).maxTextLength(50).build())
			.build();

		String text = json.readTree(client.prompt("Find it")
			.toolContext(Map.of(SessionRecallTools.SESSION_ID_CONTEXT_KEY, "s1"))
			.call()
			.content()).get("results").get(0).get("text").asString();

		assertThat(text).contains("needle").startsWith("...").endsWith("...");
		assertThat(text.length()).isLessThanOrEqualTo(56);
	}

	@Test
	void withoutTheSessionIdTheToolSaysTheSearchIsUnavailableAndWithoutToolContextTheCallFails() {
		String unavailable = search("{\"query\":\"invoice\"}", Map.of("other", "value"));

		assertThat(unavailable).containsIgnoringCase("unavailable");
		ChatClient client = ChatClient.builder(callingSearch("{\"query\":\"invoice\"}"))
			.defaultTools(SessionRecallTools.builder(this.sessions).build())
			.build();
		assertThatIllegalArgumentException().isThrownBy(() -> client.prompt("Find it").call().content());
	}

	@Test
	void theTurnAdvisorCanPutTheSessionIdIntoTheToolContext() {
		ChatClient client = ChatClient.builder(callingSearch("{\"query\":\"invoice\"}"))
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).sessionIdInToolContext(true).build())
			.defaultTools(SessionRecallTools.builder(this.sessions).build())
			.build();

		String result = client.prompt("Find it").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(json.readTree(result).get("results")).hasSize(3);
	}

	private String search(String arguments, Map<String, Object> toolContext) {
		return ChatClient.builder(callingSearch(arguments))
			.defaultTools(SessionRecallTools.builder(this.sessions).build())
			.build()
			.prompt("Find it")
			.toolContext(toolContext)
			.call()
			.content();
	}

	/**
	 * A model that searches once and answers with the tool's text.
	 */
	private static ScriptedChatModel callingSearch(String arguments) {
		return new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text(unquoted(ScriptedChatModel.lastText(prompt)))
				: ScriptedChatModel.toolCall("conversation_search", arguments));
	}

	/**
	 * The tool's text: a page of JSON reaches the model as it is, a sentence as a JSON
	 * string.
	 */
	private static String unquoted(String result) {
		return result.startsWith("\"") ? json.readValue(result, String.class) : result;
	}

	private static List<String> texts(List<SessionEvent> events) {
		return events.stream().map((event) -> event.getMessage().getText()).toList();
	}

	/**
	 * The tool names of the first prompt of a model.
	 */
	static final class OfferedToolNames {

		static List<String> of(ScriptedChatModel model) {
			return ((org.springframework.ai.model.tool.ToolCallingChatOptions) model.prompts.get(0).getOptions())
				.getToolCallbacks()
				.stream()
				.map((tool) -> tool.getToolDefinition().name())
				.toList();
		}

	}

}
