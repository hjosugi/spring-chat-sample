package io.github.hjosugi.chatsample.memory;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@code EventFilter}: what a read of a session returns, how a window keeps turns whole,
 * and how two filters merge.
 */
class EventFilterTests {

	private static final EventType PROGRESS = EventType.of("ACME.PROGRESS");

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	/**
	 * {@code U1 A1 U2 A2[tool call] T2[result] A2'}, with an event of the application's
	 * own after the first turn.
	 */
	@BeforeEach
	void storeTwoTurns() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		this.sessions.appendMessage("s1", new UserMessage("U1"));
		this.sessions.appendMessage("s1", new AssistantMessage("A1"));
		this.sessions.appendEvent(SessionEvent.builder().sessionId("s1").type(PROGRESS).payload("step", 1).build());
		this.sessions.appendMessage("s1", new UserMessage("U2"));
		this.sessions.appendMessage("s1", AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "lookUp", "{}")))
			.build());
		this.sessions.appendMessage("s1", ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c1", "lookUp", "found")))
			.build());
		this.sessions.appendMessage("s1", new AssistantMessage("A2'"));
	}

	@Test
	void allIsTheLogAndActiveIsTheMessagesOfTheView() {
		assertThat(this.sessions.getEvents("s1", EventFilter.all())).hasSize(7);
		assertThat(this.sessions.getEvents("s1", EventFilter.active())).hasSize(6)
			.extracting(SessionEvent::getType)
			.containsOnly(EventType.MESSAGE);
	}

	@Test
	void aWindowIsExtendedBackToTheStartOfItsTurn() {
		assertThat(lines(EventFilter.lastN(2).toBuilder().eventTypes(Set.of(EventType.MESSAGE)).build()))
			.containsExactly("user: U2", "calls: lookUp({})", "results: found", "assistant: A2'");
		assertThat(lines(EventFilter.builder().eventTypes(Set.of(EventType.MESSAGE)).lastN(5).build()))
			.containsExactly("user: U1", "assistant: A1", "user: U2", "calls: lookUp({})", "results: found",
					"assistant: A2'");
		assertThat(lines(EventFilter.builder().eventTypes(Set.of(EventType.MESSAGE)).lastN(50).build())).hasSize(6);
	}

	@Test
	void aWindowOverAFilterThatHidesTheUserMessagesIsNotExtended() {
		assertThat(lines(EventFilter.builder().messageTypes(Set.of(MessageType.ASSISTANT, MessageType.TOOL)).lastN(2).build()))
			.containsExactly("results: found", "assistant: A2'");
	}

	@Test
	void theCriteriaSelectByMessageTypeEventTypeAndSyntheticFlag() {
		this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder()
			.lastTurns(1)
			.summarizer((request) -> List.of(new UserMessage("Shadow"), new AssistantMessage("Summary")))
			.build());

		assertThat(lines(EventFilter.builder().activeView(true).messageTypes(Set.of(MessageType.USER)).build()))
			.containsExactly("user: Shadow", "user: U2");
		assertThat(lines(EventFilter.builder().activeView(true).excludeSynthetic(true).build())).containsExactly("user: U2",
				"calls: lookUp({})", "results: found", "assistant: A2'");
		assertThat(this.sessions.getEvents("s1", EventFilter.builder().eventTypes(Set.of(PROGRESS)).build()))
			.singleElement()
			.satisfies((event) -> assertThat(event.getPayload()).containsEntry("step", 1));
		// An event of the application's own logged before the compaction is not in the view
		assertThat(this.sessions.getEvents("s1", EventFilter.builder().activeView(true).eventTypes(Set.of(PROGRESS)).build()))
			.isEmpty();
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.lastN(0));
	}

	@Test
	void mergingLetsTheOtherWindowWinAndOrsTheFlags() {
		EventFilter merged = EventFilter.lastN(50).merge(EventFilter.lastN(5));
		EventFilter view = EventFilter.lastN(50).merge(EventFilter.active());

		assertThat(merged.window()).isEqualTo(new EventFilter.Window.Last(5));
		assertThat(view.window()).isEqualTo(new EventFilter.Window.Last(50));
		assertThat(view.activeView()).isTrue();
		assertThat(view.eventTypes()).containsExactly(EventType.MESSAGE);
		assertThat(EventFilter.builder().excludeSynthetic(true).build().merge(EventFilter.all()).excludeSynthetic()).isTrue();
	}

	private List<String> lines(EventFilter filter) {
		return Transcript.of(this.sessions.getEvents("s1", filter).stream().map(SessionEvent::getMessage).toList());
	}

}
