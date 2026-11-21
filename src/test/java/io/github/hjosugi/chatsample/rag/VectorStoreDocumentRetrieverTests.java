package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code VectorStoreDocumentRetriever} of the Modular RAG page: a similarity search
 * with a threshold, a number of documents and a metadata filter, static, dynamic or given
 * with the query.
 */
class VectorStoreDocumentRetrieverTests {

	private final SimpleVectorStore vectorStore = SimpleVectorStore.builder(new BagOfWordsEmbeddingModel()).build();

	VectorStoreDocumentRetrieverTests() {
		this.vectorStore.add(List.of(
				new Document("hansel", "The main character of the story is Hansel, lost in the woods.",
						Map.of("genre", "fairytale", "tenant", "acme", "location", "Black Forest")),
				new Document("anacletus", "Anacletus, the main character of the story, walks the Whispering Woods.",
						Map.of("genre", "fairytale", "tenant", "globex", "location", "Whispering Woods")),
				new Document("report", "The main character of the report is the KPI for the next semester.",
						Map.of("genre", "business", "tenant", "acme", "location", "Office"))));
	}

	@Test
	void theRetrieverSearchesWithItsThresholdTopKAndFilter() {
		DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
			.vectorStore(this.vectorStore)
			.similarityThreshold(0.73)
			.topK(5)
			.filterExpression(new FilterExpressionBuilder().eq("genre", "fairytale").build())
			.build();

		List<Document> documents = retriever.retrieve(new Query("Hansel, main character of the story, lost in woods"));

		assertThat(documents).extracting(Document::getId).containsExactly("hansel");
		assertThat(documents.get(0).getScore()).isGreaterThanOrEqualTo(0.73);
	}

	@Test
	void aSupplierFilterIsAskedOnEveryRetrieval() {
		AtomicReference<String> currentTenant = new AtomicReference<>();
		DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
			.vectorStore(this.vectorStore)
			.filterExpression(() -> new FilterExpressionBuilder().eq("tenant", currentTenant.get()).build())
			.build();

		currentTenant.set("acme");
		List<Document> forAcme = retriever.retrieve(new Query("What are the KPIs for the next semester?"));
		currentTenant.set("globex");
		List<Document> forGlobex = retriever.retrieve(new Query("What are the KPIs for the next semester?"));

		assertThat(forAcme).extracting(Document::getId).containsExactlyInAnyOrder("report", "hansel");
		assertThat(forGlobex).extracting(Document::getId).containsExactly("anacletus");
	}

	@Test
	void theFilterOfTheQueryTakesPrecedenceOverTheRetrieversOwn() {
		DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
			.vectorStore(this.vectorStore)
			.filterExpression(new FilterExpressionBuilder().eq("tenant", "acme").build())
			.build();

		Query query = Query.builder()
			.text("Who is Anacletus?")
			.context(Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "location == 'Whispering Woods'"))
			.build();
		List<Document> retrievedDocuments = documentRetriever.retrieve(query);

		assertThat(retrievedDocuments).extracting(Document::getId).containsExactly("anacletus");
	}

	@Test
	void theFilterOfTheQueryCanBeAnExpressionToo() {
		DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
			.vectorStore(this.vectorStore)
			.build();

		Query query = Query.builder()
			.text("main character")
			.context(Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION,
					new FilterExpressionBuilder().eq("genre", "business").build()))
			.build();

		assertThat(documentRetriever.retrieve(query)).extracting(Document::getId).containsExactly("report");
	}

}
