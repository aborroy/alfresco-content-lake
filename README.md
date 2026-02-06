# Alfresco Content Lake PoC

Proof of Concept implementation for AI-powered semantic search and RAG (Retrieval-Augmented Generation) for Alfresco Content Services using **hxpr** as a Content Lake. This project enables high-quality AI search while keeping Alfresco as the source of truth, minimizing data duplication, enforcing end-user permissions server-side, and supporting on-premises AI execution.

## Architecture Overview

The solution implements a two-phase pipeline for content synchronization:

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: Fast Metadata Ingestion                           │
│  Alfresco → Discovery → Metadata → hxpr Document (PENDING)  │
└─────────────────────────────────────────────────────────────┘
                           ↓
                 Transformation Queue
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  Phase 2: Async Content Processing                          │
│  Transform → Chunk → Embed → Update Document (INDEXED)      │
└─────────────────────────────────────────────────────────────┘
```

### Key Design Principles

- Alfresco as Source of Truth: No full metadata or binary replication
- Minimal Content Lake: Store only what search needs (one hxpr Document per Alfresco node, many Embeddings per Document)
- Server-Side Permissions: End-user permissions enforced by hxpr via ACLs
- On-Premises AI: Local LLM and embedding models using Spring AI
- REST-Based Sync: Generic connector using Alfresco REST APIs (no database access)

## Project Modules

| Module | Description |
|--------|-------------|
| `content-lake-common` | Shared clients, models, and services for Alfresco, hxpr, and AI |
| `batch-ingester` | Selective batch synchronization service with async transformation pipeline |

## Prerequisites

### Required Services

1. Alfresco Content Services 25.x+
   - REST API accessible
   - Admin credentials

2. Alfresco Transform Service
   - Transform Core AIO or equivalent
   - For text extraction from documents
   - Default: `http://localhost:10090`

3. hxpr Content Lake
   - Running instance with API access
   - Identity Provider (IDP) for OAuth2
   - Default: `http://localhost:8080`

4. Docker Model Runner
   - For local embedding generation
   - Supports OpenAI-compatible API
   - Required model: `ai/mxbai-embed-large` (or similar)

### Development Environment

- Java 21 (required)
- Maven 3.9+
- Docker and Docker Compose

## Installation

### 1. Clone the Repository

```bash
git clone <repository-url>
cd alfresco-content-lake
```

### 2. Build the Project

```bash
mvn clean package
```

This will:
- Build the `content-lake-common` library
- Build the `batch-ingester` service
- Generate JAR files in `target/` directories

### 3. Configure Environment

Create a `.env` file or export environment variables:

```bash
# Alfresco Configuration
export CONTENT_SERVICE_URL=http://localhost:8080
export CONTENT_SERVICE_SECURITY_BASICAUTH_USERNAME=admin
export CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD=admin

# hxpr Content Lake
export HXPR_URL=http://localhost:8080
export HXPR_REPOSITORY_ID=default
export HXPR_TARGET_PATH=/alfresco-sync

# hxpr Identity Provider
export HXPR_IDP_TOKEN_URL=http://localhost:5002/idp/connect/token
export HXPR_IDP_CLIENT_ID=nuxeo-client
export HXPR_IDP_CLIENT_SECRET=secret
export HXPR_IDP_USERNAME=testuser
export HXPR_IDP_PASSWORD=password

# Transform Service
export TRANSFORM_URL=http://localhost:10090
export TRANSFORM_ENABLED=true

# Embedding Model (Docker Model Runner or Ollama)
export MODEL_RUNNER_URL=http://localhost:12434/engines/llama.cpp/v1
export EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5

# Performance Tuning
export TRANSFORM_WORKERS=4
export TRANSFORM_QUEUE_CAPACITY=1000
export EMBEDDING_CHUNK_SIZE=900
export EMBEDDING_CHUNK_OVERLAP=120
```

### 4. Configure Ingestion Sources

Edit `batch-ingester/src/main/resources/application.yml`:

```yaml
ingestion:
  sources:
    - folder: abc-123-def-456  # Alfresco folder nodeId
      recursive: true
      types:
        - cm:content
      mime-types:
        - application/pdf
        - text/plain
        - application/msword
  exclude:
    paths:
      - "*/surf-config/*"
      - "*/thumbnails/*"
    aspects:
      - cm:workingcopy
```

## Running the Application

### Option 1: Run with Java

```bash
java -jar batch-ingester/target/batch-ingester-1.0.0-SNAPSHOT.jar
```

### Option 2: Run with Docker Compose

```bash
docker-compose up -d
```

The service will be available at `http://localhost:9090`

### Verify Startup

Check service health:

