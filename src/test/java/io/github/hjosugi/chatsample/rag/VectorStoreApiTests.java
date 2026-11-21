package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.EmbeddedDocument;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.VectorStoreRetriever;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Vector Store API page, with {@code SimpleVectorStore}: writing, searching with a
 * {@code SearchRequest}, the read-only {@code VectorStoreRetriever}, and the
 * user-supplied embeddings of {@code upsert} and of a query, which
 * {@code SimpleVectorStore} does not support and a store of the test's own, built on the
 * page's instructions, does.
 */
class VectorStoreApiTests {

	private final BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();

	@Test
	void addEmbedsEveryDocumentAndSimilaritySearchFindsTheClosestFirst() {
		SimpleVectorStore vectorStore = CoffeeGear.store(this.embeddings);

		assertThat(this.embeddings.embeddedTexts())
			.containsExactlyElementsOf(CoffeeGear.ALL.stream().map(Document::getText).toList());
		List<Document> similarDocuments = vectorStore.similaritySearch("gooseneck kettle");

		// Four, with any similarity: the defaults of a SearchRequest.
		assertThat(similarDocuments).hasSize(SearchRequest.DEFAULT_TOP_K);
		assertThat(similarDocuments.get(0).getId()).isEqualTo("kettle");
		assertThat(similarDocuments).extracting(Document::getScore).isSortedAccordingTo((a, b) -> Double.compare(b, a));
		assertThat(similarDocuments.get(0).getMetadata()).containsEntry("type", "kettle").containsEntry("price", 59);
	}

	@Test
	void aSearchRequestSetsHowManyAndHowSimilar() {
		SimpleVectorStore vectorStore = CoffeeGear.store(this.embeddings);

		SearchRequest request = SearchRequest.builder()
			.query("gooseneck kettle heats water")
			.topK(5)
			.similarityThreshold(0.7)
			.build();

		assertThat(vectorStore.similaritySearch(request)).extracting(Document::getId).containsExactly("kettle");
	}

