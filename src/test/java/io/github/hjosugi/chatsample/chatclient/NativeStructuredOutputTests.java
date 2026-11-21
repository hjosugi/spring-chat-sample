package io.github.hjosugi.chatsample.chatclient;

import java.util.List;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code useProviderStructuredOutput()}, as the page "Provider-Native Structured Output"
 * shows it, for the providers it names among OpenAI, Anthropic and Google GenAI: what
 * goes out to each of them, the fallback for a model without the support, the switch
 * for a whole client, and the JSON mode of OpenAI. The provider enforcing the schema is
 * beyond a stand-in.
 */
class NativeStructuredOutputTests {

	private static final String FILMS = "{\"actor\":\"Tom Hanks\",\"movies\":[\"Forrest Gump\",\"Cast Away\"]}";

	private static final String FORMAT_INSTRUCTIONS = "Your response should be in JSON format.";

	@Test
	void openAiGetsTheSchemaAsAJsonSchemaResponseFormat() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText(FILMS)))) {
			ActorsFilms films = ChatClient.create(Providers.openAi(openAi))
				.prompt()
				.user("Generate the filmography for a random actor.")
				.call()
				.entity(ActorsFilms.class, (spec) -> spec.useProviderStructuredOutput());

			JsonNode request = openAi.single().body();
			assertThat(films.actor()).isEqualTo("Tom Hanks");
			assertThat(request.get("response_format").get("type").asString()).isEqualTo("json_schema");
			JsonNode schema = request.get("response_format").get("json_schema").get("schema");
			assertThat(schema.get("properties").has("actor")).isTrue();
			assertThat(schema.get("properties").has("movies")).isTrue();
			assertThat(lastUserText(request.get("messages"))).isEqualTo("Generate the filmography for a random actor.");
		}
	}

	@Test
	void withoutTheSwitchOpenAiGetsTheFormatInstructions() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText(FILMS)))) {
			ChatClient.create(Providers.openAi(openAi)).prompt("Generate the filmography").call().entity(ActorsFilms.class);

			JsonNode request = openAi.single().body();
			assertThat(request.has("response_format")).isFalse();
			assertThat(lastUserText(request.get("messages"))).contains(FORMAT_INSTRUCTIONS);
		}
	}

	@Test
	void theResponsesApiOfOpenAiGetsTheSchemaAsATextFormat() {
		String reply = "{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1757000100,\"status\":\"completed\","
				+ "\"model\":\"gpt-5-mini\",\"output\":[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\","
				+ "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":" + Providers.quote(FILMS)
				+ ",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":20,\"output_tokens\":10,\"total_tokens\":30},"
				+ "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[]}";
		try (StandIn openAi = new StandIn((request) -> Reply.json(reply))) {
			OpenAiResponsesChatModel model = OpenAiResponsesChatModel.builder()
				.options(OpenAiResponsesChatOptions.builder()
					.baseUrl(openAi.url())
					.apiKey("test-key")
					.model("gpt-5-mini")
					.maxRetries(0)
					.build())
				.build();

			ActorsFilms films = ChatClient.create(model)
				.prompt("Generate the filmography for a random actor.")
				.call()
				.entity(ActorsFilms.class, (spec) -> spec.useProviderStructuredOutput());

			assertThat(films.actor()).isEqualTo("Tom Hanks");
			StandIn.Request request = openAi.single();
			assertThat(request.path()).isEqualTo("/responses");
			JsonNode format = request.body().get("text").get("format");
			assertThat(format.get("type").asString()).isEqualTo("json_schema");
			assertThat(format.get("schema").get("properties").has("movies")).isTrue();
			assertThat(request.text()).doesNotContain(FORMAT_INSTRUCTIONS);
		}
	}

	@Test
	void anthropicGetsTheSchemaAsAnOutputFormat() {
		try (StandIn anthropic = new StandIn((request) -> Reply.json(Providers.anthropicText(FILMS)))) {
			ActorsFilms films = ChatClient.create(Providers.anthropic(anthropic))
				.prompt()
				.user("Generate the filmography for a random actor.")
				.call()
				.entity(ActorsFilms.class, (spec) -> spec.useProviderStructuredOutput());

			JsonNode request = anthropic.single().body();
			assertThat(films.actor()).isEqualTo("Tom Hanks");
			JsonNode format = request.get("output_config").get("format");
			assertThat(format.get("type").asString()).isEqualTo("json_schema");
			assertThat(format.get("schema").get("properties").has("movies")).isTrue();
			assertThat(format.get("schema").has("$schema")).isFalse();
			assertThat(anthropic.single().text()).doesNotContain(FORMAT_INSTRUCTIONS);
		}
	}

	/**
	 * A type that is used twice gets its schema once, under {@code $defs}, and is
	 * referenced from there. Anthropic's options strip {@code $defs} from the schema and
	 * keep the references, which then point nowhere.
	 */
	@Test
	void anthropicLosesTheDefinitionsThatTheSchemaRefersTo() {
		String couple = "{\"first\":{\"name\":\"Tom\"},\"second\":{\"name\":\"Rita\"}}";
		try (StandIn anthropic = new StandIn((request) -> Reply.json(Providers.anthropicText(couple)))) {
			String generated = new BeanOutputConverter<>(Couple.class).getJsonSchema();

			ChatClient.create(Providers.anthropic(anthropic))
				.prompt("Name a couple")
				.call()
				.entity(Couple.class, (spec) -> spec.useProviderStructuredOutput());

			String sent = anthropic.single().body().get("output_config").get("format").get("schema").toString();
			assertThat(generated).contains("\"$defs\"", "\"$ref\" : \"#/$defs/Person\"");
			assertThat(sent).contains("\"$ref\":\"#/$defs/Person\"").doesNotContain("\"$defs\":");
		}
	}

	@Test
	void geminiGetsTheSchemaAsAResponseJsonSchema() {
		try (StandIn gemini = new StandIn((request) -> Reply.json(Providers.geminiText(FILMS)))) {
			ActorsFilms films = ChatClient.create(Providers.gemini(gemini))
				.prompt()
				.user("Generate the filmography for a random actor.")
				.call()
				.entity(ActorsFilms.class, (spec) -> spec.useProviderStructuredOutput());

			JsonNode config = gemini.single().body().get("generationConfig");
			assertThat(films.actor()).isEqualTo("Tom Hanks");
			assertThat(config.get("responseMimeType").asString()).isEqualTo("application/json");
			assertThat(config.get("responseJsonSchema").get("properties").has("movies")).isTrue();
			assertThat(gemini.single().text()).doesNotContain(FORMAT_INSTRUCTIONS);
		}
	}

	@Test
	void theThreeProvidersHaveOptionsWithStructuredOutputSupport() {
		try (StandIn standIn = new StandIn((request) -> Reply.json("{}"))) {
			List<ChatModel> models = List.of(Providers.openAi(standIn), Providers.anthropic(standIn),
					Providers.gemini(standIn));

			assertThat(models).allSatisfy(
					(model) -> assertThat(model.getOptions()).isInstanceOf(StructuredOutputChatOptions.class));
		}
	}

	@Test
	void aModelWithoutTheSupportGetsTheFormatInstructionsWithoutAWord() {
		ScriptedChatModel model = ScriptedChatModel.answering(FILMS);

		ActorsFilms films = ChatClient.create(model)
			.prompt("Generate the filmography")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.useProviderStructuredOutput());

		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(model.getOptions()).isNotInstanceOf(StructuredOutputChatOptions.class);
		assertThat(model.lastPrompt().getUserMessage().getText()).contains(FORMAT_INSTRUCTIONS);
	}

	@Test
	void theAdvisorParamTurnsItOnForARequest() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText(FILMS)))) {
			ChatClient.create(Providers.openAi(openAi))
				.prompt()
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.user("Generate the filmography for a random actor.")
				.call()
				.entity(ActorsFilms.class);

			assertThat(openAi.single().body().get("response_format").get("type").asString()).isEqualTo("json_schema");
		}
	}

	@Test
	void theAdvisorParamTurnsItOnForAWholeClient() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText(FILMS)))) {
			ChatClient chatClient = ChatClient.builder(Providers.openAi(openAi))
				.defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.build();

			chatClient.prompt("Generate the filmography").call().entity(ActorsFilms.class);
			chatClient.prompt("Say hello").call().content();

			assertThat(openAi.requests.get(0).body().get("response_format").get("type").asString())
				.isEqualTo("json_schema");
			// A call without an entity has no schema to send.
			assertThat(openAi.requests.get(1).body().has("response_format")).isFalse();
		}
	}

	/**
	 * The stand-in cannot tell that OpenAI rejects it: what is verified is that a list
	 * goes out as a top-level array, and that the container record of the page does not.
	 */
	@Test
	void aListGoesToOpenAiAsATopLevelArrayAndAContainerAsAnObject() {
		try (StandIn openAi = new StandIn((request) -> request.text().contains("\"films\"")
				? Reply.json(Providers.openAiText("{\"films\":[" + FILMS + "]}"))
				: Reply.json(Providers.openAiText("[" + FILMS + "]")))) {
			ChatClient client = ChatClient.create(Providers.openAi(openAi));

			client.prompt("Generate filmographies for Tom Hanks and Bill Murray.")
				.call()
				.entity(new ParameterizedTypeReference<List<ActorsFilms>>() {
				}, (spec) -> spec.useProviderStructuredOutput());
			FilmographyList result = client.prompt("Generate filmographies for Tom Hanks and Bill Murray.")
				.call()
				.entity(FilmographyList.class, (spec) -> spec.useProviderStructuredOutput());

			assertThat(schemaType(openAi.requests.get(0))).isEqualTo("array");
			assertThat(schemaType(openAi.requests.get(1))).isEqualTo("object");
			assertThat(result.films()).hasSize(1);
		}
	}

	@Test
	void theJsonModesOfOpenAiAreOptionsOfTheirOwn() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("{}")))) {
			ChatClient client = ChatClient.create(Providers.openAi(openAi));
			String schema = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}";

			client.prompt("Answer in JSON")
				.options(OpenAiChatOptions.builder()
					.responseFormat(OpenAiChatModel.ResponseFormat.builder()
						.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
						.jsonSchema(schema)
						.build()))
				.call()
				.content();

			JsonNode format = openAi.single().body().get("response_format");
			assertThat(format.get("type").asString()).isEqualTo("json_schema");
			assertThat(format.get("json_schema").get("schema").get("properties").has("answer")).isTrue();
		}
	}

	private static String schemaType(StandIn.Request request) {
		return request.body().get("response_format").get("json_schema").get("schema").get("type").asString();
	}

	private static String lastUserText(JsonNode messages) {
		JsonNode content = messages.get(messages.size() - 1).get("content");
		return content.isString() ? content.asString() : content.get(0).get("text").asString();
	}

	record ActorsFilms(String actor, List<String> movies) {
	}

	record FilmographyList(List<ActorsFilms> films) {
	}

	record Person(String name) {
	}

	record Couple(Person first, Person second) {
	}

}
