# The Vault v3 — Local RAG Document Intelligence Platform

A fully self-hosted, privacy-first AI document Q&A system. Upload PDFs, DOCX, or any common format — ask questions answered strictly from retrieved passages with inline citations, grounding scores, and zero data leaving your machine.

Built with Java 21, Spring Boot 4, LangChain4j, ChromaDB, and Ollama. Optional distributed inference across team laptops for faster responses.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Technology Stack](#2-technology-stack)
3. [Prerequisites](#3-prerequisites)
4. [Quick Start — Docker](#4-quick-start--docker)
5. [Quick Start — Local Dev](#5-quick-start--local-dev)
6. [How the RAG Pipeline Works](#6-how-the-rag-pipeline-works)
7. [Document Ingestion Pipeline](#7-document-ingestion-pipeline)
8. [API Reference](#8-api-reference)
9. [Configuration Reference](#9-configuration-reference)
10. [Frontend — The Vault UI](#10-frontend--the-vault-ui)
11. [Distributed Inference — Team Laptop Setup](#11-distributed-inference--team-laptop-setup)
12. [Monitoring and Diagnostics](#12-monitoring-and-diagnostics)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Architecture Overview

```
                                    The Vault v3 — System Architecture

  +-------------------+       +----------------------------------------------+       +------------------+
  |   Browser (React) |       |         Spring Boot Application (8081)        |       |    Ollama (GPU)   |
  |                   |       |                                              |       |     (11434)       |
  |  The Vault v3.0   |       |  +------------------+  +------------------+  |       |                  |
  |  - Upload docs    |<----->|  | ChatController   |  | IngestionCtrl    |  |       |  gemma2 (9B)     |
  |  - Multi-file     |       |  |   GET /ask       |  |   POST /upload   |  |       |  - HyDE gen      |
  |  - Chat with AI   |       |  +--------+---------+  +--------+---------+  |       |  - CRAG scoring   |
  |  - Settings       |       |           |                      |           |       |  - Synthesis      |
  |  - Grounding      |       |  +--------v---------+  +--------v---------+  |       |                  |
  |    badges         |       |  | ChatbotService   |  | DocIngestionSvc  |  |       |  mxbai-embed-    |
  |  - Citations      |       |  |                  |  |                  |  |<----->|  large (1024-dim) |
  |                   |       |  | 1. Classify query|  | PDF: PDFBox +    |  |       |  - Embeddings    |
  +-------------------+       |  | 2. HyDE / Multi-Q|  |   tabula tables  |  |       |                  |
                              |  | 3. Retrieve      |  |   + Tesseract OCR|  |       +------------------+
                              |  | 4. Batch CRAG    |  | DOCX: POI tables |  |
                              |  | 5. Synthesize    |  | Other: Tika      |  |       +------------------+
                              |  | 6. Ground score  |  |                  |  |       | ChromaDB (8888)  |
                              |  +--------+---------+  +--------+---------+  |       |                  |
                              |           |                      |           |       | Collection:      |
                              |           |    +-------------+   |           |       | my_notebook_docs |
                              |           +--->| Distributed  |<-+           |       |                  |
                              |                | Inference    |              |<----->| - 1024-dim vecs  |
                              |                | Router       |              |       | - Metadata filter|
                              |                +------+-------+              |       | - Cosine search  |
                              |                       |                      |       +------------------+
                              +----------------------------------------------+
                                                      |
                                          +-----------+-----------+
                                          |  Distributed Nodes    |
                                          |  (team laptops)       |
                                          |  - Health-checked     |
                                          |  - Load-balanced      |
                                          |  - Auto-failover      |
                                          +------------------------+
```

### Service Layout

| Service | Runs On | Port | Purpose |
|---------|---------|------|---------|
| Spring Boot + React UI | Docker container | **8081** | Application server + static frontend |
| ChromaDB | Docker container | **8888** (host) → 8000 (container) | Vector database for document embeddings |
| Ollama | **Native on host** (GPU access) | **11434** | LLM inference + embedding generation |

Ollama runs natively (not containerized) so it can use your GPU (NVIDIA CUDA, AMD ROCm, Apple Metal) for fast inference.

---

## 2. Technology Stack

### Backend

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| Runtime | Java (Eclipse Temurin) | 21 | Application runtime |
| Framework | Spring Boot | 4.0.3 | REST API + dependency injection |
| AI Framework | LangChain4j | 0.36.2 | LLM orchestration, embedding, vector store |
| Generation LLM | Gemma 2 9B (Google) | via Ollama | HyDE, CRAG scoring, synthesis |
| Embedding Model | mxbai-embed-large | via Ollama | 1024-dim asymmetric embeddings |
| Vector Store | ChromaDB | 0.4.24 | Cosine similarity search + metadata filtering |

### Document Processing

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| PDF text | Apache PDFBox | 2.0.29 | Page-by-page text extraction, multi-column support |
| PDF tables | tabula-java | 1.0.5 | Structural table detection from PDF coordinates |
| OCR | Tesseract (via tess4j) | 5.11.0 | Image-heavy / scanned PDF pages |
| DOCX | Apache POI | 5.2.5 | Table-aware DOCX parsing |
| Other formats | Apache Tika | via LangChain4j | XLSX, PPTX, TXT, CSV fallback |

### Frontend

| Component | Technology | Purpose |
|-----------|-----------|---------|
| UI | React 18 (via Babel standalone) | Single-page app in index.html |
| Styling | Tailwind CSS | Utility-first responsive design |
| Icons | Google Material Symbols | Icon set |
| State | localStorage | Documents, conversations, settings persistence |

### Infrastructure

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Containerization | Docker + Docker Compose | Service orchestration |
| Build | Maven 3.9 + multi-stage Dockerfile | Dependency management + minimal runtime image |
| Monitoring | Spring Boot Actuator | /actuator/health, /actuator/metrics |

---

## 3. Prerequisites

- **Docker Desktop** (or Docker Engine + Compose)
- **Ollama** — installed on the host machine (for GPU access)

### Install Ollama

macOS:
```bash
brew install ollama
```

Linux:
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Windows (PowerShell):
```powershell
winget install Ollama.Ollama
```

### Pull Required Models

```bash
ollama pull gemma2              # Generation model (~5.5 GB)
ollama pull mxbai-embed-large   # Embedding model (~670 MB)
```

For local development without Docker, also install **Java 17+** and **Maven**.

---

## 4. Quick Start — Docker

```bash
make setup    # First time: checks prereqs, pulls models, builds image, starts services
```

Then open: **http://localhost:8081**

### Day-to-Day Commands

| Command | What it does |
|---------|-------------|
| `make run` | Start services (after first setup) |
| `make stop` | Stop services, preserve all data |
| `make rebuild` | Rebuild app image after code changes + restart |
| `make clean` | **Destructive**: stop + delete all data volumes |
| `make logs` | Follow live app logs |
| `make status` | Check health of Ollama, ChromaDB, and app |
| `make purge-old-models` | Remove old v2 models (llama3, nomic-embed-text) to free ~5 GB |

---

## 5. Quick Start — Local Dev

```bash
# 1. Start Ollama
ollama serve

# 2. Start ChromaDB only
docker compose up -d chromadb

# 3. Run Spring Boot on host (hot-reload)
./mvnw spring-boot:run

# 4. Open browser
open http://localhost:8081
```

For local macOS development with OCR support:
```bash
brew install tesseract
export OCR_TESSDATA_PATH=/usr/local/share/tessdata
```

---

## 6. How the RAG Pipeline Works

Every question goes through a 6-step pipeline. The number of LLM calls depends on the query type.

```
User Question: "What is a list in Python?"
       │
       ▼
  ┌─────────────────────────────────────────────────────────┐
  │ Step 1: CLASSIFY QUERY (zero LLM calls)                 │
  │                                                         │
  │  "what is a list in python" → 5 words → CODE_SEARCH     │
  │                                                         │
  │  Types:                                                 │
  │  - SYNTHESIS  ("summarize", "overview", "key points")   │
  │  - CODE_SEARCH (≤5 words, concept lookups)              │
  │  - FACTOID    (everything else)                         │
  └────────────────────┬────────────────────────────────────┘
                       ▼
  ┌─────────────────────────────────────────────────────────┐
  │ Step 2: QUERY EXPANSION — HyDE (1 LLM call)            │
  │                                                         │
  │  CODE_SEARCH → generates a code snippet:                │
  │  "# creating a list in Python                           │
  │   my_list = [1, 2, 3]                                   │
  │   my_list.append(4)                                     │
  │   print(len(my_list))"                                  │
  │                                                         │
  │  This code snippet is embedded as a DOCUMENT            │
  │  (no instruction prefix) so it lands in the same        │
  │  vector space as stored code chunks.                    │
  │                                                         │
  │  SYNTHESIS → skips HyDE (0 LLM calls), uses broad       │
  │  anchor strings for wide document coverage.             │
  └────────────────────┬────────────────────────────────────┘
                       ▼
  ┌─────────────────────────────────────────────────────────┐
  │ Step 3: PARALLEL RETRIEVAL (0 LLM calls)                │
  │                                                         │
  │  Embed all query variants (code HyDE + raw question)    │
  │  Search ChromaDB with cosine similarity                 │
  │  Filter by source_file metadata (if file selected)      │
  │  Deduplicate results across variants                    │
  │                                                         │
  │  Returns: 10 candidate chunks (configurable)            │
  └────────────────────┬────────────────────────────────────┘
                       ▼
  ┌─────────────────────────────────────────────────────────┐
  │ Step 4: BATCH CRAG EVALUATION (1 LLM call)              │
  │                                                         │
  │  Single LLM call scores ALL 10 chunks on a 1-5 scale:  │
  │                                                         │
  │  CODE_SEARCH rubric:                                    │
  │  5 = directly shows code example or definition          │
  │  4 = shows related code or partial explanation          │
  │  3 = mentions the concept or adjacent usage             │
  │  2 = vaguely related                                    │
  │  1 = irrelevant                                         │
  │                                                         │
  │  Chunks scoring ≥ 3 kept, sorted by score descending    │
  │  (v2 used 30 LLM calls here — v3 uses 1)               │
  └────────────────────┬────────────────────────────────────┘
                       ▼
  ┌─────────────────────────────────────────────────────────┐
  │ Step 5: GROUNDED SYNTHESIS (1 LLM call)                 │
  │                                                         │
  │  Strict rules enforced:                                 │
  │  - Answer ONLY from document passages                   │
  │  - Every claim must include 4-8 word inline quote       │
  │  - No training knowledge allowed                        │
  │  - If insufficient: say "document does not provide..."  │
  └────────────────────┬────────────────────────────────────┘
                       ▼
  ┌─────────────────────────────────────────────────────────┐
  │ Step 6: GROUNDING SCORE (0 LLM calls)                   │
  │                                                         │
  │  Token-overlap metric: what fraction of meaningful      │
  │  answer words appear in the retrieved context?          │
  │                                                         │
  │  ≥ 75% → Green "High grounding"                        │
  │  50-74% → Yellow "Medium grounding"                     │
  │  < 50% → Red "Low grounding"                            │
  └────────────────────┬────────────────────────────────────┘
                       ▼
  Response: { answer, citations[], groundingScore, chunkCount }
```

### LLM Call Budget by Query Type

| Query Type | HyDE | CRAG | Synthesis | Total |
|-----------|------|------|-----------|-------|
| FACTOID | 1 (prose) | 1 | 1 | **3** |
| CODE_SEARCH | 1 (code) | 1 | 1 | **3** |
| SYNTHESIS | 0 (skipped) | 1 | 1 | **2** |

v2 used up to 32 LLM calls per query. v3 uses 2-3.

---

## 7. Document Ingestion Pipeline

### Format Routing

| Format | Handler | Features |
|--------|---------|----------|
| `.pdf` | PDFBox + tabula + Tesseract | Page-by-page, table extraction, OCR for scanned pages |
| `.docx` | Apache POI | Table-aware (prose + Markdown tables interleaved) |
| `.xlsx, .pptx, .txt, .csv` | Apache Tika | Flat text extraction fallback |

### PDF Ingestion (per page)

```
PDF Page
  ├── Text extraction (PDFBox, sortByPosition=true)
  │     └── Skip if TOC page (>40% lines match "text .... NNN")
  ├── Table extraction (tabula-java → Markdown)
  │     └── Stored as separate chunk with content_type="table"
  ├── If text < 60 chars → OCR (Tesseract at 300 DPI)
  │     └── Stored with content_type="ocr"
  └── Prose text stored with content_type="text"

Metadata per chunk: source_file, page_number, total_pages, content_type
```

### Chunking

- **Size:** 1000 characters (~250-350 tokens for code, ~180-250 words for prose)
- **Overlap:** 250 characters
- **Why 1000:** mxbai-embed-large has a hard 512-token limit. Code has shorter tokens (~2.5 chars/token) so 1000 chars = ~400 tokens, safely within limits.

---

## 8. API Reference

### Chat

| Method | Path | Parameters | Description |
|--------|------|-----------|-------------|
| GET | `/api/chat/ask` | `question` (required), `filenames` (csv or "ALL"), `sessionId`, `minScore` (0.30), `maxResults` (10) | Full CRAG pipeline query |
| POST | `/api/chat/clear` | `sessionId` | Reset memory for one session |

**Example:**
```bash
curl "http://localhost:8081/api/chat/ask?question=What+is+a+list&filenames=ALL&minScore=0.20&maxResults=10"
```

**Response:**
```json
{
  "answer": "A list in Python is an \"ordered, mutable collection\"...",
  "citations": ["chunk text [file.pdf, Page 12]"],
  "groundingScore": 0.85,
  "chunkCount": 9
}
```

### Documents

| Method | Path | Parameters | Description |
|--------|------|-----------|-------------|
| POST | `/api/documents/upload` | `file` (multipart) | Upload and ingest one file |
| POST | `/api/documents/upload-batch` | `files[]` (multipart) | Upload and ingest multiple files |
| POST | `/api/documents/ingest` | `filePath` (absolute path) | Ingest by server-side path |
| DELETE | `/api/documents/delete` | `filename` | Remove all vectors for a file |

### Status

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/status` | Health check + model names + version |

### Distributed Inference Admin

Requires `inference.distributed.admin.enabled=true`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/inference/nodes` | List all registered nodes with status |
| GET | `/api/inference/nodes/healthy` | List only healthy nodes |
| GET | `/api/inference/nodes/availability` | Returns "AVAILABLE" or 503 |
| POST | `/api/inference/nodes/register?id=X&url=Y&maxConcurrent=Z` | Register a node |
| DELETE | `/api/inference/nodes/{id}` | Deregister a node |
| POST | `/api/inference/nodes/test/{id}` | Test connectivity + measure latency |

---

## 9. Configuration Reference

File: `src/main/resources/application.properties`

### Models

```properties
langchain4j.ollama.chat-model.model-name=gemma2          # Generation + CRAG + HyDE
langchain4j.ollama.chat-model.temperature=0.0             # Deterministic for RAG
langchain4j.ollama.embedding-model.model-name=mxbai-embed-large   # 1024-dim embeddings
```

### RAG Pipeline Tuning

```properties
# Query expansion: hyde (best quality) | multi-query (broad) | none (fastest)
rag.query-expansion.mode=hyde

# CRAG thresholds (1-5 scale). Chunks below are rejected.
rag.crag.batch.min-score=4                    # Factoid queries
rag.crag.batch.min-score.synthesis=3          # Summary/overview queries
rag.crag.batch.min-score.code=3               # Short concept queries

# Similarity floor relaxation for synthesis/code
rag.retrieval.min-score.relax-for-synthesis=0.20
rag.retrieval.min-score.relax-for-code=0.20
```

### Infrastructure

```properties
server.port=8081
server.address=0.0.0.0
chroma.base-url=${CHROMA_BASE_URL:http://localhost:8888}
ocr.tesseract.datapath=${OCR_TESSDATA_PATH:/usr/share/tessdata}
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=200MB
```

### Distributed Inference

```properties
inference.distributed.enabled=true                   # Master switch
inference.distributed.admin.enabled=true              # Admin API endpoints
inference.distributed.nodes=node1|http://IP:11434|2   # id|url|maxConcurrent
inference.distributed.default-model=                  # Override model (empty = use local config)
inference.distributed.healthcheck.interval-seconds=10
inference.distributed.healthcheck.max-failures=3
```

---

## 10. Frontend — The Vault UI

The React UI is served as a single `index.html` static asset — no Node.js, no npm, no build step. Babel 7 compiles JSX in-browser.

### Screens

| Screen | Purpose |
|--------|---------|
| **Workspace** | 30% Knowledge Base panel + 70% Chat panel |
| **Library** | Grid view of all ingested documents |
| **History** | Review past conversations |
| **Settings** | Similarity threshold slider, max results, query expansion mode |

### Key Features

- **Drag-and-drop upload** with progress indicator
- **Multi-file selection** — check documents to scope queries, or toggle "All Docs"
- **Per-tab sessions** — each browser tab gets isolated conversation memory (UUID)
- **Grounding badges** — green/yellow/red based on answer-to-context word overlap
- **Chunk count badge** — "N chunks verified" shows how many passages passed CRAG
- **Citations** — each claim links to source file + page number

### LocalStorage Keys

| Key | Content |
|-----|---------|
| `vault_docs_v3` | List of ingested documents |
| `vault_convs_v3` | Conversation history |
| `vault_settings_v3` | `{ similarityThreshold, maxResults, offlineFirst }` |

---

## 11. Distributed Inference — Team Laptop Setup

### What This Does

Each teammate exposes their local Ollama as an inference node on the LAN. The app load-balances requests across healthy nodes with automatic failover. If a node disappears, requests continue on remaining nodes, with fallback to local Ollama.

### Architecture

```
                        ┌──────────────────┐
                        │  Spring Boot App │
                        │  (coordinator)   │
                        │                  │
                        │  LoadBalancer    │──────── selects least-loaded
                        │  HealthChecker   │──────── pings every 10s
                        │  InferenceRouter │──────── failover on error
                        └──────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
     │ Laptop A    │  │ Laptop B    │  │ Laptop C    │
     │ GPU: M2 Pro │  │ GPU: RTX4090│  │ GPU: M1     │
     │ gemma2      │  │ gemma2      │  │ gemma2      │
     │ max: 2 req  │  │ max: 3 req  │  │ max: 1 req  │
     └─────────────┘  └─────────────┘  └─────────────┘
```

### Setup Steps

**1. Find your LAN IP:**
```bash
# macOS
ipconfig getifaddr en0

# Linux
hostname -I

# Windows
ipconfig
```

**2. Start Ollama as a network server:**
```bash
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

**3. Verify reachability from another machine:**
```bash
curl http://<their-LAN-ip>:11434/api/tags
```

**4. Register as a node:**
```bash
curl -X POST "http://<app-host>:8081/api/inference/nodes/register?id=laptop-dev&url=http://<LAN-ip>:11434&maxConcurrent=2"
```

**5. Verify registration:**
```bash
curl http://<app-host>:8081/api/inference/nodes
```

### How It Works at Runtime

- Health checks ping `/api/tags` every 10 seconds
- Load balancer picks the node with the most free slots (least-active-requests)
- If a node fails: automatic retry on another healthy node
- If all nodes are down: falls back to local Ollama transparently
- Semaphore-based concurrency control prevents overloading individual laptops

### Configuration (in application.properties)

```properties
inference.distributed.enabled=true
inference.distributed.admin.enabled=true
inference.distributed.nodes=laptop-dev|http://192.168.1.33:11434|2,laptop-qa|http://192.168.1.44:11434|2
inference.distributed.healthcheck.interval-seconds=10
inference.distributed.healthcheck.max-failures=3
```

---

## 12. Monitoring and Diagnostics

### Health Checks

```bash
# App
curl http://localhost:8081/api/status

# ChromaDB
curl http://localhost:8888/api/v1/heartbeat

# Ollama
curl http://localhost:11434/api/tags

# All at once
make status
```

### ChromaDB — The Most Important Check

```bash
# How many vectors are stored? (0 = ingestion failed, re-upload)
COLLECTION_ID=$(curl -s http://localhost:8888/api/v1/collections | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s "http://localhost:8888/api/v1/collections/$COLLECTION_ID/count"

# Peek at stored chunks
curl -s -X POST "http://localhost:8888/api/v1/collections/$COLLECTION_ID/get" \
  -H "Content-Type: application/json" -d '{"limit":3,"include":["documents","metadatas"]}' | python3 -m json.tool
```

### Query Pipeline Logs

```bash
docker compose logs app --tail 30 | grep -E "QUERY|RETRIEVAL|CRAG|SYNTHESIS|GROUNDING"
```

| Log prefix | Meaning |
|-----------|---------|
| `[QUERY] Type: SYNTHESIS` | Query classified as summary/overview |
| `[RETRIEVAL] 0 chunks (minScore=0.2)` | Nothing retrieved — ChromaDB empty or threshold too high |
| `[CRAG] Approved: 7 / 10 (min score: 3)` | 7 of 10 chunks passed CRAG evaluation |
| `[GROUNDING] Score: 0.85 (HIGH)` | 85% of answer words found in source context |

### Distributed Inference Monitoring

```bash
# All nodes with status
curl -s http://localhost:8081/api/inference/nodes | python3 -m json.tool

# Test specific node latency
curl -s -X POST http://localhost:8081/api/inference/nodes/test/laptop-dev | python3 -m json.tool

# Check availability
curl -s http://localhost:8081/api/inference/nodes/availability
```

### Decision Tree: "NO MATCHING SOURCES"

```
ChromaDB vector count > 0?
├── NO → Ingestion failed. Check logs: docker compose logs app | grep -i "error\|exception"
│         Re-upload the document.
└── YES → Check pipeline logs:
    ├── "[RETRIEVAL] FAILED — 0 chunks" → Lower similarity threshold in Settings
    └── "[CRAG] FAILED — all chunks scored below X" → Lower CRAG min-score in properties
```

### Spring Boot Actuator

```bash
curl http://localhost:8081/actuator/health       # Detailed health check
curl http://localhost:8081/actuator/metrics       # Available metrics list
```

---

## 13. Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| "NO MATCHING SOURCES" on all queries | ChromaDB has 0 vectors (ingestion failed silently) | Check vector count (see above). Delete doc in UI, re-upload. Check logs for exceptions. |
| Ingestion shows success but ChromaDB empty | tabula/OCR crash or embedding exceeds 512 tokens | Check `docker compose logs app \| grep error`. Ensure `make rebuild` was run after code changes. |
| Very slow responses (>60s) | Ollama model not loaded in GPU memory | Run `ollama ps`. If model not listed, first query loads it (~10s). Subsequent queries are fast. |
| "input length exceeds context length" | Chunk too large for mxbai-embed-large (512 token limit) | Reduce chunk size in `buildIngestor()`. Current safe value: 1000 chars. |
| Distributed node shows unhealthy | Node's Ollama not reachable on LAN | Verify with `curl http://<IP>:11434/api/tags`. Ensure `OLLAMA_HOST=0.0.0.0:11434`. Check firewall. |
| `No healthy nodes available` | All distributed nodes offline | Start `ollama serve` on team laptops. Or disable distributed: `inference.distributed.enabled=false` |
| UI shows "INGESTED" but queries fail | Browser localStorage stale after `make clean` | Delete the document in UI, re-upload. Or clear browser localStorage. |
| Low grounding score (<50%) | Answer includes model knowledge beyond the document | Lower CRAG threshold to be more selective, or check if query is too broad. |
| `COSStream has been closed` | Old code: tabula ObjectExtractor closing PDF prematurely | Run `make rebuild` — this bug was fixed in v3.2. |
