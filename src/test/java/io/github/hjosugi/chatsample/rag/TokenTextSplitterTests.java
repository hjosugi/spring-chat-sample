package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The {@code TokenTextSplitter} of the transformers page: chunks of a number of tokens,
 * cut at sentence ends, with an overlap if asked, and the metadata of the document they
 * come from.
 */
class TokenTextSplitterTests {

	private static final Encoding CL100K = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

	private static final Encoding O200K = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

	private static final String SENTENCES = sentences(40);

	@Test
	void textsThatFitInAChunkStayWholeWithTheirMetadata() {
		Document doc1 = new Document(
				"This is a long piece of text that needs to be split into smaller chunks for processing.",
				Map.of("source", "example.txt"));
		Document doc2 = new Document("Another document with content that will be split based on token count.",
				Map.of("source", "example2.txt"));

		TokenTextSplitter splitter = TokenTextSplitter.builder().build();
		List<Document> splitDocuments = splitter.apply(List.of(doc1, doc2));

		assertThat(splitDocuments).extracting(Document::getText).containsExactly(doc1.getText(), doc2.getText());
		assertThat(splitDocuments).extracting((document) -> document.getMetadata().get("source"))
			.containsExactly("example.txt", "example2.txt");
	}

	@Test
	void aLongerTextIsCutAtSentenceEndsIntoChunksOfAtMostTheChunkSize() {
		TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(50).withMinChunkSizeChars(20).build();

		List<Document> chunks = splitter.split(new Document(SENTENCES));

		assertThat(chunks).hasSizeGreaterThan(5);
		assertThat(chunks).allSatisfy((chunk) -> {
			assertThat(CL100K.countTokens(chunk.getText())).isLessThanOrEqualTo(50);
			assertThat(chunk.getText()).endsWith(".");
		});
		assertThat(String.join(" ", chunks.stream().map(Document::getText).toList())).isEqualTo(SENTENCES);
	}

	@Test
	void anOverlapKeepsASentenceOnABoundaryWholeInOneChunk() {
		String needle = " Set the timeout to 30 seconds.";
		StringBuilder text = new StringBuilder("coffee");
		// Put the sentence across the boundary at 50 tokens.
		while (CL100K.countTokens(text.toString()) < 47) {
			text.append(" coffee");
		}
		text.append(needle).append(" coffee".repeat(60));
		// Cut by token count alone, without looking for punctuation.
		TokenTextSplitter.Builder byTokens = TokenTextSplitter.builder()
			.withChunkSize(50)
			.withMinChunkSizeChars(10_000);

		List<Document> withoutOverlap = byTokens.build().split(new Document(text.toString()));
		List<Document> withOverlap = byTokens.withChunkOverlap(10).build().split(new Document(text.toString()));

		assertThat(withoutOverlap).noneSatisfy((chunk) -> assertThat(chunk.getText()).contains(needle.strip()));
		assertThat(withOverlap).anySatisfy((chunk) -> assertThat(chunk.getText()).contains(needle.strip()));
		assertThat(withOverlap)
			.allSatisfy((chunk) -> assertThat(CL100K.countTokens(chunk.getText())).isLessThanOrEqualTo(50));
	}

