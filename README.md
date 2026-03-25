# The Vault — Local RAG Document Intelligence Platform

A self-hosted AI document question-answering system built with Java, Spring Boot, and LangChain4j. The system implements a **Corrective Retrieval-Augmented Generation (CRAG)** pipeline that allows users to upload documents in various formats and ask natural language questions about them. The AI retrieves relevant passages, evaluates each one for relevance before using it, and returns a cited answer grounded exclusively in the uploaded content.

All inference, embedding, and vector storage run entirely on the local machine — no external API calls are made, and no document content is transmitted over the network.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [How the Components Connect](#how-the-components-connect)
3. [Technology Decisions](#technology-decisions)
4. [Prerequisites](#prerequisites)
5. [Installation](#installation)
6. [Running the Application](#running-the-application)
7. [Using the Interface](#using-the-interface)
8. [API Reference](#api-reference)
9. [Configuration](#configuration)
10. [Project Structure](#project-structure)
11. [Stopping and Restarting Services](#stopping-and-restarting-services)
12. [Troubleshooting](#troubleshooting)

---

## System Overview

The application consists of four separate processes that must all be running simultaneously:

| Process | What it does | Port |
|---------|-------------|------|
| **Spring Boot** (this app) | Serves the web UI and exposes the REST API | 8080 |
| **Ollama** | Runs AI models locally on your machine's CPU/GPU | 11434 |
| **ChromaDB** (via Docker) | Stores and searches document vectors | 8888 |
| **Browser** | Renders the React UI served by Spring Boot | — |

Spring Boot is the central coordinator. It receives file uploads from the browser, calls Ollama to generate embeddings, writes those embeddings into ChromaDB, and later orchestrates the full CRAG query pipeline when a user asks a question.

---

## How the Components Connect

Understanding how data moves through the system is essential before running or modifying anything.

### Document Ingestion Flow

When a user uploads a file through the browser, the following sequence occurs:

```
Browser (drag & drop)
    │
    │  POST /api/documents/upload  (multipart/form-data)
    ▼
IngestionController.java
    │  saves file bytes to a OS temp directory
    ▼
DocumentIngestionService.java
    │
    ├─► Apache Tika
    │       Reads the raw file bytes and extracts plain text regardless of format
    │       (PDF, DOCX, XLSX, TXT, CSV, PPTX are all handled the same way)
    │
    ├─► LangChain4j DocumentSplitter
    │       Cuts the plain text into overlapping chunks
    │       (chunk size: 300 characters, overlap: 60 characters)
    │       Overlap prevents context loss at chunk boundaries
    │
    ├─► Ollama — nomic-embed-text model  (port 11434)
    │       Converts each text chunk into a high-dimensional numerical vector
    │       that encodes its semantic meaning
    │
    └─► ChromaDB  (port 8888)
            Stores each (vector, original text) pair in the
            "my_notebook_docs" collection for later retrieval
```

### CRAG Query Flow

When a user types a question and sends it, the system runs the Corrective RAG pipeline:

```
Browser
    │
    │  GET /api/chat/ask?question=...&minScore=0.70&maxResults=5
    ▼
ChatController.java
    ▼
ChatbotService.java — askAdvancedQuestion()
    │
    ├─► Step 1: Embed the question
    │       Ollama (nomic-embed-text) converts the question text into a vector
    │       using the same model used during ingestion, so the vector spaces match
    │
    ├─► Step 2: Vector search
    │       ChromaDB receives the question vector and returns the top N chunks
    │       whose stored vectors are closest to it (cosine similarity ≥ minScore)
    │
    ├─► Step 3: CRAG Evaluation loop  ← the key differentiator
    │       For each retrieved chunk, ChatbotService sends a separate prompt to
    │       Llama 3 asking exactly: "Does this chunk answer the question? YES or NO"
    │       Chunks that receive NO are discarded entirely and never used in the answer.
    │       This prevents the model from hallucinating by filling in gaps.
    │
    ├─► Step 4: Fallback on empty result
    │       If every chunk is rejected, the system returns a fixed "not found"
    │       response rather than attempting to answer from model weights alone
    │
    ├─► Step 5: Final synthesis
    │       Only the verified chunks are assembled into a context block.
    │       Llama 3 is called a final time with the instruction to answer
    │       ONLY from that verified context — not from general knowledge.
    │
    └─► Step 6: Return response
            { "answer": "...", "citations": ["chunk1...", "chunk2..."] }
            The citations array contains the exact text the answer was built from.
```

### Why the Frontend Needs No Build Step

The React UI lives in `src/main/resources/static/index.html`. Spring Boot automatically serves any file placed in `src/main/resources/static/` as a static web asset. The file uses Babel Standalone loaded from a CDN, which compiles JSX in the browser at runtime. This means there is no `npm install`, no `webpack`, and no separate frontend server — opening `http://localhost:8080` in a browser is sufficient.

---

## Technology Decisions

**Why LangChain4j instead of calling Ollama directly?**
LangChain4j abstracts the embedding pipeline, document splitting, vector store integration, and chat memory into composable building blocks. Writing these against raw HTTP calls would require significant boilerplate and would be harder to swap out.

**Why ChromaDB version 0.4.24 specifically?**
Newer ChromaDB versions (≥ 0.5.x) removed the `/api/v1/` endpoint prefix that LangChain4j 0.36.2 uses internally. Sending a request to a newer ChromaDB returns `405 Method Not Allowed`. The version is pinned in `docker-compose.yml` and must not be changed without a corresponding LangChain4j upgrade.

**Why nomic-embed-text for embeddings and llama3 for generation?**
These are two separate roles. `nomic-embed-text` is a small, fast model optimized specifically for converting text into dense vectors — it does not generate language. `llama3` is a general-purpose language model used for both the YES/NO chunk evaluation and the final answer synthesis. Using the same embedding model at ingestion time and at query time ensures the vector spaces are compatible.

**Why chunk size 300 with 60-character overlap?**
Smaller chunks give more precise retrieval — a 2000-character chunk retrieved for one sentence inside it carries a lot of irrelevant text into the context window. The 60-character overlap ensures that sentences at the boundary of two adjacent chunks are represented in both, so a match near a boundary is not lost.

---

## Prerequisites

The following tools must be installed before proceeding. Each section includes installation instructions for macOS, Ubuntu/Debian Linux, and Windows.

---

### Java 21

The application requires Java Development Kit (JDK) version 21 exactly. Spring Boot 4.x does not support older JDK versions.

**macOS — using Homebrew:**
```bash
brew install openjdk@21

# Add to your shell profile so the terminal can find it
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

**Ubuntu / Debian:**
```bash
sudo apt update
sudo apt install openjdk-21-jdk -y
```

**Windows:**
Download the JDK 21 installer from https://jdk.java.net/21/
Run the installer, then add the `bin` directory to your `PATH` system environment variable.
For example: `C:\Program Files\Java\jdk-21\bin`

**Verify the installation:**
```bash
java -version
```
Expected output (version number may vary):
```
openjdk version "21.0.x" 2024-xx-xx
OpenJDK Runtime Environment ...
```

---

### Apache Maven

Maven is the build tool used to compile the Java source code and manage dependencies.

**macOS — using Homebrew:**
```bash
brew install maven
```

**Ubuntu / Debian:**
```bash
sudo apt install maven -y
```

**Windows:**
Download the binary ZIP from https://maven.apache.org/download.cgi
Extract it, then add its `bin` directory to your `PATH` environment variable.

**Verify:**
```bash
mvn -version
```
Expected output:
```
Apache Maven 3.x.x
Java version: 21.x.x
```

---

### Docker Desktop

Docker is used to run ChromaDB in an isolated container. Docker Desktop is the simplest way to get Docker on macOS and Windows.

Download and install from: https://www.docker.com/products/docker-desktop

After installation, launch Docker Desktop from your applications. Wait until the whale icon in the system tray stops animating — that indicates Docker's internal engine is fully started. All subsequent `docker` commands require this engine to be running.

**Verify:**
```bash
docker --version
```
Expected output:
```
Docker version 24.x.x, build ...
```

```bash
docker ps
```
This should print an empty table (no containers yet) without any error.

---

### Ollama

Ollama is a tool that downloads and runs large language models locally. It exposes them over a local HTTP API on port 11434, which LangChain4j calls directly.

**macOS / Linux:**
```bash
curl -fsSL https://ollama.com/install.sh | sh
```
This script installs Ollama and registers it as a background service that starts automatically.

**Windows:**
Download the installer from https://ollama.com/download and run it.

**Verify:**
```bash
ollama --version
```
Expected output:
```
ollama version 0.x.x
```

Check that the Ollama service is responding:
```bash
curl http://localhost:11434
```
Expected response: `Ollama is running`

If you get a connection error, start Ollama manually:
```bash
ollama serve
```
Leave this terminal open (or configure it as a background service before proceeding).

---

## Installation

### 1. Clone the repository

```bash
git clone <your-repository-url>
cd In_Document_chat
```

### 2. Download the AI models

These are one-time downloads. They are stored in Ollama's local model cache and do not need to be re-downloaded on subsequent runs.

```bash
# Llama 3 — used for CRAG evaluation and answer synthesis (~4.7 GB)
ollama pull llama3

# Nomic Embed Text — used to convert text into vectors (~274 MB)
ollama pull nomic-embed-text
```

Confirm both models are present:
```bash
ollama list
```
Expected output (timestamps and sizes will vary):
```
NAME                    ID              SIZE    MODIFIED
llama3:latest           ...             4.7 GB  ...
nomic-embed-text:latest ...             274 MB  ...
```

### 3. Start ChromaDB

The project includes a `docker-compose.yml` file that starts ChromaDB with a named Docker volume. A named volume means the vector data is persisted across container restarts — stopping and restarting ChromaDB does not erase your ingested documents.

```bash
docker-compose up -d
```

The `-d` flag runs the container in the background (detached mode).

Confirm ChromaDB is healthy:
```bash
curl http://localhost:8888/api/v1/heartbeat
```
Expected response:
```json
{"nanosecond heartbeat": 12345678901234}
```

If you prefer not to use Docker Compose, the equivalent manual command is:
```bash
docker run -d \
  -p 8888:8000 \
  --name local-chroma \
  chromadb/chroma:0.4.24
```
Note that this approach does **not** use a named volume, so all vectors are lost if you run `docker rm`.

---

## Running the Application

With Ollama running and ChromaDB healthy, start the Spring Boot application:

```bash
mvn spring-boot:run
```

The first time you run this, Maven downloads all Java dependencies from Maven Central (approximately 300–500 MB). This is a one-time operation. Subsequent runs start in seconds.

Watch the terminal output. When you see lines similar to the following, the application is ready:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
...
Started Application in 4.321 seconds (process running for 4.8)
Application Started
```

Verify the backend is responding:
```bash
curl http://localhost:8080/api/status
```
Expected response:
```json
{
  "status": "online",
  "generationModel": "llama3",
  "embeddingModel": "nomic-embed-text",
  "vectorStore": "ChromaDB",
  "version": "1.0.0"
}
```

Open the application in your browser:
```
http://localhost:8080
```

The web interface loads directly — there is no separate frontend process to start.

### Quick-start summary (all four steps at once)

If all prerequisites are already installed, the complete startup sequence is:

```bash
# Terminal 1 — if Ollama is not already running as a background service
ollama serve

# Terminal 2 — start ChromaDB
docker-compose up -d

# Terminal 3 — start the application
mvn spring-boot:run
```

Then open `http://localhost:8080`.

---

## Using the Interface

The interface has four screens accessible from the left sidebar.

### Workspace

The main working screen. It is split into two panels:

**Left panel — Knowledge Base**

This is where you load documents into the system. You can either drag a file directly onto the dashed upload zone or click it to open a file picker.

Supported file types include PDF, DOCX, DOC, TXT, CSV, XLSX, XLS, and PPTX. Support extends to any format that Apache Tika can parse — the library handles over 1,000 file types.

After you drop a file, the system uploads it to the server, extracts the text, splits it into chunks, runs embedding on each chunk, and writes the resulting vectors into ChromaDB. This process typically takes between 15 and 90 seconds depending on document size and your hardware. The upload zone shows an animated "Ingesting & vectorizing…" state while this is happening.

Each successfully ingested document appears in the list below the upload zone with its filename and size. You can remove individual documents from the list (this removes them from the UI tracking; vector data in ChromaDB is not deleted) or use "Clear All" to reset the list.

**Right panel — Chat**

Type a question about your document and press **Enter** to send it. **Shift+Enter** inserts a line break without sending.

If you have not yet typed anything, the panel shows four suggestion cards with common question starters. Clicking any card pre-fills the input box.

Each AI response includes:
- The synthesized answer in prose form
- A badge showing how many document chunks passed the CRAG evaluation ("3 verified sources")
- If no chunks passed evaluation, an amber "No matching sources" badge appears and the AI declines to answer rather than guessing
- A **"Show Sources"** toggle that expands to reveal the exact text passages the answer was constructed from, along with a "Source N" label for each

The **Clear Chat** button in the top-right resets the visible conversation and also calls `POST /api/chat/clear` to wipe the server-side conversation memory window.

### Library

A grid view of all documents that have been ingested in the current browser session. Each card displays the filename, file size, and the date and time it was uploaded. A **Chat** button on each card navigates back to the Workspace. The right column shows recent conversation threads.

### History

A log of every conversation session, stored in the browser's `localStorage`. Conversations are saved automatically — no action is required. The left column lists all sessions by their first question. Clicking a session displays the full Q&A exchange on the right, including which sources were cited in each answer. Individual sessions and the full history can be deleted here.

### Settings

**Test Connection** — sends a request to `GET /api/status` and displays the model names reported by the backend. Use this to confirm the backend is reachable before starting a session.

**Similarity Threshold** — a slider from 0.40 to 0.95. This controls the minimum cosine similarity required for a chunk to be returned from ChromaDB. A lower value retrieves more chunks but they may be loosely related. A higher value retrieves fewer, more precisely matched chunks. The displayed value is sent as the `minScore` query parameter on every `/api/chat/ask` request.

**Max Retrieved Chunks** — a slider from 1 to 10. This controls how many candidate chunks ChromaDB returns before the CRAG evaluation step. More chunks means more evaluation calls to Llama 3, which increases response time but provides more material to work with.

Both settings are saved to `localStorage` immediately and applied to subsequent requests without a page reload or server restart.

---

## API Reference

All endpoints are served by Spring Boot at `http://localhost:8080`. The browser UI calls these internally, but they can also be used directly via cURL or any HTTP client.

---

### GET /api/status

Returns the current runtime status of the backend.

```bash
curl http://localhost:8080/api/status
```

Response:
```json
{
  "status": "online",
  "generationModel": "llama3",
  "embeddingModel": "nomic-embed-text",
  "vectorStore": "ChromaDB",
  "version": "1.0.0"
}
```

---

### POST /api/documents/upload

Accepts a file via multipart form upload, ingests it through the full pipeline (Tika → splitter → embeddings → ChromaDB), and returns a confirmation string.

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/your/document.pdf"
```

Success response:
```
✅ Successfully ingested and vectorized: document.pdf
```

Error response (if something fails during ingestion):
```
❌ Error ingesting file: <error message>
```

---

### POST /api/documents/ingest

An alternative to `/upload` for scripting use cases where the file already exists on the same machine as the server. Accepts an absolute file path as a query parameter.

```bash
curl -X POST "http://localhost:8080/api/documents/ingest?filePath=/Users/yourname/Downloads/report.pdf"
```

Response format is identical to `/upload`.

---

### GET /api/chat/ask

Runs the full CRAG pipeline and returns an answer with citations.

**Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `question` | string | required | The question to ask |
| `minScore` | float | `0.70` | Minimum cosine similarity for chunk retrieval (0.0 – 1.0) |
| `maxResults` | int | `5` | Number of chunks to retrieve from ChromaDB before CRAG evaluation |

```bash
# With default retrieval settings
curl "http://localhost:8080/api/chat/ask?question=What+are+the+main+findings+in+this+report"

# With custom retrieval settings
curl "http://localhost:8080/api/chat/ask?question=What+risks+were+identified&minScore=0.75&maxResults=8"
```

Response:
```json
{
  "answer": "The report identifies three primary operational risks...",
  "citations": [
    "...the first verified text passage from the document...",
    "...the second verified text passage from the document..."
  ]
}
```

If no document chunks pass the CRAG evaluation, the `citations` array is empty and the `answer` field contains a message indicating that the document does not contain information relevant to the question.

---

### POST /api/chat/clear

Clears the server-side conversation memory window. `ChatbotService` maintains a rolling window of the last 10 messages in memory. This endpoint resets it to empty.

```bash
curl -X POST http://localhost:8080/api/chat/clear
```

Response:
```
✅ Conversation memory cleared
```

**Note:** The server holds a single shared memory instance. If multiple browser tabs are open, they all share the same memory and clearing from one tab affects all others.

---

## Configuration

### application.properties

Located at `src/main/resources/application.properties`. This file configures the connections to Ollama.

```properties
# Spring application name (used in logs)
spring.application.name=local-notebooklm

# Ollama — generation model
# This model is called twice per user question: once for CRAG chunk evaluation
# and once for the final answer synthesis.
langchain4j.ollama.chat-model.base-url=http://localhost:11434
langchain4j.ollama.chat-model.model-name=llama3
langchain4j.ollama.chat-model.temperature=0.0

# Ollama — embedding model
# This model is called once per chunk during ingestion and once per user question.
# It must be the same model used at ingestion time and at query time —
# mixing models produces incompatible vector spaces and breaks retrieval.
langchain4j.ollama.embedding-model.base-url=http://localhost:11434
langchain4j.ollama.embedding-model.model-name=nomic-embed-text
```

### VectorStoreConfig.java

Located at `src/main/java/com/example/local_notebooklm/config/VectorStoreConfig.java`. This file configures the ChromaDB connection and collection name.

```java
ChromaEmbeddingStore.builder()
    .baseUrl("http://localhost:8888")
    .collectionName("my_notebook_docs")
    .build();
```

If you change the collection name, all previously ingested documents will not be found on queries because they are stored under the old name. You would need to re-ingest all documents.

### Retrieval parameters at runtime

The `minScore` and `maxResults` parameters do not require a server restart. They are passed as query parameters on each `/api/chat/ask` request. The Settings page in the UI writes these values to `localStorage` and reads them back on each send — changing them in Settings takes effect on the very next question.

---

## Project Structure

```
In_Document_chat/
│
├── docker-compose.yml                    ← Starts ChromaDB with a persistent named volume
├── pom.xml                               ← Maven dependencies and build config
│
└── src/main/
    ├── java/com/example/local_notebooklm/
    │   │
    │   ├── Application.java              ← Spring Boot entry point
    │   │
    │   ├── config/
    │   │   ├── CorsConfig.java           ← Opens CORS for all origins (required for browser → API)
    │   │   └── VectorStoreConfig.java    ← Defines the ChromaDB EmbeddingStore bean
    │   │
    │   ├── controller/
    │   │   ├── ChatController.java       ← Routes /api/chat/ask and /api/chat/clear
    │   │   ├── IngestionController.java  ← Routes /api/documents/upload and /api/documents/ingest
    │   │   └── StatusController.java    ← Routes /api/status
    │   │
    │   ├── dto/
    │   │   └── ChatResponse.java         ← Shape of the chat API response: { answer, citations[] }
    │   │
    │   └── service/
    │       ├── ChatbotService.java       ← Owns the CRAG pipeline and the ChatMemory instance
    │       └── DocumentIngestionService.java  ← Owns the Tika → splitter → embedder → store pipeline
    │
    └── resources/
        ├── application.properties        ← Ollama model URLs and names
        └── static/
            └── index.html               ← Full React UI; served automatically by Spring Boot at /
```

---

## Stopping and Restarting Services

### Spring Boot application

Press `Ctrl+C` in the terminal where `mvn spring-boot:run` is running.

### ChromaDB

```bash
# Stop the container (data in the named volume is preserved)
docker-compose stop

# Stop and remove the container (data in the named volume is still preserved)
docker-compose down

# Stop and remove everything including the volume (all ingested vectors are deleted)
docker-compose down -v
```

### Restart ChromaDB

```bash
docker-compose up -d
```

### Ollama

On macOS and Linux, Ollama runs as a system service after installation. To stop it:

```bash
# macOS
launchctl stop com.ollama.ollama

# Linux (systemd)
sudo systemctl stop ollama
```

To start it again:
```bash
# macOS
launchctl start com.ollama.ollama

# Linux
sudo systemctl start ollama

# Any platform — manual foreground mode
ollama serve
```

### Verify all services are running

```bash
curl -s http://localhost:11434          # Ollama:    should return "Ollama is running"
curl -s http://localhost:8888/api/v1/heartbeat  # ChromaDB: should return heartbeat JSON
curl -s http://localhost:8080/api/status        # Spring Boot: should return status JSON
```

---

## Troubleshooting

### Spring Boot fails to start — "Connection refused" to ChromaDB or Ollama

The Spring Boot application does not hard-fail on startup if ChromaDB or Ollama are unreachable — it starts anyway. However, the first API call that requires them will fail. Verify both services are running using the three `curl` commands above before making any requests.

### ChromaDB returns `405 Method Not Allowed`

You are running a ChromaDB version newer than `0.4.24`. Newer versions removed the `/api/v1/` prefix from their endpoints. LangChain4j 0.36.2 expects that prefix.

```bash
# Remove whatever container is running and replace with the pinned version
docker rm -f $(docker ps -aq --filter "ancestor=chromadb/chroma")
docker-compose up -d
```

### `ollama pull` fails or models are not listed

Check whether Ollama is running:
```bash
curl http://localhost:11434
```
If this returns a connection error, start Ollama:
```bash
ollama serve
```
Then retry the pull.

### Ingestion succeeds but questions return "document does not contain the answer"

This usually means the question's vector is not matching any stored chunk above the similarity threshold. Try:
1. Lowering the similarity threshold in Settings (e.g., from 0.70 to 0.55)
2. Rephrasing the question to use terminology closer to the document's language
3. Checking in the terminal log (where `mvn spring-boot:run` is running) — it prints how many chunks were retrieved and whether each was approved or rejected by CRAG

### Maven download errors on first run

Maven downloads dependencies from Maven Central. If your network connection dropped mid-download, the local cache may be corrupted.

```bash
mvn clean package -DskipTests -U
```

The `-U` flag forces Maven to re-download any snapshot or missing dependencies.

### Port 8080 is already in use

Find and stop whatever is using port 8080:
```bash
# macOS / Linux
lsof -i :8080
kill -9 <PID>
```

Alternatively, change the Spring Boot port in `application.properties`:
```properties
server.port=9090
```
Then update `API_BASE` in `src/main/resources/static/index.html` to match:
```javascript
const API_BASE = 'http://localhost:9090';
```

### Browser shows a blank white page

The React UI uses Babel Standalone to compile JSX at runtime in the browser. If there is a JavaScript error, the page may render blank. Open the browser developer console (`F12` → Console tab) to see the error message.

A hard refresh clears the browser cache and re-fetches the latest HTML:
- macOS: `Cmd + Shift + R`
- Windows / Linux: `Ctrl + Shift + R`

---

## Infrastructure Note on Version Pinning

The `docker-compose.yml` pins ChromaDB to `0.4.24` and the `pom.xml` pins LangChain4j to `0.36.2`. These two version numbers are coupled — the LangChain4j Chroma integration at `0.36.2` uses the `/api/v1/` HTTP endpoint format, which ChromaDB dropped in later releases. Updating either dependency without updating the other will break the vector store integration with a `405 Method Not Allowed` error. Any future upgrade requires testing both together.
