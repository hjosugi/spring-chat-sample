package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SimpleVectorStore;

/**
 * What the shop sells, as documents with a type and a price, for the tests that need a
 * vector store with something in it.
 */
final class CoffeeGear {

	static final Document GRINDER = new Document("grinder",
			"The burr grinder grinds coffee beans evenly, from espresso fine to French press coarse.",
			Map.of("type", "grinder", "price", 89));

	static final Document KETTLE = new Document("kettle",
			"The gooseneck kettle heats water to an exact temperature and pours it slowly.",
			Map.of("type", "kettle", "price", 59));

	static final Document FILTERS = new Document("filters",
			"Paper filters for the pour-over dripper come in packs of 100.", Map.of("type", "filter", "price", 6));

	static final Document SCALE = new Document("scale",
			"The scale weighs beans and water to a tenth of a gram, and has a timer.",
			Map.of("type", "scale", "price", 25));

	static final Document BEANS = new Document("beans",
			"Single-origin coffee beans are roasted every Monday and shipped within a week.",
			Map.of("type", "beans", "price", 14));

	static final List<Document> ALL = List.of(GRINDER, KETTLE, FILTERS, SCALE, BEANS);

	private CoffeeGear() {
	}

	/**
	 * A store with everything the shop sells.
	 */
	static SimpleVectorStore store(EmbeddingModel embeddingModel) {
		SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
		store.add(ALL);
		return store;
	}

	/**
	 * A tool for the tests of the tool calling loop.
	 */
	static class Stock {

		@Tool(description = "Tells how many of an item are in stock")
		String stock(String item) {
			return "3 " + item + "s in stock";
		}

	}

}
