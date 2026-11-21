package io.github.hjosugi.chatsample.rag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.JsonMetadataGenerator;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The {@code JsonReader} and the {@code TextReader} of the JSON and text reader page.
 */
class JsonAndTextReaderTests {

	private static final String NL = System.lineSeparator();

	private final Resource bikes = new ClassPathResource("rag/bikes.json");

	@Test
	void eachObjectOfAnArrayBecomesADocumentWithALinePerChosenKey() {
		JsonReader jsonReader = new JsonReader(this.bikes, "description", "content");

		List<Document> documents = jsonReader.get();

		assertThat(documents).extracting(Document::getText)
			.containsExactly("description: A high-performance mountain bike for trail riding." + NL,
					"description: An aerodynamic road bike for racing enthusiasts." + NL);
		assertThat(documents).allSatisfy((document) -> assertThat(document.getMetadata()).isEmpty());
	}

	@Test
	void theLinesFollowTheOrderOfTheKeys() {
		List<Document> documents = new JsonReader(this.bikes, "description", "brand").get();

		assertThat(documents.get(1).getText())
			.isEqualTo("description: An aerodynamic road bike for racing enthusiasts." + NL + "brand: Cannondale" + NL);
	}

	@Test
	void withoutKeysOrWithNoneOfThemTheContentIsTheObjectAsAMap() {
		assertThat(new JsonReader(this.bikes).get().get(0).getText())
			.isEqualTo("{id=1, brand=Trek, description=A high-performance mountain bike for trail riding.}");
		assertThat(new JsonReader(this.bikes, "price").get().get(0).getText()).startsWith("{id=1, brand=Trek");
	}

	@Test
	void aMetadataGeneratorMakesTheMetadataOfEachDocument() {
		JsonMetadataGenerator brand = (item) -> Map.of("brand", item.get("brand"), "id", item.get("id"));

		List<Document> documents = new JsonReader(this.bikes, brand, "description").get();

		assertThat(documents).extracting(Document::getMetadata)
			.containsExactly(Map.of("brand", "Trek", "id", 1), Map.of("brand", "Cannondale", "id", 2));
	}

	@Test
	void aJsonPointerReadsPartOfTheDocument() {
		JsonReader jsonReader = new JsonReader(new ClassPathResource("rag/store.json"), "description");

		assertThat(jsonReader.get("/store/books/0")).extracting(Document::getText)
			.containsExactly("description: Everything about brewing at home." + NL);
		assertThat(jsonReader.get("/store/books")).hasSize(2);
		// A single object as the root is one document.
		assertThat(jsonReader.get()).hasSize(1);
		assertThatIllegalArgumentException().isThrownBy(() -> jsonReader.get("/store/magazines"))
			.withMessage("Invalid JSON Pointer: /store/magazines");
	}

	@Test
	void aTextFileIsOneDocumentWithItsCharsetAndSource() {
		TextReader textReader = new TextReader(new ClassPathResource("rag/handbook.txt"));
		textReader.getCustomMetadata().put("filename", "text-source.txt");

		List<Document> documents = textReader.read();

		assertThat(documents).hasSize(1);
		assertThat(documents.get(0).getText()).startsWith("Employee handbook of the coffee gear shop.")
			.contains("28 vacation days");
		assertThat(documents.get(0).getMetadata()).containsEntry("charset", "UTF-8")
			.containsEntry("source", "handbook.txt")
			.containsEntry("filename", "text-source.txt");
	}

	@Test
	void theCharsetCanBeChangedAndTheResourceGivenAsAUrl(@TempDir Path dir) throws Exception {
		Path latin1 = dir.resolve("latin1.txt");
		Files.write(latin1, "Café crème".getBytes(StandardCharsets.ISO_8859_1));

		TextReader textReader = new TextReader(latin1.toUri().toString());
		textReader.setCharset(StandardCharsets.ISO_8859_1);

		Document document = textReader.get().get(0);
		assertThat(document.getText()).isEqualTo("Café crème");
		assertThat(document.getMetadata()).containsEntry("charset", "ISO-8859-1").containsEntry("source", "latin1.txt");
	}

	@Test
	void aSplitterCutsTheTextIntoChunks() {
		List<Document> documents = new TextReader(new FileSystemResource("src/test/resources/rag/handbook.txt")).get();

		List<Document> splitDocuments = TokenTextSplitter.builder()
			.withChunkSize(30)
			.withMinChunkSizeChars(20)
			.build()
			.apply(documents);

		assertThat(splitDocuments).hasSizeGreaterThan(1)
			.allSatisfy((chunk) -> assertThat(chunk.getMetadata()).containsEntry("source", "handbook.txt"));
	}

}
