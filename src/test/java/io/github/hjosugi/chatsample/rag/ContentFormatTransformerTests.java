package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.transformer.ContentFormatTransformer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code ContentFormatTransformer} of the transformers page, of which the page says
 * only that it "ensures uniform content formats across all documents".
 */
class ContentFormatTransformerTests {

	/**
	 * Between two default formatters it adds the excluded keys of its own to those of
	 * each document, and keeps the document's templates: documents with different
	 * templates stay different.
	 */
	@Test
	void aDefaultFormatterOnlyAddsItsExcludedKeysToTheDocumentsOwn() {
		Document plain = new Document("Grinders grind.", Map.of("source", "a.md", "chunk_index", 0));
		Document bracketed = new Document("Kettles pour.", Map.of("source", "b.md", "chunk_index", 0));
		bracketed.setContentFormatter(
				DefaultContentFormatter.builder().withTextTemplate("[{metadata_string}] {content}").build());
		ContentFormatTransformer transformer = new ContentFormatTransformer(DefaultContentFormatter.builder()
			.withExcludedEmbedMetadataKeys("chunk_index")
			.withTextTemplate("{content} ({metadata_string})")
			.build());

		List<Document> documents = transformer.apply(List.of(plain, bracketed));

		assertThat(documents.get(0).getFormattedContent(MetadataMode.EMBED))
			.isEqualTo("source: a.md\n\nGrinders grind.");
		assertThat(documents.get(1).getFormattedContent(MetadataMode.EMBED)).isEqualTo("[source: b.md] Kettles pour.");
		// For inference the key is still there.
		assertThat(documents.get(0).getFormattedContent(MetadataMode.INFERENCE)).contains("chunk_index: 0");
	}

	@Test
	void anyOtherFormatterReplacesTheDocumentsOwn() {
		ContentFormatter titleOnly = (document, mode) -> document.getMetadata().get("title") + ": "
				+ document.getText();
		Document document = new Document("Grinders grind.", Map.of("title", "Grinders"));

		new ContentFormatTransformer(titleOnly).apply(List.of(document));

		assertThat(document.getFormattedContent()).isEqualTo("Grinders: Grinders grind.");
	}

}
