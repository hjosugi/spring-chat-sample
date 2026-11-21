package io.github.hjosugi.chatsample.models;

import java.net.URI;
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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.content.Media;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.model.openai.autoconfigure.OpenAiDecisionAutoConfiguration;
import org.springframework.ai.openai.OpenAiDecisionModel;
import org.springframework.ai.openai.OpenAiDecisionOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The OpenAI Decision Models page: how the questions and the inputs map to the request
 * of the Decisions API, how the answers, the request id and the usage come back, and the
 * properties.
 */
class OpenAiDecisionTests {

	private final StandIn openAi = new StandIn(DecisionModelTests::triage);

	@AfterEach
	void stop() {
		this.openAi.close();
	}

	private OpenAiDecisionOptions.Builder connected() {
		return OpenAiDecisionOptions.builder().baseUrl(this.openAi.url() + "/v1").apiKey("test-key").maxRetries(0);
	}

	private OpenAiDecisionModel model() {
		return OpenAiDecisionModel.builder().options(connected().build()).build();
	}

	@Test
	void eachKindOfQuestionMapsToItsQuestionTypeInTheRequest() {
		model().call(DecisionModelTests.triage().build());

		StandIn.Request request = this.openAi.single();
		assertThat(request.path()).isEqualTo("/v1/decisions");
		assertThat(request.apiKey()).isEqualTo("test-key");
		JsonNode body = request.body();
		assertThat(body.get("model").asString()).isEqualTo("gpt-6-luna");
		assertThat(body.get("input").isString()).isTrue();
		JsonNode defect = body.get("questions").get(0);
		assertThat(defect.get("type").asString()).isEqualTo("predicate");
		assertThat(defect.get("name").asString()).isEqualTo("defect");
		assertThat(defect.get("instructions").asString()).isEqualTo("""
				Does the customer report a defect?
				Answer "yes" if: Part of the item does not work as it should
				Answer "no" if: The item works, and the message is about something else""");
		JsonNode topic = body.get("questions").get(1);
		assertThat(topic.get("type").asString()).isEqualTo("choice");
		assertThat(topic.get("choices").get(1).get("value").asString()).isEqualTo("product");
		assertThat(topic.get("choices").get(1).get("description").asString())
			.isEqualTo("A problem with an item the customer received");
		JsonNode satisfaction = body.get("questions").get(2);
		assertThat(satisfaction.get("type").asString()).isEqualTo("score");
		assertThat(satisfaction.get("levels")).extracting((level) -> level.get("label").asString())
			.containsExactly("unhappy", "neutral", "happy");
	}

	@Test
	void theSafetyIdentifierAndTheModelOfThePromptOptionsAreSent() {
		model().call(DecisionModelTests.triage()
			.options(OpenAiDecisionOptions.builder()
				.model("gpt-6-luna")
				.safetyIdentifier("user-12345")
				.customHeaders(Map.of("X-Tenant", "shop-1"))
				.build())
			.build());

		assertThat(this.openAi.single().body().get("safety_identifier").asString()).isEqualTo("user-12345");
		assertThat(this.openAi.single().header("X-Tenant")).isEqualTo("shop-1");
	}

