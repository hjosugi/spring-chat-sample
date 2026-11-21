package io.github.hjosugi.chatsample;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * The application with a model named {@code fake} of openai, and the fake embedding
 * of the guidelines.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.model.chat=openai", "spring.ai.openai.chat.model=fake",
				"fake-model.port=18102", "spring.ai.model.embedding=fake",
				"agent.workspace=target/fake-workspace/openai" })
class FakeModelAsOpenAiTests extends FakeModelTests {

}
