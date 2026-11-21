package io.github.hjosugi.chatsample.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.memory.Transcript;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.model.tool.ToolCallConfirmationRequiredException;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tool call confirmation with a blocking {@code ChatClient} call: which calls pause the
 * loop, how a paused reply looks, the rules for decisions, and the ways to resume. The
 * streamed variant, with session memory, is in {@code StreamedSessionTurnTests}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ToolCallConfirmationTests {

	private final BookingTools tools = new BookingTools();

	private final ApplicationContextRunner boot = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class));

	// --- Which calls pause ---

	@Test
	void aReplyWithACallOfAMarkedToolPausesAsAWholeAndNothingRuns() {
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> reply(call("s1", "flightStatus", "VX205"), call("b1", "bookFlight", "VX205")));

		ChatResponse reply = ChatClient.create(model)
			.prompt("Book VX205 if it is on time")
			.tools(this.tools)
			.call()
			.chatResponse();

		assertThat(ToolCallDecisions.awaitingDecision(reply)).containsExactly("b1");
		assertThat(reply.getResult().getOutput().getToolCalls()).extracting(AssistantMessage.ToolCall::name)
			.containsExactly("flightStatus", "bookFlight");
		assertThat(this.tools.ran).isEmpty();
		assertThat(model.prompts).hasSize(1);
	}

	@Test
	void contentGivesNoSignThatTheLoopPaused() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> reply(call("b1", "bookFlight", "VX205")));

		String content = ChatClient.create(model).prompt("Book VX205").tools(this.tools).call().content();

		assertThat(content).isNullOrEmpty();
		assertThat(this.tools.ran).isEmpty();
	}

	@Test
	void aProgrammaticToolIsMarkedInItsMetadata() {
		ToolCallback bookFlight = FunctionToolCallback.builder("bookFlight", (Flight flight) -> "booked")
			.description("Book a flight")
			.inputType(Flight.class)
			.toolMetadata(ToolMetadata.builder().requiresConfirmation(true).build())
			.build();

		assertThat(awaitingFrom(ToolCallingManager.builder().build(), calls(call("b1", "bookFlight", "VX205")),
				bookFlight))
			.containsExactly("b1");
	}

	@Test
	void thePoliciesNameTheCallsThatNeedConfirmation() {
		ChatResponse reply = calls(call("s1", "flightStatus", "VX205"), call("b1", "bookFlight", "VX205"),
				call("c1", "cancelBooking", "VX205"));

		assertThat(awaitingFrom(manager(ToolCallConfirmationPolicy.fromToolMetadata()), reply)).containsExactly("b1");
		assertThat(awaitingFrom(manager(ToolCallConfirmationPolicy.forTools("cancelBooking")), reply))
			.containsExactly("c1");
		assertThat(awaitingFrom(
				manager(ToolCallConfirmationPolicy.fromToolMetadata().or(ToolCallConfirmationPolicy.forTools("cancelBooking"))),
				reply))
			.containsExactly("b1", "c1");
		assertThat(awaitingFrom(manager(ToolCallConfirmationPolicy.none()), reply)).isEmpty();
		assertThat(this.tools.ran).containsExactly("flightStatus VX205", "bookFlight VX205", "cancelBooking VX205");
	}

	@Test
	void aPolicyCanLookAtTheArguments() {
		ToolCallConfirmationPolicy toLondon = (toolCall, metadata) -> toolCall.arguments().contains("LHR");

		assertThat(awaitingFrom(manager(toLondon),
				calls(call("s1", "flightStatus", "VX205"), call("s2", "flightStatus", "BA-LHR"))))
			.containsExactly("s2");
	}

	@Test
	void theAdvisorPausesForTheCallsOfItsManagersPolicy() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> reply(call("c1", "cancelBooking", "VX205")));
		ToolCallingManager manager = manager(
				ToolCallConfirmationPolicy.fromToolMetadata().or(ToolCallConfirmationPolicy.forTools("cancelBooking")));
		ChatClient client = ChatClient.builder(model)
			.defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
			.defaultTools(this.tools)
			.build();

		ChatResponse reply = client.prompt("Cancel VX205").call().chatResponse();

		assertThat(ToolCallDecisions.awaitingDecision(reply)).containsExactly("c1");
		assertThat(this.tools.ran).isEmpty();
	}

	@Test
	void underSpringBootTheFlagThePropertyAndThePolicyBeansAddUp() {
		ChatResponse reply = calls(call("s1", "flightStatus", "VX205"), call("b1", "bookFlight", "VX205"),
				call("c1", "cancelBooking", "VX205"), call("s2", "flightStatus", "BA-LHR"));
		this.boot.withPropertyValues("spring.ai.tools.confirmation.tools=cancelBooking")
			.withBean(ToolCallConfirmationPolicy.class,
					() -> (toolCall, metadata) -> toolCall.arguments().contains("LHR"))
			.run((context) -> assertThat(awaitingFrom(context.getBean(ToolCallingManager.class), reply))
				.containsExactly("b1", "c1", "s2"));
	}

	@Test
	void underSpringBootTheFlagCanOnlyBeTurnedOffWithAWarning(CapturedOutput output) {
		ChatResponse reply = calls(call("b1", "bookFlight", "VX205"), call("c1", "cancelBooking", "VX205"));
		this.boot
			.withPropertyValues("spring.ai.tools.confirmation.enabled=false",
					"spring.ai.tools.confirmation.tools=cancelBooking")
			.run((context) -> assertThat(awaitingFrom(context.getBean(ToolCallingManager.class), reply))
				.containsExactly("c1"));
		assertThat(output).contains("Tool call confirmation is disabled (spring.ai.tools.confirmation.enabled=false)");
	}

	@Test
	void theAutoConfiguredChatClientPausesWithTheAutoConfiguredManager() {
		this.boot.withBean(ChatModel.class, () -> new ScriptedChatModel((prompt) -> reply(call("c1", "cancelBooking", "VX205"))))
			.withPropertyValues("spring.ai.tools.confirmation.tools=cancelBooking")
			.run((context) -> {
				ChatResponse reply = context.getBean(ChatClient.Builder.class)
					.build()
					.prompt("Cancel VX205")
					.tools(this.tools)
					.call()
					.chatResponse();

				assertThat(ToolCallDecisions.awaitingDecision(reply)).containsExactly("c1");
				assertThat(this.tools.ran).isEmpty();
			});
	}

	// --- Keys and decisions ---

	@Test
	void callsWithoutIdsOrWithTheSameIdAreKeyedByTheirPosition() {
		AssistantMessage noIds = calls(call("", "bookFlight", "VX205"), call("", "bookFlight", "VX206")).getResult()
			.getOutput();
		AssistantMessage sameIds = calls(call("x", "bookFlight", "VX205"), call("x", "bookFlight", "VX206"),
				call("y", "flightStatus", "VX207"))
			.getResult()
			.getOutput();

		assertThat(ToolCallDecisions.toolCallKeys(noIds)).containsExactly("#0", "#1");
		assertThat(ToolCallDecisions.toolCallKeys(sameIds)).containsExactly("#0", "#1", "y");
		assertThat(awaitingFrom(ToolCallingManager.builder().build(), new ChatResponse(List.of(new Generation(noIds)))))
			.containsExactly("#0", "#1");
	}

	@Test
	void everyCallOfTheReplyNeedsADecisionThatNamesIt() {
		ChatResponse reply = calls(call("s1", "flightStatus", "VX205"), call("b1", "bookFlight", "VX205"));
		ToolCallingManager manager = ToolCallingManager.builder().build();
		Prompt prompt = prompt(List.of());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> manager.executeToolCalls(prompt, reply, ToolCallDecisions.of(ToolCallDecision.allow("b1"))))
			.withMessage("Every tool call of the reply needs a decision; missing: [s1]");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> manager.executeToolCalls(prompt, reply, ToolCallDecisions.of(ToolCallDecision.allow("s1"),
					ToolCallDecision.allow("b1"), ToolCallDecision.allow("zz"))))
			.withMessageStartingWith("No tool call has the key 'zz'");
		assertThat(this.tools.ran).isEmpty();
	}

	@Test
	void anAllowedCallRunsOnlyWithAToolThePromptCarries() {
		ChatResponse reply = calls(call("b1", "bookFlight", "VX205"));
		Prompt withoutTools = new Prompt(List.of(), ToolCallingChatOptions.builder().build());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> ToolCallingManager.builder()
				.build()
				.executeToolCalls(withoutTools, reply, ToolCallDecisions.of(ToolCallDecision.allow("b1"))))
			.withMessageContaining("The prompt does not carry the tool 'bookFlight'");
	}

	@Test
	void aDeniedCallAnswersWithTheDefaultOrTheGivenMessageAndDoesNotRun() {
		ChatResponse reply = calls(call("s1", "flightStatus", "VX205"), call("b1", "bookFlight", "VX205"),
				call("b2", "bookFlight", "VX206"));

		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(prompt(List.of()), reply, ToolCallDecisions.of(ToolCallDecision.allow("s1"),
					ToolCallDecision.deny("b1"), ToolCallDecision.deny("b2", "The user wants an aisle seat, ask again.")));

		assertThat(Transcript.of(result.conversationHistory())).containsExactly(
				"calls: flightStatus(VX205), bookFlight(VX205), bookFlight(VX206)",
				"results: \"VX205 is on time\", The user did not allow this tool call., The user wants an aisle seat, ask again.");
		assertThat(this.tools.ran).containsExactly("flightStatus VX205");
		assertThat(ToolCallDecision.DEFAULT_DENY_MESSAGE).isEqualTo("The user did not allow this tool call.");
	}

	@Test
	void runningTheSameDecisionsTwiceRunsTheToolTwice() {
		ChatResponse reply = calls(call("b1", "bookFlight", "VX205"));
		ToolCallingManager manager = ToolCallingManager.builder().build();
		ToolCallDecisions allow = ToolCallDecisions.of(ToolCallDecision.allow("b1"));

		manager.executeToolCalls(prompt(List.of()), reply, allow);
		manager.executeToolCalls(prompt(List.of()), reply, allow);

		assertThat(this.tools.ran).containsExactly("bookFlight VX205", "bookFlight VX205");
	}

	// --- Resuming ---

	@Test
	void theApplicationKeepsTheConversationAndSendsItAllOn() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Booked.") : calls(call("b1", "bookFlight", "VX205")).getResult().getOutput());
		ChatClient client = ChatClient.builder(model).defaultTools(this.tools).build();
		ChatResponse reply = client.prompt("Book VX205").call().chatResponse();
		List<Message> conversation = new ArrayList<>(List.of(new UserMessage("Book VX205"), reply.getResult().getOutput()));

		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(prompt(conversation), reply, ToolCallDecisions.of(ToolCallDecision.allow("b1")));
		String answer = client.prompt().messages(result.conversationHistory()).call().content();

		assertThat(answer).isEqualTo("Booked.");
		// The prompt ended with the paused reply: the history holds it once
		assertThat(Transcript.of(model.lastPrompt())).containsExactly("user: Book VX205", "calls: bookFlight(VX205)",
				"results: \"VX205 booked\"");
	}

	@Test
	@SuppressWarnings("removal")
	void aChatMemoryOutsideTheLoopTakesOnlyTheResultsOnResumption() {
		assertChatMemoryResumes(MessageChatMemoryAdvisor.builder(memory()).build());
	}

	@Test
	@SuppressWarnings("removal")
	void aChatMemoryInsideTheLoopTakesOnlyTheResultsOnResumption() {
		assertChatMemoryResumes(
				MessageChatMemoryAdvisor.builder(memory()).order(ToolCallingAdvisor.DEFAULT_ORDER + 100).build());
	}

	@Test
	@SuppressWarnings("removal")
	void outsideTheLoopTheResumedRequestLacksTheEarlierRoundsOfTheTurn() {
		assertThat(resumeAfterALookup(MessageChatMemoryAdvisor.builder(memory()).build()))
			.containsExactly("user: Book the next flight to Lisbon", "calls: bookFlight(VX205)", "results: \"VX205 booked\"");
	}

	@Test
	@SuppressWarnings("removal")
	void insideTheLoopTheResumedRequestCarriesTheWholeTurn() {
		assertThat(resumeAfterALookup(
				MessageChatMemoryAdvisor.builder(memory()).order(ToolCallingAdvisor.DEFAULT_ORDER + 100).build()))
			.containsExactly("user: Book the next flight to Lisbon", "calls: flightStatus(LIS)",
					"results: \"LIS is on time\"", "calls: bookFlight(VX205)", "results: \"VX205 booked\"");
	}

	@Test
	void allowedCallsOfToolsThatReturnDirectAreTheAnswerUnlessOneWasDenied() {
		ToolCallback receipt = FunctionToolCallback.builder("receipt", (Flight flight) -> "Receipt for " + flight.flightNumber())
			.description("Prints a receipt")
			.inputType(Flight.class)
			.toolMetadata(ToolMetadata.builder().requiresConfirmation(true).returnDirect(true).build())
			.build();
		ChatResponse reply = calls(call("r1", "receipt", "VX205"), call("r2", "receipt", "VX206"));
		Prompt prompt = new Prompt(List.of(), ToolCallingChatOptions.builder().toolCallbacks(receipt).build());
		ToolCallingManager manager = ToolCallingManager.builder().build();

		ToolExecutionResult allowed = manager.executeToolCalls(prompt, reply,
				ToolCallDecisions.of(ToolCallDecision.allow("r1"), ToolCallDecision.allow("r2")));
		ToolExecutionResult oneDenied = manager.executeToolCalls(prompt, reply,
				ToolCallDecisions.of(ToolCallDecision.allow("r1"), ToolCallDecision.deny("r2")));

		assertThat(allowed.returnDirect()).isTrue();
		assertThat(ToolExecutionResult.buildGenerations(allowed)).extracting((g) -> g.getOutput().getText())
			.containsExactly("\"Receipt for VX205\"", "\"Receipt for VX206\"");
		assertThat(oneDenied.returnDirect()).isFalse();
	}

	@Test
	void aLimitBreachedOnResumptionIsThrownOrAnswered() {
		ChatResponse reply = calls(call("b2", "bookFlight", "VX206"));
		// The turn so far, as the prompt carries it: one booking already ran
		List<Message> turn = List.of(new UserMessage("Book both"), calls(call("b1", "bookFlight", "VX205")).getResult()
			.getOutput(),
				org.springframework.ai.chat.messages.ToolResponseMessage.builder()
					.responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("b1",
							"bookFlight", "booked")))
					.build());
		ToolCallDecisions allow = ToolCallDecisions.of(ToolCallDecision.allow("b2"));

		assertThatExceptionOfType(ToolCallLimitExceededException.class)
			.isThrownBy(() -> ToolCallingManager.builder()
				.maxCallsPerTool("bookFlight", 1)
				.build()
				.executeToolCalls(prompt(turn), reply, allow))
			.satisfies((ex) -> assertThat(ex.getToolName()).isEqualTo("bookFlight"));
		ToolExecutionResult answered = ToolCallingManager.builder()
			.maxCallsPerTool("bookFlight", 1)
			.onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
			.build()
			.executeToolCalls(prompt(turn), reply, allow);
		assertThat(Transcript.line(answered.conversationHistory().get(answered.conversationHistory().size() - 1)))
			.contains("bookFlight");
		// With only the paused reply in the prompt, nothing counts the earlier booking
		ToolCallingManager.builder()
			.maxCallsPerTool("bookFlight", 1)
			.build()
			.executeToolCalls(prompt(List.of()), reply, allow);
		assertThat(this.tools.ran).containsExactly("bookFlight VX206");
	}

	@Test
	void aManagerOfYourOwnMustImplementTheResumptionItself() {
		ToolCallingManager own = new ToolCallingManager() {

			@Override
			public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
				return List.of();
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
				throw new IllegalStateException();
			}

		};

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> own.executeToolCalls(prompt(List.of()),
				calls(call("b1", "bookFlight", "VX205")), ToolCallDecisions.of(ToolCallDecision.allow("b1"))));
		assertThat(ToolCallingManager.builder().build()).isInstanceOf(DefaultToolCallingManager.class);
	}

	@SuppressWarnings("removal")
	private void assertChatMemoryResumes(MessageChatMemoryAdvisor memoryAdvisor) {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("Booked.") : calls(call("b1", "bookFlight", "VX205")).getResult().getOutput());
		ChatClient client = ChatClient.builder(model).defaultAdvisors(memoryAdvisor).defaultTools(this.tools).build();
		ChatResponse reply = client.prompt("Book VX205")
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "c1"))
			.call()
			.chatResponse();

		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(prompt(List.of()), reply, ToolCallDecisions.of(ToolCallDecision.allow("b1")));
		List<Message> history = result.conversationHistory();
		String answer = client.prompt()
			.messages(history.get(history.size() - 1))
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "c1"))
			.call()
			.content();

		assertThat(answer).isEqualTo("Booked.");
		assertThat(Transcript.of(this.chatMemory.get("c1"))).containsExactly("user: Book VX205", "calls: bookFlight(VX205)",
				"results: \"VX205 booked\"", "assistant: Booked.");
	}

	/**
	 * A turn that looks a flight up, then pauses to book it; the resumed request, as the
	 * model gets it.
	 */
	@SuppressWarnings("removal")
	private List<String> resumeAfterALookup(MessageChatMemoryAdvisor memoryAdvisor) {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			String last = ScriptedChatModel.lastText(prompt);
			if (last.contains("booked")) {
				return ScriptedChatModel.text("Booked.");
			}
			return last.contains("on time") ? calls(call("b1", "bookFlight", "VX205")).getResult().getOutput()
					: calls(call("s1", "flightStatus", "LIS")).getResult().getOutput();
		});
		ChatClient client = ChatClient.builder(model).defaultAdvisors(memoryAdvisor).defaultTools(this.tools).build();
		ChatResponse reply = client.prompt("Book the next flight to Lisbon")
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "c1"))
			.call()
			.chatResponse();
		ToolExecutionResult result = ToolCallingManager.builder()
			.build()
			.executeToolCalls(prompt(List.of()), reply, ToolCallDecisions.of(ToolCallDecision.allow("b1")));
		List<Message> history = result.conversationHistory();
		client.prompt()
			.messages(history.get(history.size() - 1))
			.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "c1"))
			.call()
			.content();
		return Transcript.of(model.lastPrompt());
	}

	private ChatMemory chatMemory;

	@SuppressWarnings("removal")
	private ChatMemory memory() {
		this.chatMemory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
		return this.chatMemory;
	}

	private ToolCallingManager manager(ToolCallConfirmationPolicy policy) {
		return ToolCallingManager.builder().toolCallConfirmationPolicy(policy).build();
	}

	private Prompt prompt(List<Message> messages) {
		return new Prompt(messages, ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(this.tools)).build());
	}

	/**
	 * The keys the manager pauses on for the reply, or none when it ran the calls.
	 */
	private List<String> awaitingFrom(ToolCallingManager manager, ChatResponse reply, ToolCallback... callbacks) {
		Prompt prompt = (callbacks.length == 0) ? prompt(List.of())
				: new Prompt(List.of(), ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
		try {
			manager.executeToolCalls(prompt, reply);
			return List.of();
		}
		catch (ToolCallConfirmationRequiredException ex) {
			return ex.getToolCallIds();
		}
	}

	private static AssistantMessage.ToolCall call(String id, String tool, String flight) {
		return new AssistantMessage.ToolCall(id, "function", tool, "{\"flightNumber\":\"" + flight + "\"}");
	}

	private static AssistantMessage reply(AssistantMessage.ToolCall... calls) {
		return AssistantMessage.builder().toolCalls(List.of(calls)).build();
	}

	private static ChatResponse calls(AssistantMessage.ToolCall... calls) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(calls)).build())));
	}

	static class BookingTools {

		final List<String> ran = new CopyOnWriteArrayList<>();

		@Tool(description = "Book a flight", requiresConfirmation = true)
		String bookFlight(String flightNumber) {
			this.ran.add("bookFlight " + flightNumber);
			return flightNumber + " booked";
		}

		@Tool(description = "Get the status of a flight")
		String flightStatus(String flightNumber) {
			this.ran.add("flightStatus " + flightNumber);
			return flightNumber + " is on time";
		}

		@Tool(description = "Cancel a booking")
		String cancelBooking(String flightNumber) {
			this.ran.add("cancelBooking " + flightNumber);
			return flightNumber + " cancelled";
		}

	}

	record Flight(String flightNumber) {
	}

}
