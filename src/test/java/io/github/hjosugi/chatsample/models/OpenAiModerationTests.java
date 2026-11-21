package io.github.hjosugi.chatsample.models;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationOptionsBuilder;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResult;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.openai.OpenAiModerationOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI Moderation page and the portable Moderation Models page: what goes to
 * {@code /moderations}, and what Spring AI makes of the flags and scores.
 */
class OpenAiModerationTests {

	static final String FLAGGED = """
			{"id": "modr-123", "model": "omni-moderation-2024-09-26", "results": [{
			  "flagged": true,
			  "categories": {"harassment": true, "harassment/threatening": true, "hate": false,
			    "hate/threatening": false, "illicit": true, "illicit/violent": false, "self-harm": false,
			    "self-harm/instructions": false, "self-harm/intent": false, "sexual": false,
			    "sexual/minors": false, "violence": true, "violence/graphic": false},
			  "category_scores": {"harassment": 0.91, "harassment/threatening": 0.83, "hate": 0.01,
			    "hate/threatening": 0.001, "illicit": 0.7, "illicit/violent": 0.02, "self-harm": 0.002,
			    "self-harm/instructions": 0.001, "self-harm/intent": 0.001, "sexual": 0.003,
			    "sexual/minors": 0.0001, "violence": 0.88, "violence/graphic": 0.04},
			  "category_applied_input_types": {"harassment": ["text"], "harassment/threatening": ["text"],
			    "hate": ["text"], "hate/threatening": ["text"], "illicit": ["text"], "illicit/violent": ["text"],
			    "self-harm": ["text"], "self-harm/instructions": ["text"], "self-harm/intent": ["text"],
			    "sexual": ["text"], "sexual/minors": ["text"], "violence": ["text"], "violence/graphic": ["text"]}
			}]}""";

	private final StandIn openAi = new StandIn((request) -> Reply.json(FLAGGED));

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private OpenAiModerationModel model() {
		return OpenAiModerationModel.builder()
			.options(OpenAiModerationOptions.builder()
				.baseUrl(this.openAi.url() + "/v1")
				.apiKey("test-key")
				.maxRetries(0)
				.build())
			.build();
	}

	@Test
	void theTextGoesToTheModerationsEndpointWithTheDefaultModel() {
		model().call(new ModerationPrompt("Text to be moderated"));

		StandIn.Request request = this.openAi.single();
		assertThat(request.path()).isEqualTo("/v1/moderations");
		assertThat(request.apiKey()).isEqualTo("test-key");
		assertThat(request.body().get("input").asString()).isEqualTo("Text to be moderated");
		assertThat(request.body().get("model").asString()).isEqualTo("omni-moderation-latest");
	}

	@Test
	void eachResultSaysWhetherTheTextWasFlaggedForWhichCategoriesAndHowMuch() {
		Moderation moderation = model().call(new ModerationPrompt("Text to be moderated")).getResult().getOutput();

		assertThat(moderation.getId()).isEqualTo("modr-123");
		assertThat(moderation.getModel()).isEqualTo("omni-moderation-2024-09-26");
		assertThat(moderation.getResults()).hasSize(1);
		ModerationResult result = moderation.getResults().get(0);
		assertThat(result.isFlagged()).isTrue();
		Categories categories = result.getCategories();
		assertThat(categories.isHarassment()).isTrue();
		assertThat(categories.isHarassmentThreatening()).isTrue();
		assertThat(categories.isViolence()).isTrue();
		assertThat(categories.isHate()).isFalse();
		CategoryScores scores = result.getCategoryScores();
		assertThat(scores.getHarassment()).isEqualTo(0.91);
		assertThat(scores.getViolence()).isEqualTo(0.88);
		assertThat(scores.getSexualMinors()).isEqualTo(0.0001);
	}

	/**
	 * The page says that the categories are the union of what the providers check, and
	 * that each provider fills those it supports. OpenAI's {@code illicit} category has no
	 * place among them, so a text that OpenAI flags only for it looks harmless in the
	 * categories, and only {@code isFlagged()} tells.
	 */
	@Test
	void theCategoriesOfMistralStayFalseAndOpenAisIllicitIsDropped() {
		ModerationResult result = model().call(new ModerationPrompt("Text to be moderated"))
			.getResult()
			.getOutput()
			.getResults()
			.get(0);

		assertThat(result.getCategories().isLaw()).isFalse();
		assertThat(result.getCategories().isPii()).isFalse();
		assertThat(result.getCategoryScores().getFinancial()).isZero();
		assertThat(java.util.Arrays.stream(Categories.class.getMethods()).map(java.lang.reflect.Method::getName))
			.noneMatch((name) -> name.toLowerCase(java.util.Locale.ROOT).contains("illicit"));
	}

	@Test
	void portableOptionsChooseTheModel() {
		model().call(new ModerationPrompt("Text to check",
				ModerationOptionsBuilder.builder().model("text-moderation-stable").build()));

		assertThat(this.openAi.single().body().get("model").asString()).isEqualTo("text-moderation-stable");
	}

	@Test
	void thePropertiesConfigureTheModelAndTheConnection() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OpenAiModerationAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.api-key=common-key",
					"spring.ai.openai.moderation.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.moderation.api-key=moderation-key",
					"spring.ai.openai.moderation.model=omni-moderation-2024-09-26", "spring.ai.openai.max-retries=0")
			.run((context) -> {
				context.getBean(ModerationModel.class).call(new ModerationPrompt("Text"));

				assertThat(this.openAi.single().apiKey()).isEqualTo("moderation-key");
				assertThat(this.openAi.single().body().get("model").asString())
					.isEqualTo("omni-moderation-2024-09-26");
			});
	}

}
