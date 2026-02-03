# Hibernate POC Verification Guide

## Testing the POC

### 1. Build and Test Hibernate Documentation

```bash
# Clean build
mvn clean package -DskipTests

# Test with 1 Hibernate document
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --doc-source=HIBERNATE \
  --max-guides=1 \
  --semantic

# Expected output:
# - Creates ghcr.io/quarkusio/chappie-ingestion-hibernate:3.15.0
# - Processes 1 document (~400K chars)
# - Completes in ~1-2 minutes
```

### 2. Verify Docker Image

```bash
# List images
docker images | grep chappie-ingestion-hibernate

# Expected:
# ghcr.io/quarkusio/chappie-ingestion-hibernate  3.15.0  <id>  <date>  ~521 MB
```

### 3. Test Library Metadata Structure

The documents contain these key metadata fields for filtering:

```json
{
  "library": "hibernate-orm",
  "library_version": "6.4.4.Final",
  "quarkus_version": "3.15.0",
  "quarkus_extensions": "quarkus-hibernate-orm,quarkus-hibernate-orm-panache",
  "title": "Introduction to Hibernate ORM",
  "topics": "introduction, getting-started, overview",
  "categories": "orm,persistence,database,hibernate",
  "url": "https://docs.jboss.org/hibernate/orm/6.4/introduction/html_single/Hibernate_Introduction.html"
}
```

### 4. How Runtime Filtering Will Work

In chappie-server, you can filter by library:

```java
// Example filter for Hibernate-only docs
Filter libraryFilter = Filter.isEqualTo("library", "hibernate-orm");

// Or filter by Quarkus extensions
Filter extensionFilter = ContainsString("quarkus_extensions", ",quarkus-hibernate-orm,");

// Combined filter
Filter combinedFilter = Filter.and(libraryFilter, extensionFilter);
```

### 5. Integration with quarkus-chappie Extension

The extension can detect Hibernate dependency and pass it to chappie-server:

```java
// In ChappieProcessor.java
@BuildStep
void configureRagFiltering(CurateOutcomeBuildItem curateOutcome,
                           BuildProducer<ConfigPropertyBuildItem> config) {

    // Detect Hibernate dependency
    boolean hasHibernate = curateOutcome.getApplicationModel()
        .getDependencies()
        .stream()
        .anyMatch(dep -> dep.getArtifactId().contains("hibernate"));

    if (hasHibernate) {
        // Tell chappie-server to include Hibernate docs
        config.produce(new ConfigPropertyBuildItem(
            "chappie.rag.libraries",
            "quarkus,hibernate-orm"
        ));
    }
}
```

### 6. Test Different Scenarios

#### Scenario A: Quarkus-only application
```bash
# User has no Hibernate dependency
# chappie-server filters: library='quarkus' only
# Result: Only Quarkus documentation shown
```

#### Scenario B: Application with Hibernate
```bash
# User has quarkus-hibernate-orm dependency
# chappie-server filters: library IN ('quarkus', 'hibernate-orm')
# Result: Both Quarkus and Hibernate documentation shown
```

#### Scenario C: Question about Hibernate
```bash
# User asks: "How do I configure Hibernate connection pooling?"
# System detects Hibernate dependency
# Filters to Hibernate docs
# Returns relevant Hibernate User Guide sections
```

## Expected Accuracy Improvement

**Current (Quarkus-only):**
- RAG golden set: 36/36 (100% accuracy)
- Coverage: Quarkus features only

**With Hibernate POC:**
- RAG golden set: Should maintain 100%
- Coverage: Quarkus + Hibernate ORM
- Benefit: Can answer Hibernate-specific questions accurately

**Example questions now answerable:**
- "How do I map a JPA entity?"
- "What's the difference between @OneToMany and @ManyToMany?"
- "How do I configure Hibernate second-level cache?"
- "How do I use Hibernate Envers for auditing?"

## Performance Characteristics

### Image Sizes
- Quarkus only: ~500-650 MB
- Hibernate only: ~521 MB
- Combined (estimated): ~700-800 MB

### Build Times
- Quarkus (250 docs): ~15-20 minutes
- Hibernate (2-3 docs): ~2-3 minutes
- Combined: ~20-25 minutes

### Query Performance
- Current (250 Quarkus docs): Fast (< 100ms)
- With Hibernate (+3 docs): Minimal impact (< 110ms estimated)
- Filtering overhead: Negligible (metadata index lookup)

### Database Size
- Quarkus embeddings: ~15 MB
- Hibernate embeddings: ~2 MB
- **Total: ~17 MB** (very manageable)

## Next Development Steps

### Step 1: Update chappie-server RetrievalProvider ✅ Ready
Add library-based filtering:

```java
public List<SearchMatch> search(String query, Set<String> activeLibraries) {
    Filter libraryFilter = Filter.isIn("library", activeLibraries);
    return embeddingStore.search(request, libraryFilter);
}
```

### Step 2: Update quarkus-chappie Extension ✅ Ready
Detect dependencies and pass to server:

```java
// Analyze CurateOutcomeBuildItem
Set<String> libraries = detectLibraries(curateOutcome);
// Pass to chappie-server via config
serverArgs.add("-Dchappie.active.libraries=" + String.join(",", libraries));
```

### Step 3: Add More Libraries
- SmallRye Config
- SmallRye Reactive Messaging
- SmallRye JWT
- Jakarta EE specs
- MicroProfile specs

### Step 4: Create Library Mapping Configuration
```yaml
# library-mappings.yaml
quarkus-hibernate-orm:
  libraries:
    - hibernate-orm
  versions:
    3.15.x: 6.4.4.Final
    3.16.x: 6.4.9.Final

quarkus-smallrye-config:
  libraries:
    - smallrye-config
    - microprofile-config
  versions:
    3.15.x: 3.5.0
```

## Troubleshooting

### Issue: Container exits immediately
**Expected behavior** - these are init-only images for DevServices, not standalone databases.
**Solution:** Use with chappie-server's DevServices infrastructure.

### Issue: 404 errors when fetching docs
**Cause:** docs.jboss.org may be temporarily unavailable or URL pattern changed.
**Solution:** Check docs availability, update URL pattern if needed.

### Issue: Build takes too long
**Solution:** Use `--max-guides=N` to limit documents during testing.

### Issue: Out of memory during embedding
**Cause:** Processing very large documents.
**Solution:** Increase JVM heap: `java -Xmx4g -jar ...`

## Success Criteria ✅

The POC is successful if:

1. ✅ Hibernate documentation can be fetched and converted
2. ✅ Library metadata is correctly attached to documents
3. ✅ Docker images build successfully
4. ✅ Multiple sources can be processed in one run
5. ✅ Architecture is extensible for new sources
6. ✅ No breaking changes to existing functionality

**All criteria met!** 🎉

## Demo Script

```bash
# 1. Show current capabilities
echo "Building Quarkus-only documentation..."
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --max-guides=5 \
  --semantic

# 2. Show new Hibernate support
echo "Building Hibernate documentation..."
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --doc-source=HIBERNATE \
  --semantic

# 3. Show combined build
echo "Building combined Quarkus + Hibernate..."
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 \
  --doc-source=ALL \
  --max-guides=5 \
  --semantic

# 4. Verify images
docker images | grep chappie-ingestion

# Expected output:
# chappie-ingestion-quarkus     3.15.0   ...
# chappie-ingestion-hibernate   3.15.0   ...
# chappie-ingestion-all         3.15.0   ...
```
