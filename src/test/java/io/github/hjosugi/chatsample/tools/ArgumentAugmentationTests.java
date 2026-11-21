package io.github.hjosugi.chatsample.tools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.augment.AugmentedArgumentEvent;
import org.springframework.ai.tool.augment.AugmentedToolCallback;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.ai.tool.augment.ToolInputSchemaAugmenter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool argument augmentation: the model sees extra arguments in the schema, a consumer
 * gets them, and the tool gets only its own.
 */
class ArgumentAugmentationTests {

	private static final JsonMapper json = JsonMapper.shared();

	private static final String ARGUMENTS = "{\"city\":\"Lisbon\",\"innerThought\":\"The user asked for Lisbon\",\"confidence\":\"high\"}";

	@Test
	void theModelSeesTheExtraArgumentsAndTheConsumerGetsThem() {
		List<AugmentedArgumentEvent<AgentThinking>> events = new CopyOnWriteArrayList<>();
		Weather weather = new Weather();
		AugmentedToolCallbackProvider<AgentThinking> provider = AugmentedToolCallbackProvider.<AgentThinking>builder()
			.toolObject(weather)
			.argumentType(AgentThinking.class)
			.argumentConsumer(events::add)
			.removeExtraArgumentsAfterProcessing(true)
			.build();
		ScriptedChatModel model = DefiningToolsTests.callingOnce("weather", ARGUMENTS);

		String answer = ChatClient.builder(model).defaultTools(provider).build().prompt("Weather in Lisbon?").call().content();

		assertThat(answer).isEqualTo("\"Sunny in Lisbon\"");
		JsonNode schema = json.readTree(OfferedTools.definition(model.prompts.get(0), "weather").inputSchema());
		assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrder("city", "innerThought",
				"confidence");
		assertThat(schema.get("required").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("city",
				"innerThought");
		assertThat(schema.at("/properties/innerThought/description").asString())
			.isEqualTo("Your reasoning for calling this tool");
		assertThat(events).singleElement().satisfies((event) -> {
			assertThat(event.toolDefinition().name()).isEqualTo("weather");
			assertThat(event.rawInput()).isEqualTo(ARGUMENTS);
			assertThat(event.arguments()).isEqualTo(new AgentThinking("The user asked for Lisbon", "high"));
		});
		assertThat(weather.inputs).containsExactly("Lisbon");
	}

	@Test
	void keptExtraArgumentsReachTheToolInput() {
		List<String> inputs = new CopyOnWriteArrayList<>();
		AugmentedToolCallback<AgentThinking> kept = new AugmentedToolCallback<>(ToolCallbacks.from(new Weather())[0],
				AgentThinking.class, null, false) {

			@Override
			public String call(String toolInput) {
				inputs.add(toolInput);
				return super.call(toolInput);
			}

		};
		AugmentedToolCallback<AgentThinking> removed = new AugmentedToolCallback<>(ToolCallbacks.from(new Weather())[0],
				AgentThinking.class, null, true);

		assertThat(kept.call(ARGUMENTS)).isEqualTo("\"Sunny in Lisbon\"");
		assertThat(inputs).containsExactly(ARGUMENTS);
		assertThat(removed.call(ARGUMENTS)).isEqualTo("\"Sunny in Lisbon\"");
	}

	@Test
	void theSchemaAugmenterAddsFieldsOfARecordToASchema() {
		String schema = ToolInputSchemaAugmenter.augmentToolInputSchema(
				"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}",
				ToolInputSchemaAugmenter.toAugmentedArgumentTypes(AgentThinking.class));

		JsonNode node = json.readTree(schema);
		assertThat(node.get("properties").propertyNames()).containsExactlyInAnyOrder("city", "innerThought", "confidence");
		assertThat(node.get("required").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("city",
				"innerThought");
	}

	public record AgentThinking(
			@ToolParam(description = "Your reasoning for calling this tool", required = true) String innerThought,
			@ToolParam(description = "Confidence level (low, medium, high)", required = false) String confidence) {
	}

	static class Weather {

		final List<String> inputs = new CopyOnWriteArrayList<>();

		@Tool(description = "The weather in a city")
		String weather(String city) {
			this.inputs.add(city);
			return "Sunny in " + city;
		}

	}

}
