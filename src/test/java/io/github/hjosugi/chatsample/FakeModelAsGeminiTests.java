package io.github.hjosugi.chatsample;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * The application with a model named {@code fake} of gemini, and the fake embedding
 * of the guidelines.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.model.chat=gemini", "spring.ai.google.genai.chat.model=fake",
				"fake-model.port=18103", "spring.ai.model.embedding=fake",
				"agent.workspace=target/fake-workspace/gemini" })
class FakeModelAsGeminiTests extends FakeModelTests {

}
