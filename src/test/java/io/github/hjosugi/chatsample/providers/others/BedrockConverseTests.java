package io.github.hjosugi.chatsample.providers.others;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.bedrock.converse.api.BedrockCacheOptions;
import org.springframework.ai.bedrock.converse.api.BedrockCacheStrategy;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bedrock Converse page, for what does not stream: the AWS SDK's client can be
 * pointed at a stand-in with an endpoint override, so {@link BedrockProxyChatModel}
 * builds a real, signed Converse request. A stream would need the binary AWS event-stream
 * encoding, which the stand-in does not speak.
 */
class BedrockConverseTests {

	private static final String MODEL = "amazon.nova-lite-v1:0";

	@Test
	void aCallIsASignedConverseRequestWithTheInferenceConfiguration() {
		try (StandIn bedrock = new StandIn((request) -> reply("{\"text\":\"Five pirates.\"}", "end_turn"))) {
			ChatResponse response = model(bedrock)
				.call(new Prompt(List.of(new SystemMessage("Be brief."), new UserMessage("Name five pirates.")),
						BedrockChatOptions.builder()
							.model(MODEL)
							.temperature(0.4)
							.topP(0.9)
							.maxTokens(200)
							.stopSequences(List.of("END"))
							.topK(40)
							.frequencyPenalty(0.5)
							.requestParameters(Map.of("output_config", Map.of("effort", "low")))
							.build()));

			Request request = bedrock.single();
			assertThat(request.path()).isEqualTo("/model/" + MODEL + "/converse");
			assertThat(request.header("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=test-access/")
				.contains("/us-east-1/bedrock/aws4_request");
			JsonNode body = request.body();
			assertThat(body.get("messages").toString())
				.isEqualTo("[{\"role\":\"user\",\"content\":[{\"text\":\"Name five pirates.\"}]}]");
			assertThat(body.get("system").toString()).isEqualTo("[{\"text\":\"Be brief.\"}]");
			JsonNode inference = body.get("inferenceConfig");
			assertThat(inference.get("maxTokens").asInt()).isEqualTo(200);
			assertThat(inference.get("temperature").asDouble()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.0001));
			assertThat(inference.get("topP").asDouble()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.0001));
			assertThat(inference.get("stopSequences").toString()).isEqualTo("[\"END\"]");
			assertThat(body.toString()).as("the Converse API has no top-k or frequency penalty")
				.doesNotContain("topK", "top_k", "frequency");
			assertThat(body.get("additionalModelRequestFields").toString()).isEqualTo("{\"output_config\":{\"effort\":\"low\"}}");
			assertThat(response.getResult().getOutput().getText()).isEqualTo("Five pirates.");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
			assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
			assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(15);
		}
	}

	@Test
	void theChatClientRunsTheToolsTheModelAsksFor() {
		try (StandIn bedrock = new StandIn((request) -> {
			JsonNode messages = request.body().get("messages");
			return messages.get(messages.size() - 1).get("content").get(0).has("toolResult")
					? reply("{\"text\":\"Shipped.\"}", "end_turn")
					: reply("{\"toolUse\":{\"toolUseId\":\"tooluse_1\",\"name\":\"orderStatus\",\"input\":{\"orderId\":\"A-1\"}}}",
							"tool_use");
		})) {
			String answer = ChatClient.create(model(bedrock)).prompt("Where is A-1?").tools(new Shop()).call().content();

			assertThat(answer).isEqualTo("Shipped.");
			JsonNode tool = bedrock.requests.get(0).body().get("toolConfig").get("tools").get(0).get("toolSpec");
			assertThat(tool.get("name").asString()).isEqualTo("orderStatus");
			assertThat(tool.get("inputSchema").get("json").get("properties").has("orderId")).isTrue();
			JsonNode messages = bedrock.requests.get(1).body().get("messages");
			assertThat(messages.get(1).get("content").get(0).get("toolUse").get("toolUseId").asString()).isEqualTo("tooluse_1");
			assertThat(messages.get(2).get("content").get(0).get("toolResult").get("toolUseId").asString())
				.isEqualTo("tooluse_1");
		}
	}

	@Test
	void systemOnlyCachingAddsACachePointAfterTheSystemPrompt() {
		try (StandIn bedrock = new StandIn((request) -> reply("{\"text\":\"Hi\"}", "end_turn"))) {
			model(bedrock).call(new Prompt(List.of(new SystemMessage("You are an expert."), new UserMessage("Hello")),
					BedrockChatOptions.builder()
						.model(MODEL)
						.cacheOptions(BedrockCacheOptions.builder().strategy(BedrockCacheStrategy.SYSTEM_ONLY).build())
						.build()));

			assertThat(bedrock.single().body().get("system").toString())
				.isEqualTo("[{\"text\":\"You are an expert.\"},{\"cachePoint\":{\"type\":\"default\"}}]");
		}
	}

	@Test
	void nativeStructuredOutputBecomesTheOutputConfig() {
		try (StandIn bedrock = new StandIn((request) -> reply(
				"{\"text\":\"{\\\"actor\\\":\\\"Tom Hanks\\\",\\\"movies\\\":[\\\"Big\\\"]}\"}", "end_turn"))) {
			ActorFilms films = ChatClient.create(model(bedrock))
				.prompt("Filmography")
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.call()
				.entity(ActorFilms.class);

			JsonNode format = bedrock.single().body().get("outputConfig").get("textFormat");
			assertThat(format.get("type").asString()).isEqualTo("json_schema");
			assertThat(format.get("structure").get("jsonSchema").get("name").asString()).isEqualTo("response_schema");
			assertThat(format.get("structure").get("jsonSchema").get("schema").asString()).contains("movies");
			assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big")));
		}
	}

	@Test
	void thePropertiesReachTheRequestThroughTheAutoConfiguration() {
		try (StandIn bedrock = new StandIn((request) -> reply("{\"text\":\"Hi\"}", "end_turn"))) {
			new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(BedrockConverseProxyChatAutoConfiguration.class,
						ToolCallingAutoConfiguration.class))
				.withBean(BedrockRuntimeClient.class, () -> client(bedrock))
				.withPropertyValues("spring.ai.bedrock.aws.region=eu-west-1", "spring.ai.bedrock.aws.access-key=test-access",
						"spring.ai.bedrock.aws.secret-key=test-secret", "spring.ai.bedrock.converse.chat.model=" + MODEL,
						"spring.ai.bedrock.converse.chat.temperature=0.2", "spring.ai.bedrock.converse.chat.max-tokens=64",
						"spring.ai.bedrock.converse.chat.request-parameters.output_config.effort=low")
				.run((context) -> {
					context.getBean(BedrockProxyChatModel.class).call(new Prompt("Hello"));

					JsonNode body = bedrock.single().body();
					assertThat(bedrock.single().path()).isEqualTo("/model/" + MODEL + "/converse");
					assertThat(body.get("inferenceConfig").get("maxTokens").asInt()).isEqualTo(64);
					assertThat(body.get("additionalModelRequestFields").toString())
						.isEqualTo("{\"output_config\":{\"effort\":\"low\"}}");
				});
		}
	}

	static BedrockProxyChatModel model(StandIn standIn) {
		return BedrockProxyChatModel.builder()
			.bedrockRuntimeClient(client(standIn))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
			.region(Region.US_EAST_1)
			.options(BedrockChatOptions.builder().model(MODEL).build())
			.build();
	}

	static BedrockRuntimeClient client(StandIn standIn) {
		return BedrockRuntimeClient.builder()
			.endpointOverride(URI.create(standIn.url()))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
			.build();
	}

	static Reply reply(String content, String stopReason) {
		return Reply.json("{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[" + content + "]}},\"stopReason\":\""
				+ stopReason + "\",\"usage\":{\"inputTokens\":25,\"outputTokens\":15,\"totalTokens\":40},"
				+ "\"metrics\":{\"latencyMs\":100}}");
	}

	record ActorFilms(String actor, List<String> movies) {
	}

	static class Shop {

		@Tool(description = "Tells where an order is")
		String orderStatus(String orderId) {
			return orderId + " shipped";
		}

	}

}
