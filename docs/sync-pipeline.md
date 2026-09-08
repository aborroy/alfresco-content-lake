# Sync Pipeline

## Overview

Documents flow from a content source (Alfresco or Nuxeo) through `ContentSyncService` in
`content-lake-core`, which calls SPI interfaces to stay source-agnostic. Two ingestion paths exist:
full batch sync and live/event-driven sync.

---

## Full Sync Flow (`ContentSyncService.syncNode`)

```
SourceNode (from ContentSourceClient)
  │
  ├─ findByNodeId(nodeId, sourceId) ──► staleness check (modifiedAt comparison)
  │                                     skip if already current
  │
  ├─ createDocument() or updateDocument()
  │    builds HxprDocument with metadata + ACL + ingestProperties
  │    hxprService.createDocument(parentPath, doc)
  │
  └─ processContent()
       if textExtractor.supports(mimeType): sourceClient.getContent(nodeId)
       │ otherwise: sourceClient.downloadContent() + textExtractor.extractText()
       │
       chunkingService.chunk(text)
       embeddingService.embedChunks(chunks)
       hxprService.updateEmbeddings(hxprDocId, embeddings)
       documentApi.updateById(hxprDocId, fulltext + INDEXED status)
```

Chunking is structure-aware: detected tables are kept as atomic `ChunkType.TABLE` chunks (never
hard-split mid-row or dropped as noise), and every chunk records a `sectionIndex`. That section id is
what lets retrieval later expand a matched chunk back to its parent section (small-to-big retrieval,
`rag.retrieval.small-to-big.enabled`). When keyword-context enrichment is enabled
(`content-lake.ingest.keyword-context-enrichment-enabled`), document-level context is prepended to
each chunk's keyword-search text so short chunks stay findable by the keyword leg of hybrid search.

---

## Metadata-Only Flow (`ContentSyncService.ingestMetadata`)

Used by the batch ingester's two-phase pipeline:

1. `ingestMetadata()` -- writes hxpr document with metadata, returns a `SyncResult`
2. `TransformationQueue` enqueues the `SyncResult`
3. `TransformationWorker` picks it up and calls `processContent()`

This decouples metadata indexing (fast) from text extraction and embedding (slow), allowing
incremental progress even if the transformation pipeline is slow or interrupted.

---

## Path Structure in hxpr

Documents land at: `/{hxprTargetPath}/{sourceId}/{sourcePath}/{nodeName}`

- `hxprTargetPath` -- Spring config value (e.g. `/alfresco` or `/nuxeo`)
- `sourceId` -- identifies the source system instance
- `HxprService.ensureFolder()` creates the parent path on demand

---

## Idempotency

Every write is guarded by a `modifiedAt` staleness check. If the hxpr version is already at or
newer than the incoming node, the write is skipped. This makes it safe to run batch and live
ingesters concurrently against the same node without producing duplicate writes.

---

## Scope Resolution

Before a node is synced, `ScopeResolver.isInScope(node)` determines whether it belongs in the lake:

- **Alfresco** -- `ContentLakeScopeResolver`: file is in scope when it (or an ancestor folder) has
  the `cl:indexed` aspect AND neither the file nor any ancestor has `cl:excludeFromLake = true`.
  `shouldTraverse(node)` checks for excluded aspects and path patterns.
  Ancestor lookups hit `AlfrescoClient.getNode()` with an in-memory cache (max 2 000 entries).
  Call `invalidateFolderScope(folderId)` after `cl:indexed` changes.

- **Nuxeo** -- `NuxeoScopeResolver`: config-only scope for MVP. Driven by
  `nuxeo.scope.includedRoots` and `nuxeo.scope.includedTypes` in `application.yml`.

---

## `cin_sourceId` Format

`cin_sourceId` stores `"<sourceType>:<sourceId>"` -- e.g. `"alfresco:abc-uuid"`, `"nuxeo:prod"`.
`NodeSyncService.formatSourceId(SourceNode)` produces this value on every write, so all documents
ingested by current code carry the namespaced format.

### Legacy compatibility (built in)

Documents written by older Alfresco-only code stored the raw repository UUID with no
`"<sourceType>:"` prefix. Lookups handle both transparently: `HxprService.findByNodeId(nodeId,
sourceId)` builds its predicate through `buildSourceIdPredicate` / `sourceIdVariants`, which
OR-queries the namespaced form and the legacy raw id. No migration pause or dual-write window is
required -- a rolling deploy finds old and new documents alike.

