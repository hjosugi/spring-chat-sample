package io.github.hjosugi.chatsample.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAlreadyExistsException;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

/**
 * The session concepts and the Session API overview: sessions, their events, turns, the
 * active view, and what {@code SessionService} does with them.
 */
class SessionConceptsTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void aSessionHasAnIdAnExpiryAndMetadataButNoOwnerField() {
		Session named = this.sessions.create(CreateSessionRequest.builder()
			.id("session-abc")
			.timeToLive(Duration.ofDays(7))
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.metadata(SessionAttributes.AGENT_NAME, "support")
			.build());
		Session unnamed = this.sessions.create(CreateSessionRequest.builder().build());

		assertThat(named.id()).isEqualTo("session-abc");
		assertThat(named.expiresAt()).isCloseTo(named.createdAt().plus(Duration.ofDays(7)), within(Duration.ofSeconds(1)));
		assertThat(UUID.fromString(unnamed.id())).isNotNull();
		assertThat(unnamed.expiresAt()).isCloseTo(unnamed.createdAt().plus(Duration.ofDays(60)),
				within(Duration.ofSeconds(1)));
		assertThat(SessionAttributes.agentName(named)).isEqualTo("support");
		assertThat(this.sessions.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice")).extracting(Session::id)
			.containsExactly("session-abc");
		assertThat(Session.builder().id("built").build().expiresAt()).isNull();
	}

	@Test
	void creatingATakenIdFailsAndLeavesTheSessionAlone() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").metadata("v", 1).build());

