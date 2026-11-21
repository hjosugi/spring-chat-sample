package io.github.hjosugi.chatsample.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.assertj.core.data.Offset;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.EmbeddedDocument;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The PGvector page against a real PGvector that Testcontainers starts, connected through
 * a service connection, so no URL, user or password is configured: the auto-configured
 * store and its properties, schema initialization, filters, upsert, a search with a query
 * embedding, batching, the native client and the manual configuration.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgVectorStoreTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-trixie").asCompatibleSubstituteFor("postgres"));

	/**
	 * What the shop sells, with ids PGvector takes: a UUID made from the name.
	 */
	private static final List<Document> GEAR = CoffeeGear.ALL.stream()
		.map((document) -> new Document(uuid(document.getId()), document.getText(), document.getMetadata()))
		.toList();

	private final BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(ServiceConnectionAutoConfiguration.class, DataSourceAutoConfiguration.class,
						JdbcTemplateAutoConfiguration.class, PgVectorStoreAutoConfiguration.class))
		.withUserConfiguration(Containers.class)
		.withBean(EmbeddingModel.class, () -> this.embeddings)
		.withPropertyValues("spring.ai.vectorstore.pgvector.initialize-schema=true",
				"spring.ai.vectorstore.pgvector.remove-existing-vector-store-table=true");

	@Test
	void theStoreCreatesTheExtensionsTheTableAndTheIndexWhenAsked() {
		this.runner.run((context) -> {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

			assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class)).contains("vector", "hstore",
					"uuid-ossp");
			assertThat(columns(jdbc, "public", "vector_store")).containsExactly(tuple("id", "uuid"),
					tuple("content", "text"), tuple("metadata", "json"), tuple("embedding", "vector"));
			// The dimensions of the embedding model, as none are configured.
			assertThat(dimensions(jdbc, "public.vector_store", "embedding"))
				.isEqualTo(BagOfWordsEmbeddingModel.DIMENSIONS);
			assertThat(indexDefinition(jdbc, "spring_ai_vector_index"))
				.contains("USING hnsw (embedding vector_cosine_ops)");
		});
	}

	@Test
	void documentsAreAddedAndFoundAsOnThePage() {
		this.runner.run((context) -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);
			List<Document> documents = List.of(
					new Document(
							"Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!",
							Map.of("meta1", "meta1")),
					new Document("The World is Big and Salvation Lurks Around the Corner"),
					new Document("You walk forward facing the past and you turn back toward the future.",
							Map.of("meta2", "meta2")));

			vectorStore.add(documents);
			List<Document> results = vectorStore
				.similaritySearch(SearchRequest.builder().query("Spring").topK(5).build());

			assertThat(vectorStore).isInstanceOf(PgVectorStore.class);
			assertThat(results).isNotEmpty();
			Document first = results.get(0);
			assertThat(first.getId()).isEqualTo(documents.get(0).getId());
			assertThat(first.getMetadata()).containsEntry("meta1", "meta1")
				.containsKey(DocumentMetadata.DISTANCE.value());
			assertThat(first.getScore()).isCloseTo(
					1.0 - ((Number) first.getMetadata().get(DocumentMetadata.DISTANCE.value())).doubleValue(),
					Offset.offset(1e-6));
		});
	}

	@Test
	void schemaInitializationIsOptIn() {
		this.runner
			.withPropertyValues("spring.ai.vectorstore.pgvector.initialize-schema=false",
					"spring.ai.vectorstore.pgvector.table-name=never_created")
			.run((context) -> {
				JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

				assertThat(columns(jdbc, "public", "never_created")).isEmpty();
				assertThatExceptionOfType(DataAccessException.class)
					.isThrownBy(() -> context.getBean(VectorStore.class).add(List.of(new Document("Grinders grind."))));
			});
	}

	@Test
	void thePropertiesNameTheSchemaTheTableTheColumnsTheIndexAndTheDistance() {
		this.runner
			.withPropertyValues("spring.ai.vectorstore.pgvector.schema-name=shop",
					"spring.ai.vectorstore.pgvector.table-name=help",
					"spring.ai.vectorstore.pgvector.schema-validation=true",
					"spring.ai.vectorstore.pgvector.content-field-name=body",
					"spring.ai.vectorstore.pgvector.metadata-field-name=meta",
					"spring.ai.vectorstore.pgvector.embedding-field-name=vec",
					"spring.ai.vectorstore.pgvector.index-type=IVFFlat",
					"spring.ai.vectorstore.pgvector.distance-type=EUCLIDEAN_DISTANCE",
					"spring.ai.vectorstore.pgvector.dimensions=" + BagOfWordsEmbeddingModel.DIMENSIONS,
					"spring.ai.vectorstore.pgvector.max-document-batch-size=2")
			.run((context) -> {
				JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
				VectorStore vectorStore = context.getBean(VectorStore.class);

				assertThat(columns(jdbc, "shop", "help")).containsExactly(tuple("id", "uuid"), tuple("body", "text"),
						tuple("meta", "json"), tuple("vec", "vector"));
				// L2 is IVFFlat's default operator class, so the definition does not name
				// it.
				assertThat(indexDefinition(jdbc, "help_index")).endsWith("USING ivfflat (vec)");
				vectorStore.add(GEAR);
				assertThat(jdbc.queryForObject("SELECT count(*) FROM shop.help", Integer.class)).isEqualTo(5);
				assertThat(vectorStore.similaritySearch(CoffeeGear.KETTLE.getText()).get(0).getText())
					.isEqualTo(CoffeeGear.KETTLE.getText());
			});
	}

	/**
	 * The page recommends the Euclidean distance for vectors of length 1. The store turns
	 * the similarity threshold into a distance as {@code 1 - threshold}, the same for
	 * every distance type, and the score into {@code 1 - distance}. For the Euclidean
	 * distance between such vectors, which goes up to 2, the threshold of 0.0 that
	 * accepts every document with the cosine distance leaves out every document whose
	 * cosine similarity is 0.5 or less, and the scores are not similarities.
	 */
	@Test
	void withTheEuclideanDistanceTheAcceptAllThresholdLeavesDocumentsOut() {
		this.runner.withAllowBeanDefinitionOverriding(true)
			.withBean(EmbeddingModel.class, () -> new NormalizedEmbeddingModel(this.embeddings))
			.withPropertyValues("spring.ai.vectorstore.pgvector.distance-type=EUCLIDEAN_DISTANCE",
					"spring.ai.vectorstore.pgvector.table-name=euclidean")
			.run((context) -> {
				VectorStore vectorStore = context.getBean(VectorStore.class);
				vectorStore.add(GEAR);

				// Cosine similarity 0.35 with the kettle: a distance of 1.14.
				List<Document> far = vectorStore
					.similaritySearch(SearchRequest.builder().query("kettle").similarityThresholdAll().build());
				// Cosine similarity 0.71: a distance of 0.77, a score of 0.23.
				List<Document> closer = vectorStore.similaritySearch(
						SearchRequest.builder().query("gooseneck kettle heats water").similarityThresholdAll().build());

				assertThat(far).isEmpty();
				assertThat(closer).singleElement().satisfies((document) -> {
					assertThat(document.getId()).isEqualTo(uuid("kettle"));
					assertThat(document.getScore()).isCloseTo(1 - Math.sqrt(2 - 2 * (4 / Math.sqrt(4 * 8))),
							Offset.offset(1e-3));
				});
			});
	}

	@Test
	void hnswIndexesTakeAtMost2000Dimensions() {
		this.runner
			.withPropertyValues("spring.ai.vectorstore.pgvector.table-name=too_wide",
					"spring.ai.vectorstore.pgvector.dimensions=2001")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("2000 dimensions"));
	}

	@Test
	void filtersAreTheSameTextAndDslAsForEveryStore() {
		this.runner.run((context) -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);
			vectorStore.add(List.of(new Document("The World by John", Map.of("author", "john", "article_type", "blog")),
					new Document("The World by Jill", Map.of("author", "jill", "article_type", "blog")),
					new Document("The World by Jill, in print", Map.of("author", "jill", "article_type", "book")),
					new Document("The World by Bob", Map.of("author", "bob", "article_type", "blog"))));
			FilterExpressionBuilder b = new FilterExpressionBuilder();

			List<Document> byText = vectorStore.similaritySearch(SearchRequest.builder()
				.query("The World")
				.topK(10)
				.filterExpression("author in ['john', 'jill'] && article_type == 'blog'")
				.build());
			List<Document> byDsl = vectorStore.similaritySearch(SearchRequest.builder()
				.query("The World")
				.topK(10)
				.filterExpression(b.and(b.in("author", "john", "jill"), b.eq("article_type", "blog")).build())
				.build());

			assertThat(byText).extracting(Document::getText)
				.containsExactlyInAnyOrder("The World by John", "The World by Jill");
			assertThat(byDsl).extracting(Document::getText)
				.containsExactlyInAnyOrderElementsOf(byText.stream().map(Document::getText).toList());
		});
	}

	/**
	 * The filters are portable in their syntax, not in what they mean for a document
	 * without the key: {@code SimpleVectorStore} takes a missing year for smaller than
	 * any other (see {@code MetadataFilterTests}), PGvector's JSON path does not match
	 * it.
	 */
	@Test
	void aDocumentWithoutTheKeyDoesNotMatchAComparison() {
		this.runner.run((context) -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);
			vectorStore.add(List.of(new Document("A film from 2019", Map.of("year", 2019)),
					new Document("A film without a year", Map.of("genre", "drama"))));

			List<Document> until2020 = vectorStore.similaritySearch(
					SearchRequest.builder().query("film").topK(10).filterExpression("year <= 2020").build());
			List<Document> notDrama = vectorStore.similaritySearch(
					SearchRequest.builder().query("film").topK(10).filterExpression("genre != 'drama'").build());

			assertThat(until2020).extracting(Document::getText).containsExactly("A film from 2019");
			assertThat(notDrama).isEmpty();
		});
	}

	@Test
	void upsertReplacesByIdAndEmbedsNothing() {
		this.runner.run((context) -> {
			PgVectorStore vectorStore = context.getBean(PgVectorStore.class);
			JdbcTemplate jdbc = vectorStore.<JdbcTemplate>getNativeClient().orElseThrow();
			String id = uuid("help/returns");
			this.embeddings.requests.clear();

			vectorStore.upsert(List.of(new EmbeddedDocument(new Document(id, "Returns within 30 days.", Map.of()),
					BagOfWordsEmbeddingModel.vector("returns 30 days"))));
			vectorStore.upsert(List.of(new EmbeddedDocument(new Document(id, "Returns within 60 days.", Map.of()),
					BagOfWordsEmbeddingModel.vector("returns 60 days"))));

			assertThat(jdbc.queryForList("SELECT content FROM public.vector_store", String.class))
				.containsExactly("Returns within 60 days.");
			assertThat(this.embeddings.requests).isEmpty();
		});
	}

	@Test
	void upsertChecksTheWholeBatchBeforeWritingAndTheIdMustBeAUuid() {
		this.runner.run((context) -> {
			PgVectorStore vectorStore = context.getBean(PgVectorStore.class);
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			EmbeddedDocument good = new EmbeddedDocument(new Document("Grinders grind."),
					BagOfWordsEmbeddingModel.vector("grinders"));
			EmbeddedDocument tooShort = new EmbeddedDocument(new Document("Kettles pour."), new float[] { 1, 2, 3 });

			assertThatIllegalArgumentException().isThrownBy(() -> vectorStore.upsert(List.of(good, tooShort)))
				.withMessage("Embedding at index 1 has dimension 3 but the store expects dimension "
						+ BagOfWordsEmbeddingModel.DIMENSIONS);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM public.vector_store", Integer.class)).isZero();
			assertThatIllegalArgumentException().isThrownBy(() -> vectorStore
				.upsert(List.of(new EmbeddedDocument(new Document("help/returns", "Returns.", Map.of()),
						BagOfWordsEmbeddingModel.vector("returns")))));
		});
	}

	/**
	 * {@code id-type} is a property of the auto-configuration that the page's table does
	 * not list; with {@code TEXT}, any id will do.
	 */
	@Test
	void aTextIdTypeTakesAnyId() {
		this.runner
			.withPropertyValues("spring.ai.vectorstore.pgvector.id-type=TEXT",
					"spring.ai.vectorstore.pgvector.table-name=text_ids")
			.run((context) -> {
				PgVectorStore vectorStore = context.getBean(PgVectorStore.class);

				vectorStore.upsert(List.of(new EmbeddedDocument(new Document("help/returns", "Returns.", Map.of()),
						BagOfWordsEmbeddingModel.vector("returns"))));

				assertThat(columns(context.getBean(JdbcTemplate.class), "public", "text_ids")).first()
					.isEqualTo(tuple("id", "text"));
				assertThat(vectorStore.similaritySearch("returns")).extracting(Document::getId)
					.containsExactly("help/returns");
			});
	}

	@Test
	void aSearchWithAQueryEmbeddingTakesEverythingButTheQueryFromTheRequest() {
		this.runner.run((context) -> {
			PgVectorStore vectorStore = context.getBean(PgVectorStore.class);
			vectorStore.add(GEAR);
			this.embeddings.requests.clear();

			List<Document> documents = vectorStore.similaritySearch(BagOfWordsEmbeddingModel.vector("burr grinder"),
					SearchRequest.builder()
						.query("gooseneck kettle")
						.topK(5)
						.similarityThreshold(0.1)
						.filterExpression("price > 50")
						.build());

			assertThat(documents).extracting(Document::getId).containsExactly(uuid("grinder"));
			assertThat(this.embeddings.requests).isEmpty();
			assertThatIllegalArgumentException()
				.isThrownBy(() -> vectorStore.similaritySearch(new float[] { 1, 0 }, SearchRequest.builder().build()))
				.withMessageContaining("Query embedding has dimension 2");
		});
	}

	@Test
	void documentsAreDeletedByIdAndByFilter() {
		this.runner.run((context) -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);
			vectorStore.add(GEAR);
			String kettle = vectorStore.similaritySearch("gooseneck kettle").get(0).getId();

			vectorStore.delete(List.of(kettle));
			vectorStore.delete("price < 10");
			vectorStore.delete(new FilterExpressionBuilder().eq("type", "scale").build());

			assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("coffee").topK(10).build()))
				.extracting((document) -> document.getMetadata().get("type"))
				.containsExactlyInAnyOrder("grinder", "beans");
		});
	}

	@Test
	void theNativeClientIsTheJdbcTemplate() {
		this.runner.run((context) -> {
			PgVectorStore vectorStore = context.getBean(PgVectorStore.class);

			assertThat(vectorStore.<JdbcTemplate>getNativeClient()).containsSame(context.getBean(JdbcTemplate.class));
		});
	}

	@Test
	void theStoreEmbedsInTheBatchesOfItsBatchingStrategyAndABeanReplacesTheDefault() {
		this.runner.run((context) -> {
			assertThat(context).getBean("pgVectorStoreBatchingStrategy").isInstanceOf(TokenCountBatchingStrategy.class);
			this.embeddings.requests.clear();

			context.getBean(VectorStore.class).add(GEAR);

			assertThat(this.embeddings.requests).singleElement()
				.satisfies((request) -> assertThat(request.getInstructions()).hasSize(5));
		});
		List<List<Document>> batched = new ArrayList<>();
		this.runner.withBean(BatchingStrategy.class, () -> (documents) -> {
			batched.add(documents);
			return documents.stream().map(List::of).toList();
		}).run((context) -> {
			assertThat(context).doesNotHaveBean("pgVectorStoreBatchingStrategy");
			this.embeddings.requests.clear();

			context.getBean(VectorStore.class).add(GEAR);

			assertThat(batched).hasSize(1);
			assertThat(this.embeddings.requests).hasSize(5);
		});
	}

	@Test
	void theStoreCanBeBuiltByHand() {
		this.runner.run((context) -> {
			JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

			PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, this.embeddings)
				.dimensions(BagOfWordsEmbeddingModel.DIMENSIONS)
				.distanceType(PgDistanceType.COSINE_DISTANCE)
				.indexType(PgIndexType.HNSW)
				.initializeSchema(true)
				.schemaName("public")
				.vectorTableName("manual_store")
				.maxDocumentBatchSize(10000)
				.contentFieldName("content")
				.metadataFieldName("metadata")
				.embeddingFieldName("embedding")
				.build();
			vectorStore.afterPropertiesSet();
			vectorStore.add(GEAR);

			assertThat(vectorStore.similaritySearch("burr grinder").get(0).getText())
				.isEqualTo(CoffeeGear.GRINDER.getText());
			assertThat(indexDefinition(jdbcTemplate, "manual_store_index")).contains("USING hnsw");
		});
	}

	@Test
	void theChatMemoryAdvisorFiltersOnTheConversationInPgvector() {
		this.runner.run((context) -> {
			ScriptedChatModel chatModel = ScriptedChatModel.answering("Noted.");
			ChatClient chatClient = ChatClient.builder(chatModel)
				.defaultAdvisors(VectorStoreChatMemoryAdvisor.builder(context.getBean(VectorStore.class)).build())
				.build();

			chatClient.prompt("My name is Bond.")
				.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "007"))
				.call()
				.content();
			chatClient.prompt("What is my name?")
				.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "006"))
				.call()
				.content();
			chatClient.prompt("What is my name?")
				.advisors((a) -> a.param(MemoryAdvisor.CONVERSATION_ID, "007"))
				.call()
				.content();

			assertThat(chatModel.prompts.get(1).getSystemMessage().getText()).doesNotContain("Bond");
			assertThat(chatModel.prompts.get(2).getSystemMessage().getText())
				.contains("<memory-entry type=\"user\">My name is Bond.</memory-entry>");
		});
	}

	private static String uuid(String key) {
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private static List<Tuple> columns(JdbcTemplate jdbc, String schema, String table) {
		return jdbc.query(
				"SELECT column_name, udt_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
				(rs, row) -> tuple(rs.getString(1), rs.getString(2)), schema, table);
	}

	private static int dimensions(JdbcTemplate jdbc, String table, String column) {
		return jdbc.queryForObject("SELECT atttypmod FROM pg_attribute WHERE attrelid = ?::regclass AND attname = ?",
				Integer.class, table, column);
	}

	private static String indexDefinition(JdbcTemplate jdbc, String index) {
		return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, index);
	}

	/**
	 * Vectors of length 1, as the page says the Euclidean distance needs.
	 */
	static final class NormalizedEmbeddingModel implements EmbeddingModel {

		private final EmbeddingModel delegate;

		NormalizedEmbeddingModel(EmbeddingModel delegate) {
			this.delegate = delegate;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			return new EmbeddingResponse(this.delegate.call(request)
				.getResults()
				.stream()
				.map((embedding) -> new Embedding(normalized(embedding.getOutput()), embedding.getIndex()))
				.toList());
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		@Override
		public int dimensions() {
			return this.delegate.dimensions();
		}

		private static float[] normalized(float[] vector) {
			double length = 0;
			for (float value : vector) {
				length += value * value;
			}
			float[] normalized = new float[vector.length];
			for (int i = 0; i < vector.length; i++) {
				normalized[i] = (float) (vector[i] / Math.sqrt(length));
			}
			return normalized;
		}

	}

	/**
	 * The container as a service connection. It outlives each context: JUnit starts it
	 * once for the class, so the context must not stop it when it closes.
	 */
	@Configuration(proxyBeanMethods = false)
	static class Containers {

		@Bean(destroyMethod = "")
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return postgres;
		}

	}

}
