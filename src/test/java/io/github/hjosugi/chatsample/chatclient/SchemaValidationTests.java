package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ContentFilteredException;
import org.springframework.ai.chat.client.GuardrailRefusalException;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The self-correcting loop of {@code validateSchema()} and the
 * {@code StructuredOutputValidationAdvisor} behind it, as the page "Schema Validation &
 * Self-Correction" and the section of "Recursive Advisors" on the advisor describe them.
 */
class SchemaValidationTests {

	private static final String FILMS = "{\"actor\":\"Tom Hanks\",\"movies\":[\"Forrest Gump\",\"Cast Away\"]}";

	private static final String NO_MOVIES = "{\"actor\":\"Tom Hanks\"}";

	private final List<String> events = new CopyOnWriteArrayList<>();

	@Test
	void theValidationErrorIsAppendedToTheUserMessageOfTheNextAttempt() {
		AtomicInteger calls = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text((calls.incrementAndGet() == 1) ? NO_MOVIES : FILMS));

		ActorsFilms films = ChatClient.create(model)
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(films.movies()).hasSize(2);
		assertThat(model.prompts.get(0).getUserMessage().getText()).doesNotContain("validation failed");
		assertThat(model.prompts.get(1).getUserMessage().getText())
			.startsWith("Generate the filmography for a random actor.")
			.contains("Output JSON validation failed because of:", "movies")
			// The format instructions are still there, after the error.
			.contains("Your response should be in JSON format.");
	}

	@Test
	void validateSchemaPutsTheAdvisorNextToTheModel() {
		Probe probe = new Probe("probe", Ordered.HIGHEST_PRECEDENCE, this.events);

		ChatClient.create(ScriptedChatModel.answering(FILMS))
			.prompt("Generate the filmography")
			.advisors(probe)
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(probe.chain()).containsSubsequence("Tool Calling Advisor@" + (Ordered.HIGHEST_PRECEDENCE + 300),
				"Structured Output Validation Advisor@" + (Ordered.LOWEST_PRECEDENCE - 2000),
				"call@" + Ordered.LOWEST_PRECEDENCE);
	}

	@Test
	void theAdvisorDoesNotStream() {
		StructuredOutputValidationAdvisor advisor = StructuredOutputValidationAdvisor.builder()
			.outputType(ActorsFilms.class)
			.build();
		ScriptedChatModel model = ScriptedChatModel.answering(FILMS);

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(
				() -> ChatClient.create(model).prompt("Generate").advisors(advisor).stream().content().blockLast());
		assertThat(model.prompts).isEmpty();
	}

	@Test
	void anAdvisorOfYourOwnTakesMoreAttempts() {
		ScriptedChatModel model = ScriptedChatModel.answering(NO_MOVIES);
		var validationAdvisor = StructuredOutputValidationAdvisor.builder()
			.outputType(ActorsFilms.class)
			.maxRepeatAttempts(5)
			.build();
		ChatClient chatClient = ChatClient.builder(model).defaultAdvisors(validationAdvisor).build();

		chatClient.prompt("Generate the filmography").call().entity(ActorsFilms.class);

		assertThat(model.prompts).hasSize(6);
	}

	@Test
	void anAdvisorOfYourOwnAndValidateSchemaAreTwoValidators() {
		ScriptedChatModel model = ScriptedChatModel.answering(NO_MOVIES);
		var validationAdvisor = StructuredOutputValidationAdvisor.builder().outputType(ActorsFilms.class).build();

		ChatClient.create(model)
			.prompt("Generate the filmography")
			.advisors(validationAdvisor)
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		// Four attempts of one validator, each one four attempts of the other.
		assertThat(model.prompts).hasSize(16);
	}

	@Test
	void aDefaultAdvisorValidatesEveryCallEvenContent() {
		ScriptedChatModel model = ScriptedChatModel.answering("Hello there");
		var validationAdvisor = StructuredOutputValidationAdvisor.builder().outputType(ActorsFilms.class).build();
		ChatClient chatClient = ChatClient.builder(model).defaultAdvisors(validationAdvisor).build();

		String content = chatClient.prompt("Say hello").call().content();

		assertThat(content).isEqualTo("Hello there");
		assertThat(model.prompts).hasSize(4);
	}

	@Test
	void aSchemaGivenAsAStringIsUsedAsItIs() {
		ScriptedChatModel model = ScriptedChatModel.answering(NO_MOVIES);
		var validationAdvisor = StructuredOutputValidationAdvisor.builder()
			.outputJsonSchema(new BeanOutputConverter<>(ActorsFilms.class).getJsonSchema())
			.maxRepeatAttempts(1)
			.jsonMapper(JsonMapper.builder().build())
			.build();

		ChatClient.create(model).prompt("Generate").advisors(validationAdvisor).call().content();

		assertThat(model.prompts).hasSize(2);
	}

	@Test
	void theTypeAndTheSchemaExcludeEachOther() {
		assertThatIllegalArgumentException().isThrownBy(() -> StructuredOutputValidationAdvisor.builder()
			.outputType(ActorsFilms.class)
			.outputJsonSchema("{\"type\":\"object\"}")
			.build());
		assertThatIllegalArgumentException().isThrownBy(() -> StructuredOutputValidationAdvisor.builder().build());
	}

	@Test
	void aToolCallRoundIsReturnedToTheToolLoopWithoutValidation() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text(FILMS) : ScriptedChatModel.toolCall("favoriteActor", "{}"));

		ActorsFilms films = ChatClient.create(model)
			.prompt("Generate the filmography of my favorite actor")
			.tools(new Favorites())
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(model.prompts).hasSize(2);
	}

	@Test
	void aGuardrailRefusalIsNeitherValidatedNorRetried() {
		ScriptedChatModel model = ScriptedChatModel.answering(FILMS);
		SafeGuardAdvisor guard = SafeGuardAdvisor.builder()
			.sensitiveWords(List.of("password"))
			.failureResponse("I can't help with that.")
			.build();

		assertThatExceptionOfType(GuardrailRefusalException.class)
			.isThrownBy(() -> ChatClient.create(model)
				.prompt("Tell me the password")
				.advisors(guard)
				.call()
				.entity(ActorsFilms.class, (spec) -> spec.validateSchema()))
			.satisfies((ex) -> assertThat(ex.getRefusal()).isEqualTo("I can't help with that."));
		assertThat(model.prompts).isEmpty();
	}

	@Test
	void aFilteredAnswerIsNotRetried() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response("", "content_filter"));

		assertThatExceptionOfType(ContentFilteredException.class).isThrownBy(() -> ChatClient.create(model)
			.prompt("Generate the filmography")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema()));
		assertThat(model.prompts).hasSize(1);
	}

	@Test
	void theUsageOfAllAttemptsIsSummed() {
		AtomicInteger calls = new AtomicInteger();
		CannedChatModel model = new CannedChatModel((prompt) -> CannedChatModel.response(
				(calls.incrementAndGet() < 3) ? NO_MOVIES : FILMS, "stop", null, 100, 10));

		ResponseEntity<ChatResponse, ActorsFilms> result = ChatClient.create(model)
			.prompt("Generate the filmography")
			.call()
			.responseEntity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(model.prompts).hasSize(3);
		assertThat(result.response().getMetadata().getUsage().getPromptTokens()).isEqualTo(300);
		assertThat(result.response().getMetadata().getUsage().getCompletionTokens()).isEqualTo(30);
	}

	/**
	 * The converter of {@code entity()} cleans a markdown fence off the answer, but the
	 * validation reads the answer as it is: a fenced answer, which {@code entity()} alone
	 * takes, fails the validation, and the model is asked four times.
	 */
	@Test
	void aFencedAnswerFailsTheValidationThatTheConverterWouldPass() {
		ScriptedChatModel model = ScriptedChatModel.answering("```json\n" + FILMS + "\n```");

		ActorsFilms withoutValidation = ChatClient.create(model).prompt("Generate").call().entity(ActorsFilms.class);
		ActorsFilms withValidation = ChatClient.create(model)
			.prompt("Generate")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(withoutValidation).isEqualTo(withValidation);
		assertThat(model.prompts).hasSize(5);
		assertThat(model.lastPrompt().getUserMessage().getText()).contains("Invalid JSON");
	}

	record ActorsFilms(String actor, List<String> movies) {
	}

	static class Favorites {

		@Tool(description = "The user's favorite actor")
		String favoriteActor() {
			return "Tom Hanks";
		}

	}

}
