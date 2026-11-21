package io.github.hjosugi.chatsample.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.hjosugi.chatsample.guidelines.GuidelinesAutoConfiguration;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The team's coding guidelines, which the pages in {@code resources/guidelines} hold:
 * what the MCP server serves. The chat reads the same pages into a vector store.
 */
class Guidelines {

	private final List<Guideline> guidelines;

	Guidelines() {
		try {
			List<Guideline> loaded = new ArrayList<>();
			for (Resource page : new PathMatchingResourcePatternResolver()
				.getResources(GuidelinesAutoConfiguration.PAGES)) {
				String text = page.getContentAsString(StandardCharsets.UTF_8);
				String id = page.getFilename().replaceFirst("\\.md$", "");
				String title = text.lines()
					.filter((line) -> line.startsWith("# "))
					.findFirst()
					.map((line) -> line.substring(2).strip())
					.orElse(id);
				loaded.add(new Guideline(id, title, text));
			}
			loaded.sort(Comparator.comparing(Guideline::id));
			this.guidelines = List.copyOf(loaded);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	List<Topic> topics() {
		return this.guidelines.stream().map(Guideline::topic).toList();
	}

	Optional<Guideline> find(String id) {
		return this.guidelines.stream().filter((guideline) -> guideline.id().equalsIgnoreCase(id.strip())).findFirst();
	}

	record Topic(String id, String title) {
	}

	record Guideline(String id, String title, String text) {

		Topic topic() {
			return new Topic(this.id, this.title);
		}

	}

}
