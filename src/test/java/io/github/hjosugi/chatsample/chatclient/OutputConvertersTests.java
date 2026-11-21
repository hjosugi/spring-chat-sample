package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.CompositeResponseTextCleaner;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.ai.converter.ResponseTextCleaner;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.converter.ThinkingTagCleaner;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * The {@code StructuredOutputConverter} API below {@code entity(...)}, as the page
 * "Output Converters" shows it: the format instructions, the schema, the built-in
 * converters with the {@code ChatClient} and with the {@code ChatModel}, the cleaning of
 * the answer, and converters of your own.
 */
class OutputConvertersTests {

	private static final String FILMS = "{\"actor\":\"Tom Hanks\",\"movies\":[\"Forrest Gump\",\"Cast Away\"]}";

	@Test
	void theMapConverterAsksForJsonInTheseWords() {
		assertThat(new MapOutputConverter().getFormat()).isEqualTo("""
				Your response should be in JSON format.
				The data structure for the JSON should match this Java class: java.util.HashMap
				Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
				Remove the ```json markdown surrounding the output including the trailing "```".
				""");
		assertThat(new MapOutputConverter().getJsonSchema()).isEqualTo(StructuredOutputConverter.NO_JSON_SCHEMA);
	}

	@Test
	void theBeanConverterHasADraft202012SchemaInItsFormat() {
		BeanOutputConverter<ActorsFilms> converter = new BeanOutputConverter<>(ActorsFilms.class);

		assertThat(converter.getJsonSchema()).contains("https://json-schema.org/draft/2020-12/schema", "\"actor\"",
				"\"movies\"");
		assertThat(converter.getFormat()).contains("Your response should be in JSON format.", converter.getJsonSchema());
	}

	@Test
	void withTheChatModelTheFormatGoesIntoATemplate() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering(FILMS);
		StructuredOutputConverter<ActorsFilms> converter = new BeanOutputConverter<>(ActorsFilms.class);

		Prompt prompt = PromptTemplate.builder()
			.template("""
					Generate the filmography of 5 movies for {actor}.
					{format}
					""")
			.variables(Map.of("actor", "Tom Hanks", "format", converter.getFormat()))
			.build()
			.create();
		String text = chatModel.call(prompt).getResult().getOutput().getText();
		ActorsFilms actorsFilms = converter.convert(text);

