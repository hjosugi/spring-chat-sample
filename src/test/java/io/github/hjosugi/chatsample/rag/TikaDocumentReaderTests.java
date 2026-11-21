package io.github.hjosugi.chatsample.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static io.github.hjosugi.chatsample.rag.Handbook.squeezed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The {@code TikaDocumentReader} of the document reader pages: the text of a Word file, a
 * PDF or a web page in one document, and a ceiling on how much text it extracts.
 */
class TikaDocumentReaderTests {

	@TempDir
	static Path dir;

	private static Resource wordSample;

	/**
	 * A Word file of two paragraphs, as few parts as Word needs. Written by hand: POI's
	 * own writer needs the OOXML schemas, which Tika's dependencies leave out.
	 */
	@BeforeAll
	static void writeAWordFile() throws Exception {
		Path file = dir.resolve("word-sample.docx");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			entry(zip, "[Content_Types].xml", """
					<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
					<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
					<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
					<Default Extension="xml" ContentType="application/xml"/>
					<Override PartName="/word/document.xml" \
					ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
					</Types>""");
			entry(zip, "_rels/.rels", """
					<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
					<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
					<Relationship Id="rId1" \
					Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
					Target="word/document.xml"/>
					</Relationships>""");
			entry(zip, "word/document.xml", """
					<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
					<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
					<w:p><w:r><w:t>Returns</w:t></w:r></w:p>
					<w:p><w:r><w:t>Any item can be returned within 30 days of delivery.</w:t></w:r></w:p>
					</w:body></w:document>""");
		}
		wordSample = new FileSystemResource(file);
	}

	private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	@Test
	void aWordFileBecomesOneDocumentWithItsText() {
		TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(wordSample);

		List<Document> documents = tikaDocumentReader.read();

		assertThat(documents).hasSize(1);
		assertThat(squeezed(documents.get(0).getText()))
			.isEqualTo("Returns Any item can be returned within 30 days of delivery.");
		assertThat(documents.get(0).getMetadata()).containsEntry("source", "word-sample.docx");
	}

	@Test
	void theFormatIsDetectedFromTheContent() {
		Path pdf = Handbook.write(dir.resolve("handbook.pdf"), true);

		Document fromPdf = new TikaDocumentReader(new FileSystemResource(pdf)).read().get(0);
		Document fromHtml = new TikaDocumentReader(new ClassPathResource("rag/my-page.html")).read().get(0);

		assertThat(squeezed(fromPdf.getText())).contains("Grinders", "Burr grinders grind evenly.", "Beans");
		assertThat(squeezed(fromHtml.getText())).contains("This is the main content of my web page.")
			.doesNotContain("<p>");
	}

	@Test
	void aDocumentWithMoreTextThanTheLimitFailsInsteadOfFillingTheHeap() {
		assertThat(TikaDocumentReader.DEFAULT_MAX_CHARACTERS).isEqualTo(10_000_000);

		assertThatIllegalStateException()
			.isThrownBy(() -> new TikaDocumentReader(wordSample, 20, ExtractedTextFormatter.defaults()).read())
			.withMessage("Text extracted from 'word-sample.docx' exceeds the maximum of 20 characters");
	}

	@Test
	void aLimitOfMinusOneTurnsTheLimitOff() {
		List<Document> documents = new TikaDocumentReader(wordSample, -1, ExtractedTextFormatter.defaults()).read();

		assertThat(documents.get(0).getText()).contains("30 days of delivery");
	}

}