### `findByNodeId` overloads

`findByNodeId(String nodeId, String sourceId)` is the preferred entry point; all source-aware
callers pass the formatted `sourceId`. The single-arg `findByNodeId(String nodeId)` overload remains
as a convenience for callers without source context and delegates to the two-arg form with a `null`
`sourceId` (matching on `cin_id` alone).

---

## Live Ingestion -- Alfresco

`alfresco-live-ingester` connects to Alfresco ActiveMQ and consumes `alfresco.repo.event2` topics.
Each `RepoEvent` is dispatched to a typed handler:

| Handler | Trigger |
|---|---|
| `NodeCreatedHandler` | new file or folder |
| `NodeUpdatedHandler` | content or metadata change |
| `NodeDeletedHandler` | deletion |
| `FolderMovedHandler` | folder move (triggers subtree reconciliation) |
| `ChildAssociationCreatedHandler` / `DeletedHandler` | child assoc changes |
| `PeerAssociationCreatedHandler` / `DeletedHandler` | peer assoc changes |
| `PermissionUpdatedHandler` | ACL change when emitted by the repository |
| `FolderIndexedScopeChangedHandler` | `cl:indexed` aspect toggled on a folder |

`RecentEventDeduplicator` prevents redundant processing when multiple events arrive for the same
node within a short window.

Alfresco Repository does not reliably emit permission update events for the ACL changes needed by
Content Lake. Because of that, Alfresco ACL reconciliation should not rely on the live ingester as
the primary mechanism. The primary production path is the repository-side `content-lake-repo-model`
addon, which detects ACL changes after commit and publishes a persistent ActiveMQ queue message.
`alfresco-batch-ingester` consumes that queue in a transacted listener and executes ACL
reconciliation, so failed reconciliation attempts are redelivered by the broker.

---

## Live Ingestion -- Nuxeo

`nuxeo-live-ingester` uses audit polling via `NuxeoAuditClient`. It queries the Nuxeo audit log
periodically, tracking the last-seen cursor in `AuditCursorStore` (default implementation:
`FileAuditCursorStore`). `NuxeoAuditMetrics` exposes polling and processing counters via Micrometer.

---

## Batch Ingestion -- Alfresco

`alfresco-batch-ingester` triggers a full sync:

1. `NodeDiscoveryService` walks the Alfresco folder tree, filtered by `ContentLakeScopeResolver`
2. Each in-scope node is passed to `BatchIngestionService`
3. `MetadataIngester` handles two-phase: metadata first, then transformation queue
4. `TransformationWorker` picks up tasks from `TransformationQueue` and calls `processContent()`

The `CinRemote`/`CinContext` content model is defined natively by the ai-ready-index engine, so no
client-side model provisioning runs at startup.

The same service also exposes `POST /api/sync/permissions` for Alfresco ACL reconciliation:

- File requests update only the stored hxpr ACL for that file.
- Folder requests traverse descendants recursively and update or delete affected file documents
  without re-running text extraction or embeddings.
- In production, the Alfresco repository addon should publish a queue message after ACL changes
  commit, and `alfresco-batch-ingester` should consume that message and run this reconciliation.
- It also remains available as an explicit fallback when you need to reconcile a node manually.

---

## Batch Ingestion -- Nuxeo

`nuxeo-batch-ingester` uses NXQL discovery:

```sql
SELECT * FROM Document
WHERE ecm:path STARTSWITH '/default-domain/workspaces'
  AND ecm:primaryType IN ('File','Note')
  AND ecm:currentLifeCycleState != 'deleted'
  AND ecm:isProxy = 0
  AND ecm:isCheckedInVersion = 0
```

`NuxeoDiscoveryService` pages through results using `currentPageIndex` and `pageSize`.
`NuxeoBatchIngestionService` calls `ContentSyncService` for each discovered document.

---

## Deletion

Deletion is one operation, `NodeSyncService.delete(SourceTombstone)`, reached from every source. A
`SourceTombstone` carries a node id, an optional event timestamp, and a reason (`DELETED`,
`OUT_OF_SCOPE`, `MISSING_AT_RECONCILE`). It is deliberately not a `SourceNode`: by the time most
tombstones are raised the node is gone from its source, so there is no name, mime type or ACL to carry.