		assertThat(actorsFilms.actor()).isEqualTo("Tom Hanks");
		assertThat(ScriptedChatModel.lastText(chatModel.lastPrompt()))
			.startsWith("Generate the filmography of 5 movies for Tom Hanks.\n")
			.contains(converter.getFormat());
	}

	@Test
	void theBeanConverterWorksWithTheChatClient() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering(FILMS);

		ActorsFilms actorsFilms = ChatClient.create(chatModel)
			.prompt()
			.user((u) -> u.text("Generate the filmography of 5 movies for {actor}.").param("actor", "Tom Hanks"))
			.call()
			.entity(ActorsFilms.class);

		assertThat(actorsFilms.movies()).containsExactly("Forrest Gump", "Cast Away");
		assertThat(chatModel.lastPrompt().getUserMessage().getText())
			.startsWith("Generate the filmography of 5 movies for Tom Hanks.");
	}

	@Test
	void theSchemaFollowsJsonPropertyOrder() {
		String schema = new BeanOutputConverter<>(OrderedFilms.class).getJsonSchema();

		assertThat(schema.indexOf("\"actor\"")).isLessThan(schema.indexOf("\"movies\""));
		assertThat(new BeanOutputConverter<>(ReversedFilms.class).getJsonSchema().indexOf("\"movies\""))
			.isLessThan(new BeanOutputConverter<>(ReversedFilms.class).getJsonSchema().indexOf("\"actor\""));
	}

	@Test
	void theBeanConverterTakesAGenericType() {
		BeanOutputConverter<List<ActorsFilms>> converter = new BeanOutputConverter<>(
				new ParameterizedTypeReference<List<ActorsFilms>>() {
				});

		List<ActorsFilms> films = converter.convert("[" + FILMS + "]");

		assertThat(films).extracting(ActorsFilms::actor).containsExactly("Tom Hanks");
		assertThat(converter.getJsonSchema()).contains("\"type\" : \"array\"");
	}

	@Test
	void theMapConverterWorksWithTheChatClientAndTheChatModel() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("{\"numbers\":[1,2,3,4,5,6,7,8,9]}");
		MapOutputConverter converter = new MapOutputConverter();

		Map<String, Object> viaClient = ChatClient.create(chatModel)
			.prompt()
			.user("Provide an array of the numbers from 1 to 9 under the key 'numbers'.")
			.call()
			.entity(new MapOutputConverter());
		Prompt prompt = PromptTemplate.builder()
			.template("""
					Provide an array of the numbers from 1 to 9 under the key 'numbers'.
					{format}
					""")
			.variables(Map.of("format", converter.getFormat()))
			.build()
			.create();
		Map<String, Object> viaModel = converter.convert(chatModel.call(prompt).getResult().getOutput().getText());

		assertThat(viaClient.get("numbers")).isEqualTo(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
		assertThat(viaModel).isEqualTo(viaClient);
	}

	@Test
	void aMapThroughAParameterizedTypeComesWithASchema() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("{\"numbers\":[1,2,3]}");

		Map<String, Object> map = ChatClient.create(chatModel)
			.prompt("Provide the numbers under the key 'numbers'.")
			.call()
			.entity(new ParameterizedTypeReference<Map<String, Object>>() {
			});

		assertThat(map).containsKey("numbers");
		assertThat(chatModel.lastPrompt().getUserMessage().getText()).contains("Here is the JSON Schema instance")
			.doesNotContain("java.util.HashMap");
	}

	@Test
	void theListConverterSplitsCommaSeparatedValues() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("vanilla, chocolate, strawberry, mint, coffee");
		ListOutputConverter converter = new ListOutputConverter();

		List<String> viaClient = ChatClient.create(chatModel)
			.prompt()
			.user((u) -> u.text("List five {subject}.").param("subject", "ice cream flavors"))
			.call()
			.entity(new ListOutputConverter());
		Prompt prompt = PromptTemplate.builder()
			.template("""
					List five {subject}.
					{format}
					""")
			.variables(Map.of("subject", "ice cream flavors", "format", converter.getFormat()))
			.build()
			.create();
		List<String> viaModel = converter.convert(chatModel.call(prompt).getResult().getOutput().getText());

		assertThat(viaClient).containsExactly("vanilla", "chocolate", "strawberry", "mint", "coffee");
		assertThat(viaModel).isEqualTo(viaClient);
		assertThat(chatModel.prompts.get(0).getUserMessage().getText()).contains("comma-separated values");
	}

	@Test
	void theDefaultCleanerRemovesThinkingAndAFenceAroundTheWholeAnswer() {
		BeanOutputConverter<ActorsFilms> converter = new BeanOutputConverter<>(ActorsFilms.class);

		assertThat(converter.convert("  <thinking>Hmm</thinking>\n```json\n" + FILMS + "\n```  ").actor())
			.isEqualTo("Tom Hanks");
		assertThat(converter.convert("<think>Qwen thinks</think>" + FILMS).actor()).isEqualTo("Tom Hanks");
		assertThat(converter.convert("<reasoning>Why</reasoning>" + FILMS).actor()).isEqualTo("Tom Hanks");
	}

	@Test
	void theDefaultCleanerDoesNotCutJsonOutOfProse() {
		BeanOutputConverter<ActorsFilms> converter = new BeanOutputConverter<>(ActorsFilms.class);

		assertThatRuntimeException()
			.isThrownBy(() -> converter.convert("Here's the filmography:\n```json\n" + FILMS + "\n```"));
	}

	/**
	 * The page says that {@code validateSchema()} works with such a converter as with
	 * {@code entity(ActorsFilms.class)}. The validation reads the answer before any
	 * cleaner: an answer that only the cleaner makes valid fails it, the model is asked
	 * four times, and the last answer is converted all the same.
	 */
	@Test
	void aCleanerOfYourOwnCutsTheJsonOutOfProseButValidateSchemaAsksFourTimes() {
		ResponseTextCleaner extractJson = (text) -> {
			if (text == null) {
				return null;
			}
			int start = text.indexOf('{');
			int end = text.lastIndexOf('}');
			return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
		};
		BeanOutputConverter<ActorsFilms> converter = new BeanOutputConverter<>(ActorsFilms.class, null,
				CompositeResponseTextCleaner.builder()
					.addCleaner(new ThinkingTagCleaner())
					.addCleaner(extractJson)
					.build());
		ScriptedChatModel chatModel = ScriptedChatModel
			.answering("<think>{draft}</think>Here's the filmography:\n```json\n" + FILMS + "\n```");

		ActorsFilms films = ChatClient.create(chatModel)
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.entity(converter, (spec) -> spec.validateSchema());

		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(converter.getJsonSchema()).isNotEmpty();
		assertThat(chatModel.prompts).hasSize(4);
		assertThat(chatModel.lastPrompt().getUserMessage().getText()).contains("Output JSON validation failed because of:");
	}

	@Test
	void aDelegatingConverterKeepsBothSwitchesWorking() {
		AuditingOutputConverter<ActorsFilms> converter = new AuditingOutputConverter<>(ActorsFilms.class);
		ScriptedChatModel chatModel = ScriptedChatModel.answering(FILMS);

		ActorsFilms films = ChatClient.create(chatModel)
			.prompt("Generate the filmography for a random actor.")
			.call()
			.entity(converter, (spec) -> spec.useProviderStructuredOutput().validateSchema());

		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(converter.seen).containsExactly(FILMS);
	}

	@Test
	void aConverterWithoutASchemaFailsWithValidateSchema() {
		ChatClient client = ChatClient.create(ScriptedChatModel.answering("actor: Tom Hanks"));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> client.prompt("Generate").call().entity(new YamlConverter(), (spec) -> spec.validateSchema()));
	}

	@Test
	void aConverterWithoutASchemaFallsBackToItsFormatWithUseProviderStructuredOutput() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("actor: Tom Hanks");

		String actor = ChatClient.create(chatModel)
			.prompt("Generate")
			.call()
			.entity(new YamlConverter(), (spec) -> spec.useProviderStructuredOutput());

		assertThat(actor).isEqualTo("Tom Hanks");
		assertThat(chatModel.lastPrompt().getUserMessage().getText()).endsWith("Answer in YAML with a key actor.");
	}

	record ActorsFilms(String actor, List<String> movies) {
	}

	@JsonPropertyOrder({ "actor", "movies" })
	record OrderedFilms(String actor, List<String> movies) {
	}

	@JsonPropertyOrder({ "movies", "actor" })
	record ReversedFilms(String actor, List<String> movies) {
	}

	/**
	 * The converter of the page, which keeps what it converts instead of logging it.
	 */
	static class AuditingOutputConverter<T> implements StructuredOutputConverter<T> {

		final List<String> seen = new CopyOnWriteArrayList<>();

		private final BeanOutputConverter<T> delegate;

		AuditingOutputConverter(Class<T> type) {
			this.delegate = new BeanOutputConverter<>(type);
		}

		@Override
		public String getFormat() {
			return this.delegate.getFormat();
		}

		@Override
		public String getJsonSchema() {
			return this.delegate.getJsonSchema();
		}

		@Override
		public T convert(String text) {
			this.seen.add(text);
			return this.delegate.convert(text);
		}

	}

	/**
	 * A converter for a format other than JSON, without a schema.
	 */
	static class YamlConverter implements StructuredOutputConverter<String> {

		@Override
		public String getFormat() {
			return "Answer in YAML with a key actor.";
		}

		@Override
		public String convert(String text) {
			return text.substring(text.indexOf(':') + 1).strip();
		}

	}

}
