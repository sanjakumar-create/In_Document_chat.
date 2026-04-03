# The Vault v3 — Architecture Diagrams (Mermaid)

> Paste any diagram into [mermaid.live](https://mermaid.live) or any Mermaid-compatible renderer (GitHub, Notion, Obsidian, VS Code preview).

---

## 1. System Overview — All Services and Connections

```mermaid
graph TB
    subgraph Browser["Browser (React SPA)"]
        UI["The Vault v3.0 UI<br/>index.html + Babel 7 + Tailwind"]
    end

    subgraph Docker["Docker Compose"]
        subgraph App["Spring Boot App :8081"]
            CC["ChatController<br/>GET /api/chat/ask"]
            IC["IngestionController<br/>POST /api/documents/upload"]
            SC["StatusController<br/>GET /api/status"]
            NAC["NodeAdminController<br/>GET /api/inference/nodes"]
            CS["ChatbotService<br/>(RAG Pipeline)"]
            DIS["DocumentIngestionService<br/>(PDF/DOCX/Tika)"]
            ISA["InferenceServiceAdapter"]
            DInf["DistributedInferenceService"]
            LB["LoadBalancer"]
            IR["InferenceRouter"]
            HC["HealthCheckService<br/>(every 10s)"]
            NR["NodeRegistry"]
        end
        subgraph Chroma["ChromaDB :8888→8000"]
            COLL["Collection:<br/>my_notebook_docs<br/>(cosine, 1024-dim)"]
        end
    end

    subgraph Host["Host Machine (GPU)"]
        OL["Ollama :11434<br/>gemma2 + mxbai-embed-large"]
    end

    subgraph LAN["Team LAN (Optional)"]
        N1["Laptop A<br/>Ollama + gemma2"]
        N2["Laptop B<br/>Ollama + gemma2"]
        N3["Laptop C<br/>Ollama + gemma2"]
    end

    UI -->|HTTP REST| CC
    UI -->|HTTP REST| IC
    UI -->|HTTP REST| SC

    CC --> CS
    IC --> DIS
    NAC --> NR
    NAC --> DInf

    CS -->|"resolveChatModel()"| ISA
    ISA --> DInf
    DInf --> LB
    DInf --> IR
    DInf --> NR
    IR -->|"HTTP POST /api/chat"| N1
    IR -->|"HTTP POST /api/chat"| N2
    IR -->|"HTTP POST /api/chat"| N3
    ISA -.->|"fallback if no<br/>distributed nodes"| OL

    CS -->|"embed queries"| OL
    DIS -->|"embed chunks"| OL
    CS -->|"search / store"| COLL
    DIS -->|"store vectors"| COLL

    HC -->|"ping /api/tags"| N1
    HC -->|"ping /api/tags"| N2
    HC -->|"ping /api/tags"| N3
    HC --> NR

    style Browser fill:#1e293b,stroke:#3b82f6,color:#e2e8f0
    style App fill:#1e293b,stroke:#10b981,color:#e2e8f0
    style Chroma fill:#1e293b,stroke:#f59e0b,color:#e2e8f0
    style Host fill:#1e293b,stroke:#8b5cf6,color:#e2e8f0
    style LAN fill:#1e293b,stroke:#ef4444,color:#e2e8f0
```

---

## 2. RAG Query Pipeline — Full 6-Step Flow

```mermaid
flowchart TD
    Q["User Question<br/>'What is a list in Python?'"]

    subgraph Step1["Step 1: Query Classification (0 LLM calls)"]
        CL{{"classifyQuery()"}}
        SYN["SYNTHESIS<br/>keywords: summarize,<br/>overview, key points"]
        CODE["CODE_SEARCH<br/>≤ 5 words"]
        FACT["FACTOID<br/>everything else"]
    end

    subgraph Step2["Step 2: Query Expansion — HyDE (0-1 LLM calls)"]
        HYS["Skip HyDE<br/>Use raw question +<br/>broad anchor strings"]
        HYC["Code HyDE<br/>Generate code snippet<br/>via LLM (1 call)"]
        HYF["Prose HyDE<br/>Generate passage<br/>via LLM (1 call)"]
        QV["QueryVariants<br/>(text, isDocumentPassage)"]
    end

    subgraph Step3["Step 3: Parallel Retrieval (0 LLM calls)"]
        EMB["Embed variants<br/>mxbai-embed-large"]
        PASS["HyDE passages:<br/>NO prefix (passage space)"]
        QUERY["Raw questions:<br/>WITH prefix (query space)"]
        SEARCH["ChromaDB cosine search<br/>+ metadata filter<br/>(source_file)"]
        DEDUP["Deduplicate by chunk text"]
    end

    subgraph Step4["Step 4: Batch CRAG (1 LLM call)"]
        SCORE["Gemma 2 scores all chunks<br/>1-5 scale in single call"]
        RUBRIC{"Adaptive rubric<br/>per query type"}
        FILTER["Filter: keep ≥ threshold<br/>FACTOID: ≥4<br/>SYNTHESIS/CODE: ≥3"]
        SORT["Sort by score DESC"]
    end

    subgraph Step5["Step 5: Grounded Synthesis (1 LLM call)"]
        SYNTH["Gemma 2 generates answer<br/>with inline 4-8 word quotes<br/>from verified passages"]
    end

    subgraph Step6["Step 6: Grounding Score (0 LLM calls)"]
        GS["Token-overlap metric<br/>answer words ∩ context words"]
        BADGE["≥75% Green | 50-74% Yellow | <50% Red"]
    end

    RESP["Response:<br/>{answer, citations[], groundingScore, chunkCount}"]

    Q --> CL
    CL -->|"'summarize this'"| SYN
    CL -->|"'what is list' (3 words)"| CODE
    CL -->|"'How does exception handling...'"| FACT

    SYN --> HYS
    CODE --> HYC
    FACT --> HYF

    HYS --> QV
    HYC --> QV
    HYF --> QV

    QV --> EMB
    EMB --> PASS
    EMB --> QUERY
    PASS --> SEARCH
    QUERY --> SEARCH
    SEARCH --> DEDUP

    DEDUP -->|"10 candidate chunks"| SCORE
    SCORE --> RUBRIC
    RUBRIC --> FILTER
    FILTER --> SORT

    SORT -->|"5-8 verified chunks"| SYNTH

    SYNTH --> GS
    GS --> BADGE
    BADGE --> RESP

    style Step1 fill:#0f172a,stroke:#6366f1,color:#e2e8f0
    style Step2 fill:#0f172a,stroke:#8b5cf6,color:#e2e8f0
    style Step3 fill:#0f172a,stroke:#3b82f6,color:#e2e8f0
    style Step4 fill:#0f172a,stroke:#f59e0b,color:#e2e8f0
    style Step5 fill:#0f172a,stroke:#10b981,color:#e2e8f0
    style Step6 fill:#0f172a,stroke:#ef4444,color:#e2e8f0
```

---

## 3. Document Ingestion Pipeline

```mermaid
flowchart TD
    UPLOAD["File Upload<br/>(PDF / DOCX / TXT / CSV / XLSX)"]

    ROUTE{{"Route by<br/>file extension"}}

    subgraph PDF["PDF Pipeline (PDFBox + tabula + Tesseract)"]
        direction TB
        LOAD["PDDocument.load()"]
        TABINIT["Create shared<br/>ObjectExtractor<br/>(once per PDF)"]
        LOOP["For each page (1..N)"]

        subgraph PerPage["Per-Page Processing"]
            TEXT["PDFTextStripper<br/>extract text<br/>(sortByPosition=true)"]
            TOC{"TOC page?<br/>>40% lines match<br/>'text .... NNN'"}
            TABLE["tabula extract<br/>tables → Markdown"]
            LEN{"text.length()<br/>< 60 chars?"}
            OCR["Tesseract OCR<br/>300 DPI render"]
            OCRLEN{"OCR text<br/>< 60 chars?"}
            SKIP1["Skip page<br/>(blank)"]
            SKIP2["Skip page<br/>(TOC)"]
            INGEST_TBL["Ingest table chunk<br/>content_type=table"]
            INGEST_TXT["Ingest text chunk<br/>content_type=text"]
            INGEST_OCR["Ingest OCR chunk<br/>content_type=ocr"]
        end

        CATCH["Per-page try/catch<br/>skip on error,<br/>continue to next page"]
    end

    subgraph DOCX["DOCX Pipeline (Apache POI)"]
        direction TB
        POILOAD["XWPFDocument"]
        BODY["Iterate body elements<br/>in reading order"]
        PARA["Paragraphs → prose buffer"]
        DTABLE["XWPFTable → Markdown"]
        FLUSH["Flush prose buffer<br/>before each table"]
    end

    subgraph GENERIC["Generic Pipeline (Apache Tika)"]
        direction TB
        TIKA["ApacheTikaDocumentParser<br/>flat text extraction"]
    end

    subgraph Chunk["Chunking + Embedding + Storage"]
        SPLIT["DocumentSplitters.recursive<br/>(1000 chars, 250 overlap)"]
        EMBED["mxbai-embed-large<br/>→ 1024-dim vector"]
        STORE["ChromaDB store<br/>vector + text + metadata"]
    end

    UPLOAD --> ROUTE
    ROUTE -->|".pdf"| LOAD
    LOAD --> TABINIT
    TABINIT --> LOOP
    LOOP --> TEXT
    TEXT --> TOC
    TOC -->|"Yes"| SKIP2
    TOC -->|"No"| TABLE
    TABLE --> INGEST_TBL
    TABLE --> LEN
    LEN -->|"≥ 60"| INGEST_TXT
    LEN -->|"< 60"| OCR
    OCR --> OCRLEN
    OCRLEN -->|"≥ 60"| INGEST_OCR
    OCRLEN -->|"< 60"| SKIP1
    LOOP --> CATCH

    ROUTE -->|".docx"| POILOAD
    POILOAD --> BODY
    BODY --> PARA
    BODY --> DTABLE
    PARA --> FLUSH
    DTABLE --> FLUSH

    ROUTE -->|"other"| TIKA

    INGEST_TBL --> SPLIT
    INGEST_TXT --> SPLIT
    INGEST_OCR --> SPLIT
    FLUSH --> SPLIT
    TIKA --> SPLIT

    SPLIT --> EMBED
    EMBED --> STORE

    style PDF fill:#0f172a,stroke:#ef4444,color:#e2e8f0
    style DOCX fill:#0f172a,stroke:#3b82f6,color:#e2e8f0
    style GENERIC fill:#0f172a,stroke:#f59e0b,color:#e2e8f0
    style Chunk fill:#0f172a,stroke:#10b981,color:#e2e8f0
```

---

## 4. Distributed Inference — Load Balancing and Failover

```mermaid
flowchart TD
    CS["ChatbotService<br/>resolveChatModel()"]
    ISA["InferenceServiceAdapter<br/>resolveModel()"]
    DIS["DistributedInferenceService"]

    CHECK{"distributed<br/>enabled?"}
    HEALTHY{"any healthy<br/>node?"}

    LB["LoadBalancer.select()"]

    subgraph Selection["Node Selection Algorithm"]
        FILTER["Filter → healthy nodes only"]
        SLOTS{"nodes with<br/>free slots?"}
        LEAST["Pick node with<br/>fewest active requests"]
        RR["Round-robin<br/>among healthy nodes"]
    end

    NODE["Selected Node"]
    SEM{"tryAcquire()<br/>semaphore"}
    SEND["HTTP POST<br/>{baseUrl}/api/chat<br/>timeout: 3 min"]

    SUCCESS{"HTTP 200?"}
    MARK_OK["markHealthy()<br/>reset failures"]
    FAIL["recordFailure()"]

    RETRY{"retry on<br/>fallback node?"}
    FALLBACK["Select alternative<br/>healthy node"]
    SEND2["Retry request<br/>on fallback node"]

    LOCAL["Fall back to<br/>local Ollama LLM"]

    RETURN["Return response<br/>to ChatbotService"]

    CS --> ISA
    ISA --> DIS
    DIS --> CHECK
    CHECK -->|"No"| LOCAL
    CHECK -->|"Yes"| HEALTHY
    HEALTHY -->|"No"| LOCAL
    HEALTHY -->|"Yes"| LB

    LB --> FILTER
    FILTER --> SLOTS
    SLOTS -->|"Yes"| LEAST
    SLOTS -->|"No"| RR
    LEAST --> NODE
    RR --> NODE

    NODE --> SEM
    SEM -->|"acquired"| SEND
    SEM -->|"rejected (at capacity)"| RETRY

    SEND --> SUCCESS
    SUCCESS -->|"Yes"| MARK_OK
    SUCCESS -->|"No"| FAIL

    MARK_OK --> RETURN
    FAIL --> RETRY

    RETRY -->|"Yes"| FALLBACK
    RETRY -->|"No"| LOCAL
    FALLBACK --> SEND2
    SEND2 --> RETURN

    LOCAL --> RETURN

    style Selection fill:#0f172a,stroke:#8b5cf6,color:#e2e8f0
```

---

## 5. Health Check Lifecycle

```mermaid
sequenceDiagram
    participant HC as HealthCheckService<br/>(every 10s)
    participant NR as NodeRegistry
    participant N1 as Node: laptop-dev
    participant N2 as Node: laptop-qa

    loop Every 10 seconds
        HC->>NR: getAllNodes()
        NR-->>HC: [laptop-dev, laptop-qa]

        HC->>N1: GET /api/tags (3s timeout)
        N1-->>HC: 200 OK {"models":[...]}
        Note over HC,N1: markHealthy() → failureCount=0

        HC->>N2: GET /api/tags (3s timeout)
        N2--xHC: TIMEOUT (3s)
        Note over HC,N2: recordFailure() → failureCount=1

        HC->>N2: GET /api/tags (next cycle)
        N2--xHC: TIMEOUT
        Note over HC,N2: recordFailure() → failureCount=2

        HC->>N2: GET /api/tags (next cycle)
        N2--xHC: TIMEOUT
        Note over HC,N2: recordFailure() → failureCount=3<br/>❌ MARKED UNHEALTHY
        Note over HC: Log: "Distributed node DOWN: laptop-qa"
    end

    Note over N2: laptop-qa comes back online

    loop Next health check
        HC->>N2: GET /api/tags
        N2-->>HC: 200 OK
        Note over HC,N2: markHealthy() → failureCount=0<br/>✅ MARKED HEALTHY
        Note over HC: Log: "Distributed node recovered: laptop-qa"
    end
```

---

## 6. Full Request Lifecycle — Sequence Diagram

```mermaid
sequenceDiagram
    actor User
    participant UI as Browser<br/>(React)
    participant CC as ChatController
    participant CS as ChatbotService
    participant ISA as InferenceService<br/>Adapter
    participant LLM as Ollama / Distributed<br/>(Gemma 2)
    participant EMB as Ollama<br/>(mxbai-embed-large)
    participant DB as ChromaDB

    User->>UI: "What is a list in Python?"
    UI->>CC: GET /api/chat/ask?question=...&filenames=file.pdf

    CC->>CS: askAdvancedQuestion(question, 0.30, 10, filenames, sessionId)

    Note over CS: Step 1: classifyQuery() → FACTOID (6 words)
    Note over CS: Step 2: effectiveMinScore=0.30, effectiveCrag=4

    CS->>ISA: resolveChatModel()
    ISA-->>CS: distributed model (or local fallback)

    Note over CS: Step 3: HyDE — generate prose passage
    CS->>LLM: generate("Write a passage that answers: What is a list...")
    LLM-->>CS: "A list in Python is an ordered mutable collection..."

    Note over CS: Step 4: Embed HyDE passage (passage space, no prefix)
    CS->>EMB: embed("A list in Python is an ordered...")
    EMB-->>CS: [0.12, -0.45, 0.78, ...] (1024-dim)

    Note over CS: Step 5: ChromaDB search
    CS->>DB: search(vector, minScore=0.30, filter=source_file, max=10)
    DB-->>CS: 10 matching chunks with metadata

    Note over CS: Step 6: Batch CRAG — score all chunks
    CS->>ISA: resolveChatModel()
    CS->>LLM: generate("Rate each passage 1-5: [1] chunk1 [2] chunk2 ...")
    LLM-->>CS: [5, 4, 2, 4, 1, 3, 5, 2, 4, 3]
    Note over CS: Filter ≥ 4: keep 5 chunks, sort desc

    Note over CS: Step 7: Grounded synthesis
    CS->>ISA: resolveChatModel()
    CS->>LLM: generate("Answer using ONLY these passages, quote 4-8 words...")
    LLM-->>CS: "A list in Python is an 'ordered, mutable collection'..."

    Note over CS: Step 8: Grounding score = 0.63 (token overlap)

    CS-->>CC: ChatResponse{answer, citations, 0.63, 5}
    CC-->>UI: JSON response
    UI-->>User: Answer + citations + green/yellow/red badge
```

---

## 7. Spring Bean Dependency Graph

```mermaid
graph TD
    subgraph Controllers
        CC["ChatController"]
        IC["IngestionController"]
        SC["StatusController"]
        NAC["NodeAdminController"]
    end

    subgraph Services
        CS["ChatbotService"]
        DIS_SVC["DocumentIngestionService"]
    end

    subgraph Distributed["Distributed Inference"]
        ISA["InferenceServiceAdapter"]
        DINF["DistributedInferenceService"]
        IR["InferenceRouter"]
        LB_BEAN["LoadBalancer"]
        NR_BEAN["NodeRegistry"]
        HC_BEAN["HealthCheckService"]
    end

    subgraph Beans["Spring Beans"]
        CLM["ChatLanguageModel<br/>(Ollama auto-config)"]
        EM["EmbeddingModel<br/>(Ollama auto-config)"]
        ES["EmbeddingStore&lt;TextSegment&gt;<br/>(VectorStoreConfig)"]
    end

    subgraph Config["Configuration"]
        VSC["VectorStoreConfig<br/>→ ChromaEmbeddingStore"]
        CORS["CorsConfig<br/>→ WebMvcConfigurer"]
    end

    CC --> CS
    IC --> DIS_SVC
    NAC --> NR_BEAN
    NAC --> DINF

    CS --> CLM
    CS --> ISA
    CS --> EM
    CS --> ES

    DIS_SVC --> EM
    DIS_SVC --> ES

    ISA --> DINF
    DINF --> NR_BEAN
    DINF --> LB_BEAN
    DINF --> IR
    IR --> LB_BEAN
    HC_BEAN --> NR_BEAN

    VSC --> ES

    style Controllers fill:#1e3a5f,stroke:#3b82f6,color:#e2e8f0
    style Services fill:#1e3a5f,stroke:#10b981,color:#e2e8f0
    style Distributed fill:#1e3a5f,stroke:#8b5cf6,color:#e2e8f0
    style Beans fill:#1e3a5f,stroke:#f59e0b,color:#e2e8f0
    style Config fill:#1e3a5f,stroke:#6b7280,color:#e2e8f0
```

---

## 8. Embedding Space — Query vs Passage

```mermaid
graph LR
    subgraph QuerySpace["Query Space (with instruction prefix)"]
        RQ["Raw question:<br/>'Represent this sentence for<br/>searching relevant passages:<br/>What is a list in Python?'"]
        MQ["Multi-query variants"]
        SA["Synthesis anchors:<br/>'contents topics summary'"]
    end

    subgraph PassageSpace["Passage Space (no prefix)"]
        HYDE["HyDE passage:<br/>'A list in Python is an<br/>ordered mutable collection...'"]
        DOC1["Stored chunk 1:<br/>'my_list = [1, 2, 3]<br/>my_list.append(4)'"]
        DOC2["Stored chunk 2:<br/>'Lists are ordered, mutable<br/>sequences in Python...'"]
        DOC3["Stored chunk 3:<br/>'fruits = [apple, banana]<br/>fruits.sort()'"]
    end

    RQ -.->|"cross-space<br/>cosine similarity"| DOC1
    RQ -.->|"cross-space<br/>cosine similarity"| DOC2
    HYDE -.->|"same-space<br/>HIGH similarity"| DOC2
    HYDE -.->|"same-space<br/>HIGH similarity"| DOC1

    style QuerySpace fill:#1e293b,stroke:#3b82f6,color:#e2e8f0
    style PassageSpace fill:#1e293b,stroke:#10b981,color:#e2e8f0
```

> **Key insight:** HyDE passages are embedded in **passage space** (same as stored documents) so they have high similarity to real chunks. Raw questions are embedded in **query space** with the instruction prefix — the model bridges the two spaces at search time.

---

## How to Render These Diagrams

**Option 1 — GitHub:** Push this file to GitHub. GitHub renders Mermaid natively in markdown.

**Option 2 — VS Code:** Install the "Markdown Preview Mermaid Support" extension.

**Option 3 — Online:** Copy any code block to [mermaid.live](https://mermaid.live) for instant rendering + PNG/SVG export.

**Option 4 — Notion/Obsidian:** Both support Mermaid in code blocks natively.