The order matters. Embedding children are cleared before the document itself, because hxpr's cascade on
document delete is not contractual and an orphaned `_e_*` child remains searchable: the read path
substitutes the `*` embedding-type wildcard, so a child with no parent still answers queries. Clearing
the children is best effort, since a surviving parent is the worse of the two outcomes.

An event timestamp, when present, is compared against the stored `source_modifiedAt` and a delete older
than the indexed version is skipped. That guard orders events; it does not veto a reconciliation sweep,
which observed the source directly and therefore carries no event time.

## Reconciliation Sweep

Deletion otherwise depends entirely on a delete event arriving. With the live ingester down, a dropped
broker message, or a node removed while only the batch ingester runs, the document outlives its source
and search returns it as a phantom result, with nothing to notice.

After a successful batch sync, each batch ingester can compare the index against what its discovery pass
actually saw and delete the difference. It is **off by default** (`*.reconcile.enabled`), because it
deletes documents.

Every precondition fails towards doing nothing:

| Guard | Why |
|---|---|
| Discovery must report itself complete | Several discovery paths return a partial result and log a warning rather than failing, so completeness is asserted by discovery, never inferred by the caller |
| No node-level failures | A node that failed may or may not have been enumerated, so the discovered set is not trustworthy. There is no tolerance knob: a partial-failure threshold is not a number an operator can calibrate |
| An empty discovery deletes nothing | Zero nodes is exactly what a broken discovery returns |
| The discovered-id set must not have overflowed `max-seen-ids` | An incomplete record of what discovery saw would make present documents look missing |
| `max-delete-ratio` and `max-deletes` | The backstop against a partial pass that was never reported as one |
| Scope | Only documents under the roots discovery actually covered are candidates, so a folder-scoped sync cannot delete documents another sync owns |

The scope predicate matches on `cin_paths`, which holds the **hxpr** path
(`<hxprTargetPath>/<repositoryId><sourcePath>`) rather than the raw source path; convert with
`NodeSyncService.contentLakePathPrefix`. A document with no paths never matches and so is never deleted.

The sweep's outcome is attached to the job, so `GET /api/sync/status/{jobId}` reports it:

```json
{
  "reconciliation": {
    "status": "COMPLETED",
    "indexed": 42, "inScope": 12, "candidates": 1,
    "deleted": 1, "failed": 0, "ratio": 0.083,
    "detail": "1 document(s) deleted"
  }
}
```

Any `status` other than `COMPLETED` means nothing was deleted, and says why:
`DISABLED`, `SKIPPED_INCOMPLETE_DISCOVERY`, `SKIPPED_NODE_FAILURES`, `SKIPPED_EMPTY_DISCOVERY`,
`ABORTED_SEEN_SET_OVERFLOW`, `ABORTED_SCAN_FAILED`, `ABORTED_RATIO`, `ABORTED_ABSOLUTE_CAP`.

**Expect the first sweep on an existing corpus to abort on the ratio guard.** A corpus that has
accumulated drift genuinely has many documents to remove, and the guard cannot tell that from a broken
discovery pass. That is correct behaviour: read the logged figures, satisfy yourself the deletions are
genuine, raise the ratio for one run, then lower it again.

The filesystem source has no live ingester, so a batch sync is the only path that writes and this sweep
is the only path that ever deletes: without it, a file removed from the mounted directory stays
searchable indefinitely.

## Embedding Storage and the Embedding Type

Embeddings are stored as a Parquet file in a `SysEmbeddings` child document named
`_e_{embeddingType}`, and hxpr indexes them by that child name. The type is **derived from the
configured embedding model**, sanitized for use inside a `sys_name`: `ai/mxbai-embed-large` becomes
`ai-mxbai-embed-large`. `EmbeddingTypeResolver` is the single derivation, called by both the write and
the clear path so the two cannot drift.

Two consequences worth knowing:

- `embedding.model-name` must resolve to the same value in every module. Two writers deriving different
  types would leave one document carrying children under both, and the read path's `*` wildcard would
  then merge vectors from two models in one result set.
- Clearing a document's embeddings is type-agnostic: it removes every `_e_*` child, not only the one
  matching the current configuration. That is what stops a child written under a previously configured
  model from surviving a re-sync and continuing to answer queries.