	@Test
	void fieldsBecomeTextPartsOfOneUserMessageWithMapsAndListsAsJson() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("message", "Late again");
		fields.put("orders", 12);
		fields.put("last_order", Map.of("id", "A-1001"));
		fields.put("photo", MediaPart.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2, 3 }))));

		model().call(DecisionInput.fields(fields), Map.of("late", YesNoQuestion.of("Is the order late?")));

		JsonNode input = this.openAi.single().body().get("input");
		assertThat(input).hasSize(1);
		assertThat(input.get(0).get("role").asString()).isEqualTo("user");
		JsonNode content = input.get(0).get("content");
		assertThat(content).extracting((part) -> part.get("type").asString())
			.containsExactly("input_text", "input_text", "input_text", "input_text", "input_image");
		assertThat(content.get(0).get("text").asString()).isEqualTo("message: Late again");
		assertThat(content.get(1).get("text").asString()).isEqualTo("orders: 12");
		assertThat(content.get(2).get("text").asString()).isEqualTo("last_order: {\"id\":\"A-1001\"}");
		assertThat(content.get(3).get("text").asString()).isEqualTo("photo:");
		assertThat(content.get(4).get("image_url").asString()).isEqualTo("data:image/png;base64,AQID");
	}

	@Test
	void aConversationSendsEachMessageAfterItsRoleAndToolCallsAsText() {
		AssistantMessage withToolCall = AssistantMessage.builder()
			.content("Let me look.")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "orderStatus",
					"{\"orderId\":\"A-1001\"}")))
			.build();

		model().call(DecisionInput.conversation(List.of(new UserMessage("Where is A-1001?"), withToolCall)),
				Map.of("answered", YesNoQuestion.of("Does the last assistant message answer the user's question?")));

		JsonNode content = this.openAi.single().body().get("input").get(0).get("content");
		assertThat(content.get(0).get("text").asString()).isEqualTo("user: Where is A-1001?");
		assertThat(content.get(1).get("text").asString())
			.isEqualTo("assistant: Let me look.\n[calls orderStatus {\"orderId\":\"A-1001\"}]");
	}

	@Test
	void textWithAnImageSendsTheImageInline() {
		model().call(DecisionPrompt.builder()
			.content(TextPart.of("Is the item in the photo damaged?"),
					MediaPart.of(new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[] { 1, 2, 3 }))))
			.question("damaged", YesNoQuestion.of("Is the item damaged?"))
			.build());

		JsonNode content = this.openAi.single().body().get("input").get(0).get("content");
		assertThat(content.get(0).get("text").asString()).isEqualTo("Is the item in the photo damaged?");
		assertThat(content.get(1).get("image_url").asString()).isEqualTo("data:image/jpeg;base64,AQID");
	}

	@Test
	void aHostedImageUrlIsRejectedBeforeAnythingIsSent() {
		DecisionPrompt prompt = DecisionPrompt.builder()
			.content(TextPart.of("Is the item damaged?"),
					MediaPart.of(new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://images.example/1.png"))))
			.question("damaged", YesNoQuestion.of("Is the item damaged?"))
			.build();

		assertThatIllegalArgumentException().isThrownBy(() -> model().call(prompt))
			.withMessageContaining("only inline base64 images");
		assertThat(this.openAi.requests).isEmpty();
	}

	@Test
	void theRequestIdIsTheIdAndTheUsageHasTheCachedTokens() {
		DecisionResponse response = model().call(DecisionModelTests.triage().build());

		assertThat(response.getMetadata().getId()).isEqualTo("req_123");
		Usage usage = response.getMetadata().getUsage();
		assertThat(usage.getPromptTokens()).isEqualTo(120);
		assertThat(usage.getCacheReadInputTokens()).isEqualTo(100L);
		assertThat(usage.getCacheWriteInputTokens()).isEqualTo(20L);
	}

	@Test
	void aChoiceAnswerOutsideTheOptionsMakesTheCallFail() {
		try (StandIn odd = new StandIn((request) -> Reply.json("""
				{"model": "m", "answers": [{"type": "choice", "name": "topic", "choice": "weather", "confidence": 0.9,
				   "probabilities": [{"value": "weather", "probability": 1.0}]}],
				 %s}""".formatted(DecisionModelTests.USAGE)))) {
			DecisionModel model = OpenAiDecisionModel.builder()
				.options(OpenAiDecisionOptions.builder().baseUrl(odd.url() + "/v1").apiKey("test-key").build())
				.build();

			org.assertj.core.api.Assertions
				.assertThatIllegalStateException()
				.isThrownBy(() -> model.call("Will it rain?",
						Map.of("topic", ChoiceQuestion.of("What is it about?", "delivery", "other"))))
				.withMessageContaining("not one of the offered options");
		}
	}

	@Test
	void thePropertiesConfigureTheModelTheSafetyIdentifierAndTheConnection() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenAiDecisionAutoConfiguration.class))
			.withPropertyValues("spring.ai.openai.api-key=common-key",
					"spring.ai.openai.decision.base-url=" + this.openAi.url() + "/v1",
					"spring.ai.openai.decision.api-key=decision-key",
					"spring.ai.openai.decision.model=gpt-6-luna-mini",
					"spring.ai.openai.decision.safety-identifier=user-1", "spring.ai.openai.max-retries=0")
			.run((context) -> {
				context.getBean(DecisionModel.class)
					.call("Where is my order?", Map.of("topic", ChoiceQuestion.of("About?", "delivery", "other")));

				StandIn.Request request = this.openAi.single();
				assertThat(request.apiKey()).isEqualTo("decision-key");
				assertThat(request.body().get("model").asString()).isEqualTo("gpt-6-luna-mini");
				assertThat(request.body().get("safety_identifier").asString()).isEqualTo("user-1");
			});
	}

}
