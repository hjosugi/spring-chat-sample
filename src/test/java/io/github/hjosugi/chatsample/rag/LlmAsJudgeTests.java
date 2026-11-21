package io.github.hjosugi.chatsample.rag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.annotation.Tool;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.endsWithToolResult;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LLM-as-a-Judge page: its {@code SelfRefineEvaluationAdvisor}, a recursive advisor
 * that asks a judge model to rate each answer and asks again, with the judge's feedback,
 * until the rating is good enough. The weather tool of the page answers -255°C first and
 * 15°C after, so the first answer fails.
 */
class LlmAsJudgeTests {

	private static final String QUESTION = "What is current weather in Paris?";

	private final ScriptedChatModel judge = new ScriptedChatModel((prompt) -> {
		String answer = lastText(prompt).replaceAll("(?s).*Answer: (.*?)\n.*", "$1");
		return text(answer.contains("15°C") ? """
				{"rating": 4, "evaluation": "Excellent response with realistic weather data", "feedback": "None"}"""
				: """
						{"rating": 1, "evaluation": "Unrealistic", "feedback": "The temperature of -255°C is impossible."}""");
	});

	private final ScriptedChatModel weatherModel = new ScriptedChatModel((prompt) -> endsWithToolResult(prompt)
			? text("The current weather in Paris is " + lastText(prompt).replace("\"", ""))
			: toolCall("weather", "{\"location\":\"Paris\"}"));

	@Test
	void outsideTheToolLoopAFailedAnswerIsAskedAgainWithTheFeedbackUntilItPasses() {
		Weather weather = new Weather(-255, 15);
		ChatClient chatClient = ChatClient.builder(this.weatherModel)
			.defaultTools(weather)
			.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
				.chatClientBuilder(ChatClient.builder(this.judge))
				.maxRepeatAttempts(15)
				.successRating(4)
				.order(ToolCallingAdvisor.DEFAULT_ORDER - 1)
				.build())
			.build();

		String answer = chatClient.prompt(QUESTION).call().content();

