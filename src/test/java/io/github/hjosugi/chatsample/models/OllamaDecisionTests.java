package io.github.hjosugi.chatsample.models;

import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaDecisionAutoConfiguration;
import org.springframework.ai.ollama.OllamaDecisionModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.systemone.api.SystemOneClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ollama Decision Models page: the decision model speaks the System One protocol
 * to Ollama's {@code /v1/systemone}, with a placeholder key, and the starter configures
 * it from {@code spring.ai.ollama}.
 */
class OllamaDecisionTests {

	private final StandIn ollama = new StandIn(SystemOne::answers);

	@AfterEach
	void stop() {
		this.ollama.close();
	}

	@Test
	void theStarterSendsTheDecisionsToTheSameOllamaWithAPlaceholderKey() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OllamaApiAutoConfiguration.class,
					OllamaDecisionAutoConfiguration.class))
			.withPropertyValues("spring.ai.ollama.base-url=" + this.ollama.url())
			.run((context) -> {
				DecisionResponse response = context.getBean(DecisionModel.class)
					.call(DecisionModelTests.triage().build());

				StandIn.Request request = this.ollama.single();
				assertThat(request.path()).isEqualTo("/v1/systemone");
				assertThat(request.header("Authorization")).isEqualTo("Bearer ollama");
				assertThat(request.body().get("model").asString()).isEqualTo("d1-omni:600m");
				assertThat(response.getYesNo("defect").isYes()).isTrue();
				assertThat(response.getMetadata().getId()).isNull();
			});
	}

	@Test
	void theModelPropertyAndThePromptOptionsChooseTheModel() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OllamaApiAutoConfiguration.class,
					OllamaDecisionAutoConfiguration.class))
			.withPropertyValues("spring.ai.ollama.base-url=" + this.ollama.url(),
					"spring.ai.ollama.decision.model=d1-omni:2b")
			.run((context) -> {
				DecisionModel model = context.getBean(DecisionModel.class);

				model.call("Where is my order?", Map.of("topic", ChoiceQuestion.of("About?", "delivery", "other")));
				model.call(DecisionModelTests.triage()
					.options(DecisionOptions.builder().model("d1-omni:600m").build())
					.build());

				assertThat(this.ollama.requests.get(0).body().get("model").asString()).isEqualTo("d1-omni:2b");
				assertThat(this.ollama.requests.get(1).body().get("model").asString()).isEqualTo("d1-omni:600m");
			});
	}

	@Test
	void theManualConfigurationNeedsTwoClientsOfTheSameServer() {
		OllamaDecisionModel model = OllamaDecisionModel.builder()
			.ollamaApi(OllamaApi.builder().baseUrl(this.ollama.url()).build())
			.systemOneClient(SystemOneClient.builder().baseUrl(this.ollama.url()).apiKey("ollama").build())
			.build();

		DecisionResponse response = model.call("Where is my order? I ordered it two weeks ago.",
				Map.of("topic", ChoiceQuestion.of("What is the message about?", "delivery", "product", "account",
						"other")));

		assertThat(response.getChoice("topic").getValue()).isEqualTo("delivery");
		assertThat(this.ollama.single().body().get("model").asString()).isEqualTo("d1-omni:600m");
		assertThat(model.getOptions().getModel()).isEqualTo("d1-omni:600m");
	}

}