```bash
curl http://localhost:9090/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

## Usage

### 1. Start Batch Synchronization

#### Sync Configured Folders

Synchronize folders defined in `application.yml`:

```bash
curl -X POST http://localhost:9090/api/sync/configured
```

#### Sync Specific Folders (Ad-hoc)

```bash
curl -X POST http://localhost:9090/api/sync/batch \
  -H "Content-Type: application/json" \
  -d '{
    "folders": ["abc-123-def"],
    "recursive": true,
    "types": ["cm:content"],
    "mimeTypes": ["application/pdf", "text/plain"]
  }'
```

### 2. Monitor Sync Status

#### Check Overall Status

```bash
curl http://localhost:9090/api/sync/status
```

Response:
```json
{
  "activeJobs": 1,
  "queueSize": 45,
  "completedCount": 150,
  "failedCount": 2
}
```

#### Check Specific Job Status

```bash
curl http://localhost:9090/api/sync/status/{jobId}
```

### 3. Clear Transformation Queue

```bash
curl -X DELETE http://localhost:9090/api/sync/queue
```

## Sync Status Values

| Status | Description |
|--------|-------------|
| `PENDING` | Metadata ingested, waiting for transformation |
| `PROCESSING` | Text extraction and embedding generation in progress |
| `INDEXED` | Fully indexed with embeddings, ready for search |
| `FAILED` | Transformation failed (check `sync_error` field) |

## Data Model in hxpr

### Document Structure

Each Alfresco node creates one hxpr Document with these properties:

| Property | Source | Purpose |
|----------|--------|---------|
| `sys_name` | Alfresco node name | Display name |
| `sys_path` | Alfresco path | Navigation |
| `cin_id` | Alfresco nodeId | Link back to Alfresco |
| `cin_sourceId` | `"alfresco"` | Source system identifier |
| `alfresco_repositoryId` | Repository ID | Multi-tenancy |
| `alfresco_modifiedAt` | Last modified date | Change detection |
| `alfresco_readAuthorities` | Read permissions | ACL filtering |
| `alfresco_mimeType` | MIME type | Content type filtering |
| `alfresco_path` | Full path | Hierarchical search |
| `sysFulltextBinary` | Extracted text | Keyword search |

### Embedding Structure

Embeddings are stored inline using the `SysEmbed` mixin:

```json
{
  "sys_primaryType": "SysFile",
  "sys_name": "quarterly-report.pdf",
  "sys_mixinTypes": ["CinRemote", "SysEmbed"],
  "cin_id": "abc-123-def",
  "cin_sourceId": "alfresco",
  "sysembed_embeddings": [
    {
      "type": "text-embedding-nomic-embed-text-v1.5",
      "text": "Q1 revenue increased by 15% compared to last year...",
      "vector": [0.1, -0.2, 0.3, ...],
      "location": {
        "text": {
          "chunkIndex": 0,
          "page": 1
        }
      }
    }
  ]
}
```

## Verifying Semantic Search

### Prerequisites

1. Complete at least one batch sync
2. Wait for all documents to reach `INDEXED` status
3. Obtain an access token from the IDP

```bash
TOKEN=$(curl -s -X POST http://localhost:5002/idp/connect/token \
  -d "grant_type=password" \
  -d "client_id=YOUR_CLIENT_ID" \
  -d "client_secret=YOUR_CLIENT_SECRET" \
  -d "username=admin" \
  -d "password=admin" \
  -d "scope=openid profile email" | jq -r '.access_token')
```

### Step 1: Verify Documents Have Embeddings

```bash
curl -X POST http://localhost:8080/api/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "HXCS-REPOSITORY: default" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "SELECT sys_id, sys_name, sysembed_embeddings FROM SysFile WHERE cin_sourceId = '\''alfresco'\''",
    "limit": 5
  }'
```

### Step 2: Generate Query Vector

```bash
QUERY_VECTOR=$(curl -s http://localhost:12434/engines/llama.cpp/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model": "text-embedding-nomic-embed-text-v1.5",
    "input": "quarterly sales revenue"
  }' | jq -c '.data[0].embedding')
```

### Step 3: Execute Semantic Search (kNN)

```bash
curl -X POST http://localhost:8080/api/query/embeddings \
  -H "Authorization: Bearer $TOKEN" \
  -H "HXCS-REPOSITORY: default" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": '"$QUERY_VECTOR"',
    "embeddingType": "text-embedding-nomic-embed-text-v1.5",
    "query": "SELECT * FROM SysFile",
    "repositoryId": "default",
    "limit": 10,
    "trackTotalCount": true
  }'