		assertThat(answer).isEqualTo("The current weather in Paris is sunny with a temperature of 15°C.");
		// Each attempt runs the whole tool loop again: the tool was asked twice.
		assertThat(weather.calls).hasValue(2);
		assertThat(this.judge.prompts).hasSize(2);
		assertThat(lastText(this.judge.prompts.get(0))).contains("Question: " + QUESTION,
				"Answer: The current weather in Paris is sunny with a temperature of -255°C.");
		String retried = this.weatherModel.prompts.get(2).getUserMessage().getText();
		assertThat(retried).startsWith(QUESTION)
			.contains("Previous response evaluation failed with feedback: The temperature of -255°C is impossible.",
					"Please repeat until evaluation passes!");
	}

	@Test
	void afterTheLastAttemptTheLastAnswerIsReturnedWhateverItsRating() {
		Weather weather = new Weather(-255, -255, -255, 15);
		ChatClient chatClient = ChatClient.builder(this.weatherModel)
			.defaultTools(weather)
			.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
				.chatClientBuilder(ChatClient.builder(this.judge))
				.maxRepeatAttempts(2)
				.successRating(4)
				.order(ToolCallingAdvisor.DEFAULT_ORDER - 1)
				.build())
			.build();

		String answer = chatClient.prompt(QUESTION).call().content();

		assertThat(answer).contains("-255°C");
		assertThat(this.judge.prompts).hasSize(3);
	}

	/**
	 * The page gives the advisor the order 0 next to tools. In Spring AI 2.1 the tool
	 * loop is an advisor of the order {@code HIGHEST_PRECEDENCE + 300}, so at 0 the judge
	 * sits inside the loop: it is asked to rate the round in which the model only asks
	 * for the tool, which has no text, and asking again cannot make the tool answer
	 * again.
	 */
	@Test
	void atOrderZeroTheJudgeRatesTheToolCallRoundAndCannotFixTheToolsAnswer() {
		Weather weather = new Weather(-255, 15);
		ChatClient chatClient = ChatClient.builder(this.weatherModel)
			.defaultTools(weather)
			.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
				.chatClientBuilder(ChatClient.builder(this.judge))
				.maxRepeatAttempts(2)
				.successRating(4)
				.order(0)
				.build())
			.build();

		String answer = chatClient.prompt(QUESTION).call().content();

		assertThat(lastText(this.judge.prompts.get(0))).contains("Answer: \n");
		assertThat(weather.calls).hasValue(1);
		assertThat(answer).contains("-255°C");
	}

	@Test
	void theAdvisorsAfterTheJudgeRunOnceForEveryAttempt() {
		AtomicInteger logged = new AtomicInteger();
		CallAdvisor myLoggingAdvisor = new CallAdvisor() {

			@Override
			public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
				logged.incrementAndGet();
				return chain.nextCall(request);
			}

			@Override
			public String getName() {
				return "logging";
			}

			@Override
			public int getOrder() {
				return ToolCallingAdvisor.DEFAULT_ORDER - 1;
			}

		};
		ChatClient chatClient = ChatClient.builder(this.weatherModel)
			.defaultTools(new Weather(-255, 15))
			.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
				.chatClientBuilder(ChatClient.builder(this.judge))
				.order(ToolCallingAdvisor.DEFAULT_ORDER - 2)
				.build(), myLoggingAdvisor)
			.build();

		chatClient.prompt(QUESTION).call().content();

		assertThat(logged).hasValue(2);
	}

	/**
	 * The page's warning: the advisor implements only {@code CallAdvisor}, so a stream is
	 * not judged.
	 */
	@Test
	void aStreamIsNotJudged() {
		ChatClient chatClient = ChatClient.builder(this.weatherModel)
			.defaultTools(new Weather(-255, 15))
			.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
				.chatClientBuilder(ChatClient.builder(this.judge))
				.order(ToolCallingAdvisor.DEFAULT_ORDER - 1)
				.build())
			.build();

		String answer = String.join("", chatClient.prompt(QUESTION).stream().content().collectList().block());

		assertThat(answer).contains("-255°C");
		assertThat(this.judge.prompts).isEmpty();
	}

	/**
	 * The weather of the page's example, with the temperatures in a fixed order.
	 */
	static class Weather {

		final AtomicInteger calls = new AtomicInteger();

		private final Deque<Integer> temperatures = new ArrayDeque<>();

		Weather(Integer... temperatures) {
			this.temperatures.addAll(List.of(temperatures));
		}

		@Tool(description = "Get the current weather for a given location")
		String weather(String location) {
			this.calls.incrementAndGet();
			int temperature = (this.temperatures.size() > 1) ? this.temperatures.poll() : this.temperatures.peek();
			return "sunny with a temperature of " + temperature + "°C.";
		}

	}

	/**
	 * The {@code SelfRefineEvaluationAdvisor} of the page, with the builder its example
	 * uses and the methods it leaves out.
	 */
	static final class SelfRefineEvaluationAdvisor implements CallAdvisor {

		private static final PromptTemplate DEFAULT_EVALUATION_PROMPT_TEMPLATE = new PromptTemplate(
				"""
						You will be given a user_question and assistant_answer couple.
						Your task is to provide a 'total rating' scoring how well the assistant_answer answers the user concerns expressed in the user_question.
						Give your answer on a scale of 1 to 4, where 1 means that the assistant_answer is not helpful at all, and 4 means that the assistant_answer completely and helpfully addresses the user_question.

						Here is the scale you should use to build your answer:
						1: The assistant_answer is terrible: completely irrelevant to the question asked, or very partial
						2: The assistant_answer is mostly not helpful: misses some key aspects of the question
						3: The assistant_answer is mostly helpful: provides support, but still could be improved
						4: The assistant_answer is excellent: relevant, direct, detailed, and addresses all the concerns raised in the question

						Provide your feedback as follows:

						\\{
						    "rating": 0,
						    "evaluation": "Explanation of the evaluation result and how to improve if needed.",
						    "feedback": "Constructive and specific feedback on the assistant_answer."
						\\}

						Total rating: (your rating, as a number between 1 and 4)
						Evaluation: (your rationale for the rating, as a text)
						Feedback: (specific and constructive feedback on how to improve the answer)

						You MUST provide values for 'Evaluation:' and 'Total rating:' in your answer.

						Now here are the question and answer.

						Question: {question}
						Answer: {answer}

						Provide your feedback. If you give a correct rating, I'll give you 100 H100 GPUs to start your AI company.

						Evaluation:
						""");

		@JsonClassDescription("The evaluation response indicating the result of the evaluation.")
		record EvaluationResponse(int rating, String evaluation, String feedback) {
		}

		private final ChatClient chatClient;

		private final int maxRepeatAttempts;

		private final int successRating;

		private final int order;

		private SelfRefineEvaluationAdvisor(ChatClient chatClient, int maxRepeatAttempts, int successRating,
				int order) {
			this.chatClient = chatClient;
			this.maxRepeatAttempts = maxRepeatAttempts;
			this.successRating = successRating;
			this.order = order;
		}

		static Builder builder() {
			return new Builder();
		}

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
			var request = chatClientRequest;
			ChatClientResponse response;
			for (int attempt = 1; attempt <= this.maxRepeatAttempts + 1; attempt++) {
				response = callAdvisorChain.copy(this).nextCall(request);
				EvaluationResponse evaluation = this.evaluate(chatClientRequest, response);
				if (evaluation.rating() >= this.successRating) {
					return response;
				}
				if (attempt > this.maxRepeatAttempts) {
					return response;
				}
				request = this.addEvaluationFeedback(chatClientRequest, evaluation);
			}
			throw new IllegalStateException("Unexpected loop exit in adviseCall");
		}

		private EvaluationResponse evaluate(ChatClientRequest request, ChatClientResponse response) {
			var evaluationPrompt = DEFAULT_EVALUATION_PROMPT_TEMPLATE
				.render(Map.of("question", getPromptQuestion(request), "answer", getAssistantAnswer(response)));
			return this.chatClient.prompt(evaluationPrompt).call().entity(EvaluationResponse.class);
		}

		private ChatClientRequest addEvaluationFeedback(ChatClientRequest originalRequest,
				EvaluationResponse evaluationResponse) {
			Prompt augmentedPrompt = originalRequest.prompt()
				.augmentUserMessage((userMessage) -> userMessage.mutate().text(String.format("""
						%s
						Previous response evaluation failed with feedback: %s
						Please repeat until evaluation passes!
						""", userMessage.getText(), evaluationResponse.feedback())).build());
			return originalRequest.mutate().prompt(augmentedPrompt).build();
		}

		private static String getPromptQuestion(ChatClientRequest request) {
			return request.prompt().getUserMessage().getText();
		}

		// A round in which the model only asks for a tool has no text.
		private static String getAssistantAnswer(ChatClientResponse response) {
			return Objects.requireNonNullElse(response.chatResponse().getResult().getOutput().getText(), "");
		}

		@Override
		public String getName() {
			return "SelfRefineEvaluationAdvisor";
		}

		@Override
		public int getOrder() {
			return this.order;
		}

		static final class Builder {

			private ChatClient.Builder chatClientBuilder;

			private int maxRepeatAttempts = 3;

			private int successRating = 4;

			private int order = 0;

			Builder chatClientBuilder(ChatClient.Builder chatClientBuilder) {
				this.chatClientBuilder = chatClientBuilder;
				return this;
			}

			Builder maxRepeatAttempts(int maxRepeatAttempts) {
				this.maxRepeatAttempts = maxRepeatAttempts;
				return this;
			}

			Builder successRating(int successRating) {
				this.successRating = successRating;
				return this;
			}

			Builder order(int order) {
				this.order = order;
				return this;
			}

			SelfRefineEvaluationAdvisor build() {
				return new SelfRefineEvaluationAdvisor(this.chatClientBuilder.build(), this.maxRepeatAttempts,
						this.successRating, this.order);
			}

		}

	}

}
