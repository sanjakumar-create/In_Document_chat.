# Command Reference — The Vault v3 Debugging & Operations

Everything you need to diagnose, operate, and tune the system.

---

## 1. Service Lifecycle

### Start / Stop / Rebuild

```bash
make setup              # First time only: check prereqs, pull models, build image, start all
make run                # Start services (after first setup)
make stop               # Stop containers, preserve all data (ChromaDB vectors intact)
make rebuild            # Rebuild Spring Boot image after code changes + restart
make clean              # DESTRUCTIVE: stop + delete ALL data volumes (ChromaDB wiped)
make logs               # Follow app container logs (Ctrl+C to stop)
make status             # Quick health check for Ollama, ChromaDB, and app
```

### Docker Compose (manual equivalents)

```bash
docker compose up -d                  # Start all services in background
docker compose up -d --build app      # Rebuild app image + restart only app container
docker compose stop                   # Stop without deleting (data preserved)
docker compose down                   # Stop + remove containers (volumes preserved)
docker compose down -v                # DESTRUCTIVE: stop + remove containers + ALL volumes
docker compose restart app            # Restart app only (no rebuild)
docker compose logs -f app            # Follow app logs live
docker compose logs app --tail 100    # Last 100 lines of app logs
docker compose ps                     # Show running containers + status + ports
```

---

## 2. Health Checks

### Is everything running?

```bash
# App alive?
curl -s http://localhost:8081/api/status | python3 -m json.tool
# Expected: {"status":"online","generationModel":"gemma2","embeddingModel":"mxbai-embed-large",...}

# ChromaDB alive?
curl -s http://localhost:8888/api/v1/heartbeat
# Expected: {"nanosecond heartbeat": ...}

# Ollama alive?
curl -s http://localhost:11434
# Expected: "Ollama is running"

# Which models are pulled?
curl -s http://localhost:11434/api/tags | python3 -c "import sys,json; [print(m['name']) for m in json.load(sys.stdin).get('models',[])]"
# Expected: gemma2:latest, mxbai-embed-large:latest
```

### Container status

```bash
docker compose ps
# Check STATUS column: "Up X minutes" = good, "unhealthy" = problem, "Exited" = crashed

docker inspect vault-chromadb --format='{{.State.Health.Status}}'
# Expected: "healthy" (if "unhealthy" — ChromaDB healthcheck failing but may still work)
```

---

## 3. ChromaDB Diagnostics (THE MOST IMPORTANT SECTION)

### Is there actually data in ChromaDB?

```bash
# List all collections
curl -s http://localhost:8888/api/v1/collections | python3 -m json.tool

# Count vectors in the main collection
curl -s "http://localhost:8888/api/v1/collections/$(curl -s http://localhost:8888/api/v1/collections | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")/count"
# Expected: a number > 0 (e.g., 1496 for a 506-page PDF)
# If 0: ingestion failed — re-upload the document
```

**This is the #1 thing to check when queries return "NO MATCHING SOURCES".** If the count is 0, no amount of threshold tuning will help — there is nothing to search.

### Peek at stored vectors (see what's actually in ChromaDB)

```bash
# Get first 3 stored chunks with their metadata
curl -s -X POST "http://localhost:8888/api/v1/collections/$(curl -s http://localhost:8888/api/v1/collections | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")/get" \
  -H "Content-Type: application/json" \
  -d '{"limit": 3, "include": ["documents","metadatas"]}' | python3 -m json.tool
# Shows: chunk text + source_file, page_number, content_type metadata
```

### Wipe ChromaDB completely (when you need a fresh start)

```bash
make clean       # Easiest: stops everything + deletes volumes
# OR manually:
docker compose down -v
docker volume rm vault-chroma-data
docker compose up -d
```

---

## 4. Ingestion Diagnostics

### Upload a file via CLI (instead of browser)

