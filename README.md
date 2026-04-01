# Spring AI RAG Expert — Retrieval-Augmented Generation with Milvus

> ⚠️ **This project is for learning purposes only.**

---

## About

This project demonstrates how to build a **Retrieval-Augmented Generation (RAG)** application using **Spring AI**, **OpenAI**, and **Milvus** as the vector database. It was developed as a hands-on exercise while following the course:

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
| Chat Model        | gpt-4-turbo |
| Embedding Model   | text-embedding-3-small |
| Vector Store      | **Milvus** (standalone via Docker) |
| Milvus SDK        | 2.3.5       |
| Apache Tika       | via spring-ai-tika-document-reader |
| Lombok            | —           |
| Maven             | Wrapper     |

---

## What is RAG?

**Retrieval-Augmented Generation (RAG)** is an AI pattern that enhances Large Language Model (LLM) responses by first retrieving relevant documents from a knowledge base (vector store) and injecting them as context into the prompt. This allows the model to answer questions based on your own data, not just its training knowledge.

---

## Changes from Previous Version

| Area | Before | After |
|------|--------|-------|
| **Vector Store** | `SimpleVectorStore` (in-memory, persisted to local JSON file) | **Milvus** (dedicated vector database running in Docker) |
| **Data Source** | Movie dataset (`movies500Trimmed.csv`) | Tow vehicle specs (`towvehicles.txt`) + Yamaha boat performance bulletins (loaded from URLs) |
| **Document Loading** | `VectorStoreConfig` bean (loads/saves a `SimpleVectorStore` at startup) | `LoadVectorStore` `CommandLineRunner` (checks if Milvus collection already has data; loads if empty) |
| **Prompt Strategy** | Single RAG prompt template with tabular movie metadata | Two-template approach: a **system message** (domain-specific reasoning rules for truck-to-boat matching) + a **user RAG prompt** |
| **Schema Init** | Not applicable (in-memory store) | `initialize-schema: true` — Milvus collection is auto-created on startup |
| **Infrastructure** | None | Docker Compose stack (Milvus standalone + etcd + MinIO) |
| **Configuration** | `application.properties` | `application.yaml` |

---

## Project Structure

```
spring-ai-rag-expert-leandro/
├── docker/
│   └── docker-compose.yml                       # Milvus standalone infrastructure
├── src/
│   └── main/
│       ├── java/guru/springframework/springairagexpert/
│       │   ├── SpringAiRagExpertApplication.java # Application entry point
│       │   ├── bootstrap/
│       │   │   └── LoadVectorStore.java          # CommandLineRunner — loads documents into Milvus on first run
│       │   ├── config/
│       │   │   └── VectorStoreProperties.java    # External config properties (sfg.aiapp)
│       │   ├── controllers/
│       │   │   └── QuestionController.java       # REST controller — exposes POST /ask
│       │   ├── model/
│       │   │   ├── Answer.java                   # Record — outgoing answer payload
│       │   │   └── Question.java                 # Record — incoming question payload
│       │   └── services/
│       │       ├── OpenAIService.java            # Service interface
│       │       └── OpenAIServiceImpl.java        # Performs similarity search, builds RAG prompt, calls OpenAI
│       └── resources/
│           ├── application.yaml                  # App settings (OpenAI + Milvus)
│           ├── towvehicles.txt                   # Tow vehicle dataset
│           └── templates/
│               ├── rag-prompt-template.st        # RAG user prompt template
│               └── system-message.st             # System prompt with domain reasoning rules
├── pom.xml
├── mvnw / mvnw.cmd
└── README.md
```

---

## Key Source Files

### `QuestionController.java`

REST controller that exposes the `POST /ask` endpoint. It delegates the question to `OpenAIService` and returns the answer as JSON.

```java
@RequiredArgsConstructor
@RestController
public class QuestionController {

    private final OpenAIService openAIService;

    @PostMapping("/ask")
    public Answer askQuestion(@RequestBody Question question) {
        return openAIService.getAnswer(question);
    }
}
```

### `OpenAIServiceImpl.java`

Core service that implements the RAG pattern:

1. Creates a **system message** from `system-message.st` containing domain-specific reasoning rules for truck-to-boat matching.
2. Performs a **similarity search** against the Milvus vector store using the user's question (top 5 results).
3. Builds a **user message** using the `rag-prompt-template.st` template, injecting the question and retrieved documents.
4. Calls the **ChatModel** (OpenAI `gpt-4-turbo`) with both messages and returns the answer.

