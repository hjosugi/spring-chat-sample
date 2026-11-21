package io.github.hjosugi.chatsample;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What the model may look up, and one thing it may do, with the user's consent. The user
 * comes from the tool context, which the chat fills from the signed-in user, never from
 * the model: a question about someone else's order finds nothing, and so does a
 * cancellation of it.
 */
@Component
class OrderTools {

	static final String USER = "user";

	private final Orders orders;

	OrderTools(Orders orders) {
		this.orders = orders;
	}

	@Tool(description = "Lists the orders of the signed-in user")
	List<Orders.Order> myOrders(ToolContext context) {
		return this.orders.of(user(context));
	}

	@Tool(description = "Tells what one order of the signed-in user is and where it is")
	String orderStatus(@ToolParam(description = "The order id, such as A-1001") String orderId, ToolContext context) {
		return this.orders.find(user(context), orderId)
			.map((order) -> order.id() + ", " + order.item() + ": " + order.status())
			.orElse("The signed-in user has no order " + orderId);
	}

	/**
	 * Changes an order, so it needs the user's confirmation: Spring AI pauses the reply
	 * when the model calls it, and the chat asks the user to allow the call.
	 */
	@Tool(description = "Cancels one order of the signed-in user, if it is still being packed",
			requiresConfirmation = true)
	String cancelOrder(@ToolParam(description = "The order id, such as A-1002") String orderId, ToolContext context) {
		return this.orders.cancel(user(context), orderId)
			.map((order) -> Orders.CANCELLED.equals(order.status()) ? order.id() + " is cancelled"
					: order.id() + " is " + order.status() + " and can no longer be cancelled")
			.orElse("The signed-in user has no order " + orderId);
	}

	private static String user(ToolContext context) {
		return (String) context.getContext().get(USER);
	}

}
