# Architecture Options for Multi-Source Documentation Ingestion

## Current State Analysis

**Existing Architecture:**
- Single Docker image: `ghcr.io/quarkusio/chappie-ingestion-quarkus:3.15.0`
- Contains ~250 Quarkus guides embedded in pgvector
- Started via DevServices by quarkus-chappie extension
- chappie-server filters by extension using `extensions_csv_padded` metadata

**Available Integration Points:**
1. **Build-time**: CurateOutcomeBuildItem provides full dependency tree
2. **Runtime**: Extension variable passed to chappie-server for RAG filtering
3. **Metadata**: Existing schema supports arbitrary metadata fields
4. **Filtering**: ContainsString and composite filters already implemented

---

## Proposed Approaches

### Option 1: Multi-Layer Docker Images with Runtime Filtering (Recommended)

**Concept:** Build multiple specialized Docker images and use database-level filtering at runtime.

**Architecture:**
```
Images:
├── chappie-ingestion-quarkus:3.15.0        (Quarkus guides)
├── chappie-ingestion-hibernate:6.4.0       (Hibernate docs)
├── chappie-ingestion-smallrye-config:3.5.0 (SmallRye Config)
├── chappie-ingestion-jakarta-ee:10.0       (Jakarta EE specs)
└── chappie-ingestion-microprofile:6.0      (MicroProfile specs)

Combined at runtime:
└── pgvector container with merged databases
```

**Implementation Steps:**

1. **Extend BakeImageCommand to support multiple doc sources:**
   ```java
   public enum DocSource {
       QUARKUS_GUIDES,      // Existing: AsciiDoc + HTML
       HIBERNATE_DOCS,      // Docling: HTML → Markdown
       SMALLRYE_DOCS,       // Docling: HTML → Markdown
       JAKARTA_SPECS,       // Docling: PDF → Markdown
       MICROPROFILE_SPECS   // Docling: PDF → Markdown
   }
   ```

2. **Add library/dependency metadata to each document:**
   ```java
   metadata.put("library", "hibernate-orm");
   metadata.put("library_version", "6.4.0");
   metadata.put("quarkus_extensions", "quarkus-hibernate-orm,quarkus-hibernate-orm-panache");
   ```

3. **Build separate images per library:**
   ```bash
   java -jar target/quarkus-app/quarkus-run.jar bake-image \
     --doc-source=HIBERNATE_DOCS \
     --library-version=6.4.0 \
     --output-image=chappie-ingestion-hibernate:6.4.0
   ```

4. **Extend quarkus-chappie to merge databases at startup:**
   ```java
   @BuildStep
   void configureDynamicRagDatabase(
       CurateOutcomeBuildItem curateOutcome,
       BuildProducer<DevServicesResultBuildItem> devServices) {

       // Analyze dependencies
       List<String> libraries = extractLibraries(curateOutcome);
       // libraries = ["hibernate-orm:6.4.0", "smallrye-config:3.5.0", ...]

       // Build composite image or mount multiple databases
       startPgVectorWithLibraries(libraries);
   }
   ```

5. **Runtime filtering in chappie-server:**
   - Pass active libraries as context variable
   - Filter RAG results using: `library IN ('hibernate-orm', 'smallrye-config', ...)`
   - Alternatively, use PostgreSQL schemas per library and query dynamically

**Pros:**
- ✅ Clean separation: one image per library/project
- ✅ Reusable: images can be versioned independently
- ✅ Flexible: add new doc sources without changing core logic
- ✅ Efficient: only query relevant documentation at runtime
- ✅ Leverages existing metadata filtering infrastructure

**Cons:**
- ❌ Complex DevServices setup (multi-container or db merge)
- ❌ Initial build creates many images
- ❌ Database merge overhead at startup

**Complexity:** Medium-High

---

### Option 2: Mega-Image with Smart Metadata Tagging

**Concept:** Single large Docker image containing ALL documentation, filtered aggressively at query time.

**Architecture:**
```
Single Image: chappie-ingestion-all:3.15.0
├── Quarkus guides (250 docs)
├── Hibernate docs (150 docs)
├── SmallRye projects (80 docs)
├── Jakarta EE specs (40 docs)
└── MicroProfile specs (30 docs)

Total: ~550 documentation sources
Database size: ~5-8GB (estimated)
```

**Implementation Steps:**

1. **Extend BakeImageCommand to ingest multiple sources sequentially:**
   ```java
   void run() {
       processQuarkusGuides();
       processHibernateDocs();
       processSmallRyeDocs();
       processJakartaSpecs();
       processMicroProfileSpecs();
       dumpAndBuildImage();
   }
   ```

