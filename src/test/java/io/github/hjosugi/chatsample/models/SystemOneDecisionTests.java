package io.github.hjosugi.chatsample.models;

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
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.content.Media;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.model.systemone.autoconfigure.SystemOneDecisionAutoConfiguration;
import org.springframework.ai.systemone.SystemOneDecisionModel;
import org.springframework.ai.systemone.SystemOneDecisionOptions;
import org.springframework.ai.systemone.api.JsonContent;
import org.springframework.ai.systemone.api.SystemOneClient;
import org.springframework.ai.systemone.api.SystemOneRetryUtils;
import org.springframework.ai.systemone.api.exception.SystemOneAnswerTypeException;
import org.springframework.ai.systemone.api.exception.SystemOneBadRequestException;
import org.springframework.ai.systemone.api.exception.SystemOneMissingAnswerException;
import org.springframework.ai.systemone.api.question.Choice;
import org.springframework.ai.systemone.api.question.Noul;
import org.springframework.ai.systemone.api.question.SystemOneRequest;
import org.springframework.ai.systemone.api.response.SystemOneResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The System One Decision Models page: the request mapping of the protocol, the
 * response mapping with the request id, errors and retries, the low-level
 * {@code SystemOneClient}, and the auto-configuration.
 */
class SystemOneDecisionTests {

	private final StandIn typeSafe = new StandIn(SystemOne::answersWithRequestId);

	@AfterEach
	void stop() {
		this.typeSafe.close();
	}

	private SystemOneClient.Builder client() {
		return SystemOneClient.builder()
			.baseUrl(this.typeSafe.url())
			.apiKey("test-key")
			.retryTemplate(SystemOneRetryUtils.noRetryTemplate());
	}

	private SystemOneDecisionModel model() {
		return SystemOneDecisionModel.builder(client().build()).build();
	}

	@Test
	void eachKindOfQuestionMapsToItsQuestionOfTheProtocol() {
		DecisionResponse response = model().call(DecisionModelTests.triage().build());

		StandIn.Request request = this.typeSafe.single();
		assertThat(request.path()).isEqualTo("/v1/systemone");
		assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
		JsonNode body = request.body();
		assertThat(body.get("model").asString()).isEqualTo("jev-latest");
		assertThat(body.get("state").asString()).startsWith("My new headphones arrived yesterday");
		JsonNode defect = body.get("questions").get("defect");
		assertThat(defect.get("type").asString()).isEqualTo("noul");
		assertThat(defect.get("criteria").get("true").asString())
			.isEqualTo("Part of the item does not work as it should");
		JsonNode topic = body.get("questions").get("topic");
		assertThat(topic.get("type").asString()).isEqualTo("choice");
		assertThat(topic.get("criteria").get("product").asString())
			.isEqualTo("A problem with an item the customer received");
		JsonNode satisfaction = body.get("questions").get("satisfaction");
		assertThat(satisfaction.get("criteria").toString()).isEqualTo("[\"unhappy\",\"neutral\",\"happy\"]");
		assertThat(response.getYesNo("defect").getProbability()).isEqualTo(0.92);
		assertThat(response.getChoice("topic").getValue()).isEqualTo("delivery");
		assertThat(response.getChoice("topic").getConfidence()).isEqualTo(0.8);
		assertThat(response.getScore("satisfaction").getProbabilities()).hasSize(3);
	}

	@Test
	void theRequestIdModelAndUsageComeBackAsMetadata() {
		DecisionResponse response = model().call(DecisionModelTests.triage().build());

		assertThat(response.getMetadata().getId()).isEqualTo("ts-req-1");
		assertThat(response.getMetadata().getModel()).isEqualTo("jev-latest-pinned");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(312);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(48);
	}

	@Test
	void withoutARequestIdTheIdIsNull() {
		try (StandIn ollama = new StandIn(SystemOne::answers)) {
			DecisionResponse response = SystemOneDecisionModel
				.builder(SystemOneClient.builder().baseUrl(ollama.url()).apiKey("ollama").build())
				.build()
				.call("Is it late?", Map.of("late", YesNoQuestion.of("Is the order late?")));

			assertThat(response.getMetadata().getId()).isNull();
		}
	}

