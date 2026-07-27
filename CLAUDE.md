# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OnCallAgent — enterprise-grade intelligent Q&A and AIOps system using Spring Boot + AI Agent architecture.

Two core modules:
- **RAG Q&A**: Retrieval-Augmented Generation with Milvus vector DB + DashScope (Qwen models), multi-turn conversation, SSE streaming
- **AIOps Intelligent Operations**: Multi-Agent collaboration using Planner-Executor-Replanner architecture for alert analysis, log query, diagnosis, and report generation

## Build & Run Commands

```bash
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run

# Quick start (build + deploy all at once)
make init

# Start/stop Milvus via Docker
docker compose -f vector-database.yml up -d
docker compose -f vector-database.yml down

# Upload docs to vector DB (after service is running)
make upload

# Run utility: drop Milvus collection to force recreate
mvn exec:java -Dexec.mainClass="org.example.tool.DropCollection"
```

**Prerequisites**: Java 17, Docker (for Milvus), `DASHSCOPE_API_KEY` env var set.

## Architecture Overview

### Package Structure

```
org.example
├── Main.java                              # @SpringBootApplication entry point
├── controller/
│   ├── ChatController.java                # Unified API: /api/chat, /api/chat_stream, /api/ai_ops
│   ├── FileUploadController.java          # /api/upload — file upload → auto vector indexing
│   └── MilvusCheckController.java         # /milvus/health — DB health check
├── service/
│   ├── ChatService.java                   # ReactAgent creation, system prompt building, tool management
│   ├── AiOpsService.java                  # Multi-Agent orchestration (Planner → Executor → Replanner)
│   ├── RagService.java                    # RAG: vector search → LLM generation (streaming)
│   ├── DocumentChunkService.java          # Smart document chunking (by Markdown headings + paragraphs)
│   ├── VectorEmbeddingService.java        # DashScope Text Embedding API wrapper
│   ├── VectorSearchService.java           # Milvus similarity search (L2 distance)
│   └── VectorIndexService.java            # File ingestion: read → chunk → embed → store in Milvus
├── agent/tool/
│   ├── DateTimeTools.java                 # @Tool: getCurrentDateTime
│   ├── InternalDocsTools.java             # @Tool: queryInternalDocs (RAG over internal knowledge base)
│   ├── QueryMetricsTools.java             # @Tool: queryPrometheusAlerts (supports mock mode)
│   └── QueryLogsTools.java                # @Tool: queryLogs, getAvailableLogTopics (supports mock mode)
├── config/
│   ├── MilvusConfig.java                  # MilvusServiceClient @Bean lifecycle
│   ├── MilvusProperties.java              # milvus.* config properties
│   ├── DashScopeConfig.java               # OkHttp / RestClient timeout config
│   ├── DocumentChunkConfig.java           # document.chunk.* (max-size, overlap)
│   ├── FileUploadConfig.java              # file.upload.* (path, allowed-extensions)
│   ├── WebConfig.java                     # UTF-8 message converters
│   └── WebMvcConfig.java                  # CORS + static resource mapping
├── client/
│   └── MilvusClientFactory.java           # Milvus connect + collection auto-provisioning ("biz" collection)
├── constant/
│   └── MilvusConstants.java               # Collection name, vector dim (1024), field lengths
├── dto/
│   ├── DocumentChunk.java                 # Chunk model (content, position, title)
│   ├── FileUploadRes.java                 # Upload response DTO
│   └── AIOpsRequest.java                  # AIOps request placeholder
└── tool/
    └── DropCollection.java                # CLI utility to drop and recreate Milvus collection
```

### Two AI Pipelines

**1. RAG Q&A Pipeline** (`/api/chat`, `/api/chat_stream`)
```
User Question → VectorSearchService (Milvus) → Retrieve top-K docs
                                                 ↓
                                    Build context + prompt
                                                 ↓
                                    DashScope LLM (qwen3-max) → SSE stream response
```

