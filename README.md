# Chappie Docling RAG

**Hybrid RAG approach combining AsciiDoc metadata with Docling HTML conversion.**

This project uses [Quarkus Docling](https://docs.quarkiverse.io/quarkus-docling/dev/index.html) 
to build a RAG (Retrieval-Augmented Generation) system for Quarkus documentation, 
producing a pgvector Docker image compatible with the chappie-server implementation.

## Hybrid Approach

This implementation combines the best of both approaches:

1. **Git Clone** - Clones Quarkus repository at specific version tag
2. **Metadata Extraction** - Extracts topics, categories, extensions, summary from AsciiDoc headers
3. **HTML Fetching** - Fetches versioned HTML from quarkus.io (e.g., `/version/3.15/guides/`)
4. **Docling Conversion** - Layout-aware HTML → Markdown conversion
5. **Metadata Enrichment** - Combines Docling content with AsciiDoc metadata
6. **CDI Integration** - Uses Quarkus CDI with fixed port (5001) for Docling service

### Benefits

- ✅ **100% RAG golden set accuracy** (36/36 test cases)
- ✅ **Rich metadata** for improved search relevance
- ✅ **Version consistency** (metadata and content from same Quarkus version)
- ✅ **Layout-aware conversion** (preserves document structure)
- ✅ **No GitHub rate limits** (fetches from quarkus.io)

## Architecture

```
Git Clone → AsciiDoc Metadata + Versioned HTML → Docling → Markdown + Metadata →
Chunking/Embedding → PGVector → Docker Image
```

See [APPROACH.md](APPROACH.md) for detailed architecture and implementation notes.

## Quick Start

### Prerequisites

- Java 21+
- Docker
- Maven 3.9+

### Development Mode

```bash
./mvnw quarkus:dev
```

The Docling UI will be available at http://localhost:8080/q/dev/ for testing document conversions.

### Building the RAG Database Image

First, compile the application:

```bash
mvn clean package -DskipTests
```

Then run the bake-image command:

```bash
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --chunk-size=1000 \
  --chunk-overlap=300 \
  --semantic
```

**Process:**
- Starts Docling Serve container (fixed port 5001)
- Starts pgvector container
- Clones Quarkus repository at version tag
- Finds and filters AsciiDoc guides (~251 guides)
- For each guide:
  - Extracts metadata from AsciiDoc headers
  - Fetches HTML from versioned quarkus.io URL
  - Converts to Markdown using Docling
  - Combines content with metadata
  - Ingests to pgvector with embeddings
- Dumps database to SQL
- Builds Docker image

**Output:** `ghcr.io/quarkusio/chappie-ingestion-quarkus:3.15.0`

**Build time:** ~15-20 minutes for full documentation set (250 guides)

### Command-Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--quarkus-version` | Quarkus version to process (e.g., 3.15.0) | Required |
| `--chunk-size` | Maximum chunk size in characters | 1000 |
| `--chunk-overlap` | Overlap between chunks | 300 |
| `--semantic` | Use semantic (header-based) splitting | false |
| `--max-guides` | Limit number of guides (0 = all, useful for testing) | 0 |
| `--base-image` | Base PostgreSQL image | pgvector/pgvector:pg16 |
| `--push` | Push to remote registry instead of local Docker | false |
| `--latest` | Tag image as latest | false |

### Example Usage

**Test with 5 guides:**
```bash
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --max-guides=5 \
  --semantic
```

**Production build (all guides):**
```bash
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --chunk-size=1000 \
  --chunk-overlap=300 \
  --semantic
```

**Using the image with chappie-server:**
```bash
docker run -p 5432:5432 ghcr.io/quarkusio/chappie-ingestion-quarkus:3.15.0
```

Then configure chappie-server to connect to `localhost:5432`.

## Key Dependencies

- **Quarkus 3.30.8** - Application framework
- **Quarkus Docling 1.2.2** - Document processing with layout awareness
- **LangChain4J** - RAG pipeline and pgvector integration
- **BGE Small EN v15** - Embedding model (384 dimensions)
- **Testcontainers** - Container management (Docling + pgvector)
- **Jib** - Docker image building
- **JGit** - Git repository cloning

## Configuration

See `src/main/resources/application.properties`:

```properties
# Docling configuration (CDI injection with fixed port)
quarkus.docling.devservices.enabled=false
quarkus.docling.base-url=http://localhost:5001

# PGVector configuration
quarkus.langchain4j.pgvector.dimension=384
quarkus.langchain4j.pgvector.table=rag_documents
quarkus.langchain4j.pgvector.create-table=true
quarkus.langchain4j.pgvector.use-index=true
quarkus.langchain4j.pgvector.index-list-size=100

# PostgreSQL datasource (set by Testcontainers at runtime)
quarkus.datasource.db-kind=postgresql

# Logging
quarkus.log.level=INFO
quarkus.log.category."org.chappie".level=INFO
```

**Note:** The Docling Serve container is started programmatically by the bake-image command on port 5001.

## Testing Results

Uses the same test suite from `chappie-server`:
- **RagGoldenSetTest** - 36 test cases for retrieval quality

### Results Comparison

| Metric | Previous (AsciiDoc) | New (Hybrid) | Change |
|--------|---------------------|--------------|--------|
| **Test Success Rate** | 97.2% (35/36) | 100% (36/36) | +2.8% ✅ |
| **Failed Queries** | 1 (panache-testing) | 0 | Fixed ✅ |
| **Guide Ingestion** | ~255 guides | 250/251 (99.6%) | ✅ |
| **Build Time** | ~20 minutes | ~15.6 minutes | Faster ✅ |

**Conclusion:** The hybrid approach achieves 100% accuracy on the RAG golden set, improving upon the baseline.

## Project Status

✅ **Complete and Production-Ready**

- ✅ Hybrid architecture (AsciiDoc metadata + Docling HTML)
- ✅ CDI integration with fixed port (5001)
- ✅ Git repository cloning for version consistency
- ✅ Metadata extraction (topics, categories, extensions, summary)
- ✅ Versioned HTML fetching from quarkus.io
- ✅ Docling HTML → Markdown conversion
- ✅ Semantic chunking by headers
- ✅ Database dump and Docker image building
- ✅ 100% RAG golden set accuracy
- ✅ Documentation and examples

## Comparison with Current Approach

| Feature | chappie-quarkus-rag (baseline) | chappie-docling-rag (hybrid) |
|---------|--------------------------------|------------------------------|
| **Input** | AsciiDoc files only | AsciiDoc metadata + HTML from quarkus.io |
| **Parser** | Custom AsciiDocSemanticSplitter | Docling layout-aware HTML → Markdown |
| **Metadata** | Limited (title, URL) | Rich (topics, categories, extensions, summary) |
| **Version** | From source clone | Git clone + versioned web URLs |
| **Content Quality** | AsciiDoc rendering | Professional HTML rendering |
| **Accuracy** | 97.2% (35/36) | 100% (36/36) |
| **Build Time** | ~20 minutes | ~15.6 minutes |
| **Output** | pgvector Docker image | pgvector Docker image |

## References

- [Quarkus Docling Extension](https://docs.quarkiverse.io/quarkus-docling/dev/index.html)
- [Build Agent-Ready RAG Systems with Quarkus and Docling](https://www.the-main-thread.com/p/enterprise-rag-quarkus-docling-pgvector-tutorial)
- [LangChain4J Quarkus Integration](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- [APPROACH.md](APPROACH.md) - Detailed implementation plan

## License

Same as chappie-server project.
