package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code MultiQueryExpander} of the Modular RAG page: a chat model turns one query
 * into several, one per line of its answer.
 */
class MultiQueryExpanderTests {

	private static final String VARIANTS = """
			How do I start a Spring Boot application?
			Which command runs a Spring Boot jar?
			How to launch Spring Boot from the IDE?""";

	@Test
	void theExpanderAsksForTheNumberOfVariantsAndKeepsTheOriginalFirst() {
		ScriptedChatModel model = ScriptedChatModel.answering(VARIANTS);
		Query original = Query.builder()
			.text("How to run a Spring Boot app?")
			.context(Map.of("tenant", "acme"))
			.build();

		MultiQueryExpander queryExpander = MultiQueryExpander.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.numberOfQueries(3)
			.build();
		List<Query> queries = queryExpander.expand(original);

		assertThat(queries).extracting(Query::text)
			.containsExactly("How to run a Spring Boot app?", "How do I start a Spring Boot application?",
					"Which command runs a Spring Boot jar?", "How to launch Spring Boot from the IDE?");
		// The variants keep the history and the context of the original.
		assertThat(queries).allSatisfy((query) -> assertThat(query.context()).isEqualTo(original.context()));
		assertThat(lastText(model.lastPrompt()))
			.startsWith("You are an expert at information retrieval and search optimization.\n"
					+ "Your task is to generate 3 different versions of the given query.")
			.contains("Original query: How to run a Spring Boot app?");
	}

	@Test
	void theOriginalCanBeLeftOut() {
		MultiQueryExpander queryExpander = MultiQueryExpander.builder()
			.chatClientBuilder(ChatClient.builder(ScriptedChatModel.answering(VARIANTS)))
			.includeOriginal(false)
			.build();

		assertThat(queryExpander.expand(new Query("How to run a Spring Boot app?"))).extracting(Query::text)
			.containsExactly("How do I start a Spring Boot application?", "Which command runs a Spring Boot jar?",
					"How to launch Spring Boot from the IDE?");
	}

	/**
	 * Not on the page: an answer with another number of lines than asked for is thrown
	 * away, and the original query is all that is searched.
	 */
	@Test
	void anAnswerWithTheWrongNumberOfLinesLeavesOnlyTheOriginalQuery() {
		Query original = new Query("How to run a Spring Boot app?");
		MultiQueryExpander queryExpander = MultiQueryExpander.builder()
			.chatClientBuilder(
					ChatClient.builder(ScriptedChatModel.answering("1. ./mvnw spring-boot:run\n2. java -jar")))
			.numberOfQueries(3)
			.build();

		assertThat(queryExpander.expand(original)).containsExactly(original);
	}

	@Test
	void aCustomPromptTemplateReplacesTheDefaultOne() {
		ScriptedChatModel model = ScriptedChatModel.answering("a\nb");

		MultiQueryExpander.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.promptTemplate(new PromptTemplate("Write {number} search queries, one per line, for: {query}"))
			.numberOfQueries(2)
			.build()
			.expand(new Query("How to run a Spring Boot app?"));

		assertThat(lastText(model.lastPrompt()))
			.isEqualTo("Write 2 search queries, one per line, for: How to run a Spring Boot app?");
	}

}
