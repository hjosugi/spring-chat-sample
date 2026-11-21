package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

/**
 * Structured output through {@code entity(...)}, as the page "Structured Output" shows
 * it: the schema in the format instructions, generic types, the two reliability
 * switches, and {@code responseEntity(...)}. Each switch has a page of its own, verified
 * by {@link SchemaValidationTests} and {@link NativeStructuredOutputTests}.
 */
class StructuredOutputTests {

	private static final String FILMS = "{\"actor\":\"Tom Hanks\",\"movies\":[\"Forrest Gump\",\"Cast Away\"]}";

	@Test
	void entityAppendsTheSchemaOfTheTypeToTheUserMessageAndParsesTheAnswer() {
		ScriptedChatModel model = ScriptedChatModel.answering(FILMS);
		String schema = new BeanOutputConverter<>(ActorsFilms.class).getJsonSchema();

		ActorsFilms films = ChatClient.create(model)
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.entity(ActorsFilms.class);

		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(films.movies()).containsExactly("Forrest Gump", "Cast Away");
		String userText = model.lastPrompt().getUserMessage().getText();
		assertThat(userText).startsWith("Generate the filmography for a random actor." + System.lineSeparator())
			.contains("Your response should be in JSON format.")
			.contains(schema);
		assertThat(model.lastPrompt().getUserMessages()).hasSize(1);
	}

	@Test
	void entityWithAParameterizedTypeMapsListsAndMaps() {
		ChatClient lists = ChatClient.create(ScriptedChatModel.answering("[" + FILMS + "]"));
		ChatClient maps = ChatClient.create(ScriptedChatModel.answering("{\"hanks\":" + FILMS + "}"));

		List<ActorsFilms> list = lists.prompt()
			.user("Generate filmographies for three random actors.")
			.call()
			.entity(new ParameterizedTypeReference<List<ActorsFilms>>() {
			});
		Map<String, ActorsFilms> map = maps.prompt("Generate a filmography by key")
			.call()
			.entity(new ParameterizedTypeReference<Map<String, ActorsFilms>>() {
			});

		assertThat(list).extracting(ActorsFilms::actor).containsExactly("Tom Hanks");
		assertThat(map.get("hanks").movies()).hasSize(2);
	}

	@Test
	void withoutASwitchAMalformedAnswerFailsToParse() {
		ScriptedChatModel model = ScriptedChatModel.answering("Here is the filmography: " + FILMS);

		assertThatRuntimeException().isThrownBy(
				() -> ChatClient.create(model).prompt("Generate the filmography").call().entity(ActorsFilms.class));
		assertThat(model.prompts).hasSize(1);
	}

	@Test
	void validateSchemaAsksAgainUntilTheAnswerMatches() {
		AtomicInteger calls = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text((calls.incrementAndGet() < 3) ? "{\"actor\":\"Tom Hanks\"}" : FILMS));

		ActorsFilms films = ChatClient.create(model)
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(films.movies()).hasSize(2);
		assertThat(model.prompts).hasSize(3);
	}

	@Test
	void validateSchemaCallsTheModelAtMostFourTimes() {
		ScriptedChatModel model = ScriptedChatModel.answering("{\"actor\":\"Tom Hanks\"}");

		ActorsFilms films = ChatClient.create(model)
			.prompt("Generate the filmography for a random actor.")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(model.prompts).hasSize(4);
		// The last answer is converted as it is.
		assertThat(films.movies()).isNull();
	}

	@Test
	void bothSwitchesComposeAndFallBackForAModelWithoutNativeSupport() {
		AtomicInteger calls = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text((calls.incrementAndGet() < 2) ? "{}" : FILMS));

		ActorsFilms films = ChatClient.create(model)
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.entity(ActorsFilms.class, (spec) -> spec.useProviderStructuredOutput().validateSchema());

		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(model.prompts).hasSize(2);
		assertThat(model.prompts.get(0).getUserMessage().getText()).contains("Your response should be in JSON format.");
	}

	@Test
	void responseEntityGivesTheEntityAndTheTokensUsed() {
		ResponseEntity<ChatResponse, ActorsFilms> result = ChatClient.create(ScriptedChatModel.answering(FILMS))
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.responseEntity(ActorsFilms.class);

		ActorsFilms films = result.entity();
		ChatResponse raw = result.response();
		long totalTokens = raw.getMetadata().getUsage().getTotalTokens();
		assertThat(films.actor()).isEqualTo("Tom Hanks");
		assertThat(totalTokens).isPositive();
	}

	@Test
	void responseEntityTakesTheSwitchesToo() {
		AtomicInteger calls = new AtomicInteger();
		ScriptedChatModel model = new ScriptedChatModel(
				(prompt) -> ScriptedChatModel.text((calls.incrementAndGet() < 2) ? "{}" : FILMS));

		ResponseEntity<ChatResponse, ActorsFilms> result = ChatClient.create(model)
			.prompt("Generate the filmography")
			.call()
			.responseEntity(ActorsFilms.class, (spec) -> spec.validateSchema());

		assertThat(result.entity().actor()).isEqualTo("Tom Hanks");
		assertThat(model.prompts).hasSize(2);
		// The usage of both calls: a word for "{}", four for the films.
		assertThat(result.response().getMetadata().getUsage().getCompletionTokens()).isEqualTo(5);
	}

	@Test
	void theStreamingResponseHasNoEntity() {
		assertThat(ChatClient.StreamResponseSpec.class.getMethods()).extracting((method) -> method.getName())
			.containsExactlyInAnyOrder("content", "chatResponse", "chatClientResponse");
	}

	record ActorsFilms(String actor, List<String> movies) {
	}

}