	@Test
	void searchRequestHasDefaultsACopyAndChecks() {
		SearchRequest defaults = SearchRequest.builder().build();
		assertThat(defaults.getQuery()).isEmpty();
		assertThat(defaults.getTopK()).isEqualTo(4);
		assertThat(defaults.getSimilarityThreshold()).isEqualTo(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL).isZero();
		assertThat(defaults.getFilterExpression()).isNull();

		SearchRequest original = SearchRequest.builder()
			.query("kettle")
			.topK(2)
			.similarityThreshold(0.5)
			.filterExpression("type == 'kettle'")
			.build();
		SearchRequest copy = SearchRequest.from(original).similarityThresholdAll().build();
		assertThat(copy.getQuery()).isEqualTo("kettle");
		assertThat(copy.getTopK()).isEqualTo(2);
		assertThat(copy.getFilterExpression()).isEqualTo(original.getFilterExpression());
		assertThat(copy.getSimilarityThreshold()).isZero();

		assertThatIllegalArgumentException().isThrownBy(() -> SearchRequest.builder().topK(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> SearchRequest.builder().similarityThreshold(1.1));
	}

	@Test
	void aReadOnlyServiceGetsTheStoreAsAVectorStoreRetriever() {
		new ApplicationContextRunner().withBean(BagOfWordsEmbeddingModel.class)
			.withBean(VectorStore.class, () -> CoffeeGear.store(this.embeddings))
			.withBean(DocumentRetrievalService.class)
			.run((context) -> {
				DocumentRetrievalService service = context.getBean(DocumentRetrievalService.class);

				assertThat(service.findSimilarDocuments("burr grinder")).first()
					.extracting(Document::getId)
					.isEqualTo("grinder");
				assertThat(service.findSimilarDocumentsWithFilters("burr grinder", "kettle"))
					.extracting(Document::getId)
					.containsExactly("kettle");
			});
	}

	@Test
	void aRetrieverCanBeALambda() {
		VectorStoreRetriever retriever = (request) -> List.of(new Document("Found for " + request.getQuery()));

		assertThat(retriever.similaritySearch("kettle")).extracting(Document::getText)
			.containsExactly("Found for kettle");
		assertThatThrownBy(() -> retriever.similaritySearch(new float[] { 1 }, SearchRequest.builder().build()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void theSimpleStoreHasANameAndNoNativeClient() {
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(this.embeddings).build();

		assertThat(vectorStore.getName()).isEqualTo("SimpleVectorStore");
		assertThat(vectorStore.<Object>getNativeClient()).isEmpty();
	}

	/**
	 * {@code SimpleVectorStore} is not among the stores that support upsert or a search
	 * with a query embedding, and says so before it asks the embedding model anything.
	 */
	@Test
	void theSimpleStoreSupportsNeitherUpsertNorASearchWithAQueryEmbedding() {
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(this.embeddings).build();
		EmbeddedDocument entry = new EmbeddedDocument(new Document("some text"), new float[] { 1, 0 });

		assertThatThrownBy(() -> vectorStore.upsert(List.of(entry))).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("SimpleVectorStore does not support upsert");
		assertThatThrownBy(() -> vectorStore.similaritySearch(new float[] { 1, 0 }, SearchRequest.builder().build()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("SimpleVectorStore does not support similarity search with a query embedding");
		assertThat(this.embeddings.requests).isEmpty();
	}

	@Test
	void anEmbeddedDocumentRefusesAnEmptyOrNonFiniteVectorAndKeepsItsOwnCopy() {
		Document document = new Document("some text");
		assertThatIllegalArgumentException().isThrownBy(() -> new EmbeddedDocument(document, new float[0]));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new EmbeddedDocument(document, new float[] { 1, Float.NaN }))
			.withMessageContaining("NaN");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new EmbeddedDocument(document, new float[] { Float.POSITIVE_INFINITY }))
			.withMessageContaining("Infinity");

		float[] vector = { 1, 2 };
		EmbeddedDocument entry = new EmbeddedDocument(document, vector);
		vector[0] = 99;
		assertThat(entry.embedding()).containsExactly(1, 2);
	}

	@Test
	void aStoreThatOptsInUpsertsByIdWithoutEmbeddingAnything() {
		InMemoryUpsertStore vectorStore = new InMemoryUpsertStore(this.embeddings);
		String id = UUID.nameUUIDFromBytes("handbook/returns".getBytes(UTF_8)).toString();

		vectorStore.upsert(List.of(new EmbeddedDocument(new Document(id, "Returns within 30 days.", Map.of("v", 1)),
				BagOfWordsEmbeddingModel.vector("returns 30 days"))));
		vectorStore.upsert(List.of(new EmbeddedDocument(new Document(id, "Returns within 60 days.", Map.of("v", 2)),
				BagOfWordsEmbeddingModel.vector("returns 60 days"))));

		assertThat(vectorStore.rows).hasSize(1);
		assertThat(vectorStore.rows.get(id).document().getText()).isEqualTo("Returns within 60 days.");
		assertThat(this.embeddings.requests).isEmpty();
	}

	@Test
	void aSearchWithAQueryEmbeddingUsesTheRequestButNotItsQueryText() {
		InMemoryUpsertStore vectorStore = new InMemoryUpsertStore(this.embeddings);
		vectorStore.upsert(List.of(
				new EmbeddedDocument(new Document("kettle", CoffeeGear.KETTLE.getText(), Map.of()),
						BagOfWordsEmbeddingModel.vector(CoffeeGear.KETTLE.getText())),
				new EmbeddedDocument(new Document("grinder", CoffeeGear.GRINDER.getText(), Map.of()),
						BagOfWordsEmbeddingModel.vector(CoffeeGear.GRINDER.getText()))));

		float[] queryEmbedding = BagOfWordsEmbeddingModel.vector("burr grinder");
		List<Document> documents = vectorStore.similaritySearch(queryEmbedding,
				SearchRequest.builder().query("ignored kettle").topK(1).build());

		assertThat(documents).extracting(Document::getId).containsExactly("grinder");
		assertThat(this.embeddings.requests).isEmpty();
		assertThatIllegalArgumentException()
			.isThrownBy(() -> vectorStore.similaritySearch(new float[] { Float.NaN }, SearchRequest.builder().build()));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> vectorStore.similaritySearch(new float[0], SearchRequest.builder().build()));
	}

	@Test
	void aReferenceRowHasEmptyTextAndAPointerAndOnlyMediaIsRefused() {
		InMemoryUpsertStore vectorStore = new InMemoryUpsertStore(this.embeddings);
		String key = "video-42/frame-4711";
		Document reference = Document.builder()
			.id(UUID.nameUUIDFromBytes(key.getBytes(UTF_8)).toString())
			.text("")
			.metadata(DocumentMetadata.CONTENT_REF.value(), "s3://media/video-42.mp4#t=4711")
			.metadata("camera", "north-gate")
			.build();

		assertThat(reference.isText()).isTrue();
		vectorStore.upsert(List.of(new EmbeddedDocument(reference, new float[] { 1, 0, 0 })));
		assertThat(vectorStore.rows.get(reference.getId()).document().getMetadata()).containsEntry("content_ref",
				"s3://media/video-42.mp4#t=4711");

		Document media = new Document(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1 })),
				Map.of());
		assertThatIllegalArgumentException()
			.isThrownBy(() -> vectorStore.upsert(List.of(new EmbeddedDocument(media, new float[] { 1 }))))
			.withMessageContaining("Only text documents are supported");
	}

