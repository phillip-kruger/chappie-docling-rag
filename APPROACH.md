# Chappie Docling RAG - New Approach

**Goal:** Replace `chappie-quarkus-rag` with a Docling-based solution that produces 
the same output (pgvector Docker image with Quarkus documentation).

## Key Differences from Current Approach

### Current (chappie-quarkus-rag)
- Reads AsciiDoc files from downloaded Quarkus source
- Manual manifest creation and enrichment
- Custom `AsciiDocSemanticSplitter` for chunking
- LangChain4J for embeddings (BGE Small EN v15)
- Outputs: pgvector Docker image

### New (chappie-docling-rag)
- Fetches Quarkus documentation from **web URLs** (e.g., https://quarkus.io/version/3.30/guides/*)
- **Docling** converts HTML to Markdown with layout-aware parsing
- Docling's intelligent document understanding (preserves structure, tables, code blocks)
- Same embedding model (BGE Small EN v15 for consistency)
- Same output: pgvector Docker image

## Why This Approach?

1. **Simpler Pipeline**
   - No need to download/unzip Quarkus source code
   - No manual manifest enrichment needed
   - Docling handles document structure automatically

2. **Always Up-to-Date**
   - Fetches live documentation from quarkus.io
   - No version mismatches

3. **Better HTML Processing**
   - Docling designed for web content
   - Preserves layout, tables, code blocks
   - Handles complex document structures

4. **Testing Docling**
   - Validates Quarkus Docling extension in production scenario
   - Real-world use case for feedback to Docling team

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  1. Fetch Quarkus Guide URLs                                 │
│     • List all guides from quarkus.io/version/X.Y.Z/guides/  │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  2. Docling Processing                                       │
│     • Convert HTML → Markdown                                │
│     • Preserve structure (headers, tables, code)             │
│     • Extract metadata                                       │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  3. Chunking & Embedding                                     │
│     • Sentence-based splitter (200 tokens, 20 overlap)       │
│     • OR Semantic splitter by Markdown headers               │
│     • BGE Small EN v15 embeddings                            │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  4. PGVector Ingestion                                       │
│     • Store embeddings + text segments                       │
│     • Metadata: URL, title, section_path                     │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  5. Database Dump & Docker Image                             │
│     • pg_dump → SQL file                                     │
│     • Jib: pgvector base + SQL init script                   │
│     • Output: ghcr.io/quarkusio/chappie-ingestion-quarkus   │
└──────────────────────────────────────────────────────────────┘
```

