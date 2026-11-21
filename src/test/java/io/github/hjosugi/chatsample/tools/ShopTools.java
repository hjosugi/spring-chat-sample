package io.github.hjosugi.chatsample.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Tools of a shop, like the sample's, that keep every call they ran: the tool, its
 * argument and the tool context it got. Cancelling an order needs a person's
 * confirmation.
 */
public class ShopTools {

	public static final String USER = "user";

	/**
	 * The calls that ran, oldest first.
	 */
	public final List<Call> calls = new CopyOnWriteArrayList<>();

	@Tool(description = "Tells where one order of the signed-in user is")
	public String orderStatus(@ToolParam(description = "The order id, such as A-1001") String orderId,
			ToolContext context) {
		this.calls.add(new Call("orderStatus", orderId, context.getContext()));
		return orderId + " of " + context.getContext().get(USER) + " was shipped";
	}

	@Tool(description = "Cancels one order of the signed-in user", requiresConfirmation = true)
	public String cancelOrder(@ToolParam(description = "The order id, such as A-1001") String orderId,
			ToolContext context) {
		this.calls.add(new Call("cancelOrder", orderId, context.getContext()));
		return orderId + " of " + context.getContext().get(USER) + " is cancelled";
	}

	/**
	 * A call that ran.
	 *
	 * @param tool the tool's name
	 * @param argument the order id the model gave
	 * @param context the tool context the tool got
	 */
	public record Call(String tool, String argument, Map<String, Object> context) {

	}

}
