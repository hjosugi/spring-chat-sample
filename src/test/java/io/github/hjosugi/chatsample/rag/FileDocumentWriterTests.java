package io.github.hjosugi.chatsample.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.writer.FileDocumentWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code FileDocumentWriter} of the writers page: the documents in a text file, with
 * markers and metadata if asked, appended if asked.
 * <p>
 * Each document is written as its formatted content, which with the default formatter is
 * the metadata, two line breaks and the text, even when no metadata is written: so every
 * document starts with an empty line and another, and nothing else separates two
 * documents.
 */
class FileDocumentWriterTests {

	private static final List<Document> PAGES = List.of(
			new Document("Grinders grind.", Map.of("page_number", 1, "end_page_number", 2)),
			new Document("Kettles pour.", Map.of("page_number", 3)));

	@TempDir
	Path dir;

	@Test
	void byDefaultTheTextsAreWrittenWithoutMetadataOrMarkers() throws Exception {
		Path file = this.dir.resolve("output.txt");

		new FileDocumentWriter(file.toString()).accept(PAGES);

		assertThat(Files.readString(file)).isEqualTo("\n\nGrinders grind.\n\nKettles pour.");
	}

	@Test
	void markersGiveTheIndexAndThePagesOfEachDocument() throws Exception {
		Path file = this.dir.resolve("output.txt");

		new FileDocumentWriter(file.toString(), true).accept(PAGES);

		assertThat(Files.readString(file)).isEqualTo("""

				### Doc: 0, pages:[1,2]


				Grinders grind.
				### Doc: 1, pages:[3,null]


				Kettles pour.""");
	}

	@Test
	void theMetadataModeAddsTheMetadataAndAppendingKeepsWhatWasThere() throws Exception {
		Path file = this.dir.resolve("output.txt");
		Files.writeString(file, "Before.");

		FileDocumentWriter writer = new FileDocumentWriter(file.toString(), true, MetadataMode.ALL, true);
		writer.accept(List.of(new Document("Grinders grind.", Map.of("source", "handbook.pdf"))));

		assertThat(Files.readString(file)).isEqualTo("""
				Before.
				### Doc: 0, pages:[null,null]
				source: handbook.pdf

				Grinders grind.""");
	}

	@Test
	void withoutAppendingTheFileIsOverwritten() throws Exception {
		Path file = this.dir.resolve("output.txt");
		Files.writeString(file, "Before.");

		new FileDocumentWriter(file.toString(), false, MetadataMode.NONE, false).write(PAGES);

		assertThat(Files.readString(file)).isEqualTo("\n\nGrinders grind.\n\nKettles pour.");
	}

}