2. **Add rich dependency metadata:**
   ```java
   metadata.put("maven_artifacts", "org.hibernate:hibernate-core,org.hibernate:hibernate-validator");
   metadata.put("quarkus_extensions", "quarkus-hibernate-orm");
   metadata.put("required_by_extensions", "hibernate-orm,hibernate-orm-panache");
   ```

3. **Build mapping matrix:**
   - Create a mapping file: extension → required libraries
   - Example: `quarkus-hibernate-orm` → `[hibernate-orm, hibernate-validator, jakarta-persistence]`
   - Store in application.properties or database

4. **Extend RetrievalProvider filtering:**
   ```java
   public List<SearchMatch> search(String query, Set<String> activeExtensions) {
       // Expand extensions to libraries
       Set<String> requiredLibraries = expandToLibraries(activeExtensions);

       // Build filter: library IN (...)
       Filter libraryFilter = Filter.isIn("library", requiredLibraries);

       // Execute search with filter
       return embeddingStore.search(request, libraryFilter);
   }
   ```

5. **quarkus-chappie passes active extensions:**
   ```java
   // In JsonObjectCreator or ChappieProcessor
   String activeExtensions = String.join(",", getActiveExtensions(curateOutcome));
   serverArgs.add("-Dchappie.active.extensions=" + activeExtensions);
   ```

**Pros:**
- ✅ Simple DevServices setup: single container
- ✅ No database merging overhead
- ✅ Easy versioning: one tag per Quarkus release
- ✅ Minimal changes to quarkus-chappie extension

**Cons:**
- ❌ Large Docker image (5-10GB compressed)
- ❌ Long build times (30-60 minutes)
- ❌ Requires maintaining extension→library mapping
- ❌ More complex metadata to manage
- ❌ All docs embedded even if unused

**Complexity:** Medium

---

### Option 3: On-Demand Database Population (Dynamic Ingestion)

**Concept:** Start with empty pgvector, populate at application startup based on detected dependencies.

**Architecture:**
```
Startup Flow:
1. quarkus-chappie detects dependencies
2. Fetches pre-computed embeddings from registry (S3/artifact repo)
3. Populates pgvector with only relevant docs
4. Starts chappie-server
```

**Implementation Steps:**

1. **Pre-compute embeddings for all doc sources:**
   ```bash
   # Generate embedding artifacts (JSON/Parquet files)
   java -jar target/quarkus-app/quarkus-run.jar generate-embeddings \
     --doc-source=HIBERNATE_DOCS \
     --output=hibernate-6.4.0-embeddings.parquet
   ```

2. **Publish embeddings to artifact repository:**
   ```
   Maven Central / GitHub Releases / S3:
   ├── quarkus-3.15.0-embeddings.parquet
   ├── hibernate-6.4.0-embeddings.parquet
   ├── smallrye-config-3.5.0-embeddings.parquet
   └── ...
   ```

3. **quarkus-chappie downloads and loads at startup:**
   ```java
   @BuildStep
   void populateRagDatabase(CurateOutcomeBuildItem curateOutcome) {
       List<Dependency> deps = curateOutcome.getApplicationModel().getDependencies();

       for (Dependency dep : deps) {
           // Download embeddings for this dependency
           File embeddings = downloadEmbeddings(dep);

           // Load into pgvector
           loadEmbeddings(pgvectorContainer, embeddings);
       }
   }
   ```

4. **Cache embeddings locally:**
   - Store in `~/.quarkus/chappie/embeddings/`
   - Reuse across application restarts
   - Version-keyed cache invalidation

**Pros:**
- ✅ Minimal Docker image (just empty pgvector)
- ✅ Only loads required documentation
- ✅ Fast iteration: add new doc sources without rebuilding images
- ✅ Scalable: embeddings stored separately

**Cons:**
- ❌ Slower startup (download + load time)
- ❌ Network dependency at startup
- ❌ Requires embedding artifact hosting infrastructure
- ❌ Complex caching logic
- ❌ Offline mode requires pre-cached embeddings

**Complexity:** High

---

### Option 4: Hybrid - Base Image + Dynamic Extensions

**Concept:** Quarkus core docs in base image, dynamic loading for library-specific docs.

**Architecture:**
```
Base Image: chappie-ingestion-quarkus:3.15.0
  └── Quarkus guides (core documentation)

Extensions loaded at runtime:
  ├── hibernate-6.4.0-embeddings.parquet
  ├── smallrye-config-3.5.0-embeddings.parquet
  └── jakarta-persistence-3.1-embeddings.parquet
```

**Implementation:**
- Combine Option 1 (base image) + Option 3 (dynamic loading)
- Always load Quarkus guides from Docker image
- Dynamically load library-specific docs from artifacts

**Pros:**
- ✅ Fast startup for Quarkus-only apps (no downloads)
- ✅ Extensible for apps using external libraries
- ✅ Reasonable image size (~2-3GB)
- ✅ Offline mode works for Quarkus docs