	@Test
	void theOverlapMustBeZeroOrMoreAndLessThanTheChunkSize() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TokenTextSplitter.builder().withChunkSize(50).withChunkOverlap(50).build());
		assertThatIllegalArgumentException().isThrownBy(() -> TokenTextSplitter.builder().withChunkOverlap(-1).build());
	}

	/**
	 * The page says the metadata is copied to every chunk. It is, and the splitter adds
	 * three keys of its own, which the page does not mention.
	 */
	@Test
	void everyChunkGetsTheMetadataOfItsDocumentAndThreeKeysOfTheSplitter() {
		Document document = new Document("handbook", SENTENCES, Map.of("source", "handbook.txt"));

		List<Document> chunks = TokenTextSplitter.builder()
			.withChunkSize(50)
			.withMinChunkSizeChars(20)
			.build()
			.split(document);

		assertThat(chunks).allSatisfy((chunk) -> assertThat(chunk.getMetadata()).containsEntry("source", "handbook.txt")
			.containsEntry("parent_document_id", "handbook")
			.containsEntry("total_chunks", chunks.size())
			.containsKey("chunk_index"));
		assertThat(chunks).extracting((chunk) -> chunk.getMetadata().get("chunk_index")).startsWith(0, 1, 2);
		assertThat(chunks).extracting(Document::getId).doesNotContain("handbook").doesNotHaveDuplicates();
	}

	@Test
	void theContentFormatterOfTheDocumentIsCopiedUnlessTurnedOff() {
		ContentFormatter formatter = (document, mode) -> "formatted";
		Document document = new Document("Short text.");
		document.setContentFormatter(formatter);
		TokenTextSplitter splitter = TokenTextSplitter.builder().build();

		Document copied = splitter.split(document).get(0);
		splitter.setCopyContentFormatter(false);
		Document notCopied = splitter.split(document).get(0);

		assertThat(copied.getContentFormatter()).isSameAs(formatter);
		assertThat(notCopied.getContentFormatter()).isSameAs(Document.DEFAULT_CONTENT_FORMATTER);
	}

	@Test
	void punctuationMarksOfAnotherLanguageMarkTheSentenceEnds() {
		String chinese = "我们的咖啡豆每周一烘焙。磨豆机可以研磨得很均匀。手冲壶可以慢慢地倒水。滤纸每包一百张。".repeat(6);

		List<Document> chunks = TokenTextSplitter.builder()
			.withChunkSize(40)
			.withMinChunkSizeChars(5)
			.withPunctuationMarks(List.of('。', '？', '！', '；'))
			.build()
			.split(new Document(chinese));

		assertThat(chunks).hasSizeGreaterThan(1).allSatisfy((chunk) -> assertThat(chunk.getText()).endsWith("。"));
	}

	@Test
	void theEncodingTypeDecidesHowTokensAreCounted() {
		List<Document> chunks = TokenTextSplitter.builder()
			.withEncodingType(EncodingType.O200K_BASE)
			.withChunkSize(50)
			.withMinChunkSizeChars(20)
			.build()
			.split(new Document(SENTENCES));

		assertThat(chunks)
			.allSatisfy((chunk) -> assertThat(O200K.countTokens(chunk.getText())).isLessThanOrEqualTo(50));
	}

	@Test
	void withoutKeepingSeparatorsLineBreaksBecomeSpaces() {
		Document document = new Document("Grinders grind.\nKettles pour.\nFilters filter.");

		assertThat(TokenTextSplitter.builder().withKeepSeparator(false).build().split(document).get(0).getText())
			.isEqualTo("Grinders grind. Kettles pour. Filters filter.");
		assertThat(TokenTextSplitter.builder().build().split(document).get(0).getText()).isEqualTo(document.getText());
	}

	@Test
	void aChunkMustBeLongerThanTheMinimumLengthToEmbed() {
		TokenTextSplitter splitter = TokenTextSplitter.builder().build();

		assertThat(splitter.split(new Document("Hello"))).isEmpty();
		assertThat(splitter.split(new Document("Hello!"))).hasSize(1);
		assertThat(TokenTextSplitter.builder().withMinChunkLengthToEmbed(10).build().split(new Document("Hello!")))
			.isEmpty();
	}

	/**
	 * The page calls maxNumChunks the maximum number of chunks. The splitter stops
	 * cutting when it has that many, then adds the rest of the text as one more chunk,
	 * however long it is.
	 */
	@Test
	void afterMaxNumChunksTheRestOfTheTextIsOneMoreChunkOfAnyLength() {
		List<Document> chunks = TokenTextSplitter.builder()
			.withChunkSize(50)
			.withMinChunkSizeChars(20)
			.withMaxNumChunks(2)
			.build()
			.split(new Document(SENTENCES));

		assertThat(chunks).hasSize(3);
		assertThat(CL100K.countTokens(chunks.get(2).getText())).isGreaterThan(50 * 5);
	}

	private static String sentences(int count) {
		StringBuilder text = new StringBuilder();
		for (int i = 1; i <= count; i++) {
			text.append(i == 1 ? "" : " ")
				.append("Sentence ")
				.append(i)
				.append(" says that the burr grinder grinds the beans evenly.");
		}
		return text.toString();
	}

}