```bash
# Single file
curl -s -X POST http://localhost:8081/api/documents/upload \
  -F "file=@/path/to/your/document.pdf"
# Expected: "Successfully ingested and vectorized: document.pdf"
# If error: check app logs immediately

# Batch upload
curl -s -X POST http://localhost:8081/api/documents/upload-batch \
  -F "files=@/path/to/file1.pdf" \
  -F "files=@/path/to/file2.docx"
```

### Check ingestion logs for errors

```bash
# All ingestion activity
docker compose logs app 2>/dev/null | grep -iE "INGEST|OCR|tabula|error|exception|embed"

# Specifically look for the failure chain:
docker compose logs app 2>/dev/null | grep -iE "COSStream|context length|Caused by|falling back"
# If you see these: ingestion crashed — see fix.md for explanation
```

### Delete a document's vectors

```bash
curl -s -X DELETE "http://localhost:8081/api/documents/delete?filename=myfile.pdf"
# Removes ALL vectors for that file from ChromaDB
```

### Test ingestion with a small file first

```bash
echo "Python lists are ordered collections. Use append() to add items." > /tmp/test.txt
curl -s -X POST http://localhost:8081/api/documents/upload -F "file=@/tmp/test.txt"
# If this works but a PDF fails: the problem is PDF-specific (tables/OCR/size)
```

---

## 5. Query Diagnostics

### Ask a question via CLI (bypass the browser)

```bash
# Basic query
curl -s "http://localhost:8081/api/chat/ask?question=what+is+a+list&filenames=ALL&minScore=0.20&maxResults=10" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('Answer:', d['answer'][:300]); print('Chunks:', d['chunkCount']); print('Grounding:', round(d['groundingScore'],2))"

# Query a specific file
curl -s "http://localhost:8081/api/chat/ask?question=summarize+this&filenames=myfile.pdf&minScore=0.20&maxResults=10" \
  | python3 -m json.tool
```

### Read query pipeline logs (shows exactly where it failed)

```bash
docker compose logs app --tail 30 | grep -E "QUERY|RETRIEVAL|CRAG|SYNTHESIS|GROUNDING"
```

**What the log lines mean:**

| Log prefix | What it tells you |
|---|---|
| `[QUERY] "..." \| files=... \| session=...` | Question received, which files being searched |
| `[QUERY] Mode: hyde` | Which expansion mode is active |
| `[QUERY] Type: SYNTHESIS / CODE_SEARCH / FACTOID` | How the query was classified |
| `[QUERY] Variants: N \| hyde-passage=M` | How many search variants, how many are HyDE passages |
| `[RETRIEVAL] X unique chunks retrieved (minScore=Y)` | How many chunks came back from ChromaDB |
| `[RETRIEVAL] FAILED — 0 chunks` | **Nothing in ChromaDB OR threshold too high** |
| `[CRAG] Approved: X / Y (min score: Z, type: T)` | How many chunks passed CRAG evaluation |
| `[CRAG] FAILED — all N chunks scored below M` | **CRAG rejected everything — lower min-score or adjust rubric** |
| `[SYNTHESIS] Generating from N verified chunks...` | Final answer being generated |
| `[GROUNDING] Score: 0.XX (HIGH/MEDIUM/LOW)` | How grounded the answer is in the source text |

### Quick decision tree when "NO MATCHING SOURCES" appears:

```
Is ChromaDB count > 0?
├── NO → Ingestion failed. Check ingestion logs. Re-upload document.
└── YES → Check app logs:
    ├── "[RETRIEVAL] FAILED — 0 chunks" → minScore too high. Lower it.
    └── "[CRAG] FAILED — all chunks scored below X" → CRAG too strict. Lower min-score.
```

---

## 6. Ollama Model Management

