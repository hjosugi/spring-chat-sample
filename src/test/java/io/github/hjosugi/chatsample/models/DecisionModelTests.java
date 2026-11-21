package io.github.hjosugi.chatsample.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.RefusalAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.openai.OpenAiDecisionModel;
import org.springframework.ai.openai.OpenAiDecisionOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The Decision Models page, new in Spring AI 2.1: asking several named questions about
 * an input, the three kinds of question and of input, options, and reading the answers,
 * refusals and metadata. The calls go through the OpenAI decision model to a stand-in
 * that answers as the page's support triage example does.
 */
class DecisionModelTests {

	/**
	 * Answers every question of a request as the triage example does: yes with 0.93, the
	 * option {@code product} (or else the first option) with 0.85, and the lowest level
	 * with 0.75. The request id is {@code req_123}, and some of the input tokens were
	 * cached.
	 */
	static Reply triage(StandIn.Request request) {
		List<String> answers = new ArrayList<>();
		for (JsonNode question : request.body().get("questions")) {
			String name = question.get("name").asString();
			switch (question.get("type").asString()) {
				case "predicate" -> answers.add("""
						{"type": "predicate", "name": "%s", "probability": 0.93}""".formatted(name));
				case "choice" -> {
					List<String> options = new ArrayList<>();
					question.get("choices").forEach((choice) -> options.add(choice.get("value").asString()));
					String chosen = options.contains("product") ? "product" : options.get(0);
					List<String> probabilities = new ArrayList<>();
					double rest = 0.15;
					for (int i = 0; i < options.size(); i++) {
						String option = options.get(i);
						double probability = option.equals(chosen) ? 0.85
								: Map.of("delivery", 0.05, "account", 0.02, "other", 0.08)
									.getOrDefault(option, rest / (options.size() - 1));
						probabilities.add("{\"value\": \"%s\", \"probability\": %s}".formatted(option, probability));
					}
					answers.add("""
							{"type": "choice", "name": "%s", "choice": "%s", "confidence": 0.8, "probabilities": [%s]}"""
						.formatted(name, chosen, String.join(",", probabilities)));
				}
				case "score" -> {
					int levels = question.get("levels").size();
					List<String> probabilities = new ArrayList<>();
					for (int i = 0; i < levels; i++) {
						double probability = (levels == 3) ? List.of(0.75, 0.2, 0.05).get(i) : (i == 0 ? 1.0 : 0.0);
						probabilities.add("{\"label\": \"%s\", \"value\": %d, \"probability\": %s}"
							.formatted(question.get("levels").get(i).get("label").asString(), i, probability));
					}
					answers.add("""
							{"type": "score", "name": "%s", "score": 0.3, "confidence": 0.7, "probabilities": [%s]}"""
						.formatted(name, String.join(",", probabilities)));
				}
				default -> throw new IllegalArgumentException("No answer for " + question);
			}
		}
		return Reply.json("""
				{"model": "gpt-6-luna-2026-05", "answers": [%s], %s}""".formatted(String.join(",", answers), USAGE))
			.header("x-request-id", "req_123");
	}

	static final String USAGE = """
			"usage": {"input_tokens": 120, "output_tokens": 9, "total_tokens": 129,
			          "input_tokens_details": {"cached_tokens": 100, "cache_write_tokens": 20},
			          "output_tokens_details": {"reasoning_tokens": 0}}""";

	private final StandIn openAi = new StandIn(DecisionModelTests::triage);

	private final DecisionModel decisionModel = OpenAiDecisionModel.builder()
		.options(OpenAiDecisionOptions.builder()
			.baseUrl(this.openAi.url() + "/v1")
			.apiKey("test-key")
			.maxRetries(0)
			.build())
		.build();

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	static DecisionPrompt.Builder triage() {
		return DecisionPrompt.builder()
			.text("My new headphones arrived yesterday, but the left side doesn't work. Can I get my money back?")
			.question("defect",
					YesNoQuestion.builder()
						.text("Does the customer report a defect?")
						.whenYes("Part of the item does not work as it should")
						.whenNo("The item works, and the message is about something else")
						.build())
			.question("topic",
					ChoiceQuestion.builder()
						.text("What is the message about?")
						.option("delivery", "Where an order is, or when it will arrive")
						.option("product", "A problem with an item the customer received")
						.option("account", "Login, address or payment details")
						.option("other", "None of the above")
						.build())
			.question("satisfaction", ScoreQuestion.of("How satisfied is the customer?", "unhappy", "neutral", "happy"));
	}

