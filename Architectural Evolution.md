

# The Vault v3.0 | Architectural Evolution

## 🏗️ Architectural Shift: Monolith to Modular Monolith

In this version, the system underwent a massive structural refactor. Below is the breakdown of why and how this was done.

### 1. What was the previous structure called?
Previously, the app used a **Monolithic Service Architecture**.
* **The "God Class" Problem:** We had two main files (`ChatbotService.java` and `DocumentIngestionService.java`) that did everything.
* **Tight Coupling:** If you wanted to change how PDFs were parsed, you had to edit the same file that handled OCR and database saving. This is risky and makes the code hard to read.

### 2. What is this new structure called?
This is now a **Modular Monolith** using the **Orchestrator Pattern**.
* **Modular Monolith:** The code is still in one project, but it is strictly separated into "Modules" (Chat vs. Ingestion).
* **Orchestrator Pattern:** We have a "Boss" class (the Orchestrator) that doesn't do the hard work; it just manages a team of "Specialized Workers" (Services).

---

## 🚀 Why we changed it (The Benefits)

### 🧩 Single Responsibility Principle (SRP)
Each class now has **one job**.
- `PdfParserService` only reads PDFs.
- `VectorRetrievalService` only talks to the database.
- **Benefit:** If there is a bug in the PDF logic, you know exactly which 50 lines of code to check instead of searching through 500.

### ⚡ Parallel & Asynchronous Ready
Because the workers are separated, we can easily change them. For example, our `VectorRetrievalService` now uses a dedicated `ThreadPool` to search for multiple query variants at the same time, making the chat response significantly faster.

### 🛡️ Failure Isolation
In the old system, if the OCR engine crashed, the entire Chatbot service could hang. Now, the `IngestionOrchestrator` handles each step independently. If one parser fails, the system can fall back to the `GenericParser` without affecting the rest of the application.

### 🛣️ The Path to Microservices
This project is now "Microservice-Ready." If we wanted to move the heavy `Ingestion` logic to a different server (to save RAM), we could literally copy the `ingestion` folder into a new project and it would work immediately.

---

## 🛠️ Updated System Design

### **The Chat Domain**
- **Orchestrator:** `ChatOrchestrator` (The Conductor)
- **Workers:**
    - `QueryClassifier`: Uses math (centroids) to detect user intent.
    - `QueryExpansion`: Generates HyDE (Hypothetical) passages.
    - `VectorRetriever`: Multi-threaded ChromaDB searching.
    - `CragEvaluator`: Batch-grades chunks from 1–5.
    - `GroundingEvaluator`: Calculates the % of truth in the answer.
    - `ChatMemoryService`: Manages isolated browser tab sessions.

### **The Ingestion Domain**
- **Orchestrator:** `IngestionOrchestrator` (The Router)
- **Workers:**
    - `PdfParserService`: Handles PDFBox, Tabula Tables, and Tesseract OCR.
    - `DocxParserService`: Handles paragraph and table extraction for Word.
    - `GenericParserService`: Uses Apache Tika for everything else.
    - `VectorizationService`: Handles the 1000/250 character chunking and embedding.

---

## ⚠️ Developer Note: Vector DB Connection
Because the system is now highly optimized, it caches collection IDs for speed.
**If you run `make clean` or wipe ChromaDB:**
1. **Restart** the Spring Boot application (IntelliJ or Docker).
2. **Re-upload** your documents.
   This ensures the `VectorizationService` and `VectorRetriever` are synchronized with the fresh database UUIDs.

---

## 🏁 Conclusion
By moving to a **Modular Monolith**, we have turned a "Document Chatbot" into a "Document Intelligence Pipeline." The code is now cleaner, faster, and built to professional enterprise standards.

---
