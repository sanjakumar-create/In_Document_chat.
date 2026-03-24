
# 🧠 Local CRAG: Privacy-First AI Chatbot (Spring Boot + LangChain4j)

An enterprise-grade, 100% local AI chatbot built with **Java, Spring Boot, and LangChain4j**. This project implements **Corrective Retrieval-Augmented Generation (CRAG)** to allow users to upload complex documents (PDFs, Word, TXT) and ask an AI questions about them. 

Because the Large Language Model (Llama 3) and Vector Database (ChromaDB) run entirely locally, **zero sensitive data is ever sent to the cloud**, guaranteeing complete data privacy. Furthermore, the CRAG architecture includes an active evaluator step to guarantee **zero AI hallucinations**.

---

## ✨ Key Features

* **100% Local & Private:** No API keys required. No OpenAI data harvesting. Everything runs on your own hardware.
* **Corrective RAG (CRAG) Architecture:** Upgrades standard RAG by forcing the AI to "grade" retrieved facts before answering, completely eliminating hallucinations.
* **Universal Document Parsing:** Utilizes **Apache Tika** to ingest almost any file format natively (PDF, DOCX, TXT, CSV, etc.).
* **Conversational Memory:** Maintains a rolling context window of the last 10 chat interactions for natural, multi-part conversations.
* **Semantic Vector Search:** Uses mathematically driven Cosine Similarity to find concepts, not just keyword matches.

---

## 🏗️ Architecture Flow

The system operates in two distinct phases:

### Phase 1: Ingestion (Data Pipeline)
1. **Extract:** Apache Tika extracts raw text from the provided file.
2. **Chunk:** LangChain4j splits the text into bite-sized segments (300 characters, 60-character overlap) to preserve context while fitting into the DB.
3. **Embed:** Ollama (`nomic-embed-text`) translates these English chunks into dense mathematical vectors.
4. **Store:** The vectors are saved into a locally hosted ChromaDB container.

### Phase 2: Retrieval & Generation (CRAG Pipeline)
1. **Query:** The user asks a question via the REST API.
2. **Retrieve:** The system converts the question to a vector and asks ChromaDB for the top 5 most relevant chunks (minimum 70% match).
3. **Evaluate (The CRAG Bouncer):** Before answering, the system sends each chunk to Llama 3 and asks a strict YES/NO question: *"Does this chunk actually answer the user's question?"*
4. **Synthesize:** Rejected chunks are thrown away. Approved chunks are combined with the user's prompt to generate a final, highly accurate, cited response.

---

## 🛠️ Tech Stack

* **Backend Framework:** Java 21, Spring Boot 3.x/4.x
* **AI Orchestration:** LangChain4j
* **Local LLM Host:** Ollama
* **Generation Model:** Llama 3 (`llama3`)
* **Embedding Model:** Nomic Embed Text (`nomic-embed-text`)
* **Vector Database:** ChromaDB (Running via Docker)
* **Document Parser:** Apache Tika

---

## 🚀 Setup & Installation

### Prerequisites
You must have the following installed on your machine:
* [Java 21](https://jdk.java.net/21/) & Maven
* [Docker Desktop](https://www.docker.com/)
* [Ollama](https://ollama.com/)

### Step 1: Start the Local AI Models
Open your terminal and pull the required models into Ollama:
```bash
# Pull the generation model
ollama run llama3

# Pull the embedding model (used for math vectors)
ollama pull nomic-embed-text
```
*(Ensure Ollama is running in the background on port `11434`)*

### Step 2: Start the Vector Database
We use Docker to run a specific, highly stable version of ChromaDB (`0.4.24`) that is perfectly compatible with LangChain4j's v1 API endpoints.
```bash
docker run -d -p 8888:8000 --name local-chroma chromadb/chroma:0.4.24
```
*(Verify it is running by visiting `http://localhost:8888/api/v1/heartbeat` in your browser).*

### Step 3: Run the Spring Boot App
Clone this repository and start the application:
```bash
mvn spring-boot:run
```
The Tomcat server will start on `http://localhost:8080`.

---

## 🔌 API Documentation & Usage

You can test this application using Postman, cURL, or a web browser.

### 1. Ingest a Document
Uploads a document to the local vector database.
* **Endpoint:** `POST /api/documents/ingest`
* **Query Parameter:** `filePath` (The absolute path to your file)
* **Example (cURL):**
    ```bash
    curl -X POST "http://localhost:8080/api/documents/ingest?filePath=/Users/name/Downloads/Policy.pdf"
    ```
* **Expected Response:** `✅ Successfully ingested and vectorized: Policy.pdf`

### 2. Ask a Question
Chat with the AI about the document you just uploaded.
* **Endpoint:** `GET /api/chat/ask`
* **Query Parameter:** `question`
* **Example (cURL):**
    ```bash
    curl -X GET "http://localhost:8080/api/chat/ask?question=What are the standard components of a policy document?"
    ```
* **Expected JSON Response:**
    ```json
    {
      "answer": "Based on the verified document context, the standard components of a policy document include:\n\n1. A purpose statement outlining why the organization is issuing the policy.\n2. An applicability and scope statement describing who the policy affects.\n3. An effective date indicating when it comes into force.\n4. A responsibilities section.",
      "citations": [
        "While such formats differ in form, policy documents usually contain certain standard components including: A purpose statement, outlining why the organization is issuing the policy...",
        "An applicability and scope statement, describing who the policy affects..."
      ]
    }
    ```

---

## 💡 Lessons Learned (Infrastructure & Versioning)

A major hurdle overcome during this project was **Docker versioning and API deprecation**. 

Initially, spinning up the `chromadb/chroma:latest` container resulted in a `405 Method Not Allowed` error from Spring Boot. Upon debugging, I discovered that the bleeding-edge version of ChromaDB deprecated the `v1` API endpoints that `langchain4j-chroma:0.36.2` relies on to build collections. Instead of refactoring the Java library, I pinned the infrastructure to `chromadb/chroma:0.4.24`, perfectly matching the database version to the application's required API contract. This reinforced the importance of immutable infrastructure and explicit version pinning in enterprise environments.

---
*Built with ❤️ using Java, Spring Boot, and local open-source AI models.*
```
