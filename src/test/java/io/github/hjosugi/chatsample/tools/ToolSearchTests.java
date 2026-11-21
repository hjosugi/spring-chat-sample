package io.github.hjosugi.chatsample.tools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.github.hjosugi.chatsample.memory.Transcript;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.tool.toolsearch.eviction.AlwaysEvictStrategy;
import org.springframework.ai.tool.toolsearch.eviction.LruEvictionStrategy;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The tool search tool and the guide to dynamic tool discovery: the model gets a search
 * tool first and the tools it finds next, per session; the indexes, their eviction, the
 * auto-configuration, and confirmation and memory with it.
 */
class ToolSearchTests {

	private final ApplicationContextRunner boot = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
				ToolSearchAdvisorAutoConfiguration.class, ChatClientAutoConfiguration.class));

	private final MyTools tools = new MyTools();

	@Test
	void theModelGetsTheSearchToolFirstAndTheToolsItFoundNext() {
		ScriptedChatModel model = searchingFor("weather");

		String answer = ask(client(model, advisor(new RegexToolIndex()).build()), "s1");

		assertThat(answer).isEqualTo("\"Rain in Amsterdam\"");
		assertThat(OfferedTools.names(model.prompts.get(0))).containsExactly("toolSearchTool");
		assertThat(OfferedTools.names(model.prompts.get(1))).containsExactlyInAnyOrder("toolSearchTool", "weather");
		assertThat(Transcript.of(model.prompts.get(1)).get(3)).isEqualTo("results: [\"weather\"]");
		// The system message tells the model how to search
		assertThat(model.prompts.get(0).getSystemMessage().getText()).contains("toolSearchTool");
		assertThat(this.tools.ran).containsExactly("weather Amsterdam");
	}

	@Test
	void luceneFindsToolsByKeywordAboveItsMinimumScore() {
		LuceneToolIndex index = new LuceneToolIndex();
		index.indexTools("s1", references());

		ToolSearchResponse found = index.search(new ToolSearchRequest("s1", "clothing shops", 5, null));

		assertThat(found.toolReferences()).extracting(ToolReference::toolName).first().isEqualTo("clothingShops");
		ScriptedChatModel model = searchingFor("weather");
		assertThat(ask(client(model, advisor(new LuceneToolIndex()).build()), "s1")).isEqualTo("\"Rain in Amsterdam\"");
	}

	@Test
	void theRegexIndexMatchesTheWordsOfTheQueryInNamesAndDescriptions() {
		RegexToolIndex index = new RegexToolIndex();
		index.indexTools("s1", references());

		assertThat(index.search(new ToolSearchRequest("s1", "what is the current time", 5, null)).toolReferences())
			.extracting(ToolReference::toolName)
			.first()
			.isEqualTo("currentTime");
		assertThat(index.search(new ToolSearchRequest("s2", "time", 5, null)).toolReferences()).isEmpty();
	}

	@Test
	void aCallWithoutASessionIdIsRefused() {
		ChatClient client = client(searchingFor("weather"), advisor(new RegexToolIndex()).build());

		assertThatIllegalArgumentException().isThrownBy(() -> client.prompt("What to wear?").call().content())
			.withMessageContaining("chat_memory_conversation_id");
	}

	@Test
	void theSessionIdCanComeFromAKeyOfYourOwnOrFromSessionAdvisors() {
		ScriptedChatModel model = searchingFor("weather");
		ChatClient client = client(model, advisor(new RegexToolIndex()).sessionIdKeyName("tenantId").build());

		String answer = client.prompt("What to wear?").advisors((a) -> a.param("tenantId", "acme")).call().content();

		assertThat(answer).isEqualTo("\"Rain in Amsterdam\"");
		assertThat(SessionAdvisors.session("s1")).isNotNull();
		ScriptedChatModel second = searchingFor("weather");
		assertThat(client(second, advisor(new RegexToolIndex()).build()).prompt("What to wear?")
			.advisors(SessionAdvisors.session("s1"))
			.call()
			.content()).isEqualTo("\"Rain in Amsterdam\"");
	}

	@Test
	void theToolsAreIndexedOncePerSessionAndAgainWhenTheyChange() {
		CountingIndex index = new CountingIndex(new RegexToolIndex());
		ChatClient client = client(searchingFor("weather"), advisor(index).build());

		ask(client, "s1");
		ask(client, "s1");
		ask(client, "s2");
		client.prompt("What to wear?")
			.tools(new MoreTools())
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "s1"))
			.call()
			.content();

		assertThat(index.indexed).containsExactly("s1", "s2", "s1");
	}

	@Test
	void maxResultsCapsWhatOneSearchFinds() {
		ScriptedChatModel model = searchingFor("location");

		ask(client(model, advisor(new RegexToolIndex()).maxResults(1).build()), "s1");

		assertThat(Transcript.of(model.prompts.get(1)).get(3)).matches("results: \\[\"[a-zA-Z]+\"\\]");
	}

	@Test
	void withoutAccumulationOnlyTheNewestSearchTurnCounts() {
		assertThat(OfferedTools.names(searchTwice(true).prompts.get(2))).containsExactlyInAnyOrder("toolSearchTool",
				"weather", "currentTime");
		assertThat(OfferedTools.names(searchTwice(false).prompts.get(2))).containsExactlyInAnyOrder("toolSearchTool",
				"currentTime");
	}

	@Test
	void evictionFreesTheIndexOfASession() {
		CountingIndex always = new CountingIndex(new RegexToolIndex());
		ChatClient everyTime = client(searchingFor("weather"),
				advisor(always).evictionStrategy(AlwaysEvictStrategy.INSTANCE).build());
		ask(everyTime, "s1");
		ask(everyTime, "s1");
		assertThat(always.indexed).containsExactly("s1", "s1");

		CountingIndex lru = new CountingIndex(new RegexToolIndex());
		ChatClient oneSession = client(searchingFor("weather"),
				advisor(lru).evictionStrategy(new LruEvictionStrategy(1)).build());
		ask(oneSession, "s1");
		ask(oneSession, "s2");
		ask(oneSession, "s1");
		assertThat(lru.cleared).contains("s1");
		assertThat(lru.indexed).containsExactly("s1", "s2", "s1");

		CountingIndex explicit = new CountingIndex(new RegexToolIndex());
		ToolSearchToolCallingAdvisor advisor = advisor(explicit).build();
		ChatClient client = client(searchingFor("weather"), advisor);
		ask(client, "s1");
		advisor.evictSession("s1");
		ask(client, "s1");
		assertThat(explicit.indexed).containsExactly("s1", "s1");
	}

	@Test
	void theAutoConfigurationReplacesTheLoopOnlyWhenEnabled() {
		this.boot.withBean(ChatModel.class, () -> ScriptedChatModel.answering("ok")).run((context) -> {
			assertThat(context).doesNotHaveBean(ToolIndex.class);
			ScriptedChatModel model = (ScriptedChatModel) context.getBean(ChatModel.class);
			context.getBean(ChatClient.Builder.class).build().prompt("Hi").tools(this.tools).call().content();
			assertThat(OfferedTools.names(model.prompts.get(0))).contains("weather");
		});
		this.boot.withBean(ChatModel.class, () -> searchingFor("weather"))
			.withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true")
			.run((context) -> {
				assertThat(context.getBean(ToolIndex.class)).isInstanceOf(RegexToolIndex.class);
				assertThat(context.getBean(ToolCallingAdvisor.Builder.class).build())
					.isInstanceOf(ToolSearchToolCallingAdvisor.class);
				ScriptedChatModel model = (ScriptedChatModel) context.getBean(ChatModel.class);
				String answer = context.getBean(ChatClient.Builder.class)
					.defaultTools(this.tools)
					.build()
					.prompt("What to wear?")
					.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "s1"))
					.call()
					.content();
				assertThat(answer).isEqualTo("\"Rain in Amsterdam\"");
				assertThat(OfferedTools.names(model.prompts.get(0))).containsExactly("toolSearchTool");
			});
	}

	/**
	 * {@code tool-index-type=vector} without a {@code VectorStore} bean fails with the
	 * auto-configuration's message. That needs {@code spring-ai-vector-store} on the
	 * classpath, which the starter does not bring ({@code spring-ai-test} brings it
	 * here): without it the start fails with a {@code ClassNotFoundException} for
	 * {@code VectorStore} instead, since the configuration is conditional on
	 * {@code VectorToolIndex}, not on {@code VectorStore}.
	 */
	@Test
	void theAutoConfigurationPicksTheIndexByPropertyUnlessTheApplicationHasOne() {
		this.boot
			.withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true",
					"spring.ai.chat.client.tool-search-advisor.tool-index-type=lucene")
			.run((context) -> assertThat(context.getBean(ToolIndex.class)).isInstanceOf(LuceneToolIndex.class));
		this.boot
			.withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true",
					"spring.ai.chat.client.tool-search-advisor.tool-index-type=lucene")
			.withBean(ToolIndex.class, RegexToolIndex::new)
			.run((context) -> assertThat(context.getBean(ToolIndex.class)).isInstanceOf(RegexToolIndex.class));
		this.boot
			.withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true",
					"spring.ai.chat.client.tool-search-advisor.tool-index-type=vector")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("requires a VectorStore bean"));
	}

	@Test
	void theAutoConfigurationTakesTheSessionKeyAndTheLimitFromProperties() {
		this.boot.withBean(ChatModel.class, () -> searchingFor("location"))
			.withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true",
					"spring.ai.chat.client.tool-search-advisor.session-id-key-name=tenantId",
					"spring.ai.chat.client.tool-search-advisor.max-results=1")
			.run((context) -> {
				ScriptedChatModel model = (ScriptedChatModel) context.getBean(ChatModel.class);
				context.getBean(ChatClient.Builder.class)
					.defaultTools(this.tools)
					.build()
					.prompt("What to wear?")
					.advisors((a) -> a.param("tenantId", "acme"))
					.call()
					.content();
				assertThat(Transcript.of(model.prompts.get(1)).get(3)).matches("results: \\[\"[a-zA-Z]+\"\\]");
			});
	}

	@Test
	void aFoundToolThatNeedsConfirmationPausesAndResumesWithTheWholeToolSet() {
		ScriptedChatModel model = searchingFor("book");
		ChatClient client = client(model, advisor(new RegexToolIndex()).build());

		ChatResponse paused = client.prompt("Book a table")
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "s1"))
			.call()
			.chatResponse();

		assertThat(ToolCallDecisions.awaitingDecision(paused)).hasSize(1);
		assertThat(this.tools.ran).isEmpty();
		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(
					new Prompt(List.of(),
							ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(this.tools)).build()),
					paused, ToolCallDecisions.of(ToolCallDecision.allow(ToolCallDecisions.awaitingDecision(paused).get(0))));
		assertThat(this.tools.ran).containsExactly("bookTable Amsterdam");
		assertThat(Transcript.line(result.conversationHistory().get(1))).isEqualTo("results: \"Table booked\"");
	}

	@Test
	void withSessionMemoryAndTheAdvisorRegisteredByHandTheFoundToolsStayForTheTurn() {
		ScriptedChatModel model = searchThenCallWeather();
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(sessions()).build())
			.defaultAdvisors(advisor(new RegexToolIndex()).build())
			.defaultTools(this.tools)
			.build();

		client.prompt("What to wear?").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(OfferedTools.names(model.prompts.get(1))).containsExactlyInAnyOrder("toolSearchTool", "weather");
		assertThat(OfferedTools.names(model.prompts.get(2))).containsExactlyInAnyOrder("toolSearchTool", "weather");
	}

	/**
	 * Registered as the loop of the {@code ChatClient}, as the auto-configuration does it,
	 * the advisor runs with its history off next to a memory advisor inside the loop, and
	 * forwards only the newest message of each round. It looks for the tools found so far
	 * in that message, not in the session: once another tool ran, the found tools are no
	 * longer offered, and the model has to search again.
	 */
	@Test
	void withSessionMemoryAndTheAdvisorAsTheAutoRegisteredLoopTheFoundToolsAreForgottenOnceAnotherToolRan() {
		ScriptedChatModel model = searchThenCallWeather();
		ChatClient client = ChatClient
			.builder(model, io.micrometer.observation.ObservationRegistry.NOOP, null, null, advisor(new RegexToolIndex()))
			.defaultAdvisors(SessionAdvisors.builder(sessions()).build())
			.defaultTools(this.tools)
			.build();

		client.prompt("What to wear?").advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(OfferedTools.names(model.prompts.get(1))).containsExactlyInAnyOrder("toolSearchTool", "weather");
		assertThat(OfferedTools.names(model.prompts.get(2))).containsExactly("toolSearchTool");
		// The model still saw the whole turn, from the session
		assertThat(Transcript.of(model.prompts.get(2))).hasSize(6);
	}

	/**
	 * The confirmation page says that with the memory inside the loop "the disclosed tools
	 * are offered again after the resumption". The resumed request carries only the tool
	 * results, and the advisor looks for found tools in the request, so it offers only the
	 * search tool.
	 */
	@Test
	void aResumedRequestOffersOnlyTheSearchToolEvenWithSessionMemory() {
		SessionService sessions = sessions();
		ScriptedChatModel model = searchingFor("book");
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(SessionAdvisors.builder(sessions).build())
			.defaultAdvisors(advisor(new RegexToolIndex()).build())
			.defaultTools(this.tools)
			.build();
		ChatResponse paused = client.prompt("Book a table").advisors(SessionAdvisors.session("s1")).call().chatResponse();
		List<Message> history = ToolCallingManager.builder()
			.build()
			.executeToolCalls(
					new Prompt(List.of(),
							ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(this.tools)).build()),
					paused, ToolCallDecisions.of(ToolCallDecision.allow(ToolCallDecisions.awaitingDecision(paused).get(0))))
			.conversationHistory();

		client.prompt().messages(history.get(history.size() - 1)).advisors(SessionAdvisors.session("s1")).call().content();

		assertThat(OfferedTools.names(model.lastPrompt())).containsExactly("toolSearchTool");
		// The session gave the model the whole turn, the search included
		assertThat(Transcript.of(model.lastPrompt())).contains("results: [\"bookTable\"]");
	}

	private static SessionService sessions() {
		return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
	}

	private static ScriptedChatModel searchThenCallWeather() {
		return new ScriptedChatModel(script((last) -> switch (last) {
			case "user" -> ScriptedChatModel.toolCall("toolSearchTool", "{\"query\":\"weather\"}");
			case "toolSearchTool" -> ScriptedChatModel.toolCall("weather", "{\"location\":\"Amsterdam\",\"atTime\":\"now\"}");
			default -> ScriptedChatModel.text("Take an umbrella.");
		}));
	}

	/**
	 * A turn that searches for the weather, then for the time, then answers; the model
	 * of it.
	 */
	private ScriptedChatModel searchTwice(boolean accumulate) {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			long searches = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
			return switch ((int) searches) {
				case 0 -> ScriptedChatModel.toolCall("toolSearchTool", "{\"query\":\"weather\"}");
				case 1 -> ScriptedChatModel.toolCall("toolSearchTool", "{\"query\":\"current time\"}");
				default -> ScriptedChatModel.text("Done.");
			};
		});
		ask(client(model, advisor(new RegexToolIndex()).referenceToolNameAccumulation(accumulate).maxResults(1).build()),
				"s1");
		return model;
	}

	private ToolSearchToolCallingAdvisor.Builder<?> advisor(ToolIndex index) {
		return ToolSearchToolCallingAdvisor.builder().toolIndex(index);
	}

	private ChatClient client(ChatModel model, ToolSearchToolCallingAdvisor advisor) {
		return ChatClient.builder(model).defaultAdvisors(advisor).defaultTools(this.tools).build();
	}

	private static String ask(ChatClient client, String session) {
		return client.prompt("What to wear?")
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, session))
			.call()
			.content();
	}

	/**
	 * A model that searches for the query, calls the first tool it found, and answers
	 * with that tool's result.
	 */
	private static ScriptedChatModel searchingFor(String query) {
		return new ScriptedChatModel((prompt) -> {
			Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
			if (!(last instanceof ToolResponseMessage results)) {
				return ScriptedChatModel.toolCall("toolSearchTool", "{\"query\":\"" + query + "\"}");
			}
			ToolResponseMessage.ToolResponse result = results.getResponses().get(0);
			if (!result.name().equals("toolSearchTool")) {
				return ScriptedChatModel.text(result.responseData());
			}
			String found = result.responseData().replaceAll("^\\[\"([^\"]+)\".*$", "$1");
			return ScriptedChatModel.toolCall(found, "{\"location\":\"Amsterdam\",\"atTime\":\"now\",\"openAt\":\"now\"}");
		});
	}

	/**
	 * A script by the newest message: {@code user}, or the name of the tool that answered.
	 */
	private static Function<Prompt, AssistantMessage> script(Function<String, AssistantMessage> byLast) {
		return (prompt) -> {
			Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
			return byLast.apply((last instanceof ToolResponseMessage results) ? results.getResponses().get(0).name() : "user");
		};
	}

	private List<ToolReference> references() {
		return List.of(ToolCallbacks.from(this.tools))
			.stream()
			.map((tool) -> ToolReference.builder()
				.toolName(tool.getToolDefinition().name())
				.summary(tool.getToolDefinition().description())
				.build())
			.toList();
	}

	static class MyTools {

		final List<String> ran = new CopyOnWriteArrayList<>();

		@Tool(description = "Get the weather for a given location at a given time")
		String weather(String location, String atTime) {
			this.ran.add("weather " + location);
			return "Rain in " + location;
		}

		@Tool(description = "Get the names of clothing shops in a location that are open at a given time")
		List<String> clothingShops(String location, String openAt) {
			this.ran.add("clothingShops " + location);
			return List.of("Raincoats & Co");
		}

		@Tool(description = "Get the current date and time in a location")
		String currentTime(String location) {
			this.ran.add("currentTime " + location);
			return "noon";
		}

		@Tool(description = "Book a table in a restaurant of a location", requiresConfirmation = true)
		String bookTable(String location) {
			this.ran.add("bookTable " + location);
			return "Table booked";
		}

	}

	static class MoreTools {

		@Tool(description = "Get the opening hours of a museum")
		String museumHours(String museum) {
			return "9 to 5";
		}

	}

	/**
	 * Notes which sessions an index indexed and cleared.
	 */
	static final class CountingIndex implements ToolIndex {

		private final ToolIndex delegate;

		final List<String> indexed = new CopyOnWriteArrayList<>();

		final List<String> cleared = new CopyOnWriteArrayList<>();

		CountingIndex(ToolIndex delegate) {
			this.delegate = delegate;
		}

		@Override
		public void indexTool(String sessionId, ToolReference toolReference) {
			this.delegate.indexTool(sessionId, toolReference);
		}

		@Override
		public void indexTools(String sessionId, List<ToolReference> toolReferences) {
			this.indexed.add(sessionId);
			this.delegate.indexTools(sessionId, toolReferences);
		}

		@Override
		public ToolSearchResponse search(ToolSearchRequest request) {
			return this.delegate.search(request);
		}

		@Override
		public void clearIndex(String sessionId) {
			this.cleared.add(sessionId);
			this.delegate.clearIndex(sessionId);
		}

	}

}
