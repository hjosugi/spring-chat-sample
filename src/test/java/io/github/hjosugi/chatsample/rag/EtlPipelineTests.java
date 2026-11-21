package io.github.hjosugi.chatsample.rag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ETL pipeline page: a reader, a transformer and a writer, chained in the functional
 * style or with the names of the domain, from a PDF into a vector store.
 */
class EtlPipelineTests {

	@TempDir
	Path dir;

	@Test
	void aPdfGoesThroughASplitterIntoAVectorStoreInTheFunctionalStyle() {
		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
				Handbook.write(this.dir.resolve("handbook.pdf"), true).toUri().toString());
		TokenTextSplitter tokenTextSplitter = TokenTextSplitter.builder().build();
		BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddings).build();

		vectorStore.accept(tokenTextSplitter.apply(pdfReader.get()));

		assertThat(embeddings.embeddedTexts()).hasSize(5);
		Document found = vectorStore.similaritySearch(SearchRequest.builder().query("kettles pour").topK(1).build())
			.get(0);
		assertThat(Handbook.squeezed(found.getText())).contains("Gooseneck kettles pour slowly.");
		assertThat(found.getMetadata()).containsEntry("page_number", 2).containsEntry("file_name", "handbook.pdf");
	}

	@Test
	void theNamesOfTheDomainDoTheSame() {
		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
				Handbook.write(this.dir.resolve("handbook.pdf"), true).toUri().toString());
		TokenTextSplitter tokenTextSplitter = TokenTextSplitter.builder().build();
		BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();
		SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddings).build();

		vectorStore.write(tokenTextSplitter.split(pdfReader.read()));

		assertThat(embeddings.embeddedTexts()).hasSize(5);
	}

	@Test
	void theThreeInterfacesAreASupplierAFunctionAndAConsumer() {
		List<Document> written = new ArrayList<>();
		DocumentReader reader = () -> List.of(new Document("Grinders grind."), new Document("Kettles pour."));
		DocumentTransformer upperCase = (documents) -> documents.stream()
			.map((document) -> document.mutate().text(document.getText().toUpperCase()).build())
			.toList();
		DocumentWriter writer = written::addAll;

		writer.write(upperCase.transform(reader.read()));

		assertThat(written).extracting(Document::getText).containsExactly("GRINDERS GRIND.", "KETTLES POUR.");
	}

}