```bash
# List installed models
ollama list

# Pull required models
ollama pull gemma2              # Generation model (~5 GB)
ollama pull mxbai-embed-large   # Embedding model (~670 MB)

# Check if a model is working
curl -s http://localhost:11434/api/generate -d '{"model":"gemma2","prompt":"hello","stream":false}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['response'][:100])"

# Check embedding model
curl -s http://localhost:11434/api/embeddings -d '{"model":"mxbai-embed-large","prompt":"test"}' \
  | python3 -c "import sys,json; e=json.load(sys.stdin)['embedding']; print(f'Dims: {len(e)}, first 3: {e[:3]}')"
# Expected: Dims: 1024

# Remove old v2 models (free ~5 GB disk)
make purge-old-models
# OR manually:
ollama rm llama3
ollama rm nomic-embed-text

# Check Ollama GPU usage
ollama ps
# Shows which models are loaded in memory and GPU usage
```

---

## 7. Session & Memory Management

```bash
# Clear a specific chat session's memory
curl -s -X POST "http://localhost:8081/api/chat/clear?sessionId=YOUR-SESSION-UUID"

# Check how many active sessions exist (from app logs)
docker compose logs app | grep "Sessions" | tail -5
# Shows eviction activity: "Evicted N idle sessions. Active: M"
```

---

## 8. Tunable Metrics — Experiment to Make Results More Robust

All settings are in `src/main/resources/application.properties`. Changes require `make rebuild`.

### Similarity Threshold (`minScore`)

**What it does:** Minimum cosine similarity between query embedding and chunk embedding. Chunks below this score are NOT retrieved from ChromaDB.

**Where set:** `ChatController.java` default (0.30), frontend `DEFAULT_SETTINGS.similarityThreshold` (0.30), and per-query-type relaxation in `application.properties`.

| Value | Effect | When to use |
|---|---|---|
| 0.10–0.20 | Very broad — retrieves almost everything | Large documents where you want maximum recall |
| 0.20–0.35 | **Recommended default for mxbai-embed-large** | General use — let CRAG filter quality |
| 0.35–0.50 | Moderate — misses some relevant content | When you get too many irrelevant chunks |
| 0.50+ | Very strict — only near-exact matches | Rarely useful with mxbai-embed-large |

**How to experiment:**
```bash
# Try different thresholds via curl (no rebuild needed):
curl -s "http://localhost:8081/api/chat/ask?question=your+question&minScore=0.15&maxResults=10" | python3 -m json.tool
curl -s "http://localhost:8081/api/chat/ask?question=your+question&minScore=0.40&maxResults=10" | python3 -m json.tool
# Compare chunkCount and answer quality
```

**Per-type relaxation** (in `application.properties`):
```properties
rag.retrieval.min-score.relax-for-synthesis=0.20   # For "summarize", "overview" queries
rag.retrieval.min-score.relax-for-code=0.20         # For short concept queries
```

---

### Batch CRAG Min Score (`rag.crag.batch.min-score`)

**What it does:** After retrieval, Gemma 2 scores each chunk 1–5 for relevance. Chunks below this score are rejected.

| Value | Effect | When to use |
|---|---|---|
| 2 | Very lenient — almost all chunks pass | When you want maximum context (risk: more noise) |
| 3 | **Recommended for synthesis/code** | Summary, overview, code concept queries |
| 4 | **Recommended for factoid** (default) | Specific fact/definition questions |
| 5 | Very strict — only perfect matches | When precision matters more than recall |

```properties
rag.crag.batch.min-score=4                    # Factoid queries (default)
rag.crag.batch.min-score.synthesis=3          # Synthesis/overview queries
rag.crag.batch.min-score.code=3               # Code/concept queries
```

**Trade-off:** Lower = more chunks reach synthesis (better coverage, risk of noise). Higher = fewer chunks (better precision, risk of missing info).

---

### Max Results (`maxResults`)

**What it does:** Maximum number of chunks retrieved from ChromaDB per query variant.

| Value | Effect | When to use |
|---|---|---|
| 3–5 | Fewer chunks, faster response | Short documents, precise questions |
| 10 | **Default — good balance** | General use |
| 15–20 | More context for synthesis | Long documents, "summarize everything" type questions |

