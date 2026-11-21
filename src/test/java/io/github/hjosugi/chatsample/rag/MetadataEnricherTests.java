package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher.SummaryType;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The enrichers of the transformers page, which ask a chat model for keywords or a
 * summary of each document and put the answer into its metadata.
 */
class MetadataEnricherTests {

	private static final String AI = "This is a document about artificial intelligence and its applications in modern technology.";

	@Test
	void theKeywordsOfTheModelGoIntoExcerptKeywords() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("artificial intelligence, technology, applications");
		KeywordMetadataEnricher enricher = KeywordMetadataEnricher.builder(chatModel).keywordCount(5).build();
		Document doc = new Document(AI);

		List<Document> enrichedDocs = enricher.apply(List.of(doc));

		assertThat(enrichedDocs.get(0).getMetadata()).containsEntry("excerpt_keywords",
				"artificial intelligence, technology, applications");
		assertThat(lastText(chatModel.lastPrompt()))
			.isEqualTo(AI + ". Give 5 unique keywords for this\ndocument. Format as comma separated. Keywords:");
		// The document itself is enriched, not a copy.
		assertThat(enrichedDocs.get(0)).isSameAs(doc);
	}

	@Test
	void aCustomTemplateReplacesTheDefaultAndTheCountIsIgnored() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("intelligence, technology");
		KeywordMetadataEnricher enricher = KeywordMetadataEnricher.builder(chatModel)
			.keywordCount(3)
			.keywordsTemplate(new PromptTemplate(
					"Extract 5 important keywords from the following text and separate them with commas:\n{context_str}"))
			.build();

		enricher.apply(List.of(new Document(AI)));

		assertThat(lastText(chatModel.lastPrompt()))
			.isEqualTo("Extract 5 important keywords from the following text and separate them with commas:\n" + AI);
	}

	@Test
	void theTwoConstructorsOfThePageMakeTheSameEnrichers() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("x");

		new KeywordMetadataEnricher(chatModel, 2).apply(List.of(new Document(AI)));
		new KeywordMetadataEnricher(chatModel, new PromptTemplate("Keywords of {context_str}"))
			.apply(List.of(new Document("beans")));

		assertThat(chatModel.prompts).extracting(ScriptedChatModel::lastText)
			.containsExactly(AI + ". Give 2 unique keywords for this\ndocument. Format as comma separated. Keywords:",
					"Keywords of beans");
	}

	/**
	 * Not on the page: the builder has no default count, so one without a count or a
	 * template fails.
	 */
	@Test
	void theKeywordCountMustBeOneOrMore() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("x");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> KeywordMetadataEnricher.builder(chatModel).keywordCount(0));
		assertThatIllegalArgumentException().isThrownBy(() -> KeywordMetadataEnricher.builder(chatModel).build());
	}

	@Test
	void summariesOfTheDocumentAndItsNeighboursGoIntoTheMetadata() {
		ScriptedChatModel chatModel = new ScriptedChatModel(
				(prompt) -> text(lastText(prompt).contains("document 1") ? "Summary 1" : "Summary 2"));
		SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(chatModel,
				List.of(SummaryType.PREVIOUS, SummaryType.CURRENT, SummaryType.NEXT));
		Document doc1 = new Document("Content of document 1");
		Document doc2 = new Document("Content of document 2");

		List<Document> enrichedDocs = enricher.apply(List.of(doc1, doc2));

		assertThat(enrichedDocs.get(0).getMetadata()).containsExactlyInAnyOrderEntriesOf(
				Map.of("section_summary", "Summary 1", "next_section_summary", "Summary 2"));
		assertThat(enrichedDocs.get(1).getMetadata()).containsExactlyInAnyOrderEntriesOf(
				Map.of("section_summary", "Summary 2", "prev_section_summary", "Summary 1"));
		assertThat(lastText(chatModel.prompts.get(0))).isEqualTo("""
				Here is the content of the section:


				Content of document 1

				Summarize the key topics and entities of the section.

				Summary:""");
	}

	/**
	 * Not on the page: unless told otherwise, the summary prompt carries the document's
	 * metadata along with its text ({@code MetadataMode.ALL}).
	 */
	@Test
	void byDefaultTheMetadataIsSummarizedWithTheText() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("Summary");
		Document document = new Document("Content of document 1", Map.of("source", "handbook.txt"));

		new SummaryMetadataEnricher(chatModel, List.of(SummaryType.CURRENT)).apply(List.of(document));
		new SummaryMetadataEnricher(chatModel, List.of(SummaryType.CURRENT), "Summarize: {context_str}",
				MetadataMode.NONE)
			.apply(List.of(new Document("Content of document 2", Map.of("source", "handbook.txt"))));

		assertThat(lastText(chatModel.prompts.get(0))).contains("source: handbook.txt\n\nContent of document 1");
		assertThat(lastText(chatModel.prompts.get(1))).isEqualTo("Summarize: \n\nContent of document 2");
	}

}
