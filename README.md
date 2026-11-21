# spring-chat-sample

A shop assistant that runs [spring-chat](https://github.com/hjosugi/spring-chat) as an application would: behind a Spring Security login, answering with Claude, or with a model of OpenAI or Google, through Spring AI, with tools that read the signed-in user's orders and one that cancels an order once the user allowed it.

Its tests also verify Spring AI 2.1.0 feature by feature, without a key: see [Spring AI, feature by feature](#spring-ai-feature-by-feature).

## Run it

spring-chat is not on Maven Central yet, so install it first:

```bash
git clone https://github.com/hjosugi/spring-chat
(cd spring-chat && ./mvnw install -DskipTests)
```

Then, in this repository:

```bash
ANTHROPIC_API_KEY=... ./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in as `alice` or `bob`; the password is the name. Without the key the application stops at the start and says so.

Claude answers unless these environment variables say otherwise:

| | |
| --- | --- |
| `CHAT_PROVIDER` | Who answers: `anthropic` (the default), `openai` or `gemini` |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` | The key of the one that answers |
| `ANTHROPIC_MODEL`, `OPENAI_MODEL`, `GEMINI_MODEL` | Its model: `claude-haiku-5-5`, `gpt-6.1-terra` or `gemini-3.5-flash-lite` by default |
| `EMBEDDING_PROVIDER` | `openai` gives the assistant the shop's help pages. It needs `OPENAI_API_KEY`, whoever answers. Unset by default |
| `OPENAI_EMBEDDING_MODEL` | The model that embeds the help pages: `text-embedding-3-small` by default |
| `SHOP_MCP_ENABLED` | `true` serves the shop's catalog as an MCP server at `POST /mcp`. Off by default |

A model named `fake` needs no key. The application then starts a stand-in for the provider's API, and Spring AI talks to it as it would to the provider. Every reply is 300 words, one every 100 ms:

```bash
CHAT_PROVIDER=openai OPENAI_MODEL=fake ./mvnw spring-boot:run
```

Ask "What did I order?" or "Where is A-1001?". Alice asking about Bob's order B-2001 finds nothing: the tools take the user from the login, never from the model. "Cancel A-1002" does not cancel it: the chat shows what the model wants to do, with an Allow and a Do not allow button, and the order is cancelled only after Alice allowed it. With the help pages, "How long does shipping take?" is answered from them. The links above the chat switch between SSE, WebSocket and spring-chat's built-in page at `/chat`.

## What is where

| | |
| --- | --- |
| The chat in a page of the application | `templates/home.html` |
| Tools for the user who asked | `ChatConfiguration`: a `ChatPromptCustomizer` adds `OrderTools` and puts the signed-in user in the tool context |
| A tool that waits for the user | `OrderTools.cancelOrder` is marked `requiresConfirmation`. Spring AI pauses the reply, and spring-chat asks the user and continues it |
| The shop's help pages in a reply | `help.HelpAutoConfiguration` reads `resources/help/*.md` through Spring AI's ETL pipeline into a vector store, when there is an embedding model. `ChatConfiguration` adds the advisor that gives the model the sections close to a question |
| The catalog as an MCP server | The package `mcp`: tools and resources made with the MCP annotations, stateless, public, and with no way to the orders |
| The login in front of everything, the chat included | `SecurityConfiguration`; spring-chat sends the CSRF token by itself |
| Title, intro, system prompt, provider and models | `application.properties`. It also turns off every kind of model that the application does not use |
| A model named `fake` | The `repro` package: `FakeModelEnvironmentPostProcessor` points Spring AI at a `SlowModel` |

A conversation is a session of Spring AI, kept in memory by spring-chat and removed a day after it started. Each belongs to the user who started it.

Two things to know before changing the sample:

- **The help pages cost a request to the embedding model for each section at every start**, and one more for each question, and again for each round in which the model calls a tool. `shop.help.similarity-threshold` says how close a section has to be. Its value was not tuned with a real model.
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

`./mvnw verify` runs the application against a stand-in for the Anthropic API, and the tests that verify Spring AI: about 1,350 tests in five minutes. It needs no key. The tests of PGvector, of the JDBC session store on PostgreSQL and of the semantic cache start containers, and skip themselves without Docker.

The tests of the application sign in through the login form, take the CSRF token from the page and ask over SSE and WebSocket, while the stand-in asks for the tools as Claude would. They cancel an order and answer the question that the chat asks first, as Alice and, in vain, as Bob. They also stop a long reply, and leave another, and check that the stand-in sees the connection close.

Three tests fail with Spring AI 2.1.0, as they did with 2.0.2, because the stand-in speaks HTTP/1.1, where the connection stays open:

- `SampleChatApplicationTests.stoppingAReplyEndsItAtAnthropic`
- `SampleChatApplicationTests.leavingAReplyEndsItAtAnthropic`
- `AnthropicStopReproTests.cancellingAStreamClosesItsConnection`, which is a race: it failed in every run on its own and passed in some runs of the whole suite.

## License

[Apache License 2.0](LICENSE)