	@Test
	void fieldsBecomeAnObjectInTheirOrderAndAConversationBecomesMessages() {
		Map<String, Object> customer = new LinkedHashMap<>();
		customer.put("message", "Late again");
		customer.put("orders", 12);
		customer.put("late_deliveries", 3);
		AssistantMessage withToolCall = AssistantMessage.builder()
			.content("Let me look.")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "orderStatus",
					"{\"orderId\":\"A-1001\"}")))
			.build();

		model().call(DecisionInput.fields(customer), Map.of("repeated", YesNoQuestion.of("Did it happen before?")));
		model().call(DecisionInput.conversation(List.of(new UserMessage("Where is A-1001?"), withToolCall)),
				Map.of("answered", YesNoQuestion.of("Is the question answered?")));

		JsonNode fields = this.typeSafe.requests.get(0).body().get("state");
		assertThat(fields.propertyNames()).containsExactly("message", "orders", "late_deliveries");
		JsonNode messages = this.typeSafe.requests.get(1).body().get("state").get("messages");
		assertThat(messages.get(0).get("role").asString()).isEqualTo("user");
		assertThat(messages.get(0).get("content").asString()).isEqualTo("Where is A-1001?");
		assertThat(messages.get(1).get("tool_calls").get(0).get("arguments").get("orderId").asString())
			.isEqualTo("A-1001");
	}

	@Test
	void anImageMakesTheCallFailBeforeAnythingIsSent() {
		DecisionPrompt prompt = DecisionPrompt.builder()
			.content(TextPart.of("Is the item damaged?"),
					MediaPart.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1 }))))
			.question("damaged", YesNoQuestion.of("Is the item damaged?"))
			.build();

		assertThatIllegalArgumentException().isThrownBy(() -> model().call(prompt));
		assertThat(this.typeSafe.requests).isEmpty();
	}

	@Test
	void anAnswerToAQuestionThatWasNotAskedMakesTheCallFail() {
		try (StandIn odd = new StandIn((request) -> Reply.json("""
				{"model": "jev-1.13.0", "answers": {"weather": {"type": "noul", "noul": 0.5}},
				 "usage": {"input_tokens": 1, "output_tokens": 1}}"""))) {
			SystemOneDecisionModel model = SystemOneDecisionModel
				.builder(SystemOneClient.builder()
					.baseUrl(odd.url())
					.apiKey("test-key")
					.retryTemplate(SystemOneRetryUtils.noRetryTemplate())
					.build())
				.build();

			assertThatIllegalStateException()
				.isThrownBy(() -> model.call("Late?", Map.of("late", YesNoQuestion.of("Is it late?"))));
		}
	}

	@Test
	void theModelOfThePromptOptionsIsUsedForOneCall() {
		SystemOneDecisionModel model = model();

		model.call(DecisionModelTests.triage()
			.options(SystemOneDecisionOptions.builder().model("jev-preview").build())
			.build());
		model.call(DecisionModelTests.triage().build());

		assertThat(this.typeSafe.requests.get(0).body().get("model").asString()).isEqualTo("jev-preview");
		assertThat(this.typeSafe.requests.get(1).body().get("model").asString()).isEqualTo("jev-latest");
	}

	@Test
	void aBadRequestIsASystemOneBadRequestExceptionWithTheRequestId() {
		try (StandIn rejecting = new StandIn((request) -> Reply.json(400, """
				{"error": {"type": "invalid_request_error", "message": "no questions"}}""")
			.header("x-typesafe-request-id", "ts-req-9"))) {
			SystemOneClient client = SystemOneClient.builder().baseUrl(rejecting.url()).apiKey("test-key").build();

			assertThatExceptionOfType(SystemOneBadRequestException.class)
				.isThrownBy(() -> client.systemOne("x", Map.of("q", Noul.of("?"))))
				.satisfies((ex) -> {
					assertThat(ex.status()).isEqualTo(400);
					assertThat(ex.requestId()).isEqualTo("ts-req-9");
				});
			assertThat(rejecting.requests).hasSize(1);
		}
	}

	@Test
	void aRateLimitIsRetriedAfterTheWaitTheServerAsksFor() {
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		try (StandIn limited = new StandIn((request) -> (calls.incrementAndGet() == 1)
				? Reply.json(429, "{\"error\": {\"message\": \"slow down\"}}").header("Retry-After", "0")
				: SystemOne.answers(request))) {
			SystemOneClient client = SystemOneClient.builder().baseUrl(limited.url()).apiKey("test-key").build();

			SystemOneResponse response = client.systemOne("x", Map.of("q", Noul.of("Is it?")));

			assertThat(response.noulValue("q")).isEqualTo(0.92);
			assertThat(limited.requests).hasSize(2);
		}
	}

	@Test
	void theClientSendsARequestOfTheProtocolAsItIsAndReadsTheAnswers() {
		SystemOneResponse response = client().build()
			.systemOne(SystemOneRequest.builder()
				.state(JsonContent.object("message", "Help! My payouts have been failing for 3 days."))
				.model("jev-preview")
				.question("is_urgent", Noul.of("Does this convey urgency?"))
				.question("department",
						Choice.builder()
							.instructions("Which team should handle this?")
							.option("billing", "Payments, invoicing, refunds")
							.option("technical", "Bugs, outages, integrations")
							.build())
				.build());

		assertThat(response.noulValue("is_urgent")).isEqualTo(0.92);
		assertThat(response.choiceValue("department")).isEqualTo("billing");
		assertThat(response.choice("department").probabilityOf("technical")).isEqualTo(0.15);
		assertThat(response.choice("department").optionsAbove(0.1)).containsExactly("billing", "technical");
		assertThat(response.usage().inputTokens()).isEqualTo(312);
		assertThat(response.requestId()).isEqualTo("ts-req-1");
		assertThatExceptionOfType(SystemOneAnswerTypeException.class).isThrownBy(() -> response.choice("is_urgent"));
		assertThatExceptionOfType(SystemOneMissingAnswerException.class).isThrownBy(() -> response.noul("nothing"));
		assertThat(this.typeSafe.single().body().get("state").get("message").asString()).startsWith("Help!");
	}

	@Test
	void theClientListsTheModelsOfTheServer() {
		try (StandIn models = new StandIn((request) -> Reply.json("""
				{"models": [{"name": "jev-latest", "description": "The latest Jev",
				             "release_date": "2026-09-10T18:38:01.391457+00:00"}]}"""))) {
			SystemOneClient client = SystemOneClient.builder().baseUrl(models.url()).apiKey("test-key").build();

			assertThat(client.listModels()).extracting((model) -> model.name()).containsExactly("jev-latest");
			assertThat(models.single().method()).isEqualTo("GET");
			assertThat(models.single().path()).isEqualTo("/v1/models");
		}
	}

	@Test
	void theStarterMakesTheClientAndTheModelFromTheProperties() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SystemOneDecisionAutoConfiguration.class))
			.withPropertyValues("spring.ai.systemone.api-key=test-key",
					"spring.ai.systemone.base-url=" + this.typeSafe.url(),
					"spring.ai.systemone.decision.model=d1-omni:600m", "spring.ai.systemone.decision.provider=laya",
					"spring.ai.systemone.retry.max-retries=0")
			.run((context) -> {
				assertThat(context).hasSingleBean(SystemOneClient.class).hasSingleBean(SystemOneDecisionModel.class);
				assertThat(context.getBean(SystemOneClient.class).defaultModel()).isEqualTo("d1-omni:600m");

				context.getBean(DecisionModel.class).call("Late?", Map.of("late", YesNoQuestion.of("Is it late?")));

				assertThat(this.typeSafe.single().body().get("model").asString()).isEqualTo("d1-omni:600m");
			});
	}

	@Test
	void aClientBeanOfTheApplicationReplacesTheAutoConfiguredOne() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SystemOneDecisionAutoConfiguration.class))
			.withBean(SystemOneClient.class, () -> client().defaultModel("own-model").build())
			.run((context) -> {
				context.getBean(DecisionModel.class)
					.call("Late?", Map.of("topic", ChoiceQuestion.of("About?", "delivery", "other")));

				assertThat(this.typeSafe.single().body().get("model").asString()).isEqualTo("own-model");
			});
	}

}
