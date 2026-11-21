package io.github.hjosugi.chatsample.rag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code JsoupDocumentReader} of the HTML reader page, on the page's own sample
 * document: which elements become text, and what goes into the metadata.
 */
class JsoupDocumentReaderTests {

	private final Resource resource = new ClassPathResource("rag/my-page.html");

	@Test
	void theSelectedElementsAreJoinedIntoOneDocumentWithTheConfiguredMetadata() {
		JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder()
			.selector("article p")
			.includeLinkUrls(true)
			.metadataTags(List.of("author", "date"))
			.additionalMetadata("source", "my-page.html")
			.build();

		List<Document> documents = new JsoupDocumentReader(this.resource, config).get();

		assertThat(documents).hasSize(1);
		assertThat(documents.get(0).getText())
			.isEqualTo("This is the main content of my web page.\nIt contains multiple paragraphs.");
		assertThat(documents.get(0).getMetadata()).containsEntry("title", "My Web Page")
			.containsEntry("author", "John Doe")
			.containsEntry("date", "2024-01-15")
			.containsEntry("source", "my-page.html")
			.doesNotContainKey("description");
	}

	/**
	 * Every link of the page, not only those of the selected elements, resolved against
	 * the URL of the resource, which for a file on the classpath is a {@code file:} URL.
	 */
	@Test
	void theLinksOfThePageAreResolvedAgainstTheUrlOfTheResource() throws Exception {
		JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder()
			.selector("article p")
			.includeLinkUrls(true)
			.build();

		Object links = new JsoupDocumentReader(this.resource, config).get().get(0).getMetadata().get("linkUrls");

		assertThat(links).isEqualTo(List.of("file:/", "file:/about", "https://www.example.com"));
		// Without a URL there is nothing to resolve against.
		Resource bytes = new ByteArrayResource(this.resource.getContentAsByteArray());
		assertThat(new JsoupDocumentReader(bytes, config).get().get(0).getMetadata().get("linkUrls"))
			.isEqualTo(List.of("", "", "https://www.example.com"));
	}

	@Test
	void byDefaultTheBodyIsOneDocumentWithTheDescriptionAndKeywords() {
		Document document = new JsoupDocumentReader(this.resource).get().get(0);

		assertThat(document.getText()).startsWith("Welcome to My Page Home About Main Content")
			.endsWith("© 2024 John Doe");
		assertThat(document.getMetadata()).containsEntry("description", "A sample web page for Spring AI")
			.containsEntry("keywords", "spring, ai, html, example")
			.doesNotContainKey("linkUrls");
	}

	@Test
	void groupingByElementMakesADocumentOfEachMatch() {
		JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder()
			.selector("p")
			.groupByElement(true)
			.build();

		List<Document> documents = new JsoupDocumentReader(this.resource, config).get();

		assertThat(documents).extracting(Document::getText)
			.containsExactly("This is the main content of my web page.", "It contains multiple paragraphs.",
					"© 2024 John Doe");
		assertThat(documents)
			.allSatisfy((document) -> assertThat(document.getMetadata()).containsEntry("title", "My Web Page"));
	}

	@Test
	void allElementsTakesTheWholeBodyWhateverTheSelector() {
		JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder().selector("h2").allElements(true).build();

		List<Document> documents = new JsoupDocumentReader(this.resource, config).get();

		assertThat(documents).hasSize(1);
		assertThat(documents.get(0).getText()).contains("Welcome to My Page", "It contains multiple paragraphs.");
	}

	@Test
	void theSeparatorJoinsTheSelectedElements() {
		JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder().selector("li").separator(" | ").build();

		assertThat(new JsoupDocumentReader(this.resource, config).get().get(0).getText()).isEqualTo("Home | About");
	}

	@Test
	void theCharsetDecodesThePage(@TempDir Path dir) throws Exception {
		Path latin1 = dir.resolve("latin1.html");
		Files.write(latin1, "<html><body><p>Café crème</p></body></html>".getBytes(StandardCharsets.ISO_8859_1));

		JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder().charset("ISO-8859-1").build();

		assertThat(new JsoupDocumentReader(new FileSystemResource(latin1), config).get().get(0).getText())
			.isEqualTo("Café crème");
	}

}
