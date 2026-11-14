package io.github.hjosugi.chatsample;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The shop's orders, by the user who placed them.
 */
@Component
class Orders {

	private final Map<String, List<Order>> byUser = Map.of("alice",
			List.of(new Order("A-1001", "Coffee grinder", "shipped on November 12"),
					new Order("A-1002", "Paper filters, 100", "being packed")),
			"bob", List.of(new Order("B-2001", "Kettle", "delivered on November 9")));

	List<Order> of(String user) {
		return this.byUser.getOrDefault(user, List.of());
	}

	Optional<Order> find(String user, String id) {
		return of(user).stream().filter((order) -> order.id().equalsIgnoreCase(id.strip())).findFirst();
	}

	record Order(String id, String item, String status) {
	}

}
