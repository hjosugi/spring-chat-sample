package io.github.hjosugi.chatsample.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metadata filters page: the text syntax and the builder, applied by
 * {@code SimpleVectorStore} to a few films, and a shared index split into groups.
 */
class MetadataFilterTests {

	private final SimpleVectorStore vectorStore = SimpleVectorStore.builder(new BagOfWordsEmbeddingModel()).build();

	private final FilterExpressionBuilder b = new FilterExpressionBuilder();

	MetadataFilterTests() {
		this.vectorStore.add(
				List.of(film("bg-drama", Map.of("country", "BG", "genre", "drama", "year", 2021)),
						film("uk-drama", Map.of("country", "UK", "genre", "drama", "year", 2019, "author", "Ann")),
						film("uk-thriller",
								Map.of("country", "UK", "genre", "thriller", "year", 2022, "isActive", true)),
						film("nl-comedy", Map.of("country", "NL", "genre", "comedy", "year", 2023, "rating", 7.5)),
						film("nl-documentary", Map.of("country", "NL", "genre", "documentary", "year", 2020,
								"release-year", 2020, "released", 1700000000000L)),
						film("unknown", Map.of("country", "BG"))));
	}

	@ParameterizedTest
	@CsvSource(delimiter = ';', quoteCharacter = '`', textBlock = """
			country == 'BG' ; bg-drama,unknown
			genre == 'drama' && year >= 2020 ; bg-drama
			genre in ['comedy', 'documentary', 'drama'] ; bg-drama,uk-drama,nl-comedy,nl-documentary
			genre not in ['comedy', 'documentary'] ; bg-drama,uk-drama,uk-thriller,unknown
			(genre == 'drama' || genre == 'thriller') && year >= 2020 ; bg-drama,uk-thriller
			not (year < 2020) ; bg-drama,uk-thriller,nl-comedy,nl-documentary
			author is not null ; uk-drama
			author is null && genre == 'drama' ; bg-drama
			country == 'UK' && year >= 2020 && isActive == true ; uk-thriller
			genre NIN ['drama'] AND country != "NL" ; uk-thriller,unknown
			genre == 'comedy' OR rating > 7.0 ; nl-comedy
			released >= 1700000000000L ; nl-documentary
			'release-year' == 2020 ; nl-documentary
			""")
	void theTextSyntaxSelectsTheFilms(String filter, String films) {
		List<Document> results = this.vectorStore
			.similaritySearch(SearchRequest.builder().query("film").topK(10).filterExpression(filter).build());

		assertThat(results).extracting(Document::getId).containsExactlyInAnyOrder(films.split(","));
	}

	@Test
	void aLargeIntegerOrOneWithAnLIsALong() {
		FilterExpressionTextParser parser = new FilterExpressionTextParser();

		assertThat(value(parser.parse("released >= 1700000000000L"))).isEqualTo(1700000000000L);
		assertThat(value(parser.parse("released >= 1700000000000"))).isEqualTo(1700000000000L);
		assertThat(value(parser.parse("year >= 2020"))).isEqualTo(2020);
		assertThat(value(parser.parse("rating > 7.5"))).isEqualTo(7.5);
	}

	/**
	 * The parser takes {@code meta.country} as one key. {@code SimpleVectorStore} looks
	 * it up as it is written, so it matches a metadata entry with a dot in its name, not
	 * a country nested in {@code meta}.
	 */
	@Test
	void aDottedKeyIsOneKeyForTheSimpleStore() {
		Map<String, Object> nested = new HashMap<>();
		nested.put("meta", Map.of("country", "BG"));
		this.vectorStore.add(List.of(film("nested", nested), film("dotted", Map.of("meta.country", "BG"))));

		assertThat(new FilterExpressionTextParser().parse("meta.country == 'BG'").left())
			.isEqualTo(new Filter.Key("meta.country"));
		assertThat(this.vectorStore.similaritySearch(
				SearchRequest.builder().query("film").topK(10).filterExpression("meta.country == 'BG'").build()))
			.extracting(Document::getId)
			.containsExactly("dotted");
	}

	@Test
	void theBuilderMakesTheSameExpressionsAsTheText() {
		FilterExpressionTextParser parser = new FilterExpressionTextParser();

		assertThat(this.b.eq("country", "BG").build()).isEqualTo(parser.parse("country == 'BG'"));
		assertThat(this.b.and(this.b.eq("genre", "drama"), this.b.gte("year", 2020)).build())
			.isEqualTo(parser.parse("genre == 'drama' && year >= 2020"));
		assertThat(this.b.and(this.b.in("genre", "drama", "documentary"), this.b.not(this.b.lt("year", 2020))).build())
			.isEqualTo(parser.parse("genre in ['drama', 'documentary'] && not year < 2020"));
		assertThat(this.b.isNotNull("author").build()).isEqualTo(parser.parse("author is not null"));
		assertThat(this.b
			.and(this.b.group(this.b.or(this.b.eq("genre", "drama"), this.b.eq("genre", "thriller"))),
					this.b.gte("year", 2020))
			.build()).isEqualTo(parser.parse("(genre == 'drama' || genre == 'thriller') && year >= 2020"));
	}

	@Test
	void builtExpressionsFilterTheSameFilms() {
		Expression nullYear = this.b.isNull("year").build();
		Expression comedyOrDocumentaryFrom2021 = this.b
			.and(this.b.nin("genre", "drama", "thriller"), this.b.gt("year", 2021))
			.build();
		Expression notUk = this.b.ne("country", "UK").build();
		Expression until2020 = this.b.lte("year", 2020).build();

		assertThat(ids(nullYear)).containsExactly("unknown");
		assertThat(ids(comedyOrDocumentaryFrom2021)).containsExactly("nl-comedy");
		assertThat(ids(notUk)).containsExactlyInAnyOrder("bg-drama", "nl-comedy", "nl-documentary", "unknown");
		// For the simple store a missing value comes before any other: a film without a
		// year is "until 2020", as it is "not 'UK'" above.
		assertThat(ids(until2020)).containsExactlyInAnyOrder("uk-drama", "nl-documentary", "unknown");
	}

	@Test
	void aGroupTagOnEveryDocumentPartitionsASharedIndex() {
		SimpleVectorStore shared = SimpleVectorStore.builder(new BagOfWordsEmbeddingModel()).build();
		shared.add(List.of(new Document("Acme sells grinders.", Map.of("group", "acme")),
				new Document("Globex sells grinders.", Map.of("group", "globex"))));

		List<Document> results = shared.similaritySearch(
				SearchRequest.builder().query("grinders").filterExpression("group == '" + "acme" + "'").build());
		shared.delete(new FilterExpressionBuilder().eq("group", "acme").build());

		assertThat(results).extracting(Document::getText).containsExactly("Acme sells grinders.");
		assertThat(shared.similaritySearch("grinders")).extracting(Document::getText)
			.containsExactly("Globex sells grinders.");
	}

	private List<String> ids(Expression expression) {
		return this.vectorStore
			.similaritySearch(SearchRequest.builder().query("film").topK(10).filterExpression(expression).build())
			.stream()
			.map(Document::getId)
			.toList();
	}

	private static Object value(Expression expression) {
		return ((Filter.Value) expression.right()).value();
	}

	private static Document film(String id, Map<String, Object> metadata) {
		return new Document(id, "A film from " + id, metadata);
	}

}