```java
@RequiredArgsConstructor
@Service
public class OpenAIServiceImpl implements OpenAIService {

    final ChatModel chatModel;
    final VectorStore vectorStore;

    @Value("classpath:/templates/rag-prompt-template.st")
    private Resource ragPromptTemplate;

    @Value("classpath:/templates/system-message.st")
    private Resource systemMessageTemplate;

    @Override
    public Answer getAnswer(Question question) {
        PromptTemplate systemMessagePromptTemplate = new SystemPromptTemplate(systemMessageTemplate);
        Message systemMessage = systemMessagePromptTemplate.createMessage();

        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question.question()).topK(5).build());
        List<String> contentList = documents.stream().map(Document::getContent).toList();

        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);
        Message userMessage = promptTemplate.createMessage(Map.of("input", question.question(),
                "documents", String.join("\n", contentList)));

        ChatResponse response = chatModel.call(new Prompt(List.of(systemMessage, userMessage)));
        return new Answer(response.getResult().getOutput().getContent());
    }
}
```

### `LoadVectorStore.java`

A `CommandLineRunner` that populates the Milvus vector store on application startup. It first attempts a similarity search to check whether data already exists. If the collection is empty **or does not yet exist** (gracefully handled via try-catch), it reads all configured documents using `TikaDocumentReader`, splits them with `TokenTextSplitter`, and adds the chunks to the vector store.

```java
@Slf4j
@Component
public class LoadVectorStore implements CommandLineRunner {

    @Autowired
    VectorStore vectorStore;

    @Autowired
    VectorStoreProperties vectorStoreProperties;

    @Override
    public void run(String... args) throws Exception {
        log.debug("Loading vector store...");

        boolean needsLoading = false;
        try {
            needsLoading = vectorStore.similaritySearch("Sportsman").isEmpty();
        } catch (Exception e) {
            log.debug("Vector store collection not yet available, will load documents: {}",
                    e.getMessage());
            needsLoading = true;
        }

        if (needsLoading) {
            log.debug("Loading documents in vector store");
            vectorStoreProperties.getDocumentsToLoad().forEach(document -> {
                log.debug("Loading document " + document.getFilename());

                TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(document);
                List<Document> documents = tikaDocumentReader.read();
                TextSplitter textSplitter = new TokenTextSplitter();
                List<Document> splitDocuments = textSplitter.apply(documents);

                vectorStore.add(splitDocuments);
            });
        }
        log.debug("Vector store loaded");
    }
}
```

### `VectorStoreProperties.java`

Binds the `sfg.aiapp` configuration prefix to typed properties used by `LoadVectorStore`.

```java
@Configuration
@ConfigurationProperties(prefix = "sfg.aiapp")
public class VectorStoreProperties {
    private String vectorStorePath;
    private List<Resource> documentsToLoad;
    // getters and setters
}
```

### System Message Template (`system-message.st`)

Domain-specific reasoning rules that instruct the LLM how to match trucks to boats:

```text
Recommend the lowest cost truck that can tow the boat.
Do not spend too much when selecting a truck to tow the boat.
When towing a boat, the boat's weight as tested should be used.
Trucks cannot pull boats with a tested weight greater than their towing capacity.
If the boat's weight is less than or equal to the truck's towing capacity, the truck can tow the boat.
If the boat's weight is greater than the truck's towing capacity, the truck cannot tow the boat.

To determine the cheapest truck that can tow the boat, the program will:
- Sort the known trucks by towing capacity in ascending order.
- Compare the boat's weight to the towing capacity of each truck.
- Recommend the cheapest truck that can tow the boat.
```

### RAG Prompt Template (`rag-prompt-template.st`)

```text
You are a helpful assistant, conversing with a user about the subjects contained
in a set of documents.
Use the information from the DOCUMENTS section to provide accurate answers.
If unsure or if the answer isn't found in the DOCUMENTS section, simply state
that you don't know the answer.

QUESTION:
{input}

DOCUMENTS:
{documents}
```

---

## Data Sources

The knowledge base consists of:

1. **`towvehicles.txt`** — Chevy truck models with prices and maximum towing capacities:
   - Chevy Traverse — $43,000 — up to 5,000 lbs
   - Chevy Colorado — $55,000 — up to 7,000 lbs
   - Chevy 1500 — $65,000 — up to 9,100 lbs
   - Chevy 2500 — $75,000 — up to 14,500 lbs
   - Chevy 3500 — $80,000 — up to 18,500 lbs
   - Chevy 3500 Duramax — $90,000 — up to 36,000 lbs

2. **Yamaha Performance Bulletins** — Boat specifications (weight, engine data) loaded from remote URLs at startup via Apache Tika:
   - Sportsman Open 212 (3,458 lbs)
   - Sportsman Open 232 (5,001 lbs)
   - Sportsman Open 322 (12,469 lbs)
   - Scout 380 LXF (19,443 lbs)

