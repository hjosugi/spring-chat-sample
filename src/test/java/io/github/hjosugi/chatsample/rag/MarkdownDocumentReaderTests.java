package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The {@code MarkdownDocumentReader} of the Markdown reader page, on the page's own
 * {@code code.md} and on a page of the shop's: headings, paragraphs, code blocks, block
 * quotes and horizontal rules.
 */
class MarkdownDocumentReaderTests {

	private static final String JAVA_CODE = """
			package com.example.demo;

			import org.springframework.boot.SpringApplication;
			import org.springframework.boot.autoconfigure.SpringBootApplication;

			@SpringBootApplication
			public class DemoApplication {
			    public static void main(String[] args) {
			        SpringApplication.run(DemoApplication.class, args);
			    }
			}
			""";

	@Test
	void byDefaultCodeBlocksAreDocumentsOfTheirOwn() {
		List<Document> documents = new MarkdownDocumentReader("classpath:rag/code.md").get();

		assertThat(documents).extracting(Document::getText, Document::getMetadata)
			.containsExactly(tuple("This is a Java sample application:", Map.of()), tuple(JAVA_CODE, Map
				.of("category", "code_block", "lang", "java")), tuple(
						"Markdown also provides the possibility to use inline code formatting throughout the entire sentence."
								+ "Another possibility is to set block code without specific highlighting:",
						Map.of("category", "code_inline")),
					tuple("./mvnw spring-javaformat:apply\n", Map.of("category", "code_block", "lang", "")));
	}

	@Test
	void horizontalRulesCanStartANewDocument() {
		MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
			.withHorizontalRuleCreateDocument(true)
			.withAdditionalMetadata("filename", "code.md")
			.build();

		List<Document> documents = new MarkdownDocumentReader(new ClassPathResource("rag/code.md"), config).get();

		assertThat(documents).extracting(Document::getText)
			.contains(
					"Markdown also provides the possibility to use inline code formatting throughout the entire sentence.",
					"Another possibility is to set block code without specific highlighting:");
		assertThat(documents)
			.allSatisfy((document) -> assertThat(document.getMetadata()).containsEntry("filename", "code.md"));
	}

	/**
	 * The page says a code block then stays in the same document as the surrounding text.
	 * It stays with the text before it only: the reader still ends the document after the
	 * block, and the text after it starts a new one.
	 */
	@Test
	void anIncludedCodeBlockJoinsTheTextBeforeItButNotTheTextAfterIt() {
		MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
			.withHorizontalRuleCreateDocument(true)
			.withIncludeCodeBlock(true)
			.withIncludeBlockquote(false)
			.withAdditionalMetadata("filename", "code.md")
			.build();

		List<Document> documents = new MarkdownDocumentReader(new ClassPathResource("rag/code.md"), config).get();

		assertThat(documents).extracting(Document::getText)
			.containsExactly("This is a Java sample application: " + JAVA_CODE,
					"Markdown also provides the possibility to use inline code formatting throughout the entire sentence.",
					"Another possibility is to set block code without specific highlighting: ./mvnw spring-javaformat:apply\n");
	}

	/**
	 * The page says the reader keeps formatting such as inline code, lists and text
	 * styling in the content. It keeps their text and drops the markup: no asterisks, no
	 * backticks, no list markers, the items of a list run together on one line. A
	 * heading's category gives way to {@code code_inline} when its section has inline
	 * code, and the paragraph after a block quote joins the quote, without a space.
	 */
	@Test
	void theTextOfFormattedMarkdownIsKeptButNotItsMarkup() {
		List<Document> documents = new MarkdownDocumentReader("classpath:rag/brewing.md").get();

		assertThat(documents).extracting(Document::getText, Document::getMetadata)
			.containsExactly(
					tuple("Grind fresh beans, just before brewing, with the burr grinder.",
							Map.of("title", "Brewing", "category", "code_inline")),
					tuple("You need: a dripper a paper filter", Map.of("title", "Pour-over", "category", "header_2")),
					tuple("Rinse the filter with hot water first.Pour slowly, in circles.",
							Map.of("category", "blockquote")));
	}

	@Test
	void anIncludedBlockQuoteStaysWithTheTextAroundIt() {
		MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
			.withIncludeBlockquote(true)
			.build();

		List<Document> documents = new MarkdownDocumentReader(new ClassPathResource("rag/brewing.md"), config).get();

		assertThat(documents).hasSize(2);
		assertThat(documents.get(1).getText()).isEqualTo(
				"You need: a dripper a paper filter Rinse the filter with hot water first.Pour slowly, in circles.");
	}

	@Test
	void aPatternReadsSeveralFiles() {
		List<Document> documents = new MarkdownDocumentReader("classpath:help/*.md").get();

		assertThat(documents).hasSize(8);
	}

}