```

### Step 4: Filter by Metadata

Search only PDF documents:

```bash
curl -X POST http://localhost:8080/api/query/embeddings \
  -H "Authorization: Bearer $TOKEN" \
  -H "HXCS-REPOSITORY: default" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": '"$QUERY_VECTOR"',
    "embeddingType": "*",
    "query": "SELECT * FROM SysFile WHERE cin_ingestProperties.cm:content.mimeType = '\''application/pdf'\''",
    "limit": 5
  }'
```

## Configuration Reference

### Transform Service Configuration

```yaml
transform:
  url: http://localhost:10090
  timeout-ms: 120000
  enabled: true
```

### Embedding Configuration

```yaml
ingestion:
  embedding:
    chunk-size: 900        # Token size per chunk
    chunk-overlap: 120     # Overlap between chunks
```

### Worker Thread Configuration

```yaml
ingestion:
  transform:
    worker-threads: 4           # Parallel transformation workers
    queue-capacity: 1000        # Max queued transformations
  batch:
    executor:
      core-size: 1
      max-size: 1
      queue-capacity: 1000
```

## TODO List

### Core Functionality

#### 1. RAG Service Implementation
- [ ] Create `rag-api` module
- [ ] Implement `SearchService` for keyword, kNN, and hybrid search
- [ ] Implement `PromptService` using Spring AI for chat models
- [ ] Add hybrid search implementation (combining vector + text match)
- [ ] Configure normalization weights for hybrid search (e.g., 0.7 vector, 0.3 text)
- [ ] Implement RAG retrieval order: vector/hybrid → extract chunks → call LLM
- [ ] Add chat model integration (local LLM via Ollama/vLLM/Docker Model Runner)

#### 2. Event-Driven Synchronization
- [ ] Implement repository event consumer for near real-time sync
- [ ] Handle node creation events
- [ ] Handle node update events
- [ ] Handle node move events
- [ ] Handle node delete events
- [ ] Handle permission change events (critical for ACL consistency)
- [ ] Implement incremental sync based on `alfresco_modifiedAt`

#### 3. Permission Handling Improvements
- [ ] Map Alfresco read authorities to hxpr `sys_acl` during sync
- [ ] Implement token forwarding from end-users to hxpr
- [ ] Test server-side permission filtering in queries
- [ ] Handle group membership changes
- [ ] Add permission validation in read/search endpoints

#### 4. Additional Properties for Hybrid Search
- [ ] Allow explicit selection of Alfresco properties for sync
- [ ] Add support for custom content model properties
- [ ] Add property-based retrieval discriminators
- [ ] Document additional property configuration

### Enhanced Functionality

#### 5. Document Filters Integration
- [ ] Add DocFilters client for Markdown extraction
- [ ] Implement Markdown chunking strategy
- [ ] Compare quality of plain text vs. Markdown for embeddings
- [ ] Document DocFilters deployment and configuration
- [ ] Add configuration flag to switch between Transform Service and DocFilters

#### 6. Testing & Validation
- [ ] Add unit tests for core services (AlfrescoClient, HxprService, EmbeddingService)
- [ ] Add integration tests for sync pipeline
- [ ] Add semantic search validation tests
- [ ] Test permission filtering with different user roles
- [ ] Performance testing with large repositories (10K+ documents)
- [ ] Test failure scenarios (Transform Service down, hxpr unavailable)

#### 7. Documentation Improvements
- [ ] Add architecture diagrams
- [ ] Document Spring AI model configuration
- [ ] Add examples for different embedding models
- [ ] Document hybrid search tuning guidelines
- [ ] Add performance tuning guide
- [ ] Document multi-repository setup

#### 8. Developer Experience
- [ ] Add Docker Compose setup with all dependencies
- [ ] Create sample data for testing
- [ ] Add `httpie` collection for API testing
- [ ] Implement health checks for all dependencies
- [ ] Add startup validation (check connectivity to Alfresco, hxpr, etc.)
- [ ] Create developer quickstart guide

#### 9. Advanced Features (Future)
- [ ] Support for multiple embedding models per document
- [ ] Implement document versioning support
- [ ] Add support for Alfresco aspects in filtering
- [ ] Implement smart retry logic for failed transformations
- [ ] Add support for binary content types (images, videos)
- [ ] Implement change detection optimization (avoid re-processing unchanged documents)
- [ ] Add multilingual support for embeddings
- [ ] Implement A/B testing framework for embedding models

#### 10. Security Enhancements
- [ ] Implement OAuth2 token refresh
- [ ] Add support for mTLS for hxpr communication
- [ ] Add request authentication/authorization
- [ ] Secure sensitive configuration (secrets management)
- [ ] Add audit logging for sync operations

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request with a clear description