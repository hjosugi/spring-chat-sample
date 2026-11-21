package io.github.hjosugi.chatsample.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code ConcatenationDocumentJoiner} of the Modular RAG page: the documents of every
 * query and source in one list, without duplicates, the best first.
 */
class ConcatenationDocumentJoinerTests {

	@Test
	void duplicatesKeepTheirFirstOccurrenceAndTheBestScoreComesFirst() {
		Map<Query, List<List<Document>>> documentsForQuery = new LinkedHashMap<>();
		documentsForQuery.put(new Query("grinder"),
				List.of(List.of(scored("grinder", "first", 0.6), scored("scale", "scale", 0.2)),
						List.of(scored("kettle", "kettle", 0.9))));
		documentsForQuery.put(new Query("burr grinder"), List
			.of(List.of(scored("grinder", "second", 0.95), Document.builder().id("filters").text("no score").build())));

		DocumentJoiner documentJoiner = new ConcatenationDocumentJoiner();
		List<Document> documents = documentJoiner.join(documentsForQuery);

		assertThat(documents).extracting(Document::getId).containsExactly("kettle", "grinder", "scale", "filters");
		// The score is kept as is: the grinder found first, with 0.6, not the better
		// second.
		assertThat(documents.get(1).getText()).isEqualTo("first");
		assertThat(documents.get(1).getScore()).isEqualTo(0.6);
		// A document without a score counts as 0.0, so it comes last.
		assertThat(documents.get(3).getScore()).isNull();
	}

	private static Document scored(String id, String text, double score) {
		return Document.builder().id(id).text(text).score(score).build();
	}

}
