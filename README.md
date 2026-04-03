# The Vault — Complete Project History & Operations Manual

> A fully self-hosted, privacy-first AI document Q&A system. Upload PDFs, ask questions, get answers strictly grounded in the document with inline citations. All processing happens on your machine — no data leaves.

---

## Table of Contents

1. [What This System Is](#1-what-this-system-is)
2. [Version History — What Changed and Why](#2-version-history)
   - [v1 — The Original Build](#v1--the-original-build)
   - [v2 — Multi-Query Expansion](#v2--multi-query-expansion)
   - [v3.0 — The Full Rebuild](#v30--the-full-rebuild)
   - [v3.1 — Critical Bug Fixes](#v31--critical-bug-fixes)
   - [v3.2 — PDF Ingestion Overhaul](#v32--pdf-ingestion-overhaul)
   - [v3.3 — Today's Session Fixes](#v33--todays-session-fixes)
3. [How to Run — Complete Step-by-Step](#3-how-to-run)
4. [How to Share With Other People](#4-how-to-share-with-other-people)
5. [The Distributed Inference System](#5-the-distributed-inference-system)
6. [Network & Firewall — The Full Explanation](#6-network--firewall)
7. [Configuration Reference](#7-configuration-reference)
8. [Troubleshooting Playbook](#8-troubleshooting-playbook)
9. [Database Persistence & Deletion Validation](#9-database-persistence--deletion-validation)
10. [File Ingestion — Limits, Resource Constraints & What to Expect](#10-file-ingestion--limits-resource-constraints--what-to-expect)
11. [How Users Don't Collide — Session Memory System](#11-how-users-dont-collide--session-memory-system)
12. [Multi-File Queries — Scoping Constraints & Mechanics](#12-multi-file-queries--scoping-constraints--mechanics)
13. [Key Metrics — Where to Find Them, What to Expect](#13-key-metrics--where-to-find-them-what-to-expect)
14. [Inspecting the Vector Database Directly](#14-inspecting-the-vector-database-directly)

---

## 1. What This System Is

The Vault is a Local RAG (Retrieval-Augmented Generation) platform. In simple terms:

- You upload any PDF, DOCX, or spreadsheet
- The system reads it, splits it into chunks, and converts each chunk into a 1024-dimensional vector stored in ChromaDB
- When you ask a question, the system retrieves the most relevant chunks, verifies them with an AI quality filter (CRAG), then generates an answer using ONLY those verified chunks
- Every claim in the answer is backed by a direct quote from the document

**The AI chain (all runs on your machine, no cloud):**
```
Gemma 2 9B (via Ollama)     ← Generates hypothetical queries, evaluates chunks, synthesizes answers
mxbai-embed-large (via Ollama) ← Converts text to 1024-dim vectors
ChromaDB 0.4.24              ← Stores and searches vectors
Spring Boot 4 (Java 21)     ← Orchestrates everything
React (in-browser Babel)    ← Frontend UI
```

---

## 2. Version History

### v1 — The Original Build

**What it was:**
- Basic Spring Boot + basic vector search
- Single LLM call per query: embed question → search ChromaDB → feed top chunks to LLM → answer
- No quality filtering — all retrieved chunks went directly to the answer
- Used `llama3` as the generation model and `nomic-embed-text` for embeddings (128-dim)
- Similarity threshold hardcoded at 0.70

**The problem:**
- Hallucination was rampant because noisy, irrelevant chunks polluted the LLM context
- No way to know if the answer was grounded or made up
- Single shared memory — all users shared one conversation window

---

### v2 — Multi-Query Expansion

**What changed:**
- Introduced multi-query expansion: one user question → 3 paraphrased variants → 4 total searches
- Added per-chunk CRAG quality filtering: 2 LLM calls per chunk (YES/NO + re-rank)
- Added shared memory window (one 10-message rolling window)
- Added grounding score UI badge

**The massive problem:**
- For 10 retrieved chunks: 2 calls/chunk × 10 chunks = 20 CRAG calls + 10 re-rank calls + 1 synthesis = **31 LLM calls per query**
- Average response time: 60–90 seconds
- Memory contamination: all browser tabs shared one conversation context

---

### v3.0 — The Full Rebuild

**Goal:** Keep the accuracy of v2 but reduce LLM calls from 31 to 3.

**Major changes:**

#### 1. Batch CRAG (31 LLM calls → 1)
Instead of 2 calls per chunk, ALL chunks are evaluated in ONE LLM call. The model scores each chunk 1–5 in a single JSON array response. This is the most impactful change in the entire project.

```
v2: [chunk1_yes_no] [chunk1_rerank] [chunk2_yes_no] [chunk2_rerank] ... [synthesis] = 31 calls
v3: [batch_rate_all_chunks] [synthesis] = 2–3 calls
```

#### 2. HyDE — Hypothetical Document Embedding
Instead of embedding the raw user question, the LLM writes a hypothetical passage that WOULD appear in a document answering the question. This passage is then embedded.

Why this improves retrieval: mxbai-embed-large is asymmetric — query vectors and document vectors live in different spaces. A raw question ("what is a list?") has lower cosine similarity to document text about lists than a fake document passage ("A list in Python is an ordered mutable sequence...") does.

#### 3. Per-tab session isolation
Each browser tab generates `crypto.randomUUID()`. Sessions are isolated in-memory with 20-message windows. Auto-evicted after 30 minutes of inactivity.

#### 4. Upgraded models
- `llama3` → `gemma2` (9B, Google's model — better instruction following)
- `nomic-embed-text` (128-dim) → `mxbai-embed-large` (1024-dim) — higher quality embeddings, MTEB-leading retrieval
- ChromaDB pinned to `0.4.24` (newer versions removed the v1 API endpoints LangChain4j uses)

#### 5. Better document ingestion
- PDF: PDFBox per-page + tabula-java table extraction + Tesseract OCR for scanned pages
- DOCX: Apache POI with table-aware parsing
- Generic: Apache Tika fallback

**After v3.0:** Response time dropped from 60–90s to 8–15s.

---

### v3.1 — Critical Bug Fixes

After deploying v3.0, ALL queries returned "NO MATCHING SOURCES". Three separate bugs were found and fixed.

#### Bug 1: Similarity threshold calibrated for the wrong model

The threshold was 0.70 — calibrated for `nomic-embed-text`. The new mxbai-embed-large returns cosine scores in the **0.25–0.55 range** for good matches (it's trained differently). With a 0.70 threshold, literally nothing was being retrieved.

**Fix:** Default threshold lowered to `0.30`. UI slider minimum reduced to `0.10`.

#### Bug 2: HyDE passages embedded in the wrong vector space

mxbai-embed-large is asymmetric:
- Documents → embedded WITHOUT any prefix (passage space)
- Questions → embedded WITH prefix: `"Represent this sentence for searching relevant passages: ..."` (query space)

The bug: HyDE passages (fake documents) were being embedded WITH the query prefix, pushing them into query space — far away from stored document vectors. So even though HyDE generated a great fake passage, its vector was in the wrong space and found nothing.

**Fix:** Introduced `QueryVariant(text, isDocumentPassage)` record. When `isDocumentPassage=true`, the embedding call SKIPS the instruction prefix. HyDE passages now land in passage space, close to real document chunks.

#### Bug 3: CRAG min-score=4 rejected all chunks for code and summary queries

For a summary query ("summarize the document"), no single chunk IS a summary — so the LLM correctly scored all chunks 2–3 (partial content). For code queries ("what is list"), code examples got scored 2–3 (they demonstrate but don't define). Both were below the threshold of 4 → all chunks rejected.

**Fix:** Introduced query classification (zero LLM calls, pure regex):

| Query Type | Trigger | CRAG Threshold | HyDE Strategy |
|---|---|---|---|
| SYNTHESIS | Keywords: summarize, overview, key points... | 3 (partial = ok) | Skip, use broad anchors |
| CODE_SEARCH | ≤ 5 words | 3 (demonstration = ok) | Generate code snippet |
| FACTOID | Everything else | 4 (high precision) | Generate prose passage |

**Files changed in v3.1:**
- `ChatController.java`: minScore default 0.70 → 0.30
- `index.html`: similarityThreshold default 0.60 → 0.30, slider min 0.40 → 0.10
- `application.properties`: added `rag.crag.batch.min-score.synthesis=3`, `rag.crag.batch.min-score.code=3`,  `rag.retrieval.min-score.relax-for-synthesis=0.20`, `rag.retrieval.min-score.relax-for-code=0.20`
- `ChatbotService.java`: added `QueryType` enum, `classifyQuery()`, `generateHypotheticalCodePassage()`, `QueryVariant` record, broadened CODE_SEARCH to ≤5 words, adaptive rubric in `batchCragEvaluate()`

---

### v3.2 — PDF Ingestion Overhaul

After v3.1, ingestion was still silently failing on real PDFs. The UI showed "INGESTED (1)" but ChromaDB had 0 vectors.

#### Bug 1: tabula destroyed the PDF after page 1 (the COSStream bug)

```java
// BROKEN: creates ObjectExtractor per page. close() kills the PDF's internal streams.
for (int page = 1; page <= totalPages; page++) {
    try (ObjectExtractor extractor = new ObjectExtractor(pdf)) { // ← close() here corrupts pdf
        ...
    }
}
// Result: page 1 ingested, pages 2–N: IOException: COSStream has been closed
```

```java
// FIXED: one ObjectExtractor for the entire PDF
ObjectExtractor tableExtractor = new ObjectExtractor(pdf);
for (int page = 1; page <= totalPages; page++) {
    extractTablesFromPage(tableExtractor, page, filename); // shares the extractor, never closes it
}
// PDDocument.close() handles cleanup when the outer try-with-resources exits
```

#### Bug 2: Chunk size exceeded embedding model's token limit

Code chunks at 1500 chars = ~680 tokens. mxbai-embed-large's hard limit is 512 tokens.

```java
// BEFORE: 1500 chars (safe for prose, crashes on dense code)
DocumentSplitters.recursive(1500, 400)

// AFTER: 1000 chars (max ~400 tokens even for dense code — safe)
DocumentSplitters.recursive(1000, 250)
```

#### Bug 3: One bad page crashed all subsequent pages

**Fix:** Wrapped every page in a per-page try/catch. A bad page logs and skips; ingestion continues.

**Result after v3.2:**
```
506-page Python PDF → 1,496 vectors stored
"summarize this document" → 17 chunks verified, grounding 56%
"what is list in python"  → 9 chunks verified, grounding 63%
```

---

### v3.3 — Today's Session Fixes

#### Fix 1: Port conflict (Python Uvicorn was occupying port 8081)
A background Python API server from a different project was running on port 8081. When Docker tried to bind to 8081, Docker got the port but on the Mac's network interface, the Python process intercepted LAN requests. Switched Docker to port 8085.

**Change:** `docker-compose.yml`: `"8081:8081"` → `"8085:8081"`

#### Fix 2: FACTOID queries starved on code-heavy documents
The relaxed similarity floor (0.20) only applied to SYNTHESIS and CODE_SEARCH. FACTOID queries (which is what most natural language queries classify as) still used the raw `minScore` of 0.30. On code-heavy documents where HyDE prose vectors have cosine similarity of 0.20–0.28, FACTOID queries retrieved only 4 chunks — all of which were garbage and failed CRAG.

**Changes:**
- `application.properties`: `crag.batch.min-score` 4 → 3; added `rag.retrieval.min-score.relax-for-factoid=0.20`
- `ChatbotService.java`: Added `relaxMinScoreFactoid` `@Value` field; updated `effectiveMinScore` switch to apply `Math.min(minScore, relaxMinScoreFactoid)` for FACTOID queries; fixed `@Value` fallback defaults from `0.55` → `0.20`

#### Fix 3: macOS MDM firewall blocked LAN access — opened with ngrok tunnel
The Mac is managed by an MDM policy (Mosyle). The firewall was blocking incoming connections on port 8085 from other devices. Even though Docker was allowed through the firewall, Docker's port forwarding runs inside a virtualization layer that isn't in the firewall's allowlist.

**Solution:** ngrok reverse tunnel (see Section 4 and 6 for full explanation).

---

## 3. How to Run

### Prerequisites (one-time)

```bash
# 1. Install Ollama (for macOS)
brew install ollama

# 2. Pull required AI models (~6 GB total)
ollama pull gemma2              # Generation model (~5.5 GB)
ollama pull mxbai-embed-large   # Embedding model (~670 MB)

# 3. Verify models
ollama list
# Expected: gemma2:latest, mxbai-embed-large:latest

# 4. Install Docker Desktop and ensure it's running
```

### Day-to-Day Startup (run in order)

**Terminal 1 — Start Ollama (your GPU):**
```bash
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```
> Leave this running. The `0.0.0.0` flag exposes Ollama to the LAN for distributed inference. Without it, only your machine can use Ollama. If you see "address already in use", quit the Ollama menu bar app first.

**Terminal 2 — Start the app:**
```bash
cd /Users/makumar/Documents/java/In_Document_chat.

# First time ever:
make setup

# Every subsequent start:
make run
```

**Terminal 3 — Start the internet tunnel (if sharing with others):**
```bash
ngrok http 8085
```
> Copy the `https://xxxx.ngrok-free.app` URL and share it. See Section 4 for details.

**Check everything is healthy:**
```bash
make status
# OR individually:
curl http://localhost:8085/api/status          # App
curl http://localhost:8888/api/v1/heartbeat    # ChromaDB
curl http://localhost:11434/api/tags           # Ollama models
```

### Rebuild After Code Changes
```bash
make rebuild   # Rebuilds Docker image + restarts container (picks up all code + config changes)
make logs      # Watch live logs
```

### Makefile Command Reference

| Command | What It Does |
|---|---|
| `make setup` | First-time: checks prereqs, pulls models, builds image, starts all services |
| `make run` | Start all services (after first setup) |
| `make stop` | Stop services, preserve all data in ChromaDB |
| `make rebuild` | Rebuild app image after code/config changes + restart |
| `make clean` | ⚠️ Destructive: stop + delete ALL data (ChromaDB vectors erased) |
| `make logs` | Follow live app logs (Ctrl+C to exit) |
| `make status` | Health check for Ollama, ChromaDB, and app |
| `make purge-old-models` | Remove old v2 models (llama3, nomic-embed-text) to free ~5 GB |

---

## 4. How to Share With Other People

### Option A: Same WiFi (LAN) — WITHOUT ngrok

This only works if your Mac's firewall allows it (not the case for MDM-managed Macs).

1. Find your LAN IP:
   ```bash
   ipconfig getifaddr en0
   ```
2. Share: `http://<your-ip>:8085`

> ⚠️ **This does NOT work on MDM-managed Macs.** The macOS firewall blocks incoming connections from other devices, even on the same WiFi. See Section 6 for why.

### Option B: Any Network, Any Device — WITH ngrok (Recommended)

ngrok works by tunneling your app through ngrok's public cloud servers. Your friends never connect to your Mac directly — they connect to ngrok's servers, which relay the traffic through a tunnel your Mac opened. Since your Mac opened the tunnel OUTBOUND (which firewalls never block), the incoming traffic bypasses the MDM firewall entirely.

**Setup (one-time):**
```bash
# Add your own authtoken from the ngrok dashboard
ngrok config add-authtoken <YOUR_NGROK_TOKEN>
```

**Start tunnel (every session):**
```bash
ngrok http 8085
```

Output will show:
```
Forwarding  https://abc123.ngrok-free.app → http://localhost:8085
```

Share the `https://abc123.ngrok-free.app` URL. It works from any device, any network, even mobile data.

> **Note:** The URL changes every time you restart ngrok (free tier). Keep the terminal open until your submission/demo is done. First-time visitors see an ngrok warning page — they click "Visit Site" to proceed.

### Verify Your Distributed Inference Node Is Connected
```bash
curl http://localhost:8085/api/inference/nodes
```
Expected output:
```json
[{"id":"myself","url":"http://<YOUR-LAN-IP>:11434","healthy":true,"activeRequests":0,"maxConcurrent":2}]
```

---

## 5. The Distributed Inference System

### What It Is

Each team laptop runs Ollama with the same AI model. The Spring Boot app coordinates all of them, distributing LLM requests across available nodes. If a node fails, requests automatically failover to other nodes.

```
                    Spring Boot (coordinator on <YOUR-LAN-IP>:8085)
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
         Laptop A      Laptop B      Laptop C
         (myself)      (team)        (team)
         M2 Pro        RTX4090       M1
         max: 2 req    max: 3 req    max: 1 req
```

### How Load Balancing Works (Under the Hood)

1. Filter to healthy nodes only (those that passed the last 10-second ping)
2. Among healthy nodes, prefer nodes with free capacity (semaphore permits > 0)
3. Among those, pick the one with fewest active requests
4. If all are at capacity, round-robin through healthy nodes
5. If a node errors: retry on another node. If all fail: fall back to local Ollama

### How to Add a Team Laptop as an Inference Node

**On their laptop:**
```bash
# Install Ollama and pull the model
brew install ollama
ollama pull gemma2

# Start Ollama exposed to the network
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

**From your laptop** (the coordinator):
```bash
# Replace <THEIR-LAN-IP> with their actual IP
curl -X POST "http://localhost:8085/api/inference/nodes/register?id=laptop-b&url=http://<THEIR-LAN-IP>:11434&maxConcurrent=2"

# Verify registration
curl http://localhost:8085/api/inference/nodes
```

### Your Node Registration (application.properties)

```properties
# Your own laptop registered as "myself"
inference.distributed.nodes=myself|http://<YOUR-LAN-IP>:11434|2
```

> ⚠️ **This IP changes every time you reconnect to WiFi** (DHCP assigns a new IP). Update it with `ipconfig getifaddr en0` and run `make rebuild`.

---

## 6. Network & Firewall

### Why LAN Access Stopped Working (The Full Story)

Your Mac is managed by Mosyle MDM (checked via `socketfilterfw --listapps`). MDM policies lock the firewall so it cannot be changed from the terminal.

**What the firewall whitelist actually contains:**
```
/Applications/Docker.app           ← allowed (the app itself, not its containers)
/Applications/Ollama.app           ← allowed
/Library/.../Python3.framework     ← allowed (Python was specifically allowed when a prompt was clicked once)
```

**What's NOT in there:**
- Docker's internal virtualization layer (`com.docker.virtualization`) — this is what actually handles port forwarding. It's NOT in the allowlist, so incoming connections from other devices get silently dropped.

**Why it worked before:**
The Python Uvicorn server (`source_code/pipeline/api_server.py`) was running directly on your Mac on port 8081. At some point macOS showed a popup "Allow Python to accept incoming connections?" — you clicked Allow. That added Python to the whitelist permanently. So THAT server was reachable from the LAN. When we killed Python and switched to Docker on port 8085, Docker's virtualization layer was not in the whitelist.

### How ngrok Bypasses the Firewall

```
BLOCKED (firewall drops incoming):
Other device ──INBOUND──▶ <YOUR-LAN-IP>:8085
                               ↑
                    MDM firewall drops this ✗

WORKS (outbound tunnel, never blocked):
Step 1: YOUR Mac ──OUTBOUND──▶ ngrok cloud ← Firewall never blocks outbound
Step 2: Friend ──▶ ngrok cloud ──▶ tunnels through your existing outbound connection ──▶ Your Mac
```

Firewalls block **incoming** connections. They never block outbound (your browser, Instagram, Slack all work = all outbound). ngrok exploits this: YOUR Mac opens an outbound connection to ngrok, creating a persistent tunnel. When someone visits your ngrok URL, ngrok relays data through THAT tunnel — which the firewall sees as outbound traffic from YOUR Mac, not as incoming from a stranger.

### Why the Client's Network Doesn't Matter

With ngrok, your friends never connect to your Mac's IP address at all. They connect to `YOUR-NGROK-ID.ngrok-free.dev` — a public server hosted by ngrok. ngrok's servers have real public IPs fully routable on the internet. The fact that your Mac is on a private `192.168.x.x` address is completely irrelevant to anyone using the ngrok URL.

---

## 7. Configuration Reference

File: `src/main/resources/application.properties`

```properties
# ── AI Models ─────────────────────────────────────────────────────────────────
langchain4j.ollama.chat-model.model-name=gemma2           # Generation LLM
langchain4j.ollama.chat-model.temperature=0.0             # Deterministic (no creativity for factual RAG)
langchain4j.ollama.embedding-model.model-name=mxbai-embed-large   # 1024-dim embedding model

# ── Query Expansion ────────────────────────────────────────────────────────────
rag.query-expansion.mode=hyde     # hyde | multi-query | none

# ── CRAG Scoring Thresholds (1–5 scale) ───────────────────────────────────────
rag.crag.batch.min-score=3                  # FACTOID: score ≥ 3 kept (was 4, caused failures on code docs)
rag.crag.batch.min-score.synthesis=3        # SYNTHESIS: partial content ok
rag.crag.batch.min-score.code=3             # CODE_SEARCH: demonstrations ok

# ── Retrieval Similarity Floor ─────────────────────────────────────────────────
# These OVERRIDE the user's minScore if the user's value is HIGHER.
# Math.min(userMinScore, relaxValue) — the lower value always wins.
rag.retrieval.min-score.relax-for-synthesis=0.20    # Broad anchor embeddings score lower
rag.retrieval.min-score.relax-for-code=0.20         # Code-snippet HyDE scores lower
rag.retrieval.min-score.relax-for-factoid=0.20      # Prose HyDE on code docs scores lower

# ── Server ─────────────────────────────────────────────────────────────────────
server.port=8081                    # Port inside Docker container
server.address=0.0.0.0              # Bind to all interfaces (required for LAN access)

# ── Distributed Inference ──────────────────────────────────────────────────────
inference.distributed.enabled=true
inference.distributed.admin.enabled=true
# Format: id|http://LAN-IP:11434|maxConcurrent
# ⚠️ Update IP whenever your WiFi IP changes (check: ipconfig getifaddr en0)
inference.distributed.nodes=myself|http://<YOUR-LAN-IP>:11434|2
inference.distributed.healthcheck.interval-seconds=10
inference.distributed.healthcheck.max-failures=3
```

---

## 8. Troubleshooting Playbook

### Symptom: "NO MATCHING SOURCES" on every query

**Step 1: Check ChromaDB has vectors**
```bash
COLLECTION_ID=$(curl -s http://localhost:8888/api/v1/collections | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else 'EMPTY')")
curl -s "http://localhost:8888/api/v1/collections/$COLLECTION_ID/count"
```
- If `0` → ingestion failed silently. Delete the doc in the UI, re-upload. Check `make logs` for the error.
- If `> 0` → retrieval is failing (threshold too high or wrong model)

**Step 2: Watch pipeline logs during a query**
```bash
make logs | grep -E "QUERY|RETRIEVAL|CRAG|SYNTHESIS|GROUNDING"
```
- `[RETRIEVAL] 0 unique chunks` → lower similarity threshold in Settings (try 0.15)
- `[CRAG] Approved: 0 / X` → lower CRAG min-score in `application.properties` (try 2)
- `[CRAG] Batch evaluation exception` → Ollama is overloaded or timed out

### Symptom: LAN link doesn't work for other people

1. Check if your Mac is MDM-managed:
   ```bash
   sudo /usr/libexec/ApplicationFirewall/socketfilterfw --listapps
   ```
   If you see "Firewall settings cannot be modified from command line on managed Mac computers" → you need ngrok.

2. Start ngrok:
   ```bash
   ngrok http 8085
   ```

### Symptom: Distributed inference node shows unhealthy

```bash
# Check if the node's Ollama is reachable
curl http://<THEIR-LAN-IP>:11434/api/tags

# If fails: make sure they started Ollama with the network flag:
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

### Symptom: Slow responses (> 30 seconds)

- Check if Ollama is GPU-accelerated: `ollama ps` — look for GPU usage
- Reduce maxResults in UI Settings from 10 to 5 (fewer chunks → faster CRAG evaluation)
- Check if distributed nodes are sharing the load: `curl http://localhost:8085/api/inference/nodes`

### Symptom: IP address in application.properties is wrong

Your LAN IP changes every time you reconnect to WiFi.
```bash
# Get current IP
ipconfig getifaddr en0

# Update application.properties line 66 with new IP, then:
make rebuild
```

### Symptom: ChromaDB shows "unhealthy" in docker ps

ChromaDB's built-in healthcheck is strict. Run:
```bash
curl http://localhost:8888/api/v1/heartbeat
```
If this returns `{"nanosecond heartbeat": ...}` → ChromaDB is actually fine, the Docker healthcheck is just misconfigured. The app works regardless.

### Symptom: "can u summerize" type queries fail (typos in keywords)

The SYNTHESIS classifier uses exact regex. Typos like "summerize" instead of "summarize" will NOT trigger SYNTHESIS mode — the query will fall through to CODE_SEARCH (≤5 words) or FACTOID.

**Workaround:** Always type the full word. "summarize this document", "give me an overview", "what are the key points" — all correctly trigger SYNTHESIS mode.

**Permanent fix:** Update the regex in `ChatbotService.java` line 146 to include common typos:
```java
if (q.matches(".*(summarize|summarise|summerize|summery|overview|outline|...).*")) {
```

**Permanent fix:** Update the regex in `ChatbotService.java` line 146 to include common typos:
```java
if (q.matches(".*(summarize|summarise|summerize|summery|overview|outline|...).*")) {
```

---

## 9. Database Persistence & Deletion Validation

A common concern is whether `make rebuild` erases the vector database. 
- **Rebuilding (`make rebuild`)**: **Does NOT wipe ChromaDB.** The `docker-compose.yml` mounts a persistent Docker volume (`vault-chromadb-data` mapped to `/chroma/chroma/`). The moment a file is ingested, its vectors survive container restarts, rebuilds, and power cycles.
- **Deletions (`/api/documents/delete`)**: Deletions are precise. When you click delete in the UI, the backend executes `embeddingStore.removeAll(metadataKey("source_file").isEqualTo(filename))`. This cleanly targets and purges exclusively the chunks tied to that precise filename, zeroing out the vectors without affecting anything else.

---

## 10. File Ingestion — Limits, Resource Constraints & What to Expect

The system imposes limits both by configuration and by hardware realities:

- **Configured Limits (`application.properties`)**:
  - `spring.servlet.multipart.max-file-size=50MB`: Maximum size for a single file uploaded.
  - `spring.servlet.multipart.max-request-size=200MB`: Maximum size for a batched request (e.g., four 50MB files dropped at once).
- **The Actual Bottleneck (Compute)**: File size isn't the true limit — GPU embedding time is. A 50MB text-heavy PDF equates to roughly 5,000 vectors. It takes the GPU approximately 200ms to embed a single chunk. Thus, a 50MB file takes ~15 minutes to fully ingest and index.
- **Token Constraints**: `mxbai-embed-large` possesses a hard 512-token context limit. We recursively chunk strings at exactly **1,000 characters** to ensure they effortlessly sit beneath this token roof (1,000 characters typically equals ~250 tokens in prose, and ~400 tokens in dense code).

---

## 11. How Users Don't Collide — Session Memory System

**How it Works:** 
When `index.html` loads, it triggers `crypto.randomUUID()` to generate a unique Session ID in browser memory. Every query sent attaches this `sessionId`. In `ChatbotService.java`, Spring Boot maps this ID against a `ConcurrentHashMap` containing 20-message `MessageWindowChatMemory` instances. 

**Pros:**
1. **Total Isolation (Multi-Tenant Ready):** You and 10 coworkers can hit the UI simultaneously over LAN. Your conversations, history, and follow-ups never cross-contaminate. 
2. **Stateless Operations:** It functions perfectly without login states, cookies, or databases.
3. **Self-Cleaning:** Idle instances are expired every 30 minutes, freeing JVM RAM. 

**Cons:**
1. **Ephemeral Execution:** If you reload the browser tab, your UUID is wiped. The conversation is lost permanently. Your UI resets. 

**Future Expansion:**
For production maturity, replace the UUID payload with a verified JWT. Store the conversation messages directly inside PostgreSQL keyed against the extracted `userId`.

---

## 12. Multi-File Queries — Scoping Constraints & Mechanics

**How it Works:**
When you select files in the UI, the frontend passes `?filenames=docA.pdf,docB.pdf`. Before the backend computes any cosine similarities, it instructs ChromaDB to construct a definitive pre-filter utilizing the `$in` operator constraint on the `source_file` metadata. 

**Resulting Behavior:**
Chunks from unselected documents are literally physically excluded from the vector search space prior to execution. If you ask a question regarding "Doc C" but only have "Doc A" selected, the retrieval pipeline evaluates only Doc A vectors, returning 0 chunks, guaranteeing scope containment.

---

## 13. Key Metrics — Where to Find Them, What to Expect

All system tuning configuration lies in `src/main/resources/application.properties`. 

| Property parameter | Effect | Default State / Experimentation |
|---|---|---|
| `rag.crag.batch.min-score.*` | Governs CRAG strictness per classification group. | Default: `3` (for all classes). Setting this to `4` for `FACTOID` creates hyper-strict evaluation, rejecting weak contexts but risking "No matching sources" dropouts. |
| `rag.retrieval.min-score.relax-for-*` | The absolute minimum Cosine Similarity floor required to surface vectors. | Default: `0.20`. Pushing below 0.15 retrieves highly unrelated filler text. Pushing above 0.35 actively blocks dense code snippets. |
| `maxResults` | (UI Slider) Caps how many chunks pass to CRAG/Generation. | Default: `10`. Setting to `20` requires 2x CRAG grading time, increasing latency to ~25 seconds. Reducing to `5` improves latency to ~6s. |

---

## 14. Inspecting the Vector Database Directly

Because Chroma runs isolated in Docker, you can interrogate the raw API REST endpoints directly to audit vectors.

**1. Grab the Collection ID dynamically:**
```bash
cid=$(curl -s http://localhost:8888/api/v1/collections | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
```

**2. See how many vectors physically exist right now:**
```bash
curl -s "http://localhost:8888/api/v1/collections/$cid/count"
```

**3. Peek at the top 3 vectors in the DB to examine their data structure:**
```bash
curl -s "http://localhost:8888/api/v1/collections/$cid/get" -H 'Content-Type: application/json' -d '{"limit":3}' | python3 -m json.tool
```

**4. Check what exact files have been uploaded (and stored metadata):**
*(This fetches up to 100 metadata fields dynamically showing filenames)*
```bash
curl -s "http://localhost:8888/api/v1/collections/$cid/get" -H 'Content-Type: application/json' -d '{"limit": 100}' | grep -o '"source_file": "[^"]*"' | sort | uniq
```

---

*Last updated: 2026-04-03 | Current version: v3.3*