		assertThatExceptionOfType(SessionAlreadyExistsException.class)
			.isThrownBy(() -> this.sessions.create(CreateSessionRequest.builder().id("s1").metadata("v", 2).build()));
		assertThat(this.sessions.findById("s1").metadata()).containsEntry("v", 1);
		assertThat(this.sessions.findOrCreate(CreateSessionRequest.builder().id("s1").metadata("v", 3).build()).metadata())
			.containsEntry("v", 1);
		assertThat(this.sessions.findById("nobody")).isNull();
	}

	@Test
	void aMessageEventWrapsAMessageAndOtherEventsCarryAPayload() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		SessionEvent message = SessionEvent.builder().sessionId("s1").message(new UserMessage("Hello")).build();
		SessionEvent progress = SessionEvent.builder()
			.sessionId("s1")
			.type(EventType.of("MY_APP.PROGRESS"))
			.payload("step", "research")
			.build();
		SessionEvent stamped = SessionEvent.builder()
			.sessionId("s1")
			.timestamp(Instant.parse("2026-06-01T12:00:00Z"))
			.message(new AssistantMessage("Response"))
			.metadata("model", "gpt-5.4-mini")
			.build();

		this.sessions.appendEvent(message);
		this.sessions.appendEvent(progress);
		this.sessions.appendEvent(stamped);

		assertThat(message.getType()).isEqualTo(EventType.MESSAGE);
		assertThat(UUID.fromString(message.getId())).isNotNull();
		assertThat(message.isTurnStart()).isTrue();
		assertThat(progress.hasMessage()).isFalse();
		assertThat(progress.getPayload()).containsEntry("step", "research");
		assertThatIllegalStateException().isThrownBy(progress::getMessage);
		assertThat(EventType.MESSAGE.canCarryMessage()).isTrue();
		assertThat(EventType.of("MY_APP.PROGRESS").canCarryMessage()).isFalse();
		// Every event is in the log, in insertion order; the messages skip the others
		assertThat(this.sessions.getEvents("s1")).extracting(SessionEvent::getType)
			.containsExactly(EventType.MESSAGE, EventType.of("MY_APP.PROGRESS"), EventType.MESSAGE);
		assertThat(Transcript.of(this.sessions.getMessages("s1"))).containsExactly("user: Hello", "assistant: Response");
		assertThat(this.sessions.getEvents("s1").get(2).getMetadata()).containsEntry("model", "gpt-5.4-mini");
	}

	@Test
	void anEventTypeIsAnUpperCaseNameOfAtMost64Characters() {
		assertThat(EventType.of("A".repeat(64)).name()).hasSize(64);
		assertThatIllegalArgumentException().isThrownBy(() -> EventType.of("A".repeat(65)));
		assertThatIllegalArgumentException().isThrownBy(() -> EventType.of("my_app.progress"));
		assertThatIllegalArgumentException().isThrownBy(() -> EventType.of("MY-APP"));
	}

	@Test
	void aRetriedAppendIsANoOpAndTheVersionMovesOnEveryNewEvent() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		SessionEvent event = SessionEvent.builder().id("e1").sessionId("s1").message(new UserMessage("Hello")).build();

		long before = this.sessions.getEventVersion("s1");
		this.sessions.appendEvent(event);
		long after = this.sessions.getEventVersion("s1");
		this.sessions.appendEvent(event);

		assertThat(after).isEqualTo(before + 1);
		assertThat(this.sessions.getEventVersion("s1")).isEqualTo(after);
		assertThat(this.sessions.getEvents("s1")).hasSize(1);
	}

	@Test
	void aVersionCheckedAppendLandsOnlyIfTheLogDidNotMove() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		long version = this.sessions.getEventVersion("s1");
		this.sessions.appendMessage("s1", new UserMessage("Another writer"));

		boolean stale = this.sessions.appendEvents("s1",
				List.of(SessionEvent.builder().sessionId("s1").message(new UserMessage("Mine")).build()), version);
		boolean fresh = this.sessions.appendEvents("s1",
				List.of(SessionEvent.builder().sessionId("s1").message(new UserMessage("Mine")).build(),
						SessionEvent.builder().sessionId("s1").message(new AssistantMessage("Ok")).build()),
				this.sessions.getEventVersion("s1"));

		assertThat(stale).isFalse();
		assertThat(fresh).isTrue();
		assertThat(Transcript.of(this.sessions.getMessages("s1"))).containsExactly("user: Another writer", "user: Mine",
				"assistant: Ok");
		// Once per successful batch
		assertThat(this.sessions.getEventVersion("s1")).isEqualTo(version + 2);
	}

	@Test
	void aCompactionChangesTheViewAndKeepsTheLog() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		for (int turn = 1; turn <= 3; turn++) {
			this.sessions.appendMessage("s1", new UserMessage("Question " + turn));
			this.sessions.appendMessage("s1", new AssistantMessage("Answer " + turn));
		}

		this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder().lastTurns(1).build());

		assertThat(Transcript.of(this.sessions.getActiveMessages("s1"))).containsExactly("user: Question 3",
				"assistant: Answer 3");
		assertThat(this.sessions.getMessages("s1")).hasSize(6);
		assertThat(this.sessions.getEvents("s1", EventFilter.all())).extracting(SessionEvent::getType)
			.contains(EventType.COMPACTION);
		assertThat(this.sessions.getEvents("s1", EventFilter.active())).extracting(SessionEvent::getType)
			.containsOnly(EventType.MESSAGE);
	}

	@Test
	void aTurnCountTriggerCountsTheUserMessagesOfTheView() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		for (int turn = 1; turn <= 3; turn++) {
			this.sessions.appendMessage("s1", new UserMessage("Question " + turn));
			this.sessions.appendMessage("s1", new AssistantMessage("Answer " + turn));
		}

		assertThat(this.sessions.compact("s1", new TurnCountTrigger(3), WindowCompactionStrategy.builder().lastTurns(1).build())
			.isUnchanged()).isTrue();
		assertThat(this.sessions.compact("s1", new TurnCountTrigger(2), WindowCompactionStrategy.builder().lastTurns(1).build())
			.isUnchanged()).isFalse();
	}

	@Test
	void aSystemMessageIsRejectedUnlessTheServiceAllowsIt() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());
		SessionService allowing = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.allowSystemMessages(true)
			.build();
		allowing.create(CreateSessionRequest.builder().id("s1").build());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.sessions.appendMessage("s1", new SystemMessage("Answer in French.")));
		allowing.appendMessage("s1", new SystemMessage("Answer in French."));
		assertThat(Transcript.of(allowing.getMessages("s1"))).containsExactly("system: Answer in French.");
	}

	@Test
	void sessionsAreDeletedOneByOneOrWhenTheyExpired() {
		this.sessions.create(CreateSessionRequest.builder().id("old").timeToLive(Duration.ofMinutes(1)).build());
		this.sessions.create(CreateSessionRequest.builder().id("new").build());
		this.sessions.create(CreateSessionRequest.builder().id("gone").build());
		this.sessions.appendMessage("gone", new UserMessage("Hello"));

		this.sessions.delete("gone");
		int removed = this.sessions.deleteExpiredSessions(Instant.now().plus(Duration.ofHours(1)));

		assertThat(removed).isEqualTo(1);
		assertThat(this.sessions.findById("old")).isNull();
		assertThat(this.sessions.findById("gone")).isNull();
		assertThat(this.sessions.findById("new")).isNotNull();
		assertThat(Map.of("events", this.sessions.getEvents("gone"))).containsEntry("events", List.of());
	}

}
