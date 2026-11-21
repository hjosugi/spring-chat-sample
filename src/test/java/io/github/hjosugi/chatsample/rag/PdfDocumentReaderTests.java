package io.github.hjosugi.chatsample.rag;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;

import static io.github.hjosugi.chatsample.rag.Handbook.squeezed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The PDF readers of the document reader pages, on the five pages of the
 * {@link Handbook}: one document per page or per group of pages, margins, page ranges and
 * a text formatter, and one document per entry of the table of contents.
 */
class PdfDocumentReaderTests {

	@TempDir
	static Path dir;

	private static String handbook;

	private static String withoutOutline;

	@BeforeAll
	static void writeTheHandbooks() {
		handbook = Handbook.write(dir.resolve("handbook.pdf"), true).toUri().toString();
		withoutOutline = Handbook.write(dir.resolve("no-outline.pdf"), false).toUri().toString();
	}

	@Test
	void thePageReaderMakesADocumentOfEachPage() {
		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder()
					.withPageTopMargin(0)
					.withPageExtractedTextFormatter(
							ExtractedTextFormatter.builder().withNumberOfTopTextLinesToDelete(0).build())
					.withPagesPerDocument(1)
					.build());

		List<Document> documents = pdfReader.read();

		assertThat(documents).hasSize(5);
		assertThat(squeezed(documents.get(1).getText()))
			.isEqualTo("Coffee Gear Handbook Kettles Gooseneck kettles pour slowly. Page 2");
		assertThat(documents.get(1).getMetadata()).containsEntry("page_number", 2)
			.containsEntry("file_name", "handbook.pdf")
			.doesNotContainKey("end_page_number");
	}

	@Test
	void pagesCanBeGroupedOrAllPutIntoOneDocument() {
		List<Document> pairs = new PagePdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder().withPagesPerDocument(2).build())
			.read();
		List<Document> all = new PagePdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder().withPagesPerDocument(PdfDocumentReaderConfig.ALL_PAGES).build())
			.read();

		assertThat(pairs)
			.extracting((document) -> document.getMetadata().get("page_number"),
					(document) -> document.getMetadata().get("end_page_number"))
			.containsExactly(org.assertj.core.groups.Tuple.tuple(1, 2), org.assertj.core.groups.Tuple.tuple(3, 4),
					org.assertj.core.groups.Tuple.tuple(5, null));
		assertThat(squeezed(pairs.get(0).getText())).contains("Grinders", "Page 1", "Kettles", "Page 2");
		assertThat(all).hasSize(1);
		assertThat(all.get(0).getMetadata()).containsEntry("page_number", 1).containsEntry("end_page_number", 5);
	}

	@Test
	void marginsInPointsLeaveTheHeaderAndTheFooterOut() {
		List<Document> documents = new PagePdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder().withPageTopMargin(60).withPageBottomMargin(50).build())
			.read();

		assertThat(squeezed(documents.get(0).getText())).isEqualTo("Grinders Burr grinders grind evenly.");
	}

	@Test
	void pageRangesReadOnlyTheirPagesAndADocumentNeverSpansAGap() {
		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder()
					.addPageRange(2, 3)
					// Beyond the last page: read to the end.
					.addPageRange(5, 20)
					.withPagesPerDocument(10)
					.build());

		List<Document> documents = pdfReader.read();

		assertThat(documents).hasSize(2);
		assertThat(documents.get(0).getMetadata()).containsEntry("page_number", 2).containsEntry("end_page_number", 3);
		assertThat(documents.get(1).getMetadata()).containsEntry("page_number", 5).doesNotContainKey("end_page_number");
		assertThat(squeezed(documents.get(1).getText())).contains("Beans").doesNotContain("Scales");
	}

	/**
	 * Pages are counted from 1, so skipping two pages before deleting skips one. And the
	 * text of a page ends with a line break, which counts as the last line: deleting one
	 * line at the bottom leaves the footer, it takes two.
	 */
	@Test
	void theTextFormatterDeletesLinesAtTheTopAndTheBottomOfEachPage() {
		List<Document> oneLine = new PagePdfDocumentReader(handbook, formatter(1, 1, 2)).read();
		List<Document> twoLines = new PagePdfDocumentReader(handbook, formatter(1, 2, 2)).read();

		assertThat(squeezed(oneLine.get(0).getText())).startsWith(Handbook.HEADER).endsWith("Page 1");
		assertThat(squeezed(oneLine.get(1).getText())).isEqualTo("Kettles Gooseneck kettles pour slowly. Page 2");
		assertThat(squeezed(twoLines.get(1).getText())).isEqualTo("Kettles Gooseneck kettles pour slowly.");
		// Aligned to the left, the body starts the text.
		assertThat(twoLines.get(4).getText().strip()).startsWith("Beans\n");
	}

	private static PdfDocumentReaderConfig formatter(int top, int bottom, int pagesToSkip) {
		return PdfDocumentReaderConfig.builder()
			.withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
				.withNumberOfTopTextLinesToDelete(top)
				.withNumberOfBottomTextLinesToDelete(bottom)
				.withNumberOfTopPagesToSkipBeforeDelete(pagesToSkip)
				.withLeftAlignment(true)
				.build())
			.build();
	}

	@Test
	void theParagraphReaderMakesADocumentOfEachEntryOfTheTableOfContents() {
		ParagraphPdfDocumentReader pdfReader = new ParagraphPdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder()
					.withPageTopMargin(0)
					.withPageExtractedTextFormatter(
							ExtractedTextFormatter.builder().withNumberOfTopTextLinesToDelete(0).build())
					.withPagesPerDocument(1)
					.build());

		List<Document> documents = pdfReader.read();

		assertThat(documents).extracting((document) -> document.getMetadata().get("title"))
			.containsExactly("Grinders", "Kettles", "Filters", "Scales", "Beans");
		// Levels count from 0, and a chapter ends on the page where the next one starts.
		assertThat(documents.get(2).getMetadata()).containsEntry("page_number", 3)
			.containsEntry("end_page_number", 4)
			.containsEntry("level", 0)
			.containsEntry("file_name", "handbook.pdf");
		assertThat(squeezed(documents.get(2).getText())).contains("Paper filters come in packs.");
	}

	@Test
	void theParagraphReaderIgnoresTheMarginsAndPagesPerDocument() {
		List<Document> documents = new ParagraphPdfDocumentReader(handbook,
				PdfDocumentReaderConfig.builder()
					.withPageTopMargin(60)
					.withPageBottomMargin(50)
					.withPagesPerDocument(5)
					.build())
			.read();

		assertThat(documents).hasSize(5);
		assertThat(squeezed(documents.get(0).getText())).contains(Handbook.HEADER, "Page 1");
	}

	@Test
	void theParagraphReaderRefusesPageRangesAndPdfsWithoutATableOfContents() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ParagraphPdfDocumentReader(handbook,
					PdfDocumentReaderConfig.builder().addPageRange(1, 2).build()))
			.withMessageContaining("Page ranges are not supported");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ParagraphPdfDocumentReader(withoutOutline, PdfDocumentReaderConfig.defaultConfig()))
			.withMessageContaining("Document outline (e.g. TOC) is null");
	}

}