---

## Infrastructure — Docker Compose (Milvus)

The project uses a **Milvus standalone** deployment orchestrated via Docker Compose. The stack includes:

| Service    | Image | Purpose |
|------------|-------|---------|
| `etcd`     | `quay.io/coreos/etcd:v3.5.5` | Metadata storage for Milvus |
| `minio`    | `minio/minio:RELEASE.2023-03-20T20-16-18Z` | Object storage backend |
| `standalone` | `milvusdb/milvus:v2.3.0` | Milvus vector database (port `19530`) |

### Starting Milvus

```bash
cd docker
docker-compose up -d
```

Milvus will be available at `localhost:19530`. The MinIO console is accessible at `localhost:9001`.

### Stopping Milvus

```bash
cd docker
docker-compose down
```

> **Tip:** To fully reset the vector store, stop the containers and delete the `docker/volumes/` directory, then restart.

---

## Configuration

### Environment Variables

Set the following environment variable before running the application:

```bash
OPENAI_API_KEY=your-openai-api-key-here
```

### Key Settings (`application.yaml`)

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      embedding:
        options:
          model: text-embedding-3-small
      chat:
        options:
          model: gpt-4-turbo
    vectorstore:
      milvus:
        initialize-schema: true          # Auto-creates the collection on startup
        client:
          host: "localhost"
          port: 19530
          username: "root"
          password: "milvus"
        databaseName: "default"
        collectionName: "vector_store"
        embeddingDimension: 1536          # Matches text-embedding-3-small output
        indexType: IVF_FLAT
        metricType: COSINE

sfg:
  aiapp:
    documentsToLoad:
      - classpath:/towvehicles.txt
      - https://yamahaoutboards.com/outboards/...   # Boat performance bulletins
```

> **`initialize-schema: true`** is required so that Spring AI automatically creates the Milvus collection (`vector_store`) on first startup. Without it, the application throws `CollectionNotExists`.

---

## API Usage

### `POST /ask`

Sends a question about trucks and boats to OpenAI, augmented with data from the Milvus vector store.

**Request body:**
```json
{
  "question": "What is a good truck to pull a Sportsman 232 boat?"
}
```

**Response body:**
```json
{
  "answer": "The Sportsman Open 232 has a tested weight of 5,001 lbs. The cheapest truck that can tow this boat is the Chevy Colorado, which costs $55,000 and can tow up to 7,000 lbs."
}
```

**Example with curl:**
```bash
curl -X POST http://localhost:8080/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is a good truck to pull a Sportsman 232 boat?"}'
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
OpenAIServiceImpl
  │  1. Build system message with domain reasoning rules
  │  2. Similarity search against Milvus VectorStore (top 5 documents)
  │  3. Build RAG user prompt with retrieved documents as context
  │  4. Call ChatModel (OpenAI gpt-4-turbo) with [system + user] messages
  │
  ▼
Answer (returned as JSON)

                        ┌──────────────┐
                        │  Milvus DB   │
                        │  (Docker)    │
                        │              │
LoadVectorStore ──────► │ vector_store │
  (on startup)          │  collection  │
                        └──────────────┘
```

On startup, `LoadVectorStore` checks if the Milvus collection already contains data. If not, it reads the configured documents (local text file + remote Yamaha boat specs via Apache Tika), splits them into chunks using `TokenTextSplitter`, generates embeddings via the `text-embedding-3-small` model, and stores them in Milvus. On subsequent startups, the data is already persisted in Milvus and the loading step is skipped.

---

## Running the Application

### 1. Start Milvus

```bash
cd docker
docker-compose up -d
```

### 2. Run the Application

```bash
./mvnw spring-boot:run
```

Or on Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `CollectionNotExists: can't find collection: vector_store` | Ensure `spring.ai.vectorstore.milvus.initialize-schema` is set to `true` in `application.yaml`. |
| Milvus connection refused | Make sure Docker containers are running: `docker-compose up -d` in the `docker/` directory. |
| Documents not loading on startup | Check the application logs at `DEBUG` level. The `LoadVectorStore` runner logs each document it processes. |
| Stale data in vector store | Stop Milvus, delete `docker/volumes/`, and restart the containers and application. |

---

## Credits

This project is based on the course **[Spring AI: Beginner to Guru](https://www.udemy.com/course/spring-ai-beginner-to-guru/)** by **John Thompson**.
Follow the author on LinkedIn: [linkedin.com/in/springguru](https://www.linkedin.com/in/springguru/)

---

## License

This repository is intended for **educational purposes only** and is not meant for production use.
