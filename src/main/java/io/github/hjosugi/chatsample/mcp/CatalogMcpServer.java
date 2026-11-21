package io.github.hjosugi.chatsample.mcp;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * What an MCP client may ask the shop: the catalog, as two tools for a model and two
 * resources for an application, with completion of the product ids. Everything here
 * only reads, and nothing here knows who asks.
 */
class CatalogMcpServer {

	private static final JsonMapper json = JsonMapper.shared();

	private final Catalog catalog;

	CatalogMcpServer(Catalog catalog) {
		this.catalog = catalog;
	}

	@McpTool(name = "products", title = "Catalog",
			description = "Lists the coffee gear the shop sells, with prices and whether each is in stock",
			annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	List<Catalog.Product> products() {
		return this.catalog.products();
	}

	@McpTool(name = "product", title = "Product",
			description = "Tells the price of one product of the shop and whether it is in stock",
			annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	String product(@McpToolParam(description = "The product's id, such as grinder") String id) {
		return this.catalog.find(id).map(Catalog.Product::describe).orElse("The shop sells no product " + id);
	}

	@McpResource(uri = "catalog://products", name = "products", title = "Catalog",
			description = "The coffee gear the shop sells", mimeType = "application/json")
	String productsResource() {
		return json.writeValueAsString(this.catalog.products());
	}

	@McpResource(uri = "catalog://products/{id}", name = "product", title = "Product",
			description = "One product of the shop", mimeType = "application/json")
	String productResource(String id) {
		return this.catalog.find(id).map(json::writeValueAsString).orElse("{}");
	}

	@McpComplete(uri = "catalog://products/{id}")
	List<String> productIds(String prefix) {
		return this.catalog.products().stream().map(Catalog.Product::id).filter((id) -> id.startsWith(prefix)).toList();
	}

}
