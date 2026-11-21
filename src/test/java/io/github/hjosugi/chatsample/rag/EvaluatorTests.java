package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluators page: the {@code Evaluator} interface, the {@code RelevancyEvaluator} on
 * the result of a RAG flow, and the {@code FactCheckingEvaluator}, each asking a judge
 * model that answers from a script.
 */
class EvaluatorTests {

	private static final String QUESTION = "Where does the adventure of Anacletus and Birba take place?";

	private static final String CONTEXT = "Anacletus and Birba travel through the Whispering Woods.";

	@Test
	void theRelevancyOfARagAnswerIsJudgedAgainstTheRetrievedContext() {
		SimpleVectorStore pgVectorStore = SimpleVectorStore.builder(new BagOfWordsEmbeddingModel()).build();
		pgVectorStore.add(List.of(new Document(CONTEXT)));
		ScriptedChatModel chatModel = new ScriptedChatModel((prompt) -> ScriptedChatModel
			.text(lastText(prompt).contains("Your task is to evaluate") ? "YES" : "In the Whispering Woods."));
		RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(pgVectorStore).build())
			.build();

		ChatResponse chatResponse = ChatClient.builder(chatModel)
			.build()
			.prompt(QUESTION)
			.advisors(ragAdvisor)
			.call()
			.chatResponse();
		EvaluationRequest evaluationRequest = new EvaluationRequest(QUESTION,
				chatResponse.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT),
				chatResponse.getResult().getOutput().getText());
		RelevancyEvaluator evaluator = new RelevancyEvaluator(ChatClient.builder(chatModel));
		EvaluationResponse evaluationResponse = evaluator.evaluate(evaluationRequest);

		assertThat(evaluationResponse.isPass()).isTrue();
		assertThat(evaluationResponse.getScore()).isEqualTo(1f);
		// The template is indented in Spring AI's source, and so is every line of it.
		assertThat(stripped(lastText(chatModel.lastPrompt()))).isEqualTo("""
				Your task is to evaluate if the response for the query
				is in line with the context information provided.

				You have two options to answer. Either YES or NO.

				Answer YES, if the response for the query
				is in line with context information otherwise NO.

				Query:
				%s

				Response:
				In the Whispering Woods.

				Context:
				%s

				Answer:""".formatted(QUESTION, CONTEXT));
	}

	/**
	 * The judge's answer must be the word itself: anything around it, even a full stop,
	 * fails the evaluation.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "YES", "yes", " Yes \n" })
	void onlyAPlainYesPasses(String answer) {
		assertThat(relevancy(answer).isPass()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "NO", "YES.", "Yes, it is.", "" })
	void anythingElseFails(String answer) {
		EvaluationResponse response = relevancy(answer);

		assertThat(response.isPass()).isFalse();
		assertThat(response.getScore()).isZero();
	}

	@Test
	void aCustomTemplateReplacesTheDefaultOne() {
		ScriptedChatModel judge = ScriptedChatModel.answering("yes");
		RelevancyEvaluator evaluator = RelevancyEvaluator.builder()
			.chatClientBuilder(ChatClient.builder(judge))
			.promptTemplate(new PromptTemplate("Q: {query}\nA: {response}\nFacts: {context}\nRelevant (yes/no)?"))
			.build();

		evaluator.evaluate(new EvaluationRequest(QUESTION, List.of(new Document(CONTEXT)), "In the woods."));

		assertThat(lastText(judge.lastPrompt()))
			.isEqualTo("Q: " + QUESTION + "\nA: In the woods.\nFacts: " + CONTEXT + "\nRelevant (yes/no)?");
	}

	/**
	 * The page says the template must contain the three placeholders. Nothing checks it:
	 * a template without {@code context} is accepted, and the judge never sees the
	 * context.
	 */
	@Test
	void aTemplateWithoutTheContextIsAcceptedAndTheJudgeDoesNotSeeIt() {
		ScriptedChatModel judge = ScriptedChatModel.answering("yes");
		RelevancyEvaluator evaluator = RelevancyEvaluator.builder()
			.chatClientBuilder(ChatClient.builder(judge))
			.promptTemplate(new PromptTemplate("Is \"{response}\" an answer to \"{query}\"?"))
			.build();

		assertThat(evaluator.evaluate(new EvaluationRequest(QUESTION, List.of(new Document(CONTEXT)), "Nowhere."))
			.isPass()).isTrue();
		assertThat(lastText(judge.lastPrompt())).doesNotContain(CONTEXT);
	}

	@Test
	void factCheckingAsksWhetherTheDocumentSupportsTheClaim() {
		ScriptedChatModel judge = ScriptedChatModel.answering("no");
		FactCheckingEvaluator evaluator = FactCheckingEvaluator.builder(ChatClient.builder(judge)).build();
		String context = "The Earth is the third planet from the Sun and the only astronomical object known to harbor life.";
		String claim = "The Earth is the fourth planet from the Sun.";

		EvaluationResponse evaluationResponse = evaluator
			.evaluate(new EvaluationRequest(List.of(new Document(context)), claim));

		assertThat(evaluationResponse.isPass()).isFalse();
		assertThat(stripped(lastText(judge.lastPrompt()))).isEqualTo("""
				Evaluate whether or not the following claim is supported by the provided document.
				Respond with "yes" if the claim is supported, or "no" if it is not.

				Document:
				%s

				Claim:
				%s""".formatted(context, claim));
	}

	@Test
	void forBespokeMinicheckOnlyTheDocumentAndTheClaimAreSent() {
		ScriptedChatModel judge = ScriptedChatModel.answering("Yes");
		FactCheckingEvaluator minicheckEvaluator = FactCheckingEvaluator.forBespokeMinicheck(ChatClient.builder(judge));

		EvaluationResponse response = minicheckEvaluator.evaluate(new EvaluationRequest(
				List.of(new Document("Grinders grind."), new Document("  "), new Document("Kettles pour.")),
				"Grinders grind."));

		assertThat(response.isPass()).isTrue();
		// The documents are joined with line separators; blank ones are left out.
		assertThat(lastText(judge.lastPrompt()).lines().map(String::strip).toList()).containsSubsequence("Document:",
				"Grinders grind.", "Kettles pour.", "", "Claim:", "Grinders grind.");
	}

	@Test
	void theEvaluationPromptCanBeReplaced() {
		ScriptedChatModel judge = ScriptedChatModel.answering("yes");

		FactCheckingEvaluator.builder(ChatClient.builder(judge))
			.evaluationPrompt("Does \"{document}\" support \"{claim}\"? Answer yes or no.")
			.build()
			.evaluate(new EvaluationRequest(List.of(new Document("Grinders grind.")), "They grind."));

		assertThat(lastText(judge.lastPrompt()))
			.isEqualTo("Does \"Grinders grind.\" support \"They grind.\"? Answer yes or no.");
	}

	@Test
	void anEvaluatorIsAFunctionOfTheRequest() {
		Evaluator longEnough = (request) -> new EvaluationResponse(request.getResponseContent().length() > 10, "",
				Map.of());

		assertThat(longEnough.evaluate(new EvaluationRequest(QUESTION, "In the Whispering Woods.")).isPass()).isTrue();
		assertThat(longEnough.evaluate(new EvaluationRequest(QUESTION, "Woods.")).isPass()).isFalse();
	}

	private static String stripped(String text) {
		return String.join("\n", text.strip().lines().map(String::strip).toList());
	}

	private static EvaluationResponse relevancy(String answer) {
		return new RelevancyEvaluator(ChatClient.builder(ScriptedChatModel.answering(answer)))
			.evaluate(new EvaluationRequest(QUESTION, List.of(new Document(CONTEXT)), "In the Whispering Woods."));
	}

}
