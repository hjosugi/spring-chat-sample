# spring-chat-sample

A coding agent that runs [spring-chat](https://github.com/hjosugi/spring-chat) as an application would: behind a Spring Security login, answering with Claude, or with a model of OpenAI or Google, through Spring AI. It works on the files of the signed-in user's own workspace: it lists, reads and searches them on its own, and it writes files and runs commands once the user allowed it.

Its tests also verify Spring AI 2.1.0 feature by feature, without a key: see [Spring AI, feature by feature](#spring-ai-feature-by-feature).

## Run it

spring-chat is not on Maven Central yet, so install it first:

```bash
git clone --branch v0.1.0 --depth 1 https://github.com/hjosugi/spring-chat
(cd spring-chat && ./mvnw install -DskipTests)
```

Then, in this repository:

```bash
ANTHROPIC_API_KEY=... ./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in as `alice` or `bob`; the password is the name. Each has a workspace of their own, a directory below `workspace/`, which starts empty. Without the key the application stops at the start and says so.

Claude answers unless these environment variables say otherwise:

| | |
| --- | --- |
| `CHAT_PROVIDER` | Who answers: `anthropic` (the default), `openai` or `gemini` |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` | The key of the one that answers |
| `ANTHROPIC_MODEL`, `OPENAI_MODEL`, `GEMINI_MODEL` | Its model: `claude-haiku-5-5`, `gpt-6.1-terra` or `gemini-3.5-flash-lite` by default |
| `EMBEDDING_PROVIDER` | `openai` gives the agent the team's coding guidelines. It needs `OPENAI_API_KEY`, whoever answers. `fake` embeds them without a key, see below. Unset by default |
| `OPENAI_EMBEDDING_MODEL` | The model that embeds the guidelines: `text-embedding-3-small` by default |
| `AGENT_MCP_ENABLED` | `true` serves the coding guidelines as an MCP server at `POST /mcp`. Off by default |
| `AGENT_WORKSPACE` | The directory with a directory of files for each user: `workspace` by default |

A model named `fake` needs no key. The application then starts a stand-in for the provider's API, and Spring AI talks to it as it would to the provider, in the format of Anthropic, of OpenAI or of Google, whichever `CHAT_PROVIDER` says. `EMBEDDING_PROVIDER=fake` does the same for the embeddings of the guidelines. With both, the whole application runs without a key:

```bash
CHAT_PROVIDER=openai OPENAI_MODEL=fake EMBEDDING_PROVIDER=fake ./mvnw spring-boot:run
```

The stand-in is a script and not a model. It asks for the tool that the request calls for, answers with what the tool returned, and quotes the guidelines when it is given some. A request it does not know gets a hint, and "long reply" gets 300 words, one every 100 ms, to stop. A bag of words embeds the guidelines, so it finds a section by the words it shares with the question: its `agent.guidelines.similarity-threshold` is 0.1.

`EMBEDDING_PROVIDER=fake` goes with a chat of Anthropic or Google, or with a model of OpenAI named `fake`. A real OpenAI chat cannot share the settings of OpenAI with it, and the application says so at the start.

Ask "List the files", "Write notes.txt: hello", "Read notes.txt", "Search for hello", "Replace 'hello' with 'goodbye' in notes.txt" or "Run ls -l" (the words that the stand-in understands; a real model understands more). Writing a file, replacing a piece of one and running a command do not happen at once: the chat shows what the model wants to do, with an Allow and a Do not allow button, and it is done only after the user allowed it. Looking at files needs no leave. The tools take the user from the login, never from the model, and a path that leaves the user's workspace, by `..`, by an absolute path or by a link, is refused: Bob cannot read what Alice's workspace holds. With the guidelines, "How should I name a test?" is answered from them.

## What is where

| | |
| --- | --- |
| The chat in a page of the application | `templates/home.html` |
| The tools for the user who asked | `CodingTools`: list, read and search files, write a file, replace a piece of one, run a command. `ChatConfiguration`: a `ChatPromptCustomizer` adds them and puts the signed-in user in the tool context |
| What the tools may reach | `Workspace`: a directory for each user, and a path that cannot leave it |
| Tools that wait for the user | `writeFile`, `replaceInFile` and `runCommand` are marked `requiresConfirmation`. Spring AI pauses the reply, and spring-chat asks the user and continues it |
| The team's coding guidelines in a reply | `guidelines.GuidelinesAutoConfiguration` reads `resources/guidelines/*.md` through Spring AI's ETL pipeline into a vector store, when there is an embedding model. `ChatConfiguration` adds the advisor that gives the model the sections close to a question |
| The guidelines as an MCP server | The package `mcp`: tools and resources made with the MCP annotations, stateless, public, and with no way to a workspace |
| The login in front of everything, the chat included | `SecurityConfiguration`; spring-chat sends the CSRF token by itself |
| Title, intro, system prompt, provider and models | `application.properties`. It also turns off every kind of model that the application does not use |
| A model named `fake` | The `repro` package: `FakeModelEnvironmentPostProcessor` points Spring AI at a `SlowModel`, which answers as `AgentScript` says |

A conversation is a session of Spring AI, kept in memory by spring-chat and removed a day after it started. Each belongs to the user who started it.

Three things to know before changing the sample:

- **A command that the user allowed runs with the rights of the application.** `Workspace` keeps the files of the users apart, and is no sandbox: a command is a shell, in the user's workspace as its working directory, and it can reach what the application's own user can. It gets no secret of the application's environment, and it stops after `agent.command-timeout`. Run the sample where that is acceptable, and read what the chat shows before allowing it.
- **The guidelines cost a request to the embedding model for each section at every start**, and one more for each question, and again for each round in which the model calls a tool. `agent.guidelines.similarity-threshold` says how close a section has to be. Its value was not tuned with a real model.
- **An application that gives its chat MCP tools sends them the tool context**, which here holds the signed-in user's name. Spring AI sends every entry of it to every MCP server unless a `ToolContextToMcpMetaConverter` keeps it back. The sample serves MCP and does not use it in its chat.

## Spring AI, feature by feature

The tests below `src/test` verify what the reference documentation of Spring AI 2.1.0 describes, page by page, and [VERIFICATION.md](VERIFICATION.md) lists them, what they found, and what they leave out.

| | Test classes | |
| --- | ---: | --- |
| Chat client, prompts, structured output, advisors, retry, guardrails, observability, agent patterns | 23 | `chatclient` |
| Tool calling, with confirmation, tool search and argument augmentation | 8 | `tools` |
| Session memory, the deprecated chat memory, and their JDBC stores | 12 | `memory` |
| RAG, the ETL pipeline and its readers, vector stores, PGvector, the semantic cache, evaluators | 34 | `rag` |
| MCP clients and servers over every transport, and the annotations | 12 | `mcp` |
| Embedding, image, speech, transcription, moderation and decision models | 26 | `models` |
| The chat models of Anthropic, OpenAI, Google GenAI, Mistral AI, Ollama, DeepSeek and Bedrock | 19 | `providers` |

The running application uses a part of what the tests verify: the chat client with advisors, tool calling with confirmation, session memory, the ETL pipeline into a vector store with the RAG advisor, and an MCP server. It starts with a model named `fake`, so that all of it can be tried without a key. The rest, such as the image, speech and moderation models, the other MCP transports and the stores of PGvector and Redis, is only in the tests: an application has no use for all of Spring AI, and the sample would not be one.

No test needs a key, and none talks to a provider. Spring AI's own client of a provider talks to a stand-in for its API, which keeps each request and answers from a script; a test asserts the request that went out and what Spring AI made of the reply. So the tests show what Spring AI sends and understands, not what a provider does with it.

Some of what they found, each with its test in [VERIFICATION.md](VERIFICATION.md):

- Portable `ChatOptions` on a prompt that goes straight to a chat model are ignored by Anthropic's and fail with a `ClassCastException` at OpenAI's. Through a `ChatClient` they work.
- Anthropic's native structured output sends a schema whose references point to definitions it dropped.
- An Anthropic refusal without text comes back with no generation at all: `entity()` returns `null`.
- Tool search forgets the tools it found once another tool ran, with session memory.
- By default the RAG advisor replaces a question it finds nothing for with an instruction not to answer, and no tool ever sees the question.
- A stateless MCP server that turns off the conversion of `ToolCallback` beans loses its annotated tools too.
- Options given at runtime put the model back to the default of their class for OpenAI's image, speech and transcription models.
- The decision model of 2.1 is on in an application that turned off, for 2.0, every model it does not use. This sample had one.

## Stopping a reply does not always end it at the provider

Stopping a reply, or leaving the page while it streams, cancels the stream of Spring AI. Whether that also closes the stream to the provider depends on the provider and on the connection:

| | Over HTTP/2, as the three APIs answer | Over HTTP/1.1, as a model named `fake` answers, or a proxy that speaks it |
| --- | --- | --- |
| Anthropic | closed at once | stays open, with `Unbalanced enter/exit` in the log |
| OpenAI, Chat Completions | closed at once | stays open |
| OpenAI, Responses API | | stays open |
| Gemini | stays open | stays open |
| Mistral AI, Ollama, DeepSeek | | closed |

The HTTP/1.1 column is what `StreamCancelTests` shows with Spring AI 2.1.0. The HTTP/2 column was seen with 2.0.2 and has not been checked again: the stand-in of the tests speaks HTTP/1.1.

For Anthropic and OpenAI it is a race. Their clients read the stream on a thread of their own, and the cancel closes the response from another: when the reader is waiting for the next event, which is nearly always, the connection stays open. Gemini's stream is never closed on a cancel.

A reply whose stream stays open is written to its end, and billed. That is why replies stop at 8192 tokens. `AnthropicStopReproTests` reproduces the case of Anthropic over HTTP/1.1.

## Tests

`./mvnw verify` runs the application against a stand-in for the Anthropic API, and the tests that verify Spring AI: about 1,420 tests in six minutes. It needs no key. The tests of PGvector, of the JDBC session store on PostgreSQL and of the semantic cache start containers, and skip themselves without Docker.

The tests of the application sign in through the login form, take the CSRF token from the page and ask over SSE and WebSocket, while the stand-in asks for the tools as Claude would. They write a file, replace a piece of one and run a command once the user answered the question that the chat asks first, as Alice and, in vain, as Bob, who cannot reach Alice's workspace. They also stop a long reply, and leave another, and check that the stand-in sees the connection close.

Three tests fail with Spring AI 2.1.0, as they did with 2.0.2, because the stand-in speaks HTTP/1.1, where the connection stays open:

- `SampleChatApplicationTests.stoppingAReplyEndsItAtAnthropic`
- `SampleChatApplicationTests.leavingAReplyEndsItAtAnthropic`
- `AnthropicStopReproTests.cancellingAStreamClosesItsConnection`, which is a race: it failed in every run on its own and passed in some runs of the whole suite.

## License

[Apache License 2.0](LICENSE)
