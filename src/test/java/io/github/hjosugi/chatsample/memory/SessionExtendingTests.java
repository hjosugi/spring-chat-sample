package io.github.hjosugi.chatsample.memory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springframework.ai.model.session.autoconfigure.SessionServiceDecorator;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionContexts;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The extension points of a session: event types of your own, state as the newest event,
 * advisors around the session advisors, and a decorated service.
 */
class SessionExtendingTests {

	private static final EventType AUDIT = EventType.of("ACME.AUDIT");

	private static final EventType CLAIM = EventType.of("ACME.CLAIM");

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void anEventOfYourOwnTypeIsReadFromTheLogAndNeverReachesTheModel() {
		ScriptedChatModel model = ScriptedChatModel.answering("Ok");
		ChatClient client = ChatClient.builder(model).defaultAdvisors(SessionAdvisors.builder(this.sessions).build()).build();
		client.prompt("Hello").advisors(SessionAdvisors.session("s1")).call().content();
		this.sessions.appendEvent(SessionEvent.builder().sessionId("s1").type(AUDIT).payload("action", "exported").build());

		client.prompt("Again").advisors(SessionAdvisors.session("s1")).call().content();
		this.sessions.compact("s1", (request) -> true, WindowCompactionStrategy.builder().lastTurns(1).build());

		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Hello", "assistant: Ok", "user: Again");
		assertThat(this.sessions.getEvents("s1", EventFilter.builder().eventTypes(Set.of(AUDIT)).build())).singleElement()
			.satisfies((event) -> assertThat(event.getPayload()).containsEntry("action", "exported"));
		assertThat(this.sessions.getEvents("s1", EventFilter.builder().activeView(true).eventTypes(Set.of(AUDIT)).build()))
			.isEmpty();
	}

	@Test
	void stateIsTheNewestEventOfATypeWrittenWithAVersionCheck() {
		this.sessions.create(CreateSessionRequest.builder().id("s1").build());

		assertThat(claim("s1", "alice")).isTrue();
		long version = this.sessions.getEventVersion("s1");
		// A second writer read the same version, but alice appended first
		boolean bob = this.sessions.appendEvents("s1",
				List.of(SessionEvent.builder().sessionId("s1").type(CLAIM).payload("owner", "bob").build()), version - 1);

		assertThat(bob).isFalse();
		assertThat(newestClaim("s1")).isEqualTo("alice");
		this.sessions.appendMessage("s1", new UserMessage("Any append moves the version"));
		assertThat(this.sessions.getEventVersion("s1")).isEqualTo(version + 1);
		assertThatIllegalStateException().isThrownBy(() -> claim("s1", "carol"));
	}

	@Test
	void anAdvisorOutsideTheTurnNamesTheSessionAsTheSessionAdvisorsDo() {
		List<String> seen = new CopyOnWriteArrayList<>();
		CallAdvisor outer = new CallAdvisor() {

			@Override
			public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
				String sessionId = SessionContexts.sessionId(request.context());
				SessionExtendingTests.this.sessions.findOrCreate(SessionContexts.createRequest(sessionId, request.context()));
				seen.add(sessionId + " before: " + SessionExtendingTests.this.sessions.getMessages(sessionId).size());
				ChatClientResponse response = chain.nextCall(request);
				seen.add(sessionId + " after: " + SessionExtendingTests.this.sessions.getMessages(sessionId).size());
				return response;
			}

			@Override
			public String getName() {
				return "outer";
			}

			@Override
			public int getOrder() {
				return SessionTurnAdvisor.DEFAULT_ORDER - 1;
			}

		};
		ChatClient client = ChatClient.builder(ScriptedChatModel.answering("Ok"))
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.defaultAdvisors(outer)
			.build();

		client.prompt("Hello").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(seen).containsExactly("s1 before: 0", "s1 after: 2");
		assertThatIllegalStateException().isThrownBy(() -> client.prompt("Hello").call().content());
	}

	@Test
	void theDecoratorsWrapTheAutoConfiguredServiceTheLastOneOutermost() {
		List<String> calls = new CopyOnWriteArrayList<>();
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SessionServiceAutoConfiguration.class))
			.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build())
			.withUserConfiguration(Decorators.class)
			.withBean(Calls.class, () -> new Calls(calls))
			.run((context) -> {
				context.getBean(SessionService.class).findById("s1");

				assertThat(calls).containsExactly("second", "first");
			});
	}

	@Test
	void aSessionServiceOfYourOwnIsNotDecorated() {
		List<String> calls = new CopyOnWriteArrayList<>();
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SessionServiceAutoConfiguration.class))
			.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build())
			.withBean(SessionService.class, () -> this.sessions)
			.withUserConfiguration(Decorators.class)
			.withBean(Calls.class, () -> new Calls(calls))
			.run((context) -> {
				context.getBean(SessionService.class).findById("s1");

				assertThat(calls).isEmpty();
			});
	}

	private boolean claim(String sessionId, String me) {
		for (int attempt = 0; attempt < 3; attempt++) {
			long version = this.sessions.getEventVersion(sessionId);
			String current = newestClaim(sessionId);
			if (current != null && !current.equals(me)) {
				throw new IllegalStateException("The session is taken");
			}
			SessionEvent claim = SessionEvent.builder().sessionId(sessionId).type(CLAIM).payload("owner", me).build();
			if (this.sessions.appendEvents(sessionId, List.of(claim), version)) {
				return true;
			}
		}
		throw new IllegalStateException("The session kept changing");
	}

	private String newestClaim(String sessionId) {
		List<SessionEvent> newest = this.sessions.getEvents(sessionId,
				EventFilter.builder().eventTypes(Set.of(CLAIM)).lastN(1).build());
		return newest.isEmpty() ? null : (String) newest.get(0).getPayload().get("owner");
	}

	record Calls(List<String> list) {
	}

	static class Decorators {

		@Bean
		@Order(1)
		SessionServiceDecorator first(Calls calls) {
			return (service) -> recording(service, "first", calls.list());
		}

		@Bean
		@Order(2)
		SessionServiceDecorator second(Calls calls) {
			return (service) -> recording(service, "second", calls.list());
		}

		private static SessionService recording(SessionService service, String name, List<String> calls) {
			return (SessionService) Proxy.newProxyInstance(SessionExtendingTests.class.getClassLoader(),
					new Class<?>[] { SessionService.class }, (proxy, method, args) -> {
						calls.add(name);
						return method.invoke(service, args);
					});
		}

	}

}