**2. AIOps Multi-Agent Pipeline** (`/api/ai_ops`)
```
User Trigger → SupervisorAgent
                ├── PlannerAgent: analyze alerts, plan steps (decision: PLAN|EXECUTE|FINISH)
                ├── ExecutorAgent: execute planned step, gather evidence
                └── Replanner: evaluate executor feedback, re-plan if needed
                    ↻ loop until decision = FINISH
                    → Output formatted Markdown alert analysis report
```

### Agent Tools (Spring AI @Tool annotations)

| Tool | Function | Mock Support |
|------|----------|-------------|
| `getCurrentDateTime` | Current time in user timezone | — |
| `queryInternalDocs` | RAG search over internal knowledge base (Milvus) | — |
| `queryPrometheusAlerts` | Query active alerts from Prometheus | `prometheus.mock-enabled=true` |
| `queryLogs` | Query logs from CLS (Tencent Cloud) | `cls.mock-enabled=true` |
| `getAvailableLogTopics` | List available CLS log topics | `cls.mock-enabled=true` |

### Tool Registration Strategy

- **Method tools** (Java `@Tool` beans): DateTimeTools, InternalDocsTools, QueryMetricsTools, QueryLogsTools (optional — only registered when `cls.mock-enabled=true`)
- **MCP tools** (remote): Provided via Spring AI MCP client, configured in `application.yml` under `spring.ai.mcp.client`. The SSE-based MCP client connects to Tencent Cloud for real log queries. MCP tools are auto-registered through `ToolCallbackProvider` and merged into ReactAgent via `.tools(getToolCallbacks())`

### Key Configuration

| Config | Default | Description |
|--------|---------|-------------|
| `server.port` | 9900 | HTTP port |
| `milvus.host` | localhost | Vector DB host |
| `milvus.port` | 19530 | Vector DB port |
| `rag.top-k` | 3 | Number of docs retrieved per query |
| `rag.model` | qwen3-max | LLM for RAG |
| `dashscope.embedding.model` | text-embedding-v4 | Embedding model |
| `document.chunk.max-size` | 800 | Max chars per chunk |
| `document.chunk.overlap` | 100 | Overlap between chunks |
| `prometheus.mock-enabled` | false | Mock mode for Prometheus |
| `cls.mock-enabled` | false | Mock mode for CLS logs |

### Milvus Schema

Collection `biz` with 4 fields:
- `id` (VarChar, primary key, max 256 chars)
- `vector` (FloatVector, 1024-dim, IVF_FLAT index, L2 distance)
- `content` (VarChar, max 8192 chars)
- `metadata` (JSON — stores `_source`, `_extension`, `_file_name`, `chunkIndex`, `totalChunks`, `title`)

### Session Management

`ChatController` maintains in-memory `ConcurrentHashMap<String, SessionInfo>` sessions. Each session holds up to 6 message pairs (user + assistant) with thread-safe `ReentrantLock`. Sessions are not persisted — restart resets all state.

### Configuration Profiles / Modes

- **Mock mode**: `prometheus.mock-enabled: true` and `cls.mock-enabled: true` return pre-built sample data (3 alerts: HighCPUUsage, HighMemoryUsage, SlowResponse; correlated log entries). Used for development/demo without real infrastructure.
- **Production mode**: Connects to real Prometheus API (`prometheus.base-url`) and Tencent Cloud CLS via MCP SSE endpoint (`spring.ai.mcp.client.sse.connections.tencent-cls`).

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | Non-streaming conversation with ReactAgent |
| POST | `/api/chat_stream` | SSE streaming conversation with ReactAgent |
| POST | `/api/ai_ops` | SSE streaming multi-Agent alert analysis |
| POST | `/api/chat/clear` | Clear session history |
| GET | `/api/chat/session/{sessionId}` | Get session info |
| POST | `/api/upload` | Upload file + auto vector indexing |
| GET | `/milvus/health` | Milvus health check |

### aiops-docs

The `aiops-docs/` directory contains Markdown documents (CPU, memory, disk, service, and slow-response guides) that serve as the internal knowledge base. These are uploaded via `/api/upload` and indexed into Milvus for RAG retrieval.
