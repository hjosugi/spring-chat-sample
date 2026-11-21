package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The {@code ContextualQueryAugmenter} of the Modular RAG page: the query and the text of
 * the documents in one prompt, or, without documents, an instruction not to answer.
 */
class ContextualQueryAugmenterTests {

	private static final Query QUERY = new Query("How evenly does the burr grinder grind?");

	private static final List<Document> DOCUMENTS = List.of(CoffeeGear.GRINDER, CoffeeGear.SCALE);

	@Test
	void theQueryIsAugmentedWithTheTextOfTheDocuments() {
		QueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder().build();

		Query augmented = queryAugmenter.augment(QUERY, DOCUMENTS);

		assertThat(augmented.text())
			.startsWith("Context information is below.\n\n---------------------\n" + CoffeeGear.GRINDER.getText()
					+ System.lineSeparator() + CoffeeGear.SCALE.getText() + "\n---------------------\n")
			.endsWith("Query: " + QUERY.text() + "\n\nAnswer:\n");
		// Only the text: the metadata stays out.
		assertThat(augmented.text()).doesNotContain("price");
	}

	@Test
	void byDefaultAnEmptyContextInstructsTheModelNotToAnswer() {
		Query augmented = ContextualQueryAugmenter.builder().build().augment(QUERY, List.of());

		assertThat(augmented.text()).isEqualTo("The user query is outside your knowledge base.\n"
				+ "Politely inform the user that you can't answer it.\n");
	}

	@Test
	void allowingAnEmptyContextKeepsTheQuery() {
		QueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder().allowEmptyContext(true).build();

		assertThat(queryAugmenter.augment(QUERY, List.of())).isSameAs(QUERY);
	}

	@Test
	void bothPromptsCanBeReplaced() {
		ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
			.promptTemplate(new PromptTemplate("Answer {query} from:\n{context}"))
			.emptyContextPromptTemplate(new PromptTemplate("Say that the help pages do not cover it."))
			.build();

		assertThat(queryAugmenter.augment(QUERY, List.of(CoffeeGear.GRINDER)).text())
			.isEqualTo("Answer " + QUERY.text() + " from:\n" + CoffeeGear.GRINDER.getText());
		assertThat(queryAugmenter.augment(QUERY, List.of()).text())
			.isEqualTo("Say that the help pages do not cover it.");
	}

	@Test
	void aPromptWithoutTheQueryOrTheContextIsRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ContextualQueryAugmenter.builder().promptTemplate(new PromptTemplate("{query}")).build())
			.withMessageContaining("context");
	}

	/**
	 * Not on the page: a formatter decides how the documents become the context, such as
	 * with their metadata.
	 */
	@Test
	void aDocumentFormatterChoosesWhatOfTheDocumentsGoesIntoTheContext() {
		ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
			.documentFormatter((documents) -> documents.stream()
				.map((document) -> "[" + document.getId() + "] " + document.getText())
				.collect(Collectors.joining("\n")))
			.build();

		assertThat(queryAugmenter.augment(QUERY, DOCUMENTS).text())
			.contains("[grinder] " + CoffeeGear.GRINDER.getText(), "[scale] " + CoffeeGear.SCALE.getText());
	}

}