**Cons:**
- ❌ Most complex implementation
- ❌ Two different loading paths to maintain

**Complexity:** Very High

---

## Dependency → Documentation Mapping Strategy

Regardless of chosen architecture, we need to map Maven dependencies to documentation sources:

### Mapping Examples:

```yaml
# Extension → Libraries mapping
quarkus-hibernate-orm:
  libraries:
    - hibernate-orm
    - hibernate-validator
    - jakarta-persistence-api
  versions:
    hibernate-orm: 6.4.0
    hibernate-validator: 8.0.1
    jakarta-persistence-api: 3.1.0

quarkus-smallrye-config:
  libraries:
    - smallrye-config
    - microprofile-config-api
  versions:
    smallrye-config: 3.5.0
    microprofile-config-api: 3.0

quarkus-rest:
  libraries:
    - jakarta-rest-api
    - resteasy-reactive
  versions:
    jakarta-rest-api: 3.1.0
```

### Implementation Options:

1. **Hardcoded in BakeImageCommand:**
   - Simple map in Java code
   - Updated with each Quarkus release

2. **YAML/JSON configuration file:**
   - `src/main/resources/doc-source-mappings.yaml`
   - Versioned alongside code

3. **Derived from Quarkus BOM:**
   - Parse `quarkus-bom` POM to extract versions
   - More dynamic but complex

4. **Maven Central metadata:**
   - Query Maven Central API for transitive dependencies
   - Fully dynamic but network-dependent

---

## Recommended Implementation Path

**Phase 1: Prove the Concept (Option 1 Simplified)**
1. Add Hibernate docs to existing BakeImageCommand
2. Tag documents with `library=hibernate-orm` metadata
3. Test filtering in chappie-server using library field
4. Validate 100% accuracy is maintained

**Phase 2: Multi-Image Architecture (Option 1 Full)**
1. Refactor BakeImageCommand to support `--doc-source` flag
2. Build separate images for Hibernate, SmallRye, Jakarta EE
3. Implement database merging in quarkus-chappie DevServices
4. Add dependency detection using CurateOutcomeBuildItem

**Phase 3: Smart Filtering**
1. Create extension→library mapping configuration
2. Pass active libraries to chappie-server at startup
3. Implement library-based RAG filtering
4. Add metadata boosting for primary library matches

**Phase 4: Optimization**
1. Benchmark query performance with larger corpus
2. Consider PostgreSQL schema partitioning if needed
3. Evaluate Option 4 (hybrid) if startup time becomes an issue

---

## Technical Considerations

### Database Size Estimates:
- Current (Quarkus only): ~250 guides × ~15 chunks × 384 dimensions = ~10MB embeddings
- With Hibernate: +150 docs = ~16MB
- With all libraries: +300 docs = ~25MB
- **Total estimated size: 50-100MB embeddings** (well within acceptable range)

### Query Performance:
- Current IVFFlat index handles 250 docs efficiently
- With 550 docs, may need to increase index list size from 100 to 200
- Metadata filtering happens before vector search (efficient)

### Build Time:
- Current: ~15-20 minutes for 250 guides
- Per library: ~5-10 minutes
- Total (all libraries): ~60-90 minutes
- **Can parallelize builds** (separate CI jobs per library)

### Versioning Strategy:
```
chappie-ingestion-quarkus:3.15.0
chappie-ingestion-hibernate:6.4.0-quarkus3.15
chappie-ingestion-smallrye-config:3.5.0-quarkus3.15
```

Tag format: `{library}:{library-version}-quarkus{quarkus-version}`

---

## Questions to Consider

1. **How many libraries should be supported initially?**
   - Start with top 5-10 most used extensions?
   - Hibernate, SmallRye (Config, JWT, Reactive Messaging), Jakarta EE specs?

2. **Should we support multiple versions of the same library?**
   - E.g., Hibernate 6.4 vs 6.5?
   - Likely yes, keyed by Quarkus version

3. **How do we handle version mismatches?**
   - App uses Hibernate 6.5, but only 6.4 docs available
   - Fallback to closest version? Or exclude?

4. **Should filtering be strict or fuzzy?**
   - Strict: Only show docs for exact dependencies
   - Fuzzy: Show related docs even if not direct dependency (current behavior with extension filter)

5. **Do we need a documentation registry/catalog?**
   - Central manifest listing available doc sources and versions
   - Enables auto-discovery and validation

---

## Next Steps

1. **Decide on architecture approach** (recommend Option 1)
2. **Select initial libraries to support** (suggest: Hibernate, SmallRye Config, Jakarta REST)
3. **Prototype Hibernate docs ingestion** in BakeImageCommand
4. **Test filtering accuracy** against RAG golden set
5. **Design extension→library mapping** format and storage
6. **Implement dependency detection** in quarkus-chappie
