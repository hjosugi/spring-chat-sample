package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;

/**
 * An advisor that writes down when it runs, {@code name>} on the way to the model and
 * {@code <name} on the way back, and which advisors the chain holds, so that a test sees
 * the chain and its order.
 */
record Probe(String name, int order, List<String> events, List<String> chain) implements CallAdvisor, StreamAdvisor {

	Probe(String name, int order, List<String> events) {
		this(name, order, events, new CopyOnWriteArrayList<>());
	}

	/**
	 * The advisors of a chain, as {@code name@order}.
	 */
	static List<String> describe(List<? extends Advisor> advisors) {
		return advisors.stream().map((advisor) -> advisor.getName() + "@" + advisor.getOrder()).toList();
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		this.chain.clear();
		this.chain.addAll(describe(chain.getCallAdvisors()));
		this.events.add(this.name + ">");
		ChatClientResponse response = chain.nextCall(request);
		this.events.add("<" + this.name);
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		this.chain.clear();
		this.chain.addAll(describe(chain.getStreamAdvisors()));
		this.events.add(this.name + ">");
		return chain.nextStream(request).doOnComplete(() -> this.events.add("<" + this.name));
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}