	@Test
	void aChunkWithTextAndAPointerGoesThroughAdd() {
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(this.embeddings).build();
		Document chunk = Document.builder()
			.text("The burr grinder chapter, in short.")
			.metadata(DocumentMetadata.CONTENT_REF.value(), "articles/1042")
			.build();

		vectorStore.add(List.of(chunk));

		assertThat(vectorStore.similaritySearch("burr grinder").get(0).getMetadata())
			.containsEntry(DocumentMetadata.CONTENT_REF.value(), "articles/1042");
	}

	/**
	 * The read-only service of the page.
	 */
	static class DocumentRetrievalService {

		private final VectorStoreRetriever retriever;

		DocumentRetrievalService(VectorStoreRetriever retriever) {
			this.retriever = retriever;
		}

		List<Document> findSimilarDocuments(String query) {
			return this.retriever.similaritySearch(query);
		}

		List<Document> findSimilarDocumentsWithFilters(String query, String type) {
			SearchRequest request = SearchRequest.builder()
				.query(query)
				.topK(5)
				.filterExpression("type == '" + type + "'")
				.build();
			return this.retriever.similaritySearch(request);
		}

	}

	/**
	 * A store that opts in to upsert and to searching with a query embedding as the page
	 * says a custom store does: {@code doUpsert} with {@code supportsUpsert}, and the
	 * search with {@code supportsQueryEmbeddingSearch}. It keeps its rows in a map.
	 */
	static final class InMemoryUpsertStore extends AbstractObservationVectorStore {

		final Map<String, EmbeddedDocument> rows = new ConcurrentHashMap<>();

		InMemoryUpsertStore(EmbeddingModel embeddingModel) {
			super(new Builder(embeddingModel));
		}

		@Override
		protected boolean supportsUpsert() {
			return true;
		}

		@Override
		protected void doUpsert(List<EmbeddedDocument> entries) {
			entries.forEach((entry) -> this.rows.put(entry.document().getId(), entry));
		}

		@Override
		protected boolean supportsQueryEmbeddingSearch() {
			return true;
		}

		@Override
		protected List<Document> doSimilaritySearch(float[] queryEmbedding, SearchRequest request) {
			return this.rows.values()
				.stream()
				.map((row) -> row.document()
					.mutate()
					.score(SimpleVectorStore.EmbeddingMath.cosineSimilarity(queryEmbedding, row.embedding()))
					.build())
				.filter((document) -> document.getScore() >= request.getSimilarityThreshold())
				.sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
				.limit(request.getTopK())
				.toList();
		}

		@Override
		public void doAdd(List<Document> documents) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void doDelete(List<String> idList) {
			idList.forEach(this.rows::remove);
		}

		@Override
		public List<Document> doSimilaritySearch(SearchRequest request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {
			return VectorStoreObservationContext.builder("in-memory", operationName);
		}

		static final class Builder extends AbstractVectorStoreBuilder<Builder> {

			Builder(EmbeddingModel embeddingModel) {
				super(embeddingModel);
			}

			@Override
			public InMemoryUpsertStore build() {
				return new InMemoryUpsertStore(getEmbeddingModel());
			}

		}

	}

}