**How to experiment:**
```bash
curl -s "http://localhost:8081/api/chat/ask?question=summarize&maxResults=20&minScore=0.15" | python3 -m json.tool
```

---

### Query Expansion Mode (`rag.query-expansion.mode`)

**What it does:** How the user's question is transformed before embedding for retrieval.

| Mode | LLM calls | Best for | Trade-off |
|---|---|---|---|
| `hyde` | 1 (generates passage) | **Default** — best quality for most queries | Slower, but produces better embeddings |
| `multi-query` | 1 (generates 3 paraphrases) | Broad vocabulary coverage | 4 embedding calls instead of 1 |
| `none` | 0 | Fastest, debugging | Misses vocabulary mismatches |

```properties
rag.query-expansion.mode=hyde         # Default
rag.query-expansion.mode=multi-query  # More search variants
rag.query-expansion.mode=none         # Raw question only (fastest)
```

---

### Chunk Size and Overlap (in `DocumentIngestionService.java`)

**What it does:** Controls how documents are split into pieces for embedding.

| Parameter | Current | Effect of increasing | Effect of decreasing |
|---|---|---|---|
| Chunk size | 1000 chars | More context per chunk but may exceed 512 token limit | Less context, more precise retrieval |
| Overlap | 250 chars | Better continuity across chunks | Less redundancy, fewer total chunks |

```java
// In buildIngestor():
DocumentSplitters.recursive(1000, 250)  // current
DocumentSplitters.recursive(800, 200)   // smaller chunks, more precise
DocumentSplitters.recursive(1200, 300)  // larger chunks, more context (test embedding limit!)
```

**WARNING:** mxbai-embed-large has a hard 512-token limit. 1000 chars is safe for prose AND code. Going above 1200 risks `"input length exceeds the context length"` errors on code-heavy content. **Always re-ingest after changing chunk size** (`make clean` + re-upload).

---

### Grounding Score Thresholds (in `ChatbotService.java`)

**What it does:** Visual badge on each answer showing how well it's grounded in source text.

| Threshold | Badge | Meaning |
|---|---|---|
| >= 0.75 | Green "High grounding" | Most answer words found in retrieved chunks |
| 0.50–0.74 | Yellow "Med grounding" | Some answer words from chunks, some from model |
| < 0.50 | Red "Low grounding" | Answer may include model knowledge beyond the document |

These are display thresholds only — they don't affect retrieval or CRAG. Adjust in `ChatbotService.java` if you want stricter/looser badges.

---

### Temperature (`langchain4j.ollama.chat-model.temperature`)

**What it does:** Controls randomness in LLM responses.

| Value | Effect |
|---|---|
| 0.0 | **Default — deterministic** (same question = same answer) |
| 0.1–0.3 | Slight variation, still factual |
| 0.5+ | More creative, risk of hallucination in RAG context |

**Recommendation:** Keep at 0.0 for RAG. Deterministic responses are more reliable for document Q&A.

---

## 9. End-to-End Diagnostic Playbook

When something isn't working, run these in order:

```bash
# Step 1: Is everything running?
docker compose ps
curl -s http://localhost:8081/api/status | python3 -m json.tool

# Step 2: Are models available?
curl -s http://localhost:11434/api/tags | python3 -c "import sys,json; [print(m['name']) for m in json.load(sys.stdin).get('models',[])]"

# Step 3: Is ChromaDB empty?
curl -s "http://localhost:8888/api/v1/collections" | python3 -c "import sys,json; cs=json.load(sys.stdin); [print(c['name'], c['id']) for c in cs]"
# Then check count:
curl -s "http://localhost:8888/api/v1/collections/COLLECTION_ID_HERE/count"

# Step 4: Test a query directly
curl -s "http://localhost:8081/api/chat/ask?question=test&filenames=ALL&minScore=0.10&maxResults=5" | python3 -m json.tool

# Step 5: Check logs for the failure point
docker compose logs app --tail 30 | grep -E "QUERY|RETRIEVAL|CRAG|FAILED|Error|Exception"
```
