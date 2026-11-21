package io.github.hjosugi.chatsample.guidelines;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.ContentFormatTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;

/**
 * The team's coding guidelines (Java, testing, Git) read at startup into a vector
 * store the chat can search, through the ETL pipeline: a Markdown reader, a token
 * splitter, a content formatter and the store as the writer. Only an application with an
 * embedding model gets them; with {@code spring.ai.model.embedding=none}, the default,
 * nothing here runs.
 * <p>
 * An auto-configuration rather than a plain configuration, because a condition on a bean
 * only sees the beans defined before it, and the embedding model comes from Spring AI's
 * own auto-configuration.
 */
@AutoConfiguration
// After every auto-configuration that may make the embedding model, whichever it is.
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnSingleCandidate(EmbeddingModel.class)
public class GuidelinesAutoConfiguration {

	/**
	 * Where the guidelines are.
	 */
	public static final String PAGES = "classpath:guidelines/*.md";

	/**
	 * The name of the vector store with the guidelines.
	 */
	public static final String BEAN_NAME = "guidelines";

	/**
	 * What the splitter adds to the metadata of each chunk. Models that embed the
	 * metadata with the text, as OpenAI's does, would otherwise embed a random id with
	 * every section.
	 */
	static final List<String> SPLITTER_KEYS = List.of("parent_document_id", "chunk_index", "total_chunks");

	/**
	 * Each section of a page becomes a document, with the heading as its {@code title}
	 * and the file as its {@code source}.
	 */
	@Bean(BEAN_NAME)
	SimpleVectorStore guidelines(EmbeddingModel embeddingModel, ResourceLoader resourceLoader) throws IOException {
		SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
		TokenTextSplitter splitter = TokenTextSplitter.builder().build();
		ContentFormatTransformer withoutSplitterKeys = new ContentFormatTransformer(
				DefaultContentFormatter.builder().withExcludedEmbedMetadataKeys(SPLITTER_KEYS).build());
		for (Resource page : ResourcePatternUtils.getResourcePatternResolver(resourceLoader).getResources(PAGES)) {
			MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
				.withAdditionalMetadata("source", page.getFilename())
				.build();
			List<Document> sections = withoutSplitterKeys
				.transform(splitter.split(new MarkdownDocumentReader(page, config).read()));
			// SimpleVectorStore refuses an empty list.
			if (!sections.isEmpty()) {
				store.write(sections);
			}
		}
		return store;
	}

}
