# Spring AI RAG — Retrieval-Augmented Generation with Spring AI

> ⚠️ **This project is for learning purposes only.**

---

## About

This project demonstrates how to build a **Retrieval-Augmented Generation (RAG)** application using **Spring AI** and **OpenAI**. It was developed as a hands-on exercise while following the course:

🎓 [Spring AI: Beginner to Guru — Udemy](https://www.udemy.com/course/spring-ai-beginner-to-guru/)

All credits for the course content and architecture guidance go to the author:

👤 [John Thompson — LinkedIn](https://www.linkedin.com/in/springguru/)

---

## Tech Stack

| Technology        | Version     |
|-------------------|-------------|
| Java              | 21          |
| Spring Boot       | 3.3.6       |
| Spring AI         | 1.0.0-M5    |
| OpenAI API        | —           |
| Embedding Model   | text-embedding-3-small |
| Lombok            | —           |
| Maven             | Wrapper     |

---

## What is RAG?

**Retrieval-Augmented Generation (RAG)** is an AI pattern that enhances Large Language Model (LLM) responses by first retrieving relevant documents from a knowledge base (vector store) and injecting them as context into the prompt. This allows the model to answer questions based on your own data, not just its training knowledge.

---

## Project Structure

```
src/
└── main/
    ├── java/guru/springframework/springairag/
    │   ├── SpringAiRagApplication.java          # Application entry point
    │   ├── config/
    │   │   ├── VectorStoreConfig.java            # SimpleVectorStore bean — loads or builds the vector store
    │   │   └── VectorStoreProperties.java        # External config properties (sfg.aiapp)
    │   ├── model/
    │   │   ├── Question.java                     # Record — incoming question payload
    │   │   └── Answer.java                       # Record — outgoing answer payload
    │   ├── service/
    │   │   ├── OpenAiService.java                # Service interface
    │   │   └── OpenAiServiceImpl.java            # Performs similarity search, builds RAG prompt, calls OpenAI
    │   └── resource/
    │       └── QuestionController.java           # REST controller — exposes POST /ask
    └── resources/
        ├── application.properties                # App settings
        ├── movies500.csv                         # Full movie dataset
        ├── movies500Trimmed.csv                  # Trimmed movie dataset used by default
        └── templates/
            ├── rag-prompt-template.st            # Basic RAG prompt template
            └── rag-prompt-template-meta.st       # RAG prompt template with column metadata
```

---

## Key Source Files

### `QuestionController.java`

REST controller that exposes the `POST /ask` endpoint. It delegates the question to `OpenAiService` and returns the answer as JSON.

```java
@RestController
@AllArgsConstructor
public class QuestionController {

    private final OpenAiService openAiService;

    @PostMapping("/ask")
    public Answer askQuestion(@RequestBody Question question) {
        return openAiService.getAnswer(question);
    }
}
```

### `OpenAiServiceImpl.java`

Core service that implements the RAG pattern:

1. Performs a **similarity search** against the `SimpleVectorStore` using the user's question (top 4 results).
2. Extracts the text content from the matched documents.
3. Builds a **prompt** using the `rag-prompt-template-meta.st` template, injecting the question and retrieved documents.
4. Calls the **ChatModel** (OpenAI) and returns the answer.

```java
@RequiredArgsConstructor
@Service
public class OpenAiServiceImpl implements OpenAiService {

    private final ChatModel chatModel;
    private final SimpleVectorStore vectorStore;

    @Value("classpath:templates/rag-prompt-template-meta.st")
    private Resource ragPromptTemplate;

    @Override
    public Answer getAnswer(Question question) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest
                .builder()
                .query(question.question()).topK(4)
                .build());

        assert documents != null;
        List<String> contentList = documents.stream().map(Document::getText).toList();

        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);
        Prompt prompt = promptTemplate.create(Map.of(
                "input", question.question(),
                "documents", String.join("\n", contentList)));

        ChatResponse response = chatModel.call(prompt);
        return new Answer(response.getResult().getOutput().getText());
    }
}
```

### `VectorStoreConfig.java`

On startup, checks if a previously persisted vector store file exists. If so, it loads it directly. Otherwise, it reads the configured documents using `TikaDocumentReader`, splits them with `TokenTextSplitter`, adds the chunks to the `SimpleVectorStore`, and saves the result to disk for future reuse.

```java
@Configuration
@Slf4j
public class VectorStoreConfig {

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel,
                                                VectorStoreProperties vectorStoreProperties) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();
        File vectorStoreFile = new File(vectorStoreProperties.getVectorStorePath());

        if (vectorStoreFile.exists()) {
            simpleVectorStore.load(vectorStoreFile);
        } else {
            vectorStoreProperties.getDocumentsToLoad().forEach(document -> {
                TikaDocumentReader documentReader = new TikaDocumentReader(document);
                List<Document> docs = documentReader.get();
                TextSplitter splitter = new TokenTextSplitter();
                List<Document> splitDocs = splitter.split(docs);
                simpleVectorStore.add(splitDocs);
            });
            simpleVectorStore.save(vectorStoreFile);
        }
        return simpleVectorStore;
    }
}
```

### `VectorStoreProperties.java`

Binds the `sfg.aiapp` configuration prefix to typed properties used by `VectorStoreConfig`.

```java
@Configuration
@ConfigurationProperties(prefix = "sfg.aiapp")
public class VectorStoreProperties {
    private String vectorStorePath;
    private List<Resource> documentsToLoad;
    // getters and setters
}
```

### RAG Prompt Template (`rag-prompt-template-meta.st`)

```text
You are a helpful assistant, conversing with a user about the subjects contained
in a set of documents.
Use the information from the DOCUMENTS section to provide accurate answers.
If unsure or if the answer isn't found in the DOCUMENTS section, simply state
that you don't know the answer.

QUESTION:
{input}

The DOCUMENTS are in a tabular dataset containing the following columns:
id, title, genres, original_language, overview, production_companies,
release_date, budget (USD), revenue (USD), runtime (in minutes), credits (cast)

DOCUMENTS:
{documents}
```

---

## API Usage

### `POST /ask`

Sends a question to OpenAI and returns the AI-generated answer augmented with data from the vector store.

**Request body:**
```json
{
  "question": "What is the best sci-fi movie of the 90s?"
}
```

**Response body:**
```json
{
  "answer": "Many consider The Matrix (1999) to be the best sci-fi film of the 90s..."
}
```

**Example with curl:**
```bash
curl -X POST http://localhost:8080/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is the best sci-fi movie of the 90s?"}'
```

---

## Architecture Overview

```
Client
  │
  ▼
QuestionController (POST /ask)
  │
  ▼
OpenAiServiceImpl
  │  1. Similarity search against SimpleVectorStore (top 4 documents)
  │  2. Build RAG prompt with retrieved documents as context
  │  3. Call ChatModel (OpenAI)
  │
  ▼
Answer (returned as JSON)
```

The `SimpleVectorStore` is populated on startup by reading `movies500Trimmed.csv`,
splitting its content via `TokenTextSplitter`, generating embeddings through the
`EmbeddingModel` (`text-embedding-3-small`), and persisting the result to a local
JSON file. On subsequent startups the vector store is loaded directly from that
file, skipping the embedding step.

---

## Configuration

Set the following environment variable before running the application:

```bash
OPENAI_API_KEY=your-openai-api-key-here
```

Key settings in `application.properties`:

```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small

# Resolves to the OS temp directory (cross-platform)
sfg.aiapp.vectorStorePath=${java.io.tmpdir}vectorstore.json

# Document(s) to embed into the vector store
sfg.aiapp.documentsToLoad[0]=classpath:/movies500Trimmed.csv
```

> **Tip:** To force re-indexing of the documents, delete the `vectorstore.json`
> file from your system's temp directory and restart the application.

---

## Running the Application

```bash
./mvnw spring-boot:run
```

Or on Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

---

## Data Source

The movie dataset files (`movies500.csv` / `movies500Trimmed.csv`) used to populate the vector store were sourced from **[Kaggle](https://www.kaggle.com/)**, the world's largest platform for data science and machine learning datasets.

> All data credits go to the original dataset authors on Kaggle.

---

## Credits

This project is based on the course **[Spring AI: Beginner to Guru](https://www.udemy.com/course/spring-ai-beginner-to-guru/)** by **John Thompson**.
Follow the author on LinkedIn: [linkedin.com/in/springguru](https://www.linkedin.com/in/springguru/)

---

## License

This repository is intended for **educational purposes only** and is not meant for production use.
