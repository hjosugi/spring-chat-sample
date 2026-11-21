package io.github.hjosugi.chatsample.memory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.ChatModelSummarizer;
import org.springframework.ai.session.compaction.CompactionBoundary;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionWindow;
import org.springframework.ai.session.compaction.CompositeCompactionTrigger;
import org.springframework.ai.session.compaction.EventText;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compaction: triggers, the window strategy with the tricky cases the page lists, the
 * summarizer, and how a pass is recorded in the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class CompactionTests {

	/**
	 * A token per word, so that the page's token counts can be written down.
	 */
	static final TokenCountEstimator WORDS = new TokenCountEstimator() {

		@Override
		public int estimate(@Nullable String text) {
			return (text == null || text.isBlank()) ? 0 : text.strip().split("\\s+").length;
		}

		@Override
		public int estimate(MediaContent content) {
			return estimate(content.getText());
		}

		@Override
		public int estimate(Iterable<MediaContent> contents) {
			int sum = 0;
			for (MediaContent content : contents) {
				sum += estimate(content);
			}
			return sum;
		}

	};

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.allowSystemMessages(true)
		.build();

	static Stream<Arguments> theTrickyCases() {
		return Stream.of(Arguments.of("U1 A1 U2 A2a A2b U3 A3", 3, "U3 A3", "U1 A1 U2 A2a A2b"),
				Arguments.of("U1 A1 U2 A2 A3 A4", 2, "U2 A2 A3 A4", "U1 A1"),
				Arguments.of("U1 A1 A2 A3", 2, "U1 A1 A2 A3", ""),
				Arguments.of("S:v1 U1 A1 S:v2 U2 A2", 20, "S:v1 U1 A1 S:v2 U2 A2", ""),
				Arguments.of("U1 S A1 U2 A2 U3 A3", 2, "S U3 A3", "U1 A1 U2 A2"));
	}

	@ParameterizedTest(name = "{0} with lastEvents({1})")
	@MethodSource
	void theTrickyCases(String before, int lastEvents, String after, String covered) {
		store(before);

		CompactionResult result = this.sessions.compact("s1", (request) -> true,
				WindowCompactionStrategy.builder().lastEvents(lastEvents).build());

		assertThat(short_(this.sessions.getActiveMessages("s1"))).isEqualTo(after);
		assertThat(short_(result.coveredEvents().stream().map(SessionEvent::getMessage).toList())).isEqualTo(covered);
		assertThat(result.isUnchanged()).isEqualTo(covered.isEmpty());
	}

	@Test
	void aTokenBudgetIsWhatIsLeftAfterTheSystemPrompt() {
		// The system prompt is 11 tokens, a turn 21: of 45, 34 are left, one turn fits
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		this.sessions.appendMessage("s1", new SystemMessage(words("s", 10)));
		for (int turn = 1; turn <= 2; turn++) {
			this.sessions.appendMessage("s1", new UserMessage(words("u" + turn, 9)));
			this.sessions.appendMessage("s1", new AssistantMessage(words("a" + turn, 10)));
		}

		this.sessions.compact("s1", (request) -> true,
				WindowCompactionStrategy.builder().tokenBudget(45).tokenCountEstimator(WORDS).build());

		assertThat(this.sessions.getActiveMessages("s1")).extracting(Message::getText)
			.containsExactly(words("s", 10), words("u2", 9), words("a2", 10));
	}

	@Test
	void lastTurnsCountsTurnsNotEventsAndKeepsThePreamble() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		this.sessions.appendMessage("s1", new AssistantMessage("Welcome"));
		store("U1 A1 U2 A2 U3 A3");
		this.sessions.appendMessage("s1", AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "lookUp", "{}")))
			.build());
		this.sessions.appendMessage("s1", ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c1", "lookUp", "found")))
			.build());
		this.sessions.appendMessage("s1", new AssistantMessage("A3b"));

		this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder().lastTurns(1).build());

		assertThat(Transcript.of(this.sessions.getActiveMessages("s1"))).containsExactly("assistant: Welcome",
				"user: U3", "assistant: A3", "calls: lookUp({})", "results: found", "assistant: A3b");
	}

	@Test
	void theTriggersCountTurnsOrTokensOrAnyOfThem() {
		store("U1 A1 U2 A2");
		WindowCompactionStrategy strategy = WindowCompactionStrategy.builder().lastTurns(1).build();
		TokenCountTrigger fourTokens = TokenCountTrigger.builder().threshold(4).tokenCountEstimator(WORDS).build();
		TokenCountTrigger manyTokens = TokenCountTrigger.builder().threshold(100).tokenCountEstimator(WORDS).build();

		assertThat(this.sessions.compact("s1", new TurnCountTrigger(2), strategy).isUnchanged()).isTrue();
		assertThat(this.sessions.compact("s1", manyTokens, strategy).isUnchanged()).isTrue();
		assertThat(this.sessions.compact("s1", CompositeCompactionTrigger.anyOf(new TurnCountTrigger(2), fourTokens), strategy)
			.isUnchanged()).isFalse();
		assertThat(WindowCompactionStrategy.DEFAULT_WINDOW).isEqualTo(CompactionWindow.lastEvents(20));
	}

	@Test
	void aSummarizerStandsInForTheCoveredTurnsAndBuildsOnThePriorSummary() {
		List<Prompt> asked = new CopyOnWriteArrayList<>();
		ScriptedChatModel summarizing = new ScriptedChatModel((prompt) -> {
			asked.add(prompt);
			return ScriptedChatModel.text("Summary " + asked.size());
		});
		WindowCompactionStrategy strategy = WindowCompactionStrategy.builder()
			.lastEvents(2)
			.summarizer(ChatModelSummarizer.builder(summarizing).overlapSize(1).build())
			.build();
		store("S U1 A1 U2 A2");
		this.sessions.compact("s1", (request) -> true, strategy);
		store("U3 A3");

		CompactionResult result = this.sessions.compact("s1", (request) -> true, strategy);

		assertThat(Transcript.of(this.sessions.getActiveMessages("s1"))).containsExactly("system: S",
				"user: " + ChatModelSummarizer.DEFAULT_SHADOW_PROMPT, "assistant: Summary 2", "user: U3", "assistant: A3");
		assertThat(Transcript.of(result.summary())).containsExactly("user: " + ChatModelSummarizer.DEFAULT_SHADOW_PROMPT,
				"assistant: Summary 2");
		String secondPrompt = asked.get(1).getContents();
		assertThat(secondPrompt).contains("Summary 1", "U2", "A2", "U3").doesNotContain("System: S");
		assertThat(this.sessions.getEvents("s1", EventFilter.active())).filteredOn(SessionEvent::isSynthetic).hasSize(2);
	}

	@Test
	void aSummarizerThatDeclinesOrFailsLeavesTheViewAlone(CapturedOutput output) {
		store("U1 A1 U2 A2");
		List<String> failures = new CopyOnWriteArrayList<>();
		ScriptedChatModel failing = new ScriptedChatModel((prompt) -> {
			throw new IllegalStateException("model down");
		});

		CompactionResult declined = this.sessions.compact("s1", (request) -> true,
				WindowCompactionStrategy.builder().lastTurns(1).summarizer((request) -> List.of()).build());
		CompactionResult failed = this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder()
			.lastTurns(1)
			.summarizer(ChatModelSummarizer.builder(failing).onSummarizationFailure((request) -> failures.add("failed")).build())
			.build());

		assertThat(declined.isUnchanged()).isTrue();
		assertThat(failed.isUnchanged()).isTrue();
		assertThat(failures).containsExactly("failed");
		assertThat(output).contains("WARN");
		assertThat(this.sessions.getEvents("s1", EventFilter.all())).extracting(SessionEvent::getType)
			.containsOnly(EventType.MESSAGE);
	}

	@Test
	void aPassAppendsABoundaryThatNamesTheViewAndTheLogKeepsEverything() {
		store("U1 A1 U2 A2 U3 A3");

		this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder()
			.lastEvents(2)
			.summarizer((request) -> List.of(new UserMessage("Shadow"), new AssistantMessage("Summary")))
			.build());

		List<SessionEvent> log = this.sessions.getEvents("s1", EventFilter.all());
		assertThat(log).extracting((event) -> event.getType().name())
			.containsExactly("MESSAGE", "MESSAGE", "MESSAGE", "MESSAGE", "MESSAGE", "MESSAGE", "COMPACTION", "MESSAGE",
					"MESSAGE");
		CompactionBoundary boundary = CompactionBoundary.from(log.get(6));
		assertThat(boundary.summaryEventIds()).containsExactly(log.get(7).getId(), log.get(8).getId());
		assertThat(boundary.preservedEventIds()).containsExactly(log.get(4).getId(), log.get(5).getId());
		assertThat(log.get(6).getPayload()).containsEntry(CompactionBoundary.PAYLOAD_COVERED_COUNT, 4);
		assertThat(CompactionBoundary.project(log)).extracting(SessionEvent::getId)
			.containsExactlyElementsOf(this.sessions.getEvents("s1", EventFilter.active()).stream().map(SessionEvent::getId).toList());
		assertThat(short_(this.sessions.getActiveMessages("s1"))).isEqualTo("Shadow Summary U3 A3");
	}

	@Test
	void eventTextIsTheOneLineRenderingThatTokensAreCountedOn() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		this.sessions.appendMessage("s1", new UserMessage("Hi"));
		this.sessions.appendMessage("s1", AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "lookUp", "{\"id\":1}")))
			.build());
		this.sessions.appendMessage("s1", ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c1", "lookUp", "found")))
			.build());

		assertThat(this.sessions.getEvents("s1")).extracting(EventText::of)
			.containsExactly("User: Hi", "Assistant [tool calls: lookUp({\"id\":1})]", "Tool [responses: lookUp -> found]");
	}

	/**
	 * Stores the messages of a line such as {@code U1 A1 S:v2}: {@code U} a user message,
	 * {@code A} an assistant message, {@code S} a system message, the text being the
	 * token, or what follows {@code S:}.
	 */
	private void store(String line) {
		this.sessions.findOrCreate(CreateSessionRequest.builder().id("s1").build());
		for (String token : line.split(" ")) {
			Message message = switch (token.charAt(0)) {
				case 'U' -> new UserMessage(token);
				case 'A' -> new AssistantMessage(token);
				case 'S' -> new SystemMessage(token.startsWith("S:") ? token.substring(2) : token);
				default -> throw new IllegalArgumentException(token);
			};
			this.sessions.appendMessage("s1", message);
		}
	}

	/**
	 * Messages as such a line again.
	 */
	private static String short_(List<Message> messages) {
		return String.join(" ", messages.stream()
			.map((message) -> (message instanceof SystemMessage && !"S".equals(message.getText()))
					? "S:" + message.getText() : message.getText())
			.toList());
	}

	private static String words(String word, int count) {
		String[] words = new String[count];
		Arrays.fill(words, word);
		return String.join(" ", words);
	}

}