	@Test
	void thePromptAsksThreeNamedQuestionsAndTheResponseHasAnAnswerForEach() {
		DecisionResponse response = this.decisionModel.call(triage().build());

		boolean defect = response.getYesNo("defect").isYes();
		String topic = response.getChoice("topic").getValue();
		int satisfaction = response.getScore("satisfaction").getNearestLevel();

		assertThat(defect).isTrue();
		assertThat(topic).isEqualTo("product");
		assertThat(satisfaction).isZero();
		assertThat(this.openAi.single().body().get("questions")).hasSize(3);
	}

	@Test
	void theShortCallTakesATextAndQuestions() {
		DecisionResponse response = this.decisionModel.call("Where is my order? I ordered it two weeks ago.",
				Map.of("topic", ChoiceQuestion.of("What is the message about?", "delivery", "product", "account",
						"other")));

		assertThat(response.getChoice("topic").getValue()).isEqualTo("product");
		assertThat(this.openAi.single().body().get("input").asString())
			.isEqualTo("Where is my order? I ordered it two weeks ago.");
	}

	@Test
	void theAnswersComeInTheOrderOfTheQuestionsOfTheBuilder() {
		try (StandIn reversed = new StandIn((request) -> Reply.json("""
				{"model": "m", "answers": [
				  {"type": "score", "name": "satisfaction", "score": 2.0, "confidence": 0.9,
				   "probabilities": [{"label": "happy", "value": 2, "probability": 1.0}]},
				  {"type": "choice", "name": "topic", "choice": "other", "confidence": 0.9,
				   "probabilities": [{"value": "other", "probability": 1.0}]},
				  {"type": "predicate", "name": "defect", "probability": 0.1}],
				 %s}""".formatted(USAGE)))) {
			DecisionModel model = OpenAiDecisionModel.builder()
				.options(OpenAiDecisionOptions.builder().baseUrl(reversed.url() + "/v1").apiKey("test-key").build())
				.build();

			DecisionResponse response = model.call(triage().build());

			assertThat(response.getAnswers().keySet()).containsExactly("defect", "topic", "satisfaction");
			assertThat(reversed.single().body().get("questions"))
				.extracting((question) -> question.get("name").asString())
				.containsExactly("defect", "topic", "satisfaction");
		}
	}

	@Test
	void fieldsAndAConversationAreInputsToo() {
		Map<String, Object> customer = new LinkedHashMap<>();
		customer.put("message", "This is the third time my order arrives late.");
		customer.put("orders", 12);
		customer.put("late_deliveries", 3);

		this.decisionModel.call(DecisionInput.fields(customer),
				Map.of("defect", YesNoQuestion.of("Does the customer say that the problem happened before?")));
		this.decisionModel.call(
				DecisionInput.conversation(List.of(new UserMessage("Can I return the headphones?"),
						new AssistantMessage("Your order will arrive on Friday."))),
				Map.of("defect", YesNoQuestion.of("Does the last assistant message answer the user's question?")));

		assertThat(this.openAi.requests.get(0).body().get("input").toString()).contains(
				"message: This is the third time my order arrives late.", "orders: 12", "late_deliveries: 3");
		assertThat(this.openAi.requests.get(1).body().get("input").toString())
			.contains("user: Can I return the headphones?", "assistant: Your order will arrive on Friday.");
	}

	@Test
	void optionsOnThePromptChangeTheModelForOneCall() {
		this.decisionModel.call(triage().options(DecisionOptions.builder().model("gpt-6-luna-large").build()).build());
		this.decisionModel.call(triage().build());

		assertThat(this.openAi.requests.get(0).body().get("model").asString()).isEqualTo("gpt-6-luna-large");
		assertThat(this.openAi.requests.get(1).body().get("model").asString()).isEqualTo("gpt-6-luna");
	}

	@Test
	void optionsAreImmutableAndCombineOnlyWhatTheOtherSets() {
		OpenAiDecisionOptions defaults = OpenAiDecisionOptions.builder()
			.model("gpt-6-luna")
			.safetyIdentifier("user-12345")
			.build();

		OpenAiDecisionOptions combined = defaults.mutate()
			.combineWith(DecisionOptions.builder().model("gpt-6-luna-large"))
			.build();

		assertThat(combined.getModel()).isEqualTo("gpt-6-luna-large");
		assertThat(combined.getSafetyIdentifier()).isEqualTo("user-12345");
		assertThat(defaults.getModel()).isEqualTo("gpt-6-luna");
	}

	@Test
	void aYesNoAnswerIsYesFromAProbabilityOfOneHalfOrFromTheThresholdYouChoose() {
		YesNoAnswer answer = this.decisionModel.call(triage().build()).getYesNo("defect");

		assertThat(answer.getProbability()).isEqualTo(0.93);
		assertThat(answer.isYes()).isTrue();
		assertThat(answer.isYes(0.9)).isTrue();
		assertThat(answer.isYes(0.95)).isFalse();
		assertThat(YesNoAnswer.builder().probability(0.5).build().isYes()).isTrue();
	}

