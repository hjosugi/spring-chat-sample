# Spring AI 2.1.0, feature by feature

What this sample verifies of Spring AI, and what it found. The record is the tests themselves: this page lists them, and what they show that the reference documentation does not say.

`./mvnw verify` runs about 1,350 tests in about five minutes. None needs a key, and none talks to a provider:

- What depends on a provider goes through Spring AI's own client of that provider to a `StandIn`, an HTTP server in the test that keeps each request and answers from a script. A test asserts the request that went out and what Spring AI made of the reply.
- What does not depend on a provider runs on a `ScriptedChatModel`, and asserts the prompts it was asked.
- PGvector, PostgreSQL and Redis run in containers. Those tests skip themselves without Docker.
- The starters that only the tests need stay out of the tests that start the whole application: see `models.TestOnlyStarters` and `src/test/resources/config/application.properties`.

A feature counts as verified when a test uses it the way its page describes and asserts what can be observed. Where Spring AI does something else than its page says, the test asserts what it does, and its name says so.

Three tests fail, as the [README](README.md#tests) explains: they reproduce a bug of Spring AI.

## The tests

143 test classes with 1234 test methods, ten of them in a base class that three classes run, below `src/test/java/io/github/hjosugi/chatsample/`. A parameterized or generated test counts once here and several times in the build.

### The application

The coding agent as a browser uses it: the login, the chat over SSE and WebSocket, the tools for the signed-in user's workspace, a write and a command that wait for the user, the coding guidelines.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `ChatConfigurationTests` | 8 | What the application refuses to start with, and what it says then |
| `ConversationsInADatabaseTests` | 3 | The application with a database and Spring AI's JDBC session starter, which is all it takes for conversations that outlast the application |
| `GuidelinesInTheChatTests` | 5 | The application with an embedding model, as `EMBEDDING_PROVIDER=openai` starts it |
| `CodingToolsTests` | 20 | What each tool of the coding agent does, without a model, and what the model is told when a path is missing, too large or outside the workspace |
| `FakeModelAsAnthropicTests` | 10 | The application with a model named `fake` of Anthropic: the tools, the confirmations and the guidelines, with no key |
| `FakeModelAsGeminiTests` | 10 | The same, in the format of Google's API |
| `FakeModelAsOpenAiTests` | 10 | The same, in the format of OpenAI's API |
| `SampleChatApplicationTests` | 14 | The application as a browser uses it |
| `WorkspaceTests` | 8 | Where the tools may go: the workspace of the user who asked, and nowhere else, whatever path the model makes up |

### Chat client, prompts, structured output, advisors, observability

Reference: ChatClient, Prompts and Templates, Structured Output, Advisors, Resilience and Safety, Observability, Agents, Chat Models, Generic Model API.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `AdvisorChainTests` | 10 | The advisor chain of a `ChatClient` call, as the pages "Advisors" and "Built-in Advisors" describe it |
| `ChatClientAdvisorsTests` | 15 | Advisors configured on a `ChatClient`, as the page "Advisors and Memory in ChatClient" shows it |
| `ChatClientBasicsTests` | 16 | Creating and calling a `ChatClient`, as the pages "Chat Client API" and "Creating and Calling a ChatClient" show it |
| `ChatClientDefaultsTests` | 17 | The defaults of a `ChatClient` and how the values of a request combine with them, as the page "Defaults and Mutation" shows it, and the copies that `mutate()` and `clone()` make |
| `ChatClientResponsesTests` | 20 | What a `ChatClient` call returns, as the page "Responses and Streaming" shows it |
| `ChatModelApiTests` | 15 | The `ChatModel` API below the `ChatClient`, as the pages "Chat Model API" and "Generic Model API" show it |
| `CustomAdvisorTests` | 8 | Advisors of your own, as the page "Writing an Advisor" shows them |
| `GuardrailsTests` | 10 | The `SafeGuardAdvisor` and refusals, as the page "Guardrails" describes them |
| `MessageMetadataTests` | 7 | Metadata on the user and system messages of a `ChatClient` call, as the page "Message Metadata" shows it |
| `MetricsTests` | 5 | The metrics that the observations produce, with the names they have in Prometheus, as the page "Metrics Reference" lists them |
| `MultipleChatModelsTests` | 6 | Several chat clients and chat models in one application, as the page "Working with Multiple Chat Models" shows it |
| `NativeStructuredOutputTests` | 12 | `useProviderStructuredOutput()`, as the page "Provider-Native Structured Output" shows it, for the providers it names among OpenAI, Anthropic and Google GenAI |
| `ObservationsTests` | 11 | The observations of the chat client, its advisors, the chat model and the tools, as the page "Observability" lists them with their keys, how they nest into the trace of a call, and the properties that log the content which the observations leave out |
| `OutputConvertersTests` | 15 | The `StructuredOutputConverter` API below `entity(...)`, as the page "Output Converters" shows it |
| `PromptPatternsTests` | 13 | The API that the page "Prompt Engineering Patterns" uses, with the examples of the page |
| `PromptTests` | 24 | Prompts, messages and prompt templates, as the page "Prompts" shows them |
| `ProviderFinishReasonTests` | 10 | How the chat models of OpenAI, Gemini and Anthropic classify why their provider stopped, including the stops that the finish reason alone does not tell, as the section "Finish Reason Categories" of the page "Responses and Streaming" describes it |
| `RecursiveAdvisorTests` | 6 | Advisors that run the rest of the chain more than once, as the page "Recursive Advisors" shows them |
| `ResponseMetadataTests` | 11 | The metadata that a response carries about its provider, as the page "Chat Response Metadata" shows it |
| `RetryTests` | 14 | Retries of failed model calls, as the page "Retry" describes them |
| `SchemaValidationTests` | 13 | The self-correcting loop of `validateSchema()` and the `StructuredOutputValidationAdvisor` behind it, as the page "Schema Validation & Self-Correction" and the section of "Recursive Advisors" on the advisor describe them |
| `StructuredOutputTests` | 9 | Structured output through `entity(...)`, as the page "Structured Output" shows it |
| `WorkflowPatternsTests` | 6 | The five workflow patterns of the page "Workflow Pattern Examples", each as a small class like the examples of the page and a scripted model, to show that the `ChatClient` API they use does what they rely on |

### Tool calling

Reference: Tool Calling, with its loop, the advisor, confirmation, tool search and argument augmentation.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `ArgumentAugmentationTests` | 3 | Tool argument augmentation |
| `ChatModelToolCallingTests` | 5 | Tool calling with a `ChatModel` and no `ChatClient` |
| `DefiningToolsTests` | 8 | The three ways to define a tool, `@Tool`, `MethodToolCallback` and `FunctionToolCallback`, and how tools reach a `ChatClient` |
| `ToolCallConfirmationTests` | 22 | Tool call confirmation with a blocking `ChatClient` call |
| `ToolCallingAdvisorTests` | 14 | `ToolCallingAdvisor` |
| `ToolCallingLoopTests` | 9 | The tool calling loop that the `ChatClient` runs, from the overview, the loop page and the tools tutorial |
| `ToolSearchTests` | 16 | The tool search tool and the guide to dynamic tool discovery |
| `ToolSpecificationTests` | 18 | The tool specification reference |

### Memory

Reference: Session API, the deprecated Chat Memory, their JDBC stores, and the migration from one to the other.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `ChatMemoryTests` | 11 | The deprecated chat memory API and its in-memory and JDBC repositories, as the pages describe them |
| `CompactionTests` | 8 | Compaction |
| `EventFilterTests` | 5 | `EventFilter` |
| `JdbcSessionRepositoryPostgresTests` | 1 | The JDBC session repository on PostgreSQL in a container |
| `JdbcSessionRepositoryTests` | 6 | The JDBC session repository on H2 in memory |
| `SessionChatClientTests` | 12 | The session advisors on a blocking `ChatClient` call |
| `SessionConceptsTests` | 10 | The session concepts and the Session API overview |
| `SessionExtendingTests` | 5 | The extension points of a session |
| `SessionMigrationTests` | 5 | Each row of the migration from chat memory to session memory, and the sessions tutorial |
| `SessionRecallTests` | 8 | Recall |
| `SessionSystemMessagesTests` | 5 | System messages and a session |
| `StreamedSessionTurnTests` | 19 | A streamed `ChatClient` call with tools on a session, as a chat UI makes it |

### Retrieval augmented generation

Reference: Retrieval Augmented Generation, Modular RAG, ETL Pipeline, Document Readers, Vector Store API, PGvector, Semantic Caching, Evaluation and Testing.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `BatchingStrategyTests` | 6 | The batching page |
| `ConcatenationDocumentJoinerTests` | 1 | The `ConcatenationDocumentJoiner` of the Modular RAG page |
| `ContentFormatTransformerTests` | 2 | The `ContentFormatTransformer` of the transformers page, of which the page says only that it "ensures uniform content formats across all documents" |
| `ContextualQueryAugmenterTests` | 6 | The `ContextualQueryAugmenter` of the Modular RAG page |
| `DecisionPostProcessorTests` | 8 | The decision post-processors of the Modular RAG page |
| `DeletingDocumentsTests` | 5 | The deleting page, with `SimpleVectorStore` |
| `EmbeddingModelApiTests` | 7 | The `EmbeddingModel` API of the embeddings page, the interface itself |
| `EtlPipelineTests` | 3 | The ETL pipeline page |
| `EvaluatorTests` | 9 | The evaluators page |
| `FileDocumentWriterTests` | 4 | The `FileDocumentWriter` of the writers page |
| `GuidelinesDocumentsTests` | 6 | The team's coding guidelines, which `GuidelinesAutoConfiguration` reads into a vector store at startup when the application has an embedding model, and only then |
| `JsonAndTextReaderTests` | 8 | The `JsonReader` and the `TextReader` of the JSON and text reader page |
| `JsoupDocumentReaderTests` | 7 | The `JsoupDocumentReader` of the HTML reader page, on the page's own sample document |
| `LlmAsJudgeTests` | 5 | The LLM-as-a-Judge page |
| `MarkdownDocumentReaderTests` | 6 | The `MarkdownDocumentReader` of the Markdown reader page, on the page's own `code.md` and on a page of the guidelines |
| `MetadataEnricherTests` | 6 | The enrichers of the transformers page, which ask a chat model for keywords or a summary of each document and put the answer into its metadata |
| `MetadataFilterTests` | 6 | The metadata filters page |
| `MultiQueryExpanderTests` | 4 | The `MultiQueryExpander` of the Modular RAG page |
| `PdfDocumentReaderTests` | 8 | The PDF readers of the document reader pages, on the five pages of the `Handbook` |
| `PgVectorDockerComposeTests` | 1 | The PGvector page's tip that PGvector can run as a Spring Boot dev service with Docker Compose, its URL, user and password then coming from the running service |
| `PgVectorStoreTests` | 17 | The PGvector page against a real PGvector that Testcontainers starts, connected through a service connection, so no URL, user or password is configured |
| `QueryTransformerTests` | 8 | The query transformers of the Modular RAG page, each asking a chat model for a better query |
| `QuestionAnswerAdvisorTests` | 10 | The `QuestionAnswerAdvisor` of the RAG page |
| `RagTutorialTests` | 2 | The "Chat with Your Documents" tutorial, with its own classes |
| `RedisSemanticCacheTests` | 9 | The semantic cache page with Redis, which Testcontainers starts (Redis 8, which has the search and JSON the cache needs) |
| `RetrievalAugmentationAdvisorTests` | 11 | The `RetrievalAugmentationAdvisor` of the RAG page |
| `SemanticCacheAdvisorTests` | 12 | The semantic cache page without Redis |
| `ServiceConnectionFactoriesTests` | 2 | The tables of the Testcontainers and Docker Compose pages |
| `TikaDocumentReaderTests` | 4 | The `TikaDocumentReader` of the document reader pages |
| `TokenTextSplitterTests` | 11 | The `TokenTextSplitter` of the transformers page |
| `VectorSimilarityTests` | 4 | The "Understanding Vectors" page |
| `VectorStoreApiTests` | 12 | The Vector Store API page, with `SimpleVectorStore` |
| `VectorStoreChatMemoryAdvisorTests` | 10 | The `VectorStoreChatMemoryAdvisor` of the chat memory page, the part of chat memory that is not deprecated |
| `VectorStoreDocumentRetrieverTests` | 4 | The `VectorStoreDocumentRetriever` of the Modular RAG page |

### Model Context Protocol

Reference: MCP Client, MCP Server (STDIO, SSE, Streamable HTTP, stateless, and the 2026-07-28 protocol), MCP Annotations, MCP Utilities.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `GuidelinesMcpServerOffTests` | 2 | The agent with its own properties |
| `GuidelinesMcpServerTests` | 4 | The agent with `agent.mcp.enabled=true` |
| `ClientPropertiesTests` | 5 | The common properties of the client starter, as the server sees their effect |
| `InteractionsTests` | 9 | What a server with sessions asks of its client while a tool runs, and what it tells it |
| `McpToolsTests` | 12 | The tools of an MCP server in a `ChatClient`, as the tutorial and "Using MCP Tools" show them |
| `ModernMcpTests` | 23 | The 2026-07-28 ("modern") protocol on both sides |
| `ServerAnnotationsTests` | 10 | The server annotations and their special parameters, as a client sees them |
| `ServerConfigurationTests` | 9 | The server pages' properties, beans and customizers |
| `ServerProtocolsTests` | 4 | The same annotated beans on every protocol and web stack that the server pages name, reached by the client of each starter |
| `StdioTests` | 4 | STDIO, where the client starts the server as a child process |
| `ToolCallbacksTests` | 6 | The tool callbacks of the legacy connections |
| `UtilitiesTests` | 4 | The utilities for programmatic clients and servers |

### Models besides chat

Reference: Embedding, Image, Audio, Moderation and Decision Models, with OpenAI, Google GenAI, Mistral AI, Ollama, Stability AI, ElevenLabs and System One.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `DecisionModelTests` | 14 | The Decision Models page, new in Spring AI 2.1 |
| `ElevenLabsSpeechTests` | 6 | The ElevenLabs Text-to-Speech page |
| `EmbeddingModelTests` | 7 | The portable `EmbeddingModel` API of the Embeddings page, through the OpenAI embedding model and a stand-in for its API |
| `GoogleGenAiEmbeddingTests` | 7 | The Google GenAI Text Embeddings page, through the Gemini Developer API of a stand-in |
| `GoogleGenAiImageTests` | 7 | The Google GenAI Image page |
| `GoogleGenAiSpeechTests` | 8 | The Google GenAI Text to Speech page |
| `GoogleGenAiTranscriptionTests` | 6 | The Google GenAI Transcriptions page |
| `ImageModelTests` | 3 | The Image Model API page |
| `MistralAiEmbeddingTests` | 6 | The Mistral AI Embeddings page |
| `MistralAiModerationTests` | 4 | The Mistral AI Moderation page |
| `ModelObservationTests` | 10 | The model sections of the Observability page and the Metrics Reference |
| `ModelSwitchesTests` | 15 | What each `spring.ai.model.*` switch does, provider by provider |
| `OllamaDecisionTests` | 3 | The Ollama Decision Models page |
| `OllamaEmbeddingTests` | 7 | The Ollama Embeddings page, against a stand-in for Ollama's `/api/embed` |
| `OpenAiClientTests` | 3 | What every OpenAI page shares |
| `OpenAiCompatibleTests` | 8 | The OpenAI-Compatible Endpoints pages (vLLM, Ollama, DeepSeek, Groq, NVIDIA, Perplexity, Docker Model Runner) |
| `OpenAiDecisionTests` | 9 | The OpenAI Decision Models page |
| `OpenAiEmbeddingTests` | 11 | The OpenAI Embeddings page |
| `OpenAiImageTests` | 10 | The OpenAI Image Generation page |
| `OpenAiModerationTests` | 5 | The OpenAI Moderation page and the portable Moderation Models page |
| `OpenAiSpeechTests` | 7 | The OpenAI Text to Speech page |
| `OpenAiTranscriptionTests` | 7 | The OpenAI Transcription page |
| `SpeechModelTests` | 6 | The Text-To-Speech API page |
| `StabilityAiImageTests` | 5 | The Stability AI Image Generation page |
| `SystemOneDecisionTests` | 13 | The System One Decision Models page |
| `TranscriptionModelTests` | 4 | The Transcription API page |

### Chat models of the providers

Reference: the chat pages of Anthropic, OpenAI (Chat Completions and Responses), Google GenAI, Mistral AI, Ollama, DeepSeek and Bedrock Converse, and the upgrade notes.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `ComparisonTests` | 3 | The columns of the chat model comparison table that the classes can answer |
| `StreamCancelTests` | 10 | Whether cancelling a stream of Spring AI closes its connection to the provider, for each chat model, over HTTP/1.1 as the stand-in speaks it |
| `UpgradingTests` | 6 | The sections of the upgrade guides to 2.0 and 2.1 that concern the options and properties of every chat provider |
| `anthropic/AnthropicChatTests` | 26 | The Anthropic chat page |
| `anthropic/AnthropicCitationsTests` | 7 | The citations page |
| `anthropic/AnthropicMigrationTests` | 7 | The rows of the page on migrating the Anthropic module to the official Java SDK that can be checked without the real API |
| `anthropic/AnthropicPromptCachingTests` | 12 | The prompt caching page |
| `anthropic/AnthropicPropertiesTests` | 7 | The `spring.ai.anthropic.*` properties of the chat page, through the auto-configuration of the starter |
| `anthropic/AnthropicSkillsAndWebSearchTests` | 7 | The skills and web search page |
| `google/GoogleGenAiChatTests` | 19 | The Google GenAI chat page |
| `google/GoogleGenAiPropertiesTests` | 6 | The `spring.ai.google.genai.*` properties of the Google GenAI chat page |
| `openai/OpenAiChatTests` | 20 | The OpenAI chat page |
| `openai/OpenAiCompatibleTests` | 6 | The OpenAI-compatible endpoints pages and the message parts of the OpenAI chat page |
| `openai/OpenAiPropertiesTests` | 7 | The `spring.ai.openai.*` properties of the chat and Responses pages, and the connection details of the OpenAI page, through the auto-configuration of the starter |
| `openai/OpenAiResponsesTests` | 14 | The Responses API page |
| `others/BedrockConverseTests` | 5 | The Bedrock Converse page, for what does not stream |
| `others/DeepSeekChatTests` | 8 | The DeepSeek page |
| `others/MistralAiChatTests` | 7 | The Mistral AI chat page, with the model module only |
| `others/OllamaChatTests` | 7 | The Ollama chat page |

### A stopped reply

The bug of Spring AI that the README describes.

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `AnthropicStopReproTests` | 2 | Cancelling a stream of Spring AI's `AnthropicChatModel` should close its connection, so that the model stops writing and billing |

### What the tests stand on

| Test class | Tests | What it shows |
| --- | ---: | --- |
| `SupportTests` | 5 | What the other tests stand on |

## What differs from the documentation, and what to know

Every entry names the test that shows it. The tests assert what Spring AI 2.1.0 does, so they pass; where that is not what the reference says, the test's name and Javadoc say so. Nothing here was checked against a real provider. Paths are in the Spring AI 2.1.0 sources.

**Bug** marks what looks like a defect, **Doc** a page that says something else than the code does, and **Know** behavior that no page describes and that is easy to trip over.

### Chat client and chat models

- **Bug.** Anthropic's native structured output sends a schema with dangling references: `AnthropicChatOptions.Builder.outputSchema` drops `$defs` and keeps the `$ref`s that point into it. A record that uses one nested type twice is enough. `NativeStructuredOutputTests#anthropicLosesTheDefinitionsThatTheSchemaRefersTo`
- **Bug.** An Anthropic message without content blocks becomes a `ChatResponse` without generations. A refusal with no text loses its stop reason, its `FILTERED` category, its id and its usage, and `entity()` returns `null` where the page promises a `ContentFilteredException`. `ProviderFinishReasonTests#anEmptyRefusalOfAnthropicLosesItsStopReasonAndEntityReturnsNull`
- **Bug.** Portable `ChatOptions` on a `Prompt` that goes straight to a chat model: Anthropic ignores them and falls back to the defaults of its options class, not even to the model's own (`claude-haiku-4-5` went out for a model configured otherwise); OpenAI and DeepSeek throw a `ClassCastException`. The chat pages say portable options can be used, the DeepSeek page's own prefix example fails this way, and so does the `ChatController` of the MCP examples page. Through a `ChatClient` they work. `ChatModelApiTests#portableOptionsOnADirectCallFailAtOpenAiAndAreIgnoredAtAnthropic`, `AnthropicChatTests#portableChatOptionsOnAPromptToTheModelAreIgnored`, `OpenAiChatTests#portableChatOptionsOnAPromptToTheModelFailWithAClassCastException`, `DeepSeekChatTests#thePrefixExampleOfThePageFailsWithAClassCastException`, `McpToolsTests#theExamplesControllerSendsTheModelNoToolAndRunsNone`
- **Bug.** `validateSchema()` checks the model's answer before any cleaner ran. An answer in a Markdown fence, which `entity()` alone accepts, fails the validation on every attempt: four model calls, and then the last answer is converted all the same. `SchemaValidationTests#aFencedAnswerFailsTheValidationThatTheConverterWouldPass`, `OutputConvertersTests#aCleanerOfYourOwnCutsTheJsonOutOfProseButValidateSchemaAsksFourTimes`
- **Bug.** A cancelled stream leaves its connection to the provider open, over HTTP/1.1, for Anthropic, OpenAI Chat Completions, the OpenAI Responses API and Gemini; the reply is written to its end. Mistral AI, Ollama and DeepSeek close theirs. See [Stopping a reply](README.md#stopping-a-reply-does-not-always-end-it-at-the-provider). `StreamCancelTests`, `AnthropicStopReproTests`
- **Bug.** Anthropic drops a fifth cache breakpoint without the one-time warning that the migration page promises: the code that warns is never reached. `AnthropicPromptCachingTests#aFifthBreakpointIsDroppedWithoutAWarning`
- **Bug.** With Anthropic skills, an `anthropic-beta` header of the request's own is sent twice, once alone and once merged with the skills' values. `AnthropicSkillsAndWebSearchTests#skillsAddTheirContainerTheCodeExecutionToolAndTheBetaHeaders`
- **Bug.** Gemini safety settings are sent without their method, also in Vertex AI mode, where the API takes one. `GoogleGenAiChatTests#safetySettingsAreSentWithoutTheirMethod`
- **Bug.** Gemini function responses go back without the id of their call, so two parallel calls of one function are matched by order alone. `GoogleGenAiChatTests#aToolLoopReplaysTheThoughtsAndTheSignatureOnTheirOwnPartsInACallAndInAStream`
- **Bug.** A top-k for OpenAI is dropped without a word when it comes through a `ChatClient`. `OpenAiChatTests#topKIsDroppedWithoutAWarningThroughAChatClient`
- **Doc.** `validateStFunctions()` works the other way round: the names of StringTemplate functions are left out of the check by default, and the method puts them in. `PromptTests#functionNamesAreLeftOutOfTheCheckUnlessValidateStFunctionsPutsThemIn`
- **Doc.** `spring.ai.chat.observations.include-error-logging` does nothing without Micrometer Tracing: its handler is only defined next to a `Tracer`. `ObservationsTests#includeErrorLoggingNeedsTracing`
- **Doc.** "The scheduler is configured through the builder of each advisor": only the deprecated `MessageChatMemoryAdvisor` has one. `AdvisorChainTests#onlyTheMessageChatMemoryAdvisorBuilderTakesAScheduler`
- **Doc.** OpenAI's finish reasons arrive as the SDK's constants, `STOP` and `CONTENT_FILTER`, not as the provider's `stop` and `content_filter`. The category is right. `ProviderFinishReasonTests#aContentFilterOfOpenAiIsFilteredUnderTheNameOfTheSdkConstant`
- **Doc.** Stop sequences of a request are added to the model's, not put in their place. `ChatClientDefaultsTests#requestStopSequencesAreAddedToThoseOfTheModel`
- **Doc.** The comparison table gives DeepSeek native structured output; its options do not implement it, and the format is asked for in the prompt. `ComparisonTests#everyChatModelButDeepSeekHasNativeStructuredOutput`, `DeepSeekChatTests#structuredOutputIsNotNativeButAskedForInThePrompt`
- **Doc.** The migration pages list `AnthropicRateLimit` as removed. It is there, and filled. `AnthropicMigrationTests#theRateLimitTypeTheMigrationPageCallsRemovedIsThere`
- **Doc.** The 2.0 guide's `AnthropicChatModel.builder().apiKey(..).defaultOptions(..)` does not compile: the builder has `options(..)`. `UpgradingTests#theAnthropicBuilderOfThe20GuideDoesNotExist`
- **Doc.** OpenAI's `User-Agent` is `spring-ai-openai`, not `spring-ai`. `OpenAiChatTests#theUserAgentIsSpringAiOpenAi`
- **Doc.** A thinking level on Gemini 2.5 Flash is refused by Spring AI itself, where the page says the API fails. `GoogleGenAiChatTests#theThinkingLevelIsCheckedAgainstTheModelsSpringAiKnows`
- **Doc.** With the Responses API, `entity()` makes a `text.format` only with the native flag. `OpenAiResponsesTests#nativeStructuredOutputBecomesTheTextFormat`
- **Doc.** The zero-shot example of the prompt patterns page limits the answer to five tokens and maps it to an enum; a bare `POSITIVE` is not JSON and cannot be converted. `PromptPatternsTests#aBareWordIsNoEnumForTheConverter`
- **Know.** A `ChatModel` of your own must return `ToolCallingChatOptions` from `getOptions()`. With the plain `ChatOptions` it has by default, the `ChatClient` leaves the tools of a request out of the prompt, without a word. `SupportTests#theChatClientRunsTheToolsTheScriptedModelAsksFor`
- **Know.** The Google GenAI SDK retries a 503 by itself, five requests and about 20 seconds, outside `spring.ai.retry`. `RetryTests#theRetryTemplateDoesNotRetryAFailedGeminiCallButTheGoogleSdkDoes`
- **Know.** `prompt(String)` is not `user(String)`: a default user text is not replaced but follows as a second user message. `ChatClientDefaultsTests#promptWithAStringKeepsTheDefaultUserTextAsASecondUserMessage`
- **Know.** The options of a `prompt(Prompt)` combine with the client's defaults, while `options(..)` replaces them. `ChatClientDefaultsTests#theOptionsOfAPromptCombineOverTheDefaultOptionsUnlikeRequestOptions`
- **Know.** An anonymous `BaseAdvisor` fails every call: its name is empty, and the observation refuses that. `CustomAdvisorTests#anAnonymousBaseAdvisorHasNoNameAndFailsTheCall`
- **Know.** Every `BaseAdvisor` in a stream wraps an error in `IllegalStateException("Stream processing failed")`; the model's error is its cause. `CustomAdvisorTests#aBaseAdvisorWrapsTheErrorsOfAStream`
- **Know.** The `SafeGuardAdvisor` matches against the texts of all messages joined without a separator: "Enter your pass" and "word count" are blocked as "password". `GuardrailsTests#aWordThatTwoMessagesMakeTogetherBlocksTheCall`
- **Know.** The logged prompt of the chat client keeps one message per role; the earlier turns are gone. `ObservationsTests#theLoggedPromptOfTheChatClientKeepsOneMessagePerRole`
- **Know.** The retry count in the warning of the auto-configured `RetryTemplate` is one counter for the whole application. `RetryTests#theRetryCountInTheWarningKeepsGrowingAcrossCalls`
- **Know.** Anthropic's prompt tokens include the tokens read from and written to its cache. `ResponseMetadataTests#anthropicReportsTheTokensReadFromAndWrittenToItsCache`
- **Know.** Gemini takes one system message; a prompt with two is refused. `GoogleGenAiChatTests#aPromptWithTwoSystemMessagesIsRefused`
- **Know.** A Gemini stream is read on the thread that subscribes: `subscribe()` returns after the whole reply. spring-chat asks the model on a worker thread for that reason. `StreamCancelTests#aGeminiStreamIsReadOnTheThreadThatSubscribes`
- **Know.** The property `spring.ai.ollama.chat.think-option` of 1.x is ignored without a word; it is `think` now. `UpgradingTests#theOllamaThinkOptionPropertyIsNowThink`

### Tool calling and memory

- **Bug.** Tool search forgets the tools it found once another tool ran, when it is the auto-registered loop and session memory is on: the loop then forwards only the newest message, and the advisor looks for found tools in the messages it gets, not in the session. `ToolSearchTests#withSessionMemoryAndTheAdvisorAsTheAutoRegisteredLoopTheFoundToolsAreForgottenOnceAnotherToolRan`
- **Doc.** After a confirmation is resumed under tool search, only the search tool is offered; the confirmation page says the found tools come back. `ToolSearchTests#aResumedRequestOffersOnlyTheSearchToolEvenWithSessionMemory`
- **Doc.** Resuming a confirmation needs the tool context in the options given to `executeToolCalls`, or a tool that takes a `ToolContext` throws. The page's examples build the options with the tools only. `StreamedSessionTurnTests#aResumeWithoutTheToolContextIsRefusedByAToolThatTakesOne`
- **Know.** A `Consumer` as a `FunctionToolCallback` answers the model `null`, where a `void` `@Tool` method answers `Done`. `DefiningToolsTests#aFunctionToolCallbackRunsAFunctionASupplierAConsumerOrABiFunction`
- **Know.** A `String` result goes to the model as JSON, with its quotes, unless Jackson can read it as JSON: "42 orders" starts with a number and goes out as it is. `ToolSpecificationTests#resultsBecomeJsonExceptForVoidAndImagesOrWhatAConverterMakesOfThem`
- **Know.** With `MessageChatMemoryAdvisor` inside the tool loop, the model gets a blank system message from the second round on, when the prompt has none. `ToolCallingLoopTests#aMemoryAdvisorInsideTheLoopStoresTheToolMessagesAndTheModelGetsABlankSystemMessage`
- **Know.** A stopped reply leaves the question in the session and no answer; the next turn sends two user messages in a row. Stopped after a tool ran, the session keeps the call and its result. `StreamedSessionTurnTests#aStoppedReplyLeavesTheQuestionWithoutAnAnswer`, `StreamedSessionTurnTests#theTurnAfterAStoppedReplySendsBothQuestionsInARow`, `StreamedSessionTurnTests#aReplyStoppedAfterItsToolRanKeepsTheCallAndItsResult`
- **Know.** Stopping a reply while a tool runs interrupts the tool's thread. The tool may have done part of its work, and no result is stored. A tool that changes something is better marked `requiresConfirmation`: its resumption runs outside the stream. `StreamedSessionTurnTests#aReplyStoppedWhileItsToolRunsInterruptsTheToolAndLeavesTheCallUnanswered`
- **Know.** In a stream, tools run without the caller's thread locals: `SecurityContextHolder` is empty in a tool, while the session advisors do restore the caller's context around their own calls. The sample gives its tools the user through the tool context. `StreamedSessionTurnTests#inAStreamTheToolsRunWithoutTheCallersSecurityContext`, `StreamedSessionTurnTests#inAStreamTheSessionIsWrittenOnAnotherThreadWithTheCallersSecurityContext`
- **Know.** A paused reply shows on the last chunk of `stream().chatResponse()` only; `stream().content()` simply ends. `StreamedSessionTurnTests`
- **Know.** `EventQuery` drops blank and repeated keywords before it checks its limit of twenty. `SessionRecallTests#aQueryOutsideTheBoundsIsRejected`

### Retrieval augmented generation

- **Bug.** `RetrievalAugmentationAdvisor` fails with "Duplicate key" when a variant of the `MultiQueryExpander` repeats the original query, which the expander keeps by default. `RetrievalAugmentationAdvisorTests#aVariantThatRepeatsTheOriginalQueryFailsTheRequest`
- **Bug.** `DefaultSemanticCache` with a distance threshold: storing without a context passes the distance as a similarity when it looks for an entry to replace, so storing "capital of Spain" deletes the cached "capital of France". `RedisSemanticCacheTests#withADistanceThresholdStoringWithoutAContextReplacesALooselySimilarEntry`
- **Bug.** `DefaultSemanticCache.set(query, response, Duration)` cuts the time to live to whole seconds; under a second deletes the entry at once. The path with a context keeps milliseconds. `RedisSemanticCacheTests#aTtlWithoutAContextIsCutToWholeSeconds`
- **Bug.** `PgVectorStore` applies a threshold as `distance < 1 - threshold` and scores as `1 - distance` whatever the distance type. With the Euclidean distance the "accept all" threshold leaves documents out, and the scores are no similarities. `PgVectorStoreTests#withTheEuclideanDistanceTheAcceptAllThresholdLeavesDocumentsOut`
- **Bug.** A `RetrievalAugmentationAdvisor` built without a task executor makes a thread pool of its own and never shuts it down. The sample gives it an executor. `RetrievalAugmentationAdvisorTests#theDefaultsRemoveDuplicatesSortByScoreAndPropagateTheCallersContext`
- **Doc.** Templates that "must contain" placeholders are not checked by `QuestionAnswerAdvisor`, `VectorStoreChatMemoryAdvisor` and `RelevancyEvaluator`: without the placeholder the context, the instructions or the memory are dropped in silence. `QuestionAnswerAdvisorTests#aTemplateWithoutTheContextPlaceholderIsAcceptedAndSendsNoContext`, `VectorStoreChatMemoryAdvisorTests#aTemplateWithoutTheInstructionsIsAcceptedAndDropsTheSystemPrompt`, `EvaluatorTests#aTemplateWithoutTheContextIsAcceptedAndTheJudgeDoesNotSeeIt`
- **Doc.** `SimpleVectorStore` takes a `BatchingStrategy` and ignores it: one document per call. `BatchingStrategyTests#theSimpleStoreIgnoresItsBatchingStrategyAndEmbedsOneDocumentPerCall`
- **Doc.** `maxNumChunks` of the `TokenTextSplitter` is no maximum: the rest of the text becomes one more chunk of any length. `TokenTextSplitterTests#afterMaxNumChunksTheRestOfTheTextIsOneMoreChunkOfAnyLength`
- **Doc.** `ContentFormatTransformer` does not make formats uniform: between two default formatters it only merges the excluded metadata keys. `ContentFormatTransformerTests#aDefaultFormatterOnlyAddsItsExcludedKeysToTheDocumentsOwn`
- **Doc.** The Markdown reader keeps the text of inline code, lists and styling, but not the markup, and an included code block joins the text before it only. `MarkdownDocumentReaderTests#theTextOfFormattedMarkdownIsKeptButNotItsMarkup`, `MarkdownDocumentReaderTests#anIncludedCodeBlockJoinsTheTextBeforeItButNotTheTextAfterIt`
- **Doc.** The LLM-as-a-judge page gives its advisor the order 0 next to a tool. In 2.1 that is inside the tool loop: the judge rates the round that calls the tool, and cannot have a bad answer written again. `LlmAsJudgeTests#atOrderZeroTheJudgeRatesTheToolCallRoundAndCannotFixTheToolsAnswer`
- **Doc.** The PGvector page's property table lacks `spring.ai.vectorstore.pgvector.id-type`; the default takes UUIDs only. `PgVectorStoreTests#aTextIdTypeTakesAnyId`
- **Doc.** A `pgvector/pgvector` service in a Docker Compose file is not recognized by Spring Boot 4.2 without the label `org.springframework.boot.service-connection: postgres`. `PgVectorDockerComposeTests#theLabelledPgvectorServiceBecomesTheDataSourceOfTheStore`
- **Doc.** Filters share a syntax across stores, not a meaning: for `SimpleVectorStore` a missing value is smaller than any other, so a document without a year matches `year <= 2020`; PGvector matches neither. A dotted key is one flat key. `MetadataFilterTests#builtExpressionsFilterTheSameFilms`, `MetadataFilterTests#aDottedKeyIsOneKeyForTheSimpleStore`, `PgVectorStoreTests#aDocumentWithoutTheKeyDoesNotMatchAComparison`
- **Know.** By default, a `RetrievalAugmentationAdvisor` that finds nothing replaces the question with "The user query is outside your knowledge base": neither the model nor a tool ever sees it. The sample allows an empty context. `RetrievalAugmentationAdvisorTests#byDefaultAnEmptyContextReplacesTheQuestionWithAnInstructionNotToAnswer`
- **Know.** The evaluators pass on the bare word only: "YES." fails. `EvaluatorTests#onlyAPlainYesPasses`
- **Know.** A text splitter adds three keys of its own to every chunk, one of them a random id, and a model that embeds metadata, as OpenAI's does, embeds them. The sample's guidelines keep them out. `TokenTextSplitterTests#everyChunkGetsTheMetadataOfItsDocumentAndThreeKeysOfTheSplitter`
- **Know.** A document without metadata is embedded, and written to a file, with two line breaks before its text. `OpenAiEmbeddingTests#metadataModeNoneSendsTheTextAfterAnEmptyMetadataBlock`
- **Know.** The PDF text formatter counts the line break at the end of a page as a line, and pages from one: deleting one bottom line leaves the footer, skipping two pages skips one. `PdfDocumentReaderTests#theTextFormatterDeletesLinesAtTheTopAndTheBottomOfEachPage`
- **Know.** A semantic cache hit is the text only, without finish reason, usage or model, and `clear()` does nothing on a store other than Redis. `SemanticCacheAdvisorTests#aCachedResponseHasTheTextButNotTheMetadata`, `SemanticCacheAdvisorTests#clearOnAStoreOtherThanRedisDoesNothing`
- **Know.** An answer of the `MultiQueryExpander`'s model with another number of lines than asked for leaves only the original query. `MultiQueryExpanderTests#anAnswerWithTheWrongNumberOfLinesLeavesOnlyTheOriginalQuery`
- **Know.** Post-processors and the query augmenter get the original query, not the transformed one. `RetrievalAugmentationAdvisorTests#postProcessorsSeeTheOriginalQueryAndTheirResultIsTheContext`
- **Know.** Tika and the Markdown reader do not go together: Maven resolves an older commonmark than Tika's Markdown parser needs, which then fails to load with a warning.

### Model Context Protocol

- **Bug.** A stateless server with `spring.ai.mcp.server.tool-callback-converter=false` also loses its annotated methods; the pages say the switch stops `ToolCallback` beans only. The guidelines server lists its specifications itself for that reason. `McpToolsTests#onAStatelessServerTurningTheConverterOffAlsoDropsTheAnnotatedTools`
- **Bug.** `@McpResource` never publishes its `title` and `annotations`. `ServerAnnotationsTests#mcpResourcePublishesItsUriNameMimeTypeSizeAndMetaButNotItsTitleOrAnnotations`
- **Bug.** Roots that a client customizer sets are not declared while the annotation scanner is on, which is the default, so a server never asks for them. `InteractionsTests#withTheAnnotationScannerOnTheRootsOfACustomizerAreNotDeclared`
- **Bug.** `spring.ai.mcp.client.root-change-notification` is read nowhere. `InteractionsTests#theRootChangeNotificationPropertyChangesNothing`
- **Bug.** The WebMVC and the WebFlux server starter cannot share a class path: both make a transport, and the server fails to start. `ServerConfigurationTests#theWebMvcAndWebFluxServerStartersCannotBeCombined`
- **Bug.** `spring.ai.mcp.server.tool-response-mime-type` makes image data with JSON quotes in it, which is no base64. `ServerConfigurationTests#toolCallbackBeansAreToolsAndAListBeanComesBeforeASingleOneOfTheSameName`
- **Doc.** The counter in the prefix of a duplicate tool name is one for all names, so the prefixes depend on the order of listing. `ToolCallbacksTests#theSecondToolOfOneNameGetsACounterPrefixAndHyphensBecomeUnderscores`
- **Doc.** The auto-configured client is named `<name> - <connection>`, so the filter example of the tools page, which compares the name with `restricted-client`, never matches. `ClientPropertiesTests#theNameAndVersionOfTheClientReachTheServerWithTheConnectionsName`, `ToolCallbacksTests#aToolFilterSeesTheConnectionAndTheTool`
- **Know.** By default every entry of the tool context goes to every MCP server as `_meta`. The sample puts the signed-in user into the tool context, so an application that gives its chat MCP tools needs a `ToolContextToMcpMetaConverter` that keeps it back. `ToolCallbacksTests`, `ModernMcpTests`
- **Know.** `McpSyncRequestContext.requestMeta()` is `null` for a request without `_meta`, and `requestMeta().get(..)` as the pages write it then fails. `ServerAnnotationsTests#theSpecialParametersReceiveTheRequestAndAreNotInTheSchema`
- **Know.** A checked exception of an `@McpTool` comes back as "Internal error" without its message; a runtime exception is an error result. `ServerAnnotationsTests#aRuntimeExceptionIsAnErrorResultAndACheckedOneFailsTheCall`
- **Know.** Only the modern transport checks the `Origin` of a request. `ServerConfigurationTests#onlyTheModernTransportChecksTheOriginOfARequest`

### Models besides chat

- **Bug.** Options given at runtime reset what they do not set to the defaults of their class: OpenAI's image model goes back to `gpt-image-1-mini`, its speech model, voice, format and speed to theirs, its transcription model to `whisper-1`, Ollama's embedding model to `mxbai-embed-large`. The image page's own runtime example does this. `OpenAiImageTests#runtimeOptionsWithoutAModelPutTheModelBackToGptImage1Mini`, `OpenAiSpeechTests#runtimeOptionsPutWhatTheyDoNotSetBackToTheDefaultsOfTheClass`, `OpenAiTranscriptionTests#runtimeOptionsPutTheModelAndTheFormatBackToTheDefaults`, `OllamaEmbeddingTests#requestOptionsWithoutAModelPutTheModelBackToMxbaiEmbedLarge`
- **Bug.** Portable options drop most of a provider's configured defaults, and the shortcut methods that the pages' sample controllers use carry portable options. `GoogleGenAiImageTests#aPromptWithoutGoogleOptionsKeepsOnlyTheModelAndTheNumberOfTheDefaults`, `GoogleGenAiSpeechTests#aPromptWithoutGoogleOptionsKeepsOnlyTheModelAndTheVoiceOfTheDefaults`, `OllamaEmbeddingTests#theKeepAliveAndTruncatePropertiesAreDroppedByTheShortcutMethods`
- **Bug.** Mistral AI ignores `spring.ai.mistralai.base-url`: each model has a base URL of its own with a default, which wins. With only the common one set, requests go to the real API. `MistralAiEmbeddingTests#theCommonBaseUrlIsIgnoredBecauseTheEmbeddingBaseUrlHasADefault`
- **Bug.** Some starters stop an application whatever `spring.ai.model.*` says, because their connection has no switch and fails without configuration: the Google GenAI starters besides chat, Vertex AI, System One, and Mistral's OCR model, which listens to `spring.ai.model.ocr`. `ModelSwitchesTests#withoutAKeyTheGoogleGenAiStartersStopTheApplicationWhateverTheSwitchesSay`, `ModelSwitchesTests#withoutAKeyTheMistralStarterStopsTheApplicationEvenWithTheSwitchesOfTheSample`, `ModelSwitchesTests#withoutAKeyTheSystemOneStarterStopsTheApplicationUnlessTheDecisionSwitchIsOff`
- **Bug.** Vertex AI fails next to the Google GenAI starter with a `NoClassDefFoundError`: Maven takes an older `google-auth-library` than Vertex AI's client needs, and the BOM does not align them. `ModelSwitchesTests#theVertexAiEmbeddingStarterNeedsAProjectWhateverTheSwitchesAndFailsNextToGoogleGenAi`
- **Bug.** The Google GenAI embedding model never sends the task type or the title, and still validates them. `GoogleGenAiEmbeddingTests#theTaskTypeAndTheTitleAreNotSent`
- **Bug.** `dimensions()` asks the API even for a model in the table of known dimensions, because the table is looked up with the wrong name; Google's reports the size of the model and not the reduced one. `EmbeddingModelTests#dimensionsAsksTheApiEvenForAModelOfTheTableOfKnownDimensions`, `MistralAiEmbeddingTests#dimensionsAsksTheApiEvenForAModelItKnows`, `GoogleGenAiEmbeddingTests#dimensionsReportsTheSizeOfTheModelAndNotTheReducedOne`
- **Bug.** `new ImagePrompt(List<ImageMessage>)` has no options, and OpenAI's image model fails on that before it sends anything. `OpenAiImageTests#aPromptOfMessagesWithoutOptionsFailsBeforeItIsSent`
- **Bug.** Stability AI ignores a model in the options of a prompt. `StabilityAiImageTests#theModelOfThePromptOptionsIsIgnored`
- **Bug.** OpenAI's moderation drops the categories `illicit` and `illicit/violent`: a text flagged only for them is flagged with every category false. `OpenAiModerationTests#theCategoriesOfMistralStayFalseAndOpenAisIllicitIsDropped`
- **Bug.** Image observations: the size key is built from width and height only, so OpenAI's `size` leaves it out, and there are no usage keys for OpenAI. `ModelObservationTests#anImageCallIsAnImageOperationWithTheSizeTheFormatAndTheStyleButNoUsageForOpenAi`
- **Doc.** The default base URL of OpenAI is `https://api.openai.com/v1`, and a base URL is used as it is: with the `https://api.openai.com` of the connection tables, requests go to `/embeddings` without `/v1`. `OpenAiEmbeddingTests#theBaseUrlIsUsedAsItIsWithoutAddingV1`
- **Doc.** Portable `TextToSpeechOptions` are recommended for code that does not depend on a provider; ElevenLabs ignores them. `SpeechModelTests#aPortableVoiceReachesOpenAiAndGoogleButNotElevenLabs`
- **Doc.** Some documented defaults are not sent, so the server's own apply: Google's image aspect ratio, Ollama's keep-alive and truncate. `GoogleGenAiImageTests#noAspectRatioIsSentUnlessOneIsSet`, `OllamaEmbeddingTests#keepAliveAndTruncateAreNotSentUnlessSet`
- **Know.** The decision model of 2.1 has a switch of its own, `spring.ai.model.decision`. An application that turned off the models it does not use for 2.0 has an `OpenAiDecisionModel` after the upgrade. The sample had. `ModelSwitchesTests#withoutTheSwitchOfTheDecisionModelTheApplicationHasOne`
- **Know.** The Google GenAI and Vertex AI text embeddings listen to `spring.ai.model.embedding.text`, not to `spring.ai.model.embedding`. `ModelSwitchesTests#theSwitchesOfTheSampleLeaveTheGoogleGenAiEmbeddingModelOn`
- **Know.** An empty OpenAI key sends no `Authorization` header. `OpenAiCompatibleTests#anEmptyKeySendsNoAuthorizationHeader`
- **Know.** Mistral's moderation reports dangerous, criminal and jailbreaking content as categories of their own; the combined one is deprecated and stays false. `MistralAiModerationTests#dangerousCriminalAndJailbreakingAreCategoriesOfTheirOwn`

### The application and spring-chat

- **Know.** Spring Boot 4.2 writes a space after `data:` in a server-sent event. A browser drops it; a test that compares the bytes does not.
- **Know.** The Anthropic client of 2.1 sends its key as `Authorization: Bearer`, and drops `temperature`, `topP` and `topK` with a warning.
- **Know.** `AnthropicStopReproTests#cancellingAStreamClosesItsConnection` shows a race. It failed in every run on its own, and passed in some runs of the whole suite.

## Not verified

Nothing was checked against a real provider: the suite has no key, and makes no request outside the machine it runs on. What a provider's API does with a request is therefore not known from here, only what Spring AI sends and what it makes of an answer in the provider's format.

Beyond that, these were left out, each for the reason given:

- **The other twenty vector stores** (Apache Cassandra, Azure AI Search, Bedrock Knowledge Base, Chroma, Coherence, Couchbase, Elasticsearch, GemFire, MariaDB, Milvus, MongoDB Atlas, Neo4j, OpenSearch, Oracle, Pinecone, Qdrant, Redis as a vector store, S3 Vectors, Typesense, Weaviate). Each needs its own server. The Vector Store API is verified with `SimpleVectorStore` and with PGvector in a container; for the others only the tables of service connections were checked against the classes.
- **Chat memory repositories** for Cassandra, Neo4j, MongoDB, Redis and Cosmos DB, and the JDBC dialects other than H2. The session repository ran Spring AI's own contract on H2 and on PostgreSQL 17; its MySQL, MariaDB and SQL Server dialects did not run.
- **Models that need a download or a server:** Transformers (its ONNX model is downloaded from GitHub on first use), PostgresML, the default model of the semantic cache (downloaded from Hugging Face).
- **Models that need cloud credentials:** Vertex AI's requests and replies, Google GenAI in Vertex AI mode for the models besides chat, Azure and Microsoft Foundry with Microsoft Entra credentials (the Foundry mode with an API key is verified).
- **Chat models:** Bedrock Converse streaming, whose binary event stream the stand-in does not speak (calls, tools, a cache point and structured output are verified); Qxotic jinfer, which is not in the BOM of 2.1.0; image and PDF input of the Responses API; audio and video input and cached content of Gemini; the Mistral AI properties, because its starter cannot be on the test class path next to an application without a Mistral key.
- **A cancelled stream over HTTP/2.** The stand-in speaks HTTP/1.1.
- **What only time shows:** timeouts, keep-alive intervals, back-off, proxies. The tests wait for nothing.
- **Keys read from environment variables.**
- **MCP:** MCP Security, a community module whose page names no version; STDIO on Windows; the asynchronous modern client's handlers; some server properties (`request-timeout`, `page-size`, subscriptions, the request state codec).
- **Tool search:** the vector and the decision index, and the eviction strategies.
- **Sessions:** asynchronous and concurrent compaction, which Spring AI's own tests cover, and the options of the summarizer.
- **Observability:** the wiring through Spring Boot Actuator, tracing spans and OTLP. The observations and metrics are verified with Micrometer wired as Spring Boot does it.
- **Community libraries** that the pages point to: Spring AI Agent Utils, Session Extensions, Decision Extensions.
- **Retrieval quality.** The embedding model of the tests is a bag of words. It shows that a store stores, searches and filters, and nothing about meaning.
