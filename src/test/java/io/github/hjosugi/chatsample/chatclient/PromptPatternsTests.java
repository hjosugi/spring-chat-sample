package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * The API that the page "Prompt Engineering Patterns" uses, with the examples of the
 * page: portable and model-specific options, entities, templates with parameters, and
 * prompts that hold JSON or code. What the model makes of a technique is not tested; what
 * Spring AI sends and parses is.
 */
class PromptPatternsTests {

	@Test
	void thePortableOptionsReachTheModel() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");

		ChatClient.create(model)
			.prompt("Classify this")
			.options(ChatOptions.builder().model("claude-sonnet-4-6").temperature(0.1).maxTokens(250).topK(40).topP(0.8))
			.call()
			.content();

		ChatOptions options = model.lastPrompt().getOptions();
		assertThat(options.getModel()).isEqualTo("claude-sonnet-4-6");
		assertThat(options.getTemperature()).isEqualTo(0.1);
		assertThat(options.getMaxTokens()).isEqualTo(250);
		assertThat(options.getTopK()).isEqualTo(40);
		assertThat(options.getTopP()).isEqualTo(0.8);
	}

	@Test
	void anthropicLeavesOutTheSamplingOptionsWithAWarning() {
		try (StandIn anthropic = new StandIn((request) -> Reply.json(Providers.anthropicText("ok")));
				LogCapture log = new LogCapture(AnthropicChatModel.class, Level.WARN)) {
			ChatClient.create(Providers.anthropic(anthropic))
				.prompt("Classify this")
				.options(ChatOptions.builder().temperature(0.1).topK(40).topP(0.8).maxTokens(5))
				.call()
				.content();

			JsonNode request = anthropic.single().body();
			assertThat(request.has("temperature")).isFalse();
			assertThat(request.has("top_k")).isFalse();
			assertThat(request.has("top_p")).isFalse();
			assertThat(request.get("max_tokens").asInt()).isEqualTo(5);
			assertThat(log.messages()).anySatisfy((message) -> assertThat(message).contains("does not support temperature"));
		}
	}

	@Test
	void theModelSpecificOptionsOfOpenAiReachTheRequest() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("{}")))) {
			OpenAiChatOptions.Builder openAiOptions = OpenAiChatOptions.builder()
				.model("gpt-5.4-mini")
				.reasoningEffort("low")
				.verbosity("low")
				.responseFormat(OpenAiChatModel.ResponseFormat.builder()
					.type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
					.build());

			ChatClient.create(Providers.openAi(openAi)).prompt("...").options(openAiOptions).call().content();

			JsonNode request = openAi.single().body();
			assertThat(request.get("model").asString()).isEqualTo("gpt-5.4-mini");
			assertThat(request.get("reasoning_effort").asString()).isEqualTo("low");
			assertThat(request.get("verbosity").asString()).isEqualTo("low");
			assertThat(request.get("response_format").get("type").asString()).isEqualTo("json_object");
		}
	}

	@Test
	void theModelSpecificOptionsOfAnthropicReachTheRequest() {
		try (StandIn anthropic = new StandIn((request) -> Reply.json(Providers.anthropicText("ok")))) {
			AnthropicChatOptions.Builder anthropicOptions = AnthropicChatOptions.builder()
				.model("claude-sonnet-4-6")
				.maxTokens(4096)
				.thinkingEnabled(2048);

			ChatClient.create(Providers.anthropic(anthropic)).prompt("...").options(anthropicOptions).call().content();

			JsonNode request = anthropic.single().body();
			assertThat(request.get("model").asString()).isEqualTo("claude-sonnet-4-6");
			assertThat(request.get("max_tokens").asInt()).isEqualTo(4096);
			assertThat(request.get("thinking").get("type").asString()).isEqualTo("enabled");
			assertThat(request.get("thinking").get("budget_tokens").asInt()).isEqualTo(2048);
		}
	}

	@Test
	void zeroShotMapsTheAnswerToAnEnum() {
		ScriptedChatModel model = ScriptedChatModel.answering("\"POSITIVE\"");

		Sentiment reviewSentiment = ChatClient.create(model).prompt("""
				Classify movie reviews as POSITIVE, NEUTRAL or NEGATIVE.
				Review: "Her" is a disturbing study revealing the direction
				humanity is headed if AI is allowed to keep evolving,
				unchecked. I wish there were more movies like this masterpiece.
				Sentiment:
				""").options(ChatOptions.builder().model("claude-sonnet-4-6").maxTokens(5)).call().entity(Sentiment.class);

		assertThat(reviewSentiment).isEqualTo(Sentiment.POSITIVE);
		assertThat(model.lastPrompt().getUserMessage().getText()).contains("\"enum\"", "POSITIVE", "NEUTRAL",
				"NEGATIVE");
	}

	/**
	 * With {@code maxTokens(5)}, as on the page, a model answers {@code POSITIVE}, not
	 * the JSON string {@code "POSITIVE"} that the format instructions ask for; the
	 * converter does not take a bare word.
	 */
	@Test
	void aBareWordIsNoEnumForTheConverter() {
		ScriptedChatModel model = ScriptedChatModel.answering("POSITIVE");

		assertThatRuntimeException().isThrownBy(() -> ChatClient.create(model).prompt("Classify this review").call().entity(Sentiment.class));
	}

	@Test
	void aFewShotPromptKeepsItsJsonExamplesAsTheyAre() {
		ScriptedChatModel model = ScriptedChatModel.answering("{\"size\": \"large\"}");
		String prompt = """
				Parse a customer's pizza order into valid JSON

				EXAMPLE 1:
				I want a small pizza with cheese, tomato sauce, and pepperoni.
				JSON Response:
				```
				{
				    "size": "small",
				    "type": "normal",
				    "ingredients": ["cheese", "tomato sauce", "pepperoni"]
				}
				```

				Now, I would like a large pizza, with the first half cheese and mozzarella.
				""";

		ChatClient.create(model).prompt(prompt).options(ChatOptions.builder().maxTokens(250)).call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo(prompt);
	}

	@Test
	void systemPromptingWithJsonOutputMapsNestedRecordsAndEnums() {
		ScriptedChatModel model = ScriptedChatModel
			.answering("{\"movie_reviews\":[{\"sentiment\":\"NEGATIVE\",\"name\":\"Her\"}]}");

		MovieReviews movieReviews = ChatClient.create(model).prompt().system("""
				Classify movie reviews as positive, neutral or negative. Return
				valid JSON.
				""").user("""
				Review: "Her" is a disturbing study revealing the direction
				humanity is headed if AI is allowed to keep evolving,
				unchecked. It's so disturbing I couldn't watch it.

				JSON Response:
				""").call().entity(MovieReviews.class);

		assertThat(movieReviews.movie_reviews()).singleElement()
			.isEqualTo(new MovieReviews.Movie(MovieReviews.Sentiment.NEGATIVE, "Her"));
		assertThat(model.lastPrompt().getSystemMessage().getText()).startsWith("Classify movie reviews");
		assertThat(model.lastPrompt().getUserMessage().getText()).contains("JSON Response:", "movie_reviews");
	}

	@Test
	void contextualPromptingFillsInTheContext() {
		ScriptedChatModel model = ScriptedChatModel.answering("1. Pac-Man");

		ChatClient.create(model).prompt().user((u) -> u.text("""
				Suggest 3 topics to write an article about with a few lines of
				description of what this article should contain.

				Context: {context}
				""").param("context", "You are writing for a blog about retro 80's arcade video games.")).call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt()))
			.endsWith("Context: You are writing for a blog about retro 80's arcade video games.\n");
	}

	@Test
	void stepBackPromptingHandsTheFirstAnswerToTheSecondPrompt() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.text(
				ScriptedChatModel.lastText(prompt).contains("Context:") ? "A storyline" : "1. An abandoned base"));
		ChatClient chatClient = ChatClient.builder(model)
			.defaultOptions(ChatOptions.builder().model("claude-sonnet-4-6").maxTokens(1024))
			.build();

		String stepBack = chatClient.prompt("""
				Based on popular first-person shooter action games, what are
				5 fictional key settings?
				""").call().content();
		String story = chatClient.prompt().user((u) -> u.text("""
				Write a one paragraph storyline for a new level of a first-
				person shooter video game that is challenging and engaging.

				Context: {step-back}
				""").param("step-back", stepBack)).call().content();

		assertThat(story).isEqualTo("A storyline");
		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).endsWith("Context: 1. An abandoned base\n");
		assertThat(model.prompts).allSatisfy((prompt) -> assertThat(prompt.getOptions().getMaxTokens()).isEqualTo(1024));
	}

	@Test
	void selfConsistencyAsksFiveTimesAndVotes() {
		record EmailClassification(Classification classification, String reasoning) {
			enum Classification {

				IMPORTANT, NOT_IMPORTANT

			}
		}
		AtomicInteger calls = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel
			.text("{\"classification\":\"" + ((calls.incrementAndGet() % 2 == 0) ? "NOT_IMPORTANT" : "IMPORTANT")
					+ "\",\"reasoning\":\"It reports a bug.\"}"));
		ChatClient chatClient = ChatClient.create(model);
		String email = "Hi, I did notice a bug in the contact form. Cheers, Harry the Hacker.";

		int importantCount = 0;
		int notImportantCount = 0;
		for (int i = 0; i < 5; i++) {
			EmailClassification output = chatClient.prompt().user((u) -> u.text("""
					Email: {email}
					Classify the above email as IMPORTANT or NOT IMPORTANT. Let's
					think step by step and explain why.
					""").param("email", email))
				.options(ChatOptions.builder().temperature(1.0))
				.call()
				.entity(EmailClassification.class);
			if (output.classification() == EmailClassification.Classification.IMPORTANT) {
				importantCount++;
			}
			else {
				notImportantCount++;
			}
		}

		assertThat(importantCount > notImportantCount ? "IMPORTANT" : "NOT IMPORTANT").isEqualTo("IMPORTANT");
		assertThat(model.prompts).hasSize(5)
			.allSatisfy((prompt) -> assertThat(prompt.getOptions().getTemperature()).isEqualTo(1.0));
	}

	@Test
	void treeOfThoughtsAndAutomaticPromptEngineeringChainTheirAnswers() {
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text("answer " + prompt.getUserMessage().getText().length()));
		ChatClient chatClient = ChatClient.create(model);

		String moves = chatClient.prompt("Generate 3 different possible opening moves.")
			.options(ChatOptions.builder().temperature(0.7))
			.call()
			.content();
		String bestMove = chatClient.prompt()
			.user((u) -> u.text("Analyze these opening moves and select the strongest one:\n{moves}").param("moves", moves))
			.call()
			.content();
		chatClient.prompt()
			.user((u) -> u.text("Based on this selected opening move:\n{best_move}").param("best_move", bestMove))
			.call()
			.content();

		assertThat(model.prompts.get(1).getUserMessage().getText()).endsWith("\n" + moves);
		assertThat(model.prompts.get(2).getUserMessage().getText()).endsWith("\n" + bestMove);
	}

	@Test
	void codeInAParamStaysAsItIsEvenWithBraces() {
		ScriptedChatModel model = ScriptedChatModel.answering("It renames files.");
		String code = """
				files=( "$folder_name"/* )
				for file in "${files[@]}"; do
				new_file_name="draft_$(basename "$file")"
				done
				""";

		ChatClient.create(model).prompt().user((u) -> u.text("""
				Explain to me the below Bash code:
				```
				{code}
				```
				""").param("code", code)).call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).contains("for file in \"${files[@]}\"; do",
				"new_file_name=\"draft_$(basename \"$file\")\"");
	}

	enum Sentiment {

		POSITIVE, NEUTRAL, NEGATIVE

	}

	record MovieReviews(Movie[] movie_reviews) {

		enum Sentiment {

			POSITIVE, NEUTRAL, NEGATIVE

		}

		record Movie(Sentiment sentiment, String name) {
		}

	}

}