	@Test
	void aChoiceAnswerHasTheSelectedOptionAndTheProbabilityOfEach() {
		ChoiceAnswer topic = this.decisionModel.call(triage().build()).getChoice("topic");

		assertThat(topic.getValue()).isEqualTo("product");
		assertThat(topic.getProbability()).isEqualTo(0.85);
		assertThat(topic.getProbability("other")).isEqualTo(0.08);
		assertThat(topic.getRankedOptions()).containsExactly("product", "other", "delivery", "account");
		assertThat(topic.getConfidence()).isEqualTo(0.8);
	}

	@Test
	void aScoreAnswerHasTheNearestTheExpectedAndTheRoundedLevel() {
		ScoreAnswer satisfaction = this.decisionModel.call(triage().build()).getScore("satisfaction");

		assertThat(satisfaction.getNearestLevel()).isZero();
		assertThat(satisfaction.getValue()).isEqualTo(0.3);
		assertThat(satisfaction.getRoundedLevel()).isZero();
		assertThat(satisfaction.getProbabilities()).containsExactly(0.75, 0.2, 0.05);
		assertThat(satisfaction.getConfidence()).isEqualTo(0.7);
	}

	@Test
	void theExpectedLevelCanBeALevelThatTheModelThinksUnlikely() {
		// probabilities 0.5, 0, 0.5 over the levels unhappy, neutral, happy
		ScoreAnswer satisfaction = ScoreAnswer.builder().value(1.0).probabilities(List.of(0.5, 0.0, 0.5)).build();

		assertThat(satisfaction.getNearestLevel()).isZero();
		assertThat(satisfaction.getValue()).isEqualTo(1.0);
		assertThat(satisfaction.getRoundedLevel()).isEqualTo(1);
	}

	@Test
	void withoutProbabilitiesTheNearestLevelIsTheRoundedLevel() {
		ScoreAnswer satisfaction = ScoreAnswer.builder().value(1.6).build();

		assertThat(satisfaction.getNearestLevel()).isEqualTo(satisfaction.getRoundedLevel()).isEqualTo(2);
	}

	@Test
	void aRefusedQuestionHasARefusalAnswerAndTheOthersAreStillAnswered() {
		try (StandIn refusing = new StandIn((request) -> Reply.json("""
				{"model": "m", "answers": [
				  {"type": "predicate", "name": "defect", "probability": 0.7},
				  {"type": "refusal", "name": "topic"},
				  {"type": "score", "name": "satisfaction", "score": 1.0, "confidence": 0.9,
				   "probabilities": [{"label": "neutral", "value": 1, "probability": 1.0}]}],
				 %s}""".formatted(USAGE)))) {
			DecisionModel model = OpenAiDecisionModel.builder()
				.options(OpenAiDecisionOptions.builder().baseUrl(refusing.url() + "/v1").apiKey("test-key").build())
				.build();

			DecisionResponse response = model.call(triage().build());

			assertThat(response.isRefused("topic")).isTrue();
			assertThat(response.hasAnswer("topic")).isTrue();
			assertThat(response.getAnswer("topic")).isInstanceOf(RefusalAnswer.class);
			assertThatIllegalStateException().isThrownBy(() -> response.getChoice("topic"));
			assertThat(response.getYesNo("defect").isYes()).isTrue();
			assertThat(response.isRefused("defect")).isFalse();
		}
	}

	@Test
	void aSwitchCoversEveryKindOfAnswer() {
		DecisionResponse response = this.decisionModel.call(triage().build());

		List<String> kinds = response.getAnswers().values().stream().map(DecisionModelTests::kind).toList();

		assertThat(kinds).containsExactly("yes", "choice product", "score 0");
	}

	private static String kind(DecisionAnswer answer) {
		// No default branch: the compiler checks that every kind is here.
		return switch (answer) {
			case YesNoAnswer yesNo -> yesNo.isYes() ? "yes" : "no";
			case ChoiceAnswer choice -> "choice " + choice.getValue();
			case ScoreAnswer score -> "score " + score.getNearestLevel();
			case RefusalAnswer refusal -> "refused";
		};
	}

	@Test
	void theMetadataHasTheUsageTheIdAndTheModel() {
		DecisionResponse response = this.decisionModel.call(triage().build());

		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(120);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(9);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(129);
		assertThat(response.getMetadata().getId()).isEqualTo("req_123");
		assertThat(response.getMetadata().getModel()).isEqualTo("gpt-6-luna-2026-05");
	}

}
