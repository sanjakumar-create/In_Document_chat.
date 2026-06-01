
# 🚀 Practical Task: Adding JDBC Persistence to The Vault

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![H2 Database](https://img.shields.io/badge/H2_Database-Lightweight-blue?style=for-the-badge&logo=sqlite&logoColor=white)

> **Context & Motivation:** > In the initial phases of this Retrieval-Augmented Generation (RAG) capstone project, the Large Language Model (LLM) relied on an in-memory `ConcurrentHashMap` to remember conversation history. While fast, this meant that every time the application restarted, crashed, or updated, all user conversations were permanently lost. 
> 
> This document outlines the implementation details for transitioning the system's session memory from volatile RAM to a **persistent relational database using JDBC**, making the application production-ready and fault-tolerant.

## 📑 Table of Contents
1. [Task Objectives](#1-task-objectives)
2. [Technical Stack Selection](#2-technical-stack-selection)
3. [Deep Dive: Implementation Details](#3-deep-dive-implementation-details)
4. [Verification & Testing Strategy](#4-verification--testing-strategy)
5. [Architectural & Business Impact](#5-architectural--business-impact)

---

## 1. Task Objectives
The following milestones were achieved during this refactoring process:

- [x] **JDBC Integration:** Safely wire Spring's `JdbcTemplate` and `DataSource` into the existing Spring Boot application.
- [x] **Persistent Chat Memory:** Implement the LangChain4j `ChatMemoryStore` interface to push and pull message histories from a real database.
- [x] **State Survivability:** Guarantee that LLM context, system prompts, and user queries survive full application reboots.
- [x] **Automated Schema Management:** Ensure the database tables and indexes are automatically generated upon application startup.

---

## 2. Technical Stack Selection
To keep the application lightweight while learning core database concepts, we chose the following tools:

* **Database (H2):** We opted for H2 in "file-based" mode. It behaves exactly like a heavy database (like PostgreSQL or MySQL) but writes to a simple local file (`./data/vaultdb`). This avoids the need for complex Docker setups during local development but allows for an easy swap to PostgreSQL later.
* **Persistence (Spring JDBC):** Instead of using a heavy ORM like Hibernate/JPA, we used `spring-boot-starter-jdbc`. This gives us raw, precise control over the SQL queries being executed, which is highly beneficial for understanding exactly how data moves between Java and the database.
* **AI Integration:** LangChain4j framework.

---

## 3. Deep Dive: Implementation Details

### A. Dependency Management (`pom.xml`)
To let our Spring Boot app talk to a database, we introduced two crucial dependencies. 

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>

```

### B. Relational Schema Design (`schema.sql`)

Instead of a Java Map, our conversations are now stored in a structured SQL table. We created a file at `src/main/resources/schema.sql` which Spring Boot automatically executes on startup.

```sql
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, -- Unique ID for every single message
    session_id VARCHAR(255) NOT NULL,     -- The UUID that ties messages to a specific user's browser tab
    role VARCHAR(50) NOT NULL,            -- Identifies who is speaking: 'SYSTEM', 'USER', or 'AI'
    content TEXT NOT NULL,                -- The actual text of the message or prompt
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP -- Ensures chronological ordering
);

-- Crucial for Performance: Because we constantly ask the database for "all messages for session X", 
-- an index prevents the database from having to scan every row in the table.
CREATE INDEX IF NOT EXISTS idx_session_id ON chat_messages(session_id);

```

### C. Application Properties (`application.properties`)

We explicitly instructed Spring Boot on how to connect to the database.

```properties
# 1. Database Connection URL. Note the 'file:./data/vaultdb'. This creates a folder named 'data' in the project root.
# DB_CLOSE_ON_EXIT=FALSE ensures the database file doesn't get corrupted or wiped when the app stops.
spring.datasource.url=jdbc:h2:file:./data/vaultdb;DB_CLOSE_ON_EXIT=FALSE

# 2. H2 Driver and Credentials (default H2 settings)
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# 3. Tells Spring to look for 'schema.sql' and execute it every time the app starts.
spring.sql.init.mode=always

```

### D. The Brains: Service Refactoring (`JdbcChatMemoryStore.java`)

This was the most critical part of the task. LangChain4j requires a `ChatMemoryStore` implementation to know where to save memory. We created a class that implements this interface using `JdbcTemplate`.

**How the logic works:**

1. **`getMessages(Object memoryId)`:** When the user sends a new message, LangChain4j calls this method. We run `SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC`. We then map the resulting SQL rows back into LangChain4j `UserMessage` and `AiMessage` Java objects so the LLM has context.
2. **`updateMessages(Object memoryId, List<ChatMessage> messages)`:** After the LLM generates a response, LangChain4j calls this method to save the updated conversation. To prevent duplicate data and maintain the exact sliding window of memory, our logic deletes the old rows for that session and performs a `batchUpdate` (batch insert) of the new list of messages.
3. **`deleteMessages(Object memoryId)`:** If a user clicks "Clear Chat", this triggers a simple `DELETE FROM chat_messages WHERE session_id = ?`.

---

## 4. Verification & Testing Strategy

To ensure the implementation is robust, the following testing protocols were executed:

### 🧪 End-to-End Statefulness Test

1. Compile and run the application (`make run`).
2. Interact with the AI: "Hi, my name is Alex and my favorite color is Blue."
3. Observe the AI's response.
4. **Kill the server completely** (Force quit / `Ctrl+C`).
5. Restart the server.
6. Return to the same browser session and ask: "What is my name and favorite color?"
7. **Expected & Actual Result:** The AI successfully replies "Your name is Alex and your favorite color is Blue", proving that data is safely resting on the hard drive, not in RAM.

### 🔍 Direct Database Audit (H2 Console)

1. Navigate to `http://localhost:8080/h2-console` in the browser.
2. Login using the JDBC URL: `jdbc:h2:file:./data/vaultdb` and username `sa`.
3. Execute: `SELECT * FROM chat_messages;`
4. **Expected & Actual Result:** The database correctly reflects the UI. For every interaction, there is a `USER` row immediately followed by an `AI` row, securely linked by the same `session_id`.

---

## 5. Architectural & Business Impact

Transitioning to JDBC fundamentally upgrades the application from a "toy" prototype to a production-ready system:

* 🛡️ **Fault Tolerance:** Server crashes, power outages, and routine CI/CD code deployments will no longer frustrate users by wiping out their conversation history.
* 📈 **Stateless Scalability:** Because the application memory is now externalized to a database, we can safely spin up 5 or 10 instances of this Spring Boot app behind a Load Balancer. No matter which server handles the user's HTTP request, it can simply fetch the context from the shared database.
* 📊 **Security & Auditing:** Since every prompt and response is logged in a relational table, administrators can query the database to audit LLM performance, monitor for prompt-injection attacks, or analyze user behavior.

```

```
