# The Vault — Local RAG Document Intelligence Platform

A self-hosted AI document question-answering system built with Java, Spring Boot, and LangChain4j. Upload documents in any common format and ask natural-language questions about them. The system retrieves relevant passages from the document, evaluates each one for relevance before using it, and returns an answer that is grounded exclusively in the uploaded content, with citations pointing to the exact source passages used.

All inference, embedding, and vector storage run entirely on the local machine. No document content is transmitted to any external service.

---

## Table of Contents

1. [How All the Pieces Fit Together](#how-all-the-pieces-fit-together)
2. [Prerequisites](#prerequisites)
3. [Setup (Docker — recommended)](#setup-docker--recommended)
4. [Setup (Local Java — for development)](#setup-local-java--for-development)
5. [Using the Interface](#using-the-interface)
6. [API Reference](#api-reference)
7. [Configuration](#configuration)
8. [Project Structure](#project-structure)
9. [Stopping and Restarting](#stopping-and-restarting)
10. [Troubleshooting](#troubleshooting)

---

## How All the Pieces Fit Together

The application is composed of four processes. Understanding what each one does and how they connect is important before running or modifying anything.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Your machine                                                           │
│                                                                         │
│   Browser ──────────► Spring Boot app (port 8080)                       │
│                              │                                          │
│                  ┌───────────┴───────────┐                              │
│                  │                       │                              │
│            Ollama (port 11434)    ChromaDB (port 8888)                  │
│            Llama 3 + nomic-       Vector database                       │
│            embed-text             Stores document                       │
│            Run AI inference       embeddings                            │
└─────────────────────────────────────────────────────────────────────────┘
```

**Spring Boot** is the central coordinator. It receives file uploads from the browser, calls Ollama to produce embeddings, stores those embeddings in ChromaDB, and later runs the full CRAG query pipeline when a user asks a question. It also serves the React web UI directly — there is no separate frontend server.

**Ollama** is a tool that downloads and runs large language models locally on your CPU or GPU. It exposes a simple HTTP API on port 11434. Spring Boot calls this API to:
- Convert text chunks into numerical vectors (using `nomic-embed-text`, the embedding model)
- Grade retrieved chunks as YES or NO for relevance (using `llama3`, the generation model)
- Generate the final synthesized answer (using `llama3`)

**ChromaDB** is a vector database. After Ollama converts a text chunk into a vector, Spring Boot writes that vector and the original text into ChromaDB. When a question is asked, ChromaDB finds the stored vectors most mathematically similar to the question's vector — this is semantic search, not keyword search.

**Ollama runs natively on the host machine, not inside Docker.** This is intentional. Docker cannot access your GPU directly on macOS (Apple Silicon Metal) and involves extra complexity on other platforms. Ollama must run natively to use your GPU for fast inference. ChromaDB and the Spring Boot app run inside Docker containers, and they reach Ollama via `host.docker.internal:11434`.

### Document Ingestion — what happens when you upload a file

```
Browser (drag & drop a PDF, DOCX, TXT, etc.)
    │
    │  POST /api/documents/upload
    ▼
Spring Boot — IngestionController
    │  saves file bytes to OS temp directory
    ▼
DocumentIngestionService
    │
    ├─► Apache Tika
    │       Reads the raw file bytes and extracts plain text.
    │       Handles 1,000+ formats transparently — PDF, DOCX, XLSX,
    │       PPTX, TXT, CSV all go through the same code path.
    │
    ├─► LangChain4j DocumentSplitter
    │       Cuts the plain text into overlapping chunks.
    │       Chunk size: 300 characters. Overlap: 60 characters.
    │       The overlap prevents context from being lost at boundaries.
    │
    ├─► Ollama — nomic-embed-text (port 11434)
    │       Converts each text chunk into a 768-dimensional numerical
    │       vector that encodes the semantic meaning of the text.
    │
    └─► ChromaDB (port 8888)
            Stores each (vector, original text) pair permanently
            in the "my_notebook_docs" collection.
```

### CRAG Query — what happens when you ask a question

CRAG (Corrective Retrieval-Augmented Generation) adds an evaluation step that standard RAG omits. Before using a retrieved chunk to construct an answer, the system asks the AI whether that chunk actually answers the question. Chunks that fail the evaluation are discarded. This prevents the model from filling gaps with information from its training data rather than from the uploaded document.

```
Browser
    │
    │  GET /api/chat/ask?question=...&minScore=0.70&maxResults=5
    ▼
Spring Boot — ChatController → ChatbotService
    │
    ├─► Step 1: Embed the question
    │       Ollama (nomic-embed-text) converts the question into a vector
    │       using the same model as ingestion, so the spaces are compatible.
    │
    ├─► Step 2: Vector search
    │       ChromaDB returns the top N chunks whose stored vectors are
    │       closest to the question vector (cosine similarity ≥ minScore).
    │
    ├─► Step 3: CRAG evaluation loop
    │       For every retrieved chunk, Spring Boot sends a prompt to Llama 3:
    │       "Does this chunk answer [question]? Reply YES or NO."
    │       Chunks that receive NO are discarded entirely.
    │
    ├─► Step 4: Fallback
    │       If every chunk is rejected, the system returns a fixed
    │       "not found" response. It does not attempt to answer from
    │       the model's general training knowledge.
    │
    ├─► Step 5: Final synthesis
    │       The verified chunks are assembled into a context block.
    │       Llama 3 is called with the instruction to answer ONLY from
    │       that context — not from general knowledge.
    │
    └─► Step 6: Response
            { "answer": "...", "citations": ["chunk1...", "chunk2..."] }
```

---

## Prerequisites

You need two tools installed on your machine. Java and Maven are **not** required if you use the Docker setup.

---

### 1. Docker Desktop

Docker Desktop runs ChromaDB and the Spring Boot application inside isolated containers. It must be running before any `docker` or `docker-compose` commands will work.

**macOS:** Download and run the installer from https://www.docker.com/products/docker-desktop

**Windows:** Download and run the installer from https://www.docker.com/products/docker-desktop
After installation, ensure "Use WSL 2 based engine" is enabled in Docker Desktop settings.

**Ubuntu / Debian:**
```bash
# Add Docker's official GPG key and repository
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker Engine
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Allow running Docker without sudo
sudo usermod -aG docker $USER
newgrp docker
```

After installation, launch Docker Desktop (macOS/Windows) or start the Docker service (Linux):
```bash
# Linux only
sudo systemctl start docker
sudo systemctl enable docker
```

**Verify:**
```bash
docker --version
# Expected: Docker version 24.x.x or higher

docker compose version
# Expected: Docker Compose version v2.x.x

docker ps
# Expected: an empty table, no error
```

---

### 2. Ollama

Ollama downloads and runs large language models on your local machine. It runs as a background service and exposes an HTTP API on port 11434.

**macOS / Linux:**
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

**Windows:** Download and run the installer from https://ollama.com/download

After installation on macOS and Linux, Ollama registers itself as a background service and starts automatically. On Windows it also starts as a background service after installation.

**Verify:**
```bash
ollama --version
# Expected: ollama version 0.x.x

curl http://localhost:11434
# Expected: Ollama is running
```

If you get a connection error on the curl step, Ollama's service did not start automatically. Start it manually:
```bash
ollama serve
# Leave this terminal open
```

---

## Setup (Docker — recommended)

This approach requires only Docker Desktop and Ollama. Java and Maven are not needed on your machine — the application compiles itself inside a Docker build stage.

### Step 1 — Clone the repository

```bash
git clone <repository-url>
cd In_Document_chat
```

### Step 2 — Run the setup command

```bash
make setup
```

This single command does five things in sequence:
1. Confirms Docker is running
2. Confirms Ollama is running
3. Downloads the two AI models (`llama3` ~4.7 GB, `nomic-embed-text` ~274 MB)
4. Builds the Spring Boot Docker image (first build downloads ~300 MB of Maven dependencies — subsequent builds use Docker's layer cache)
5. Starts ChromaDB and the Spring Boot app in containers

The first run takes 10–20 minutes depending on your internet connection and hardware. Subsequent runs take under 30 seconds because Docker caches all downloaded layers.

When setup completes, you will see:
```
=== Service Status ===
  Ollama    (port 11434): ONLINE
  ChromaDB  (port 8888):  ONLINE
  App       (port 8081):  ONLINE
```

Then open your browser and navigate to:
```
http://localhost:8081
```

### Windows users (no make)

If you are on Windows and `make` is not available, run these commands individually:

```bat
rem Pull models
ollama pull llama3
ollama pull nomic-embed-text

rem Build and start
docker compose up -d --build
```

---

## Setup (Local Java — for development)

Use this approach if you want to run the Spring Boot application directly on your machine (for debugging, hot-reload, etc.) while still running ChromaDB in Docker.

### Additional prerequisites

**Java 21:**

macOS:
```bash
brew install openjdk@21
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Ubuntu/Debian:
```bash
sudo apt update && sudo apt install openjdk-21-jdk -y
```

Windows: Download the JDK 21 installer from https://jdk.java.net/21/ and add its `bin` directory to your `PATH`.

**Maven:**

macOS:
```bash
brew install maven
```

Ubuntu/Debian:
```bash
sudo apt install maven -y
```

Windows: Download the binary ZIP from https://maven.apache.org/download.cgi and add its `bin` to `PATH`.

**Verify both:**
```bash
java -version
# Expected: openjdk version "21.x.x"

mvn -version
# Expected: Apache Maven 3.x.x, Java version: 21.x.x
```

### Starting services

```bash
# Step 1 — Pull AI models (one-time)
ollama pull llama3
ollama pull nomic-embed-text

# Step 2 — Start ChromaDB only (not the app — you'll run that locally)
docker compose up -d chromadb

# Step 3 — Verify ChromaDB is healthy
curl http://localhost:8888/api/v1/heartbeat
# Expected: {"nanosecond heartbeat": ...}

# Step 4 — Start the Spring Boot app
mvn spring-boot:run
```

First run downloads ~300 MB of Maven dependencies. When you see `Application Started` in the output, open `http://localhost:8080`.

---

## Using the Interface

### Workspace

The main screen. Split into two panels:

**Left — Knowledge Base**

Drag a file onto the dashed upload zone, or click it to open a file picker. Supported types include PDF, DOCX, DOC, TXT, CSV, XLSX, XLS, and PPTX.

After a file is dropped, the zone shows "Ingesting & vectorizing…" while the system extracts text, splits it into chunks, embeds each chunk with Ollama, and writes the vectors into ChromaDB. This typically takes 15–90 seconds depending on file size and hardware.

Each successfully ingested file appears in the list with its name and size. Individual files can be removed from the list using the delete button that appears on hover.

**Right — Chat**

Type a question and press **Enter** to send it. **Shift+Enter** inserts a line break without sending.

The four suggestion cards on the welcome screen are clickable shortcuts that pre-fill the input with a common question.

Each AI response shows:
- The synthesized answer
- A green badge: "N verified sources" — the number of chunks that passed the CRAG evaluation
- An amber badge: "No matching sources" — if all chunks were rejected, the system declines to answer rather than guessing
- A **Show Sources** toggle that expands to reveal the exact text passages the answer was derived from

**Clear Chat** in the top-right resets the visible conversation and calls `POST /api/chat/clear` to reset the server-side memory window.

### Library

A grid of all documents ingested in the current browser session. Each card shows the filename, file size, upload date, and a **Chat** shortcut back to Workspace. Recent conversation threads are listed in the right column.

### History

Every conversation is automatically saved to the browser's `localStorage`. The left column lists sessions by their first question. Click a session to read the full Q&A exchange on the right, including which sources were cited. Sessions and the full history can be deleted.

### Settings

| Control | Effect |
|---------|--------|
| **Test Connection** | Calls `GET /api/status` and shows the model names reported by the running backend |
| **Similarity Threshold** | Slider 0.40–0.95. Minimum cosine similarity for a chunk to be retrieved from ChromaDB. Lower = broader retrieval; higher = more precise |
| **Max Retrieved Chunks** | Slider 1–10. How many candidates ChromaDB returns before CRAG evaluates them |

Both retrieval settings are stored in `localStorage` and sent as query parameters on every chat request. Changes take effect immediately — no restart needed.

---

## API Reference

All endpoints are at `http://localhost:8080/api/`.

### GET /api/status

```bash
curl http://localhost:8080/api/status
```

```json
{
  "status": "online",
  "generationModel": "llama3",
  "embeddingModel": "nomic-embed-text",
  "vectorStore": "ChromaDB",
  "version": "1.0.0"
}
```

### POST /api/documents/upload

Accepts a file upload from the browser via `multipart/form-data`.

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/your/document.pdf"
```

Success: `✅ Successfully ingested and vectorized: document.pdf`
Failure: `❌ Error ingesting file: <message>`

### POST /api/documents/ingest

Alternative for scripts — accepts the file's absolute path on the server machine.

```bash
curl -X POST "http://localhost:8080/api/documents/ingest?filePath=/absolute/path/to/file.pdf"
```

### GET /api/chat/ask

Runs the CRAG pipeline and returns an answer with citations.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `question` | string | required | The question to ask |
| `minScore` | float | `0.70` | Minimum cosine similarity for retrieval |
| `maxResults` | int | `5` | Chunks to retrieve before CRAG evaluation |

```bash
curl "http://localhost:8080/api/chat/ask?question=What+are+the+main+findings"

curl "http://localhost:8080/api/chat/ask?question=What+risks+were+identified&minScore=0.75&maxResults=8"
```

```json
{
  "answer": "The report identifies three primary risks...",
  "citations": [
    "...first verified text passage from the document...",
    "...second verified text passage from the document..."
  ]
}
```

If no chunks pass CRAG evaluation, `citations` is empty and `answer` states the document does not contain the relevant information.

### POST /api/chat/clear

Resets the server-side conversation memory window (last 10 messages).

```bash
curl -X POST http://localhost:8080/api/chat/clear
```

`✅ Conversation memory cleared`

Note: The server holds a single shared memory instance. Multiple open browser tabs share the same memory.

---

## Configuration

### application.properties

Located at `src/main/resources/application.properties`. All values support environment-variable overrides, which the Docker Compose file uses automatically.

```properties
# Ollama — generation model
langchain4j.ollama.chat-model.base-url=${LANGCHAIN4J_OLLAMA_CHAT_MODEL_BASE_URL:http://localhost:11434}
langchain4j.ollama.chat-model.model-name=llama3
langchain4j.ollama.chat-model.temperature=0.0

# Ollama — embedding model
langchain4j.ollama.embedding-model.base-url=${LANGCHAIN4J_OLLAMA_EMBEDDING_MODEL_BASE_URL:http://localhost:11434}
langchain4j.ollama.embedding-model.model-name=nomic-embed-text

# ChromaDB
chroma.base-url=${CHROMA_BASE_URL:http://localhost:8888}
```

The syntax `${ENV_VAR:default}` means: use the environment variable if set, otherwise fall back to the default. When `docker-compose.yml` starts the app container, it sets all three environment variables to point at the sibling services using Docker's internal network names.

### Changing model names

To use a different Ollama model, change `model-name` in `application.properties` and re-run `ollama pull <new-model-name>`. The embedding model name must be kept consistent — changing it after documents are already ingested will make existing vectors incompatible with new queries, because the two models produce different vector spaces. If you change the embedding model, you must wipe ChromaDB and re-ingest all documents.

---

## Project Structure

```
In_Document_chat/
│
├── Dockerfile                             ← Two-stage build: Maven compile → JRE runtime
├── docker-compose.yml                     ← Starts chromadb + app; Ollama stays on host
├── Makefile                               ← Developer convenience commands (make setup, run, etc.)
├── pom.xml                                ← Maven dependencies
│
└── src/main/
    ├── java/com/example/local_notebooklm/
    │   │
    │   ├── Application.java
    │   │
    │   ├── config/
    │   │   ├── CorsConfig.java            ← Opens CORS for all origins (browser can call API)
    │   │   └── VectorStoreConfig.java     ← Creates the ChromaDB EmbeddingStore bean;
    │   │                                     reads chroma.base-url from properties
    │   ├── controller/
    │   │   ├── ChatController.java        ← /api/chat/ask  /api/chat/clear
    │   │   ├── IngestionController.java   ← /api/documents/upload  /api/documents/ingest
    │   │   └── StatusController.java      ← /api/status
    │   │
    │   ├── dto/
    │   │   └── ChatResponse.java          ← { answer: string, citations: string[] }
    │   │
    │   └── service/
    │       ├── ChatbotService.java        ← CRAG pipeline + ChatMemory singleton
    │       └── DocumentIngestionService.java  ← Tika → splitter → embedder → ChromaDB
    │
    └── resources/
        ├── application.properties         ← Service URLs with env-var overrides
        └── static/
            └── index.html                 ← React SPA; served at http://localhost:8080
```

---

## Stopping and Restarting

### Using make

```bash
make stop       # Stop containers, preserve all data
make run        # Restart stopped containers
make rebuild    # Rebuild the Spring Boot image after code changes and restart
make clean      # Remove containers AND all stored vectors (cannot be undone)
make logs       # Follow the Spring Boot application logs
make status     # Check all three services
```

### Using docker compose directly

```bash
# Stop, keep data
docker compose stop

# Start again
docker compose start

# Stop and delete containers (volume data is preserved)
docker compose down

# Stop and delete containers AND volumes (all vectors wiped)
docker compose down -v

# Rebuild the app image after a code change
docker compose up -d --build app

# Follow logs
docker compose logs -f app
```

### Stopping Ollama

```bash
# macOS
launchctl stop com.ollama.ollama

# Linux
sudo systemctl stop ollama

# Any platform — if you ran it manually in a terminal
# Just press Ctrl+C in that terminal
```

---

## Troubleshooting

### `make: command not found` (Windows)

`make` is not installed by default on Windows. Either install it via Chocolatey (`choco install make`) or run the underlying `docker compose` commands directly (see the Windows section under Setup).

### Setup fails: "Ollama is not running"

Ollama must be running before `make setup`. Start it:
```bash
ollama serve
```
Then re-run `make setup`.

### App container starts but questions fail with a connection error

The app container cannot reach Ollama on the host. Verify:
```bash
curl http://localhost:11434
```
If Ollama is running but questions still fail, check the container logs:
```bash
docker compose logs app
```
Look for `Connection refused` to `host.docker.internal:11434`. On Linux this can happen if the `extra_hosts` entry in `docker-compose.yml` was removed. It must stay as `"host.docker.internal:host-gateway"`.

### ChromaDB returns `405 Method Not Allowed`

You are running a ChromaDB version newer than `0.4.24`. The `docker-compose.yml` pins it to `0.4.24` — this pin must not be changed without also upgrading LangChain4j. To restore the correct version:
```bash
docker compose down
docker compose up -d
```

### Ingestion succeeds but questions return "document does not contain the answer"

The CRAG evaluator rejected all retrieved chunks. Try:
1. Lowering the Similarity Threshold in Settings (try 0.55)
2. Checking the app logs — they print how many chunks were retrieved and whether each passed or failed CRAG:
   ```bash
   make logs
   # or
   docker compose logs -f app
   ```
3. Rephrasing the question to use wording closer to the document's own language

### First Docker build is very slow

The first build downloads Maven dependencies (~300 MB) inside the container. This is a one-time operation. Docker caches the downloaded dependencies as a separate image layer. As long as `pom.xml` does not change, subsequent builds skip this step entirely and take under a minute.

### Port 8080 is already in use

Find and stop the conflicting process:
```bash
# macOS / Linux
lsof -i :8080 | grep LISTEN
kill -9 <PID>
```

Or change the application port. In `application.properties`, add:
```properties
server.port=9090
```
Then update `API_BASE` in `src/main/resources/static/index.html`:
```javascript
const API_BASE = 'http://localhost:9090';
```
And update the port mapping in `docker-compose.yml`:
```yaml
ports:
  - "9090:9090"
```

### Browser shows a blank white page

Open the browser developer console (F12 → Console). If a JavaScript error is visible, do a hard refresh to clear the cache:
- macOS: `Cmd + Shift + R`
- Windows / Linux: `Ctrl + Shift + R`

---

## A Note on Version Pinning

`docker-compose.yml` pins ChromaDB to `0.4.24` and `pom.xml` pins LangChain4j to `0.36.2`. These two versions are tightly coupled — LangChain4j at `0.36.2` constructs ChromaDB API calls using the `/api/v1/` URL prefix, which ChromaDB removed in versions `0.5.x` and later. Updating either dependency without a coordinated update of the other will break vector storage with a `405 Method Not Allowed` error at runtime, not at compile time.
