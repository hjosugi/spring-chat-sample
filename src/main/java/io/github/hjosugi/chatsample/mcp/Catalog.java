package io.github.hjosugi.chatsample.mcp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The coffee gear the shop sells: public data, as on a product page. The orders of a
 * user are not here and never reach the MCP server.
 */
class Catalog {

	private final List<Product> products = List.of(
			new Product("grinder", "Coffee grinder", new BigDecimal("89.00"), true),
			new Product("filters", "Paper filters, 100", new BigDecimal("4.50"), true),
			new Product("kettle", "Gooseneck kettle", new BigDecimal("59.00"), false),
			new Product("dripper", "Ceramic dripper", new BigDecimal("24.00"), true),
			new Product("scale", "Coffee scale", new BigDecimal("35.00"), false));

	List<Product> products() {
		return this.products;
	}

	Optional<Product> find(String id) {
		return this.products.stream().filter((product) -> product.id().equalsIgnoreCase(id.strip())).findFirst();
	}

	/**
	 * A product.
	 *
	 * @param id the product's id, such as {@code grinder}
	 * @param name what it is
	 * @param price its price in euros
	 * @param inStock whether it can be shipped now
	 */
	record Product(String id, String name, BigDecimal price, boolean inStock) {

		String describe() {
			return this.name + " (" + this.id + "): " + this.price + " EUR, "
					+ (this.inStock ? "in stock" : "out of stock");
		}

	}

}
