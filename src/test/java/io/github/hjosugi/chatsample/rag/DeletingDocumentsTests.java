package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser.FilterExpressionParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The deleting page, with {@code SimpleVectorStore}: by id, by a filter expression built
 * by hand or written as text, and the replacement of a document's old version.
 */
class DeletingDocumentsTests {

	private final SimpleVectorStore vectorStore = SimpleVectorStore.builder(new BagOfWordsEmbeddingModel()).build();

	private final Document bgDocument = new Document("The World is Big", Map.of("country", "Bulgaria"));

	private final Document nlDocument = new Document("The World is Big", Map.of("country", "Netherlands"));

	@Test
	void documentsAreDeletedByIdAndUnknownIdsAreIgnored() {
		this.vectorStore.add(List.of(this.bgDocument, this.nlDocument));

		this.vectorStore.delete(List.of(this.bgDocument.getId(), "no-such-id"));

		assertThat(all()).extracting(Document::getId).containsExactly(this.nlDocument.getId());
	}

	@Test
	void aFilterExpressionDeletesTheDocumentsItMatches() {
		this.vectorStore.add(List.of(this.bgDocument, this.nlDocument));

		Filter.Expression filterExpression = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("country"),
				new Filter.Value("Bulgaria"));
		this.vectorStore.delete(filterExpression);

		SearchRequest request = SearchRequest.builder()
			.query("World")
			.filterExpression("country == 'Bulgaria'")
			.build();
		assertThat(this.vectorStore.similaritySearch(request)).isEmpty();
		assertThat(all()).extracting(Document::getId).containsExactly(this.nlDocument.getId());
	}

	@Test
	void aFilterCanBeGivenAsText() {
		this.vectorStore.add(List.of(this.bgDocument, this.nlDocument));

		this.vectorStore.delete("country == 'Bulgaria'");

		assertThat(this.vectorStore.similaritySearch(SearchRequest.builder().query("World").topK(5).build()))
			.extracting((document) -> document.getMetadata().get("country"))
			.containsExactly("Netherlands");
	}

	@Test
	void anInvalidFilterThrows() {
		this.vectorStore.add(List.of(this.bgDocument));

		assertThatExceptionOfType(FilterExpressionParseException.class)
			.isThrownBy(() -> this.vectorStore.delete("country == "));
		assertThat(all()).hasSize(1);
	}

	@Test
	void theOldVersionOfADocumentIsReplacedByTheNewOne() {
		Document documentV1 = new Document("AI and Machine Learning Best Practices",
				Map.of("docId", "AIML-001", "version", "1.0", "lastUpdated", "2024-01-01"));
		Document documentV2 = new Document("AI and Machine Learning Best Practices - Updated",
				Map.of("docId", "AIML-001", "version", "2.0", "lastUpdated", "2024-02-01"));
		this.vectorStore.add(List.of(documentV1));

		Filter.Expression deleteOldVersion = new Filter.Expression(Filter.ExpressionType.AND,
				new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("docId"), new Filter.Value("AIML-001")),
				new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("version"), new Filter.Value("1.0")));
		this.vectorStore.delete(deleteOldVersion);
		this.vectorStore.add(List.of(documentV2));

		SearchRequest request = SearchRequest.builder()
			.query("AI and Machine Learning")
			.filterExpression("docId == 'AIML-001'")
			.build();
		assertThat(this.vectorStore.similaritySearch(request)).extracting(Document::getText)
			.containsExactly("AI and Machine Learning Best Practices - Updated");

		this.vectorStore.delete("docId == 'AIML-001' AND version == '2.0'");
		assertThat(this.vectorStore.similaritySearch(request)).isEmpty();
	}

	private List<Document> all() {
		return this.vectorStore.similaritySearch(SearchRequest.builder().query("World").topK(100).build());
	}

}
