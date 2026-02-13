# Alfresco Content Lake

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.9+-red.svg)](https://maven.apache.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue.svg)](https://docs.docker.com/compose/)
[![Status](https://img.shields.io/badge/Status-PoC-yellow.svg)]()

**AI-powered semantic search and RAG for Alfresco using hxpr Content Lake**

[Features](#features) • [Quick Start](#quick-start) • [Architecture](#architecture) • [Authentication](#authentication) • [API Usage](#api-usage) • [Configuration](#configuration)

## Overview

Proof of Concept for AI-powered semantic search and Retrieval-Augmented Generation (RAG) on Alfresco Content Services.

Leverages **hxpr** as a Content Lake to enable high-quality AI search while:

* Keeping Alfresco as the source of truth
* Enforcing server-side permissions via ACLs
* Supporting on-premises AI execution
* Minimizing data duplication

## Features

- Two-Phase Sync Pipeline: Fast metadata ingestion + async content processing
- Semantic Search: Vector embeddings with permission-aware kNN search
- RAG: LLM-powered question answering grounded in Alfresco document content
- Permission-Aware: Server-side ACL enforcement via hxpr
- Local AI: On-premises LLM and embedding models using Spring AI
- REST API: Generic connector using Alfresco REST APIs
- Secured Endpoints: Alfresco authentication (username/password or tickets)

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  Phase 1: Fast Metadata Ingestion                        │
│  Alfresco → Discovery → Metadata → hxpr (PENDING)        │
└──────────────────────────────────────────────────────────┘
                         ↓
              Transformation Queue
                         ↓
┌──────────────────────────────────────────────────────────┐
│  Phase 2: Async Content Processing                       │
│  Transform → Chunk → Embed → Update (INDEXED)            │
└──────────────────────────────────────────────────────────┘
                         ↓
┌──────────────────────────────────────────────────────────┐
│  Phase 3: RAG (Retrieval-Augmented Generation)           │
│  Query → Embed → Search → Augment → LLM → Answer         │
└──────────────────────────────────────────────────────────┘
```

### Modules

| Module | Port | Description |
|--------|------|-------------|
| `content-lake-common` | — | Shared clients (hxpr, Alfresco), embedding service, chunking |
| `batch-ingester` | 9090 | Document discovery, sync, transformation, and embedding ingestion |
| `rag-service` | 9091 | Semantic search and RAG question answering |

## Quick Start

### Prerequisites

- Java 21+ and Maven 3.9+
- Docker and Docker Compose
- Alfresco Content Services 25.x+
  - Alfresco Transform Service (for text extraction)
- hxpr Content Lake (with OAuth2 IDP)
- Docker Model Runner (for embeddings and LLM)

### Installation

```bash
# Clone repository
git clone https://github.com/aborroy/alfresco-content-lake.git
cd alfresco-content-lake

# Build all modules
mvn clean package

# Configure (see Environment Variables below)
export ALFRESCO_URL=http://localhost:8080
export ALFRESCO_INTERNAL_USERNAME=admin
export ALFRESCO_INTERNAL_PASSWORD=admin
# ... (see full configuration below)

# Run ingestion
java -jar batch-ingester/target/batch-ingester-1.0.0-SNAPSHOT.jar

# Run RAG service
java -jar rag-service/target/rag-service-1.0.0-SNAPSHOT.jar

# Or with Docker Compose (both services)
docker-compose up
```

### Environment Variables

```bash
# Alfresco (Internal Service Account)
export ALFRESCO_URL=http://localhost:8080
export ALFRESCO_INTERNAL_USERNAME=admin
export ALFRESCO_INTERNAL_PASSWORD=admin

# hxpr Content Lake
export HXPR_URL=http://localhost:8080
export HXPR_REPOSITORY_ID=default
export HXPR_IDP_TOKEN_URL=http://localhost:5002/idp/connect/token
export HXPR_IDP_CLIENT_ID=nuxeo-client
export HXPR_IDP_CLIENT_SECRET=secret
export HXPR_IDP_USERNAME=testuser
export HXPR_IDP_PASSWORD=password

# Transform Service (batch-ingester only)
export TRANSFORM_URL=http://localhost:10090
export TRANSFORM_ENABLED=true

# AI/Embeddings (both services)
export MODEL_RUNNER_URL=http://localhost:12434/engines/llama.cpp/v1
export EMBEDDING_MODEL=ai/mxbai-embed-large

# LLM (rag-service only)
export LLM_MODEL=ai/gpt-oss
export LLM_TEMPERATURE=0.3
export LLM_MAX_TOKENS=1024

# RAG defaults (rag-service only)
export RAG_DEFAULT_TOP_K=5
export RAG_DEFAULT_MIN_SCORE=0.5
export RAG_MAX_CONTEXT_LENGTH=12000

# Performance (batch-ingester only)
export TRANSFORM_WORKERS=4
export EMBEDDING_CHUNK_SIZE=900
export EMBEDDING_CHUNK_OVERLAP=120
```

## Authentication

All REST API endpoints (`/api/**`) on both services require authentication validated against Alfresco.

### Supported Methods

| Method | Example |
|--------|---------|
| **Basic Auth** | `curl -u admin:password http://localhost:9090/api/sync/status` |
| **Ticket (query)** | `curl "http://localhost:9090/api/sync/status?alf_ticket=TICKET_xxx"` |
| **Ticket (header)** | `curl -H "Authorization: Basic BASE64(TICKET_xxx)" ...` |

**Note:** Bearer token authentication (OAuth2/OIDC with Keycloak) is not yet supported.

### Quick Example

```bash
# Authenticate and start sync
curl -X POST http://localhost:9090/api/sync/configured \
  -u admin:admin

# Or use Alfresco ticket
TICKET=$(curl -X POST http://localhost:8080/alfresco/api/-default-/public/authentication/versions/1/tickets \
  -H "Content-Type: application/json" \
  -d '{"userId":"admin","password":"admin"}' | jq -r '.entry.id')

curl -X POST "http://localhost:9090/api/sync/configured?alf_ticket=$TICKET"
```

## API Usage

### Batch Ingester (port 9090)

#### Start Synchronization

```bash
# Sync configured folders
curl -X POST http://localhost:9090/api/sync/configured -u admin:admin

# Sync specific folder
curl -X POST http://localhost:9090/api/sync/batch \
  -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{"folders": ["node-id"], "recursive": true, "types": ["cm:content"]}'
```

#### Monitor Progress

```bash
# Overall status
curl http://localhost:9090/api/sync/status -u admin:admin

# Job-specific status
curl http://localhost:9090/api/sync/status/{jobId} -u admin:admin
```

### RAG Service (port 9091)

#### RAG Prompt

Ask a question and get an LLM-generated answer grounded in your Alfresco documents:

```bash
curl -X POST http://localhost:9091/api/rag/prompt -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{ "question": "What are the key findings in the Q4 report?" }'
```

With options:

```bash
curl -X POST http://localhost:9091/api/rag/prompt -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Summarize the budget proposal",
    "topK": 10,
    "minScore": 0.6,
    "includeContext": true
  }'
```

Response:

```json
{
  "answer": "The Q4 report highlights a 12% revenue increase...",
  "question": "What are the key findings in the Q4 report?",
  "model": "ai/gpt-oss",
  "searchTimeMs": 245,
  "generationTimeMs": 1830,
  "totalTimeMs": 2075,
  "sourcesUsed": 3,
  "sources": [
    {
      "documentId": "abc-123",
      "nodeId": "e4f5a6b7-...",
      "name": "Q4-Financial-Report.pdf",
      "path": "/Company Home/Reports/Q4-Financial-Report.pdf",
      "chunkText": "Revenue for Q4 increased by 12%...",
      "score": 0.87
    }
  ]
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `question` | String | *required* | Natural-language question |
| `topK` | int | 5 | Number of chunks to retrieve for context |
| `minScore` | double | 0.5 | Minimum similarity threshold |
| `filter` | String | — | Additional HXQL filter |
| `systemPrompt` | String | — | Override the default LLM system prompt |
| `includeContext` | boolean | false | Include retrieved chunks in response |

#### Semantic Search

Search directly against the embedded chunks without LLM generation:

```bash
curl -X POST http://localhost:9091/api/search/semantic -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{ "query": "a girl falls in a crater", "topK": 5, "minScore": 0.6 }'
```

Semantic search applies a minimum similarity score to suppress low-quality vector matches when no strong semantic relation exists.

* Default value: `0.5`
* Applied server-side after vector retrieval
* Can be overridden per request

### Health Checks

```bash
# Batch ingester (no auth required)
curl http://localhost:9090/actuator/health

# RAG service (no auth required)
curl http://localhost:9091/actuator/health

# RAG service detailed health (auth required)
curl http://localhost:9091/api/rag/health -u admin:admin
```

## Configuration

### Ingestion

Edit `batch-ingester/src/main/resources/application.yml`:

```yaml
ingestion:
  sources:
    - folder: your-folder-node-id
      recursive: true
      types: [cm:content]
      mime-types: []  # Empty = all types
  exclude:
    paths: ["*/surf-config/*", "*/thumbnails/*"]
    aspects: [cm:workingcopy]
```

### RAG

Edit `rag-service/src/main/resources/application.yml`:

```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: ${LLM_MODEL:ai/gpt-oss}
          temperature: ${LLM_TEMPERATURE:0.3}
          maxTokens: ${LLM_MAX_TOKENS:1024}

rag:
  default-top-k: 5
  default-min-score: 0.5
  max-context-length: 12000
  default-system-prompt: >
    You are a document assistant that answers questions based strictly on
    the provided context.

    RULES:
    1. Use ONLY information from the DOCUMENT CONTEXT below. Do not use prior knowledge.
    2. When referencing information, cite the source using its label (e.g. "According to Source 1...").
    3. If multiple sources contain relevant information, synthesize them and cite each.
    4. If the context does not contain enough information to fully answer the question,
    clearly state what you can answer and what is missing.
    5. Be concise and direct. Do not repeat the question or add unnecessary preamble.

semantic-search:
  default-min-score: 0.5
```

## Roadmap

### Next (Q2 2026 - Open Source Release)

- [ ] Event-driven sync (near real-time)
- [ ] OAuth2/Keycloak integration
- [ ] Comprehensive testing suite
- [ ] Production deployment guide

### Future

- [ ] Streaming responses (SSE) for progressive answer generation
- [ ] Conversation history / multi-turn chat sessions
- [ ] Re-ranking with cross-encoder models
- [ ] Multiple embedding models per document
- [ ] Document versioning support
- [ ] DocFilters integration (better text extraction)
- [ ] Multilingual embeddings
- [ ] Performance optimizations for 10K+ documents

#### Core Functionality
1. RAG Service: SearchService, PromptService, hybrid search, LLM integration
2. Event-Driven Sync: Real-time updates, permission changes, incremental sync
3. Permission Handling: Token forwarding, server-side filtering, group membership
4. Custom Properties: Support for custom content model properties in search

#### Enhanced Functionality
5. DocFilters Integration: Markdown extraction, improved chunking
6. Testing: Unit, integration, performance tests
7. Documentation: Architecture diagrams, tuning guides, multi-repo setup
8. Developer Experience: Docker Compose setup, sample data, health checks

## Development

### Build

```bash
mvn clean package
```

### Run Tests

```bash
mvn test
```

### Run Locally

```bash
# Batch Ingester
mvn spring-boot:run -pl batch-ingester
# or
java -jar batch-ingester/target/batch-ingester-1.0.0-SNAPSHOT.jar

# RAG Service
mvn spring-boot:run -pl rag-service
# or
java -jar rag-service/target/rag-service-1.0.0-SNAPSHOT.jar
```

## Contributing

Contributions welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'feat: add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Acknowledgments

- Built with [Spring AI](https://spring.io/projects/spring-ai)
- Uses [Alfresco Java SDK](https://github.com/Alfresco/alfresco-java-sdk)
- Powered by [hxpr Content Lake](https://www.hyland.com/)
- Created for the Alfresco/Hyland community