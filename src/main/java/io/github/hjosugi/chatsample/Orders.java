package io.github.hjosugi.chatsample;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The shop's orders, by the user who placed them. An order that is still being packed
 * can be cancelled, once; nothing else about an order changes.
 */
@Component
class Orders {

	static final String BEING_PACKED = "being packed";

	static final String CANCELLED = "cancelled";

	private final Map<String, List<Order>> byUser = new ConcurrentHashMap<>();

	Orders() {
		this.byUser.put("alice", new ArrayList<>(List.of(new Order("A-1001", "Coffee grinder", "shipped on November 12"),
				new Order("A-1002", "Paper filters, 100", BEING_PACKED), new Order("A-1003", "Espresso cups, 4", BEING_PACKED),
				new Order("A-1004", "Milk frother", BEING_PACKED))));
		this.byUser.put("bob", new ArrayList<>(List.of(new Order("B-2001", "Kettle", "delivered on November 9"))));
	}

	List<Order> of(String user) {
		List<Order> orders = this.byUser.getOrDefault(user, List.of());
		synchronized (orders) {
			return List.copyOf(orders);
		}
	}

	Optional<Order> find(String user, String id) {
		return of(user).stream().filter((order) -> order.id().equalsIgnoreCase(id.strip())).findFirst();
	}

	/**
	 * Cancels an order of the user, if it is still being packed.
	 * @return the order as it is now, or empty if the user has no such order
	 */
	Optional<Order> cancel(String user, String id) {
		List<Order> orders = this.byUser.getOrDefault(user, List.of());
		synchronized (orders) {
			for (int i = 0; i < orders.size(); i++) {
				Order order = orders.get(i);
				if (order.id().equalsIgnoreCase(id.strip())) {
					if (BEING_PACKED.equals(order.status())) {
						orders.set(i, new Order(order.id(), order.item(), CANCELLED));
					}
					return Optional.of(orders.get(i));
				}
			}
		}
		return Optional.empty();
	}

	record Order(String id, String item, String status) {
	}

}
