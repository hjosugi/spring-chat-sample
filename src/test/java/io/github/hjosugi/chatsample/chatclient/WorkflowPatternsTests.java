package io.github.hjosugi.chatsample.chatclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five workflow patterns of the page "Workflow Pattern Examples", each as a small
 * class like the examples of the page and a scripted model, to show that the
 * {@code ChatClient} API they use does what they rely on: one step's answer in the next
 * prompt, prompts in parallel, typed decisions that steer the next call, and a loop that
 * ends on an evaluation.
 */
class WorkflowPatternsTests {

	@Test
	void theChainWorkflowHandsEachAnswerToTheNextStepAsItIs() {
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text("{step " + prompt.getUserMessage().getText().length() + "}"));
		String[] systemPrompts = { "Extract the numbers.", "Sort them.", "Format them as a table." };

		String result = new ChainWorkflow(ChatClient.create(model), systemPrompts).chain("Q3: 45%, 12M users");

		assertThat(model.prompts).hasSize(3);
		assertThat(model.prompts.get(0).getUserMessage().getText()).isEqualTo("{Extract the numbers.}\n {Q3: 45%, 12M users}");
		String firstAnswer = "{step " + "{Extract the numbers.}\n {Q3: 45%, 12M users}".length() + "}";
		// The braces of the format string and of the answers reach the model untouched.
		assertThat(model.prompts.get(1).getUserMessage().getText()).isEqualTo("{Sort them.}\n {" + firstAnswer + "}");
		assertThat(result).startsWith("{step ");
	}

	@Test
	void theParallelizationWorkflowAsksForEachInputAndKeepsTheirOrder() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			String text = prompt.getUserMessage().getText();
			return ScriptedChatModel.text("Impact on " + text.substring(text.indexOf("Input: ") + 7, text.indexOf(':', text.indexOf("Input: ") + 7)));
		});

		List<String> parallelResponse = new ParallelizationWorkflow(ChatClient.create(model)).parallel(
				"Analyze how market changes will impact this stakeholder group.",
				List.of("Customers: ...", "Employees: ...", "Investors: ...", "Suppliers: ..."), 4);

		assertThat(parallelResponse).containsExactly("Impact on Customers", "Impact on Employees", "Impact on Investors",
				"Impact on Suppliers");
		assertThat(model.prompts).hasSize(4);
	}

	@Test
	void theRoutingWorkflowClassifiesTheInputAndAsksTheSpecialist() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> prompt.getUserMessage().getText().contains("Classify")
				? ScriptedChatModel.text("{\"reasoning\":\"A double charge\",\"selection\":\"billing\"}")
				: ScriptedChatModel.text("We refunded the second charge."));
		Map<String, String> routes = Map.of("billing",
				"You are a billing specialist. Help resolve billing issues...", "technical",
				"You are a technical support engineer. Help solve technical problems...", "general",
				"You are a customer service representative. Help with general inquiries...");

		String response = new RoutingWorkflow(ChatClient.create(model)).route("My account was charged twice last week",
				routes);

		assertThat(response).isEqualTo("We refunded the second charge.");
		assertThat(model.prompts).hasSize(2);
		assertThat(model.prompts.get(1).getUserMessage().getText())
			.startsWith("You are a billing specialist.")
			.endsWith("Input: My account was charged twice last week");
	}

	@Test
	void theOrchestratorPlansTheSubtasksAndTheWorkersDoThem() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			String text = prompt.getUserMessage().getText();
			if (text.startsWith("Analyze this task")) {
				return ScriptedChatModel.text("{\"analysis\":\"Two audiences\",\"tasks\":["
						+ "{\"type\":\"technical\",\"description\":\"Write the API reference\"},"
						+ "{\"type\":\"user-friendly\",\"description\":\"Write a getting started guide\"}]}");
			}
			return ScriptedChatModel.text("Done: " + text.substring(text.indexOf("Style: ") + 7, text.indexOf('\n', text.indexOf("Style: "))));
		});

		WorkerResponse response = new OrchestratorWorkersWorkflow(ChatClient.create(model))
			.process("Generate both technical and user-friendly documentation for a REST API endpoint");

		assertThat(response.analysis()).isEqualTo("Two audiences");
		assertThat(response.workerResponses()).containsExactly("Done: technical", "Done: user-friendly");
		assertThat(model.prompts).hasSize(3);
	}

	@Test
	void theEvaluatorOptimizerLoopsUntilTheEvaluationPasses() {
		AtomicInteger generations = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			if (prompt.getSystemMessage().getText().startsWith("Evaluate")) {
				boolean last = prompt.getUserMessage().getText().contains("AtomicInteger");
				return ScriptedChatModel.text(last ? "{\"evaluation\":\"PASS\",\"feedback\":\"Good\"}"
						: "{\"evaluation\":\"NEEDS_IMPROVEMENT\",\"feedback\":\"Not thread-safe\"}");
			}
			String code = (generations.incrementAndGet() == 1) ? "class Counter { int count; }"
					: "class Counter { AtomicInteger count; }";
			return ScriptedChatModel.text("{\"thoughts\":\"Attempt " + generations.get() + "\",\"response\":\"" + code + "\"}");
		});

		RefinedResponse response = new EvaluatorOptimizerWorkflow(ChatClient.create(model))
			.loop("Create a Java class implementing a thread-safe counter");

		assertThat(response.solution()).contains("AtomicInteger");
		assertThat(response.chainOfThought()).extracting(Generation::thoughts).containsExactly("Attempt 1", "Attempt 2");
		assertThat(model.prompts).hasSize(4);
		// The second generation sees the feedback on the first.
		assertThat(model.prompts.get(2).getUserMessage().getText()).contains("Not thread-safe");
	}

	@Test
	void anAgentLoopEndsWhenTheModelAnswersWithoutATool() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> {
			long toolResults = prompt.getInstructions().stream().filter((m) -> m.getMessageType() == MessageType.TOOL).count();
			return (toolResults < 3) ? ScriptedChatModel.toolCall("nextStep", "{}") : ScriptedChatModel.text("Goal reached");
		});
		Steps steps = new Steps();

		String answer = ChatClient.create(model).prompt("Reach the goal").tools(steps).call().content();

		assertThat(answer).isEqualTo("Goal reached");
		assertThat(steps.taken).hasValue(3);
		assertThat(model.prompts).hasSize(4);
		assertThat(model.lastPrompt().getInstructions()).extracting((message) -> message.getMessageType())
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT,
					MessageType.TOOL, MessageType.ASSISTANT, MessageType.TOOL);
	}

	static class Steps {

		final AtomicInteger taken = new AtomicInteger();

		@Tool(description = "Takes the next step")
		String nextStep() {
			return "Step " + this.taken.incrementAndGet() + " taken";
		}

	}

	record ChainWorkflow(ChatClient chatClient, String[] systemPrompts) {

		String chain(String userInput) {
			String response = userInput;
			for (String prompt : this.systemPrompts) {
				String input = String.format("{%s}\n {%s}", prompt, response);
				response = this.chatClient.prompt(input).call().content();
			}
			return response;
		}

	}

	record ParallelizationWorkflow(ChatClient chatClient) {

		List<String> parallel(String prompt, List<String> inputs, int nWorkers) {
			ExecutorService executor = Executors.newFixedThreadPool(nWorkers);
			try {
				List<CompletableFuture<String>> futures = inputs.stream()
					.map((input) -> CompletableFuture.supplyAsync(
							() -> this.chatClient.prompt(prompt + "\nInput: " + input).call().content(), executor))
					.toList();
				return futures.stream().map(CompletableFuture::join).toList();
			}
			finally {
				executor.shutdown();
			}
		}

	}

	record RoutingResponse(String reasoning, String selection) {
	}

	record RoutingWorkflow(ChatClient chatClient) {

		String route(String input, Map<String, String> routes) {
			RoutingResponse routing = this.chatClient.prompt("""
					Classify the input into one of these routes: %s
					Input: %s""".formatted(routes.keySet(), input)).call().entity(RoutingResponse.class);
			return this.chatClient.prompt(routes.get(routing.selection()) + "\nInput: " + input).call().content();
		}

	}

	record Task(String type, String description) {
	}

	record OrchestratorResponse(String analysis, List<Task> tasks) {
	}

	record WorkerResponse(String analysis, List<String> workerResponses) {
	}

	record OrchestratorWorkersWorkflow(ChatClient chatClient) {

		WorkerResponse process(String taskDescription) {
			OrchestratorResponse orchestratorResponse = this.chatClient
				.prompt("Analyze this task and break it down into 2-3 distinct approaches: " + taskDescription)
				.call()
				.entity(OrchestratorResponse.class);
			List<String> workerResponses = orchestratorResponse.tasks()
				.parallelStream()
				.map((task) -> this.chatClient.prompt()
					.user((u) -> u.text("Generate content for: {task}\nStyle: {style}\nGuidelines: {guidelines}\n")
						.param("task", taskDescription)
						.param("style", task.type())
						.param("guidelines", task.description()))
					.call()
					.content())
				.toList();
			return new WorkerResponse(orchestratorResponse.analysis(), workerResponses);
		}

	}

	record Generation(String thoughts, String response) {
	}

	record EvaluationResponse(Evaluation evaluation, String feedback) {

		enum Evaluation {

			PASS, NEEDS_IMPROVEMENT, FAIL

		}

	}

	record RefinedResponse(String solution, List<Generation> chainOfThought) {
	}

	record EvaluatorOptimizerWorkflow(ChatClient chatClient) {

		RefinedResponse loop(String task) {
			List<String> memory = new ArrayList<>();
			List<Generation> chainOfThought = new ArrayList<>();
			String context = "";
			while (true) {
				Generation generation = generate(task, context);
				memory.add(generation.response());
				chainOfThought.add(generation);
				EvaluationResponse evaluation = evaluate(generation.response(), task);
				if (evaluation.evaluation() == EvaluationResponse.Evaluation.PASS) {
					return new RefinedResponse(generation.response(), chainOfThought);
				}
				context = "Previous attempts:\n" + String.join("\n", memory) + "\nFeedback: " + evaluation.feedback();
			}
		}

		private Generation generate(String task, String context) {
			return this.chatClient.prompt(new Prompt(task + "\n" + context)).call().entity(Generation.class);
		}

		private EvaluationResponse evaluate(String content, String task) {
			return this.chatClient.prompt()
				.system("Evaluate this code implementation for correctness and thread safety.")
				.user("Task: " + task + "\nContent: " + content)
				.call()
				.entity(EvaluationResponse.class);
		}

	}

}
