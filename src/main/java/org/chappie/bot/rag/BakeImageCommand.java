package org.chappie.bot.rag;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.Platform;

import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import io.quarkiverse.docling.runtime.client.DoclingService;
import jakarta.inject.Inject;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import org.chappie.bot.rag.source.DocumentationSource;
import org.chappie.bot.rag.source.DocumentationSource.DocumentInfo;
import org.chappie.bot.rag.source.QuarkusDocumentationSource;
import org.chappie.bot.rag.source.HibernateDocumentationSource;

/**
 * Multi-source documentation ingestion command to build pgvector database images.
 *
 * Supports multiple documentation sources:
 * - QUARKUS: Quarkus guides (AsciiDoc metadata + Docling HTML conversion)
 * - HIBERNATE: Hibernate ORM documentation (Docling HTML conversion)
 *
 * Process:
 * 1. Prepares documentation source (clone repo, fetch docs, etc.)
 * 2. Lists documents to process
 * 3. Fetches and converts each document using Docling
 * 4. Adds library metadata for filtering
 * 5. Ingests into pgvector and bakes a Docker image
 */
@Command(
    name = "bake-image",
    mixinStandardHelpOptions = true,
    description = "Build pgvector database images with documentation from various sources (Quarkus, Hibernate, etc.)"
)
public class BakeImageCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(BakeImageCommand.class);
    private static final String DB_NAME = "postgres";
    private static final String DOCLING_IMAGE = "ghcr.io/docling-project/docling-serve:v1.10.0";
    private static final int EMBEDDING_DIMENSIONS = 384; // BGE Small EN v15

    @Option(names = "--quarkus-version", required = true,
            description = "Target Quarkus version (e.g., 3.30.6)")
    String quarkusVersion;

    @Option(names = "--doc-source", defaultValue = "QUARKUS",
            description = "Documentation source: QUARKUS, HIBERNATE, or ALL (default: ${DEFAULT-VALUE})")
    DocSource docSource;

    @Option(names = "--chunk-size", defaultValue = "1000",
            description = "Splitter chunk size (default: ${DEFAULT-VALUE})")
    int chunkSize;

    @Option(names = "--chunk-overlap", defaultValue = "300",
            description = "Splitter chunk overlap (default: ${DEFAULT-VALUE})")
    int chunkOverlap;

    @Option(names = "--semantic",
            description = "Use semantic chunking (split by AsciiDoc/Markdown headers) instead of fixed-size chunks")
    boolean semanticChunking;

    @Option(names = "--push",
            description = "Push to remote registry instead of loading to local Docker daemon")
    boolean push;
    
    @Option(names = "--registry-username",
            description = "Registry username (used only with --push)")
    String registryUsername;

    @Option(names = "--registry-password",
            description = "Registry password (used only with --push)")
    String registryPassword;

    @Option(names = "--latest",
            description = "Tag this as the latest image")
    boolean latest;

    @Option(names = "--base-image", defaultValue = "pgvector/pgvector:pg16",
            description = "Base image for final image (default: ${DEFAULT-VALUE})")
    String baseImageRef;

    @Option(names = "--max-guides", defaultValue = "0",
            description = "Maximum number of guides to process (0 = all, useful for testing)")
    int maxGuides;

    @Inject
    DoclingService doclingService;

    private PostgreSQLContainer<?> pgContainer;
    private GenericContainer<?> doclingContainer;

    /**
     * Documentation source types
     */
    public enum DocSource {
        QUARKUS,    // Quarkus guides
        HIBERNATE,  // Hibernate ORM documentation
        ALL         // All sources (sequential processing)
    }

    @Override
    public void run() {
        long t0 = System.nanoTime();
        LOG.infof("[bake-image] Started at %s", Instant.now());
        LOG.infof("[bake-image] Quarkus version: %s", quarkusVersion);
        LOG.infof("[bake-image] Documentation source: %s", docSource);
        LOG.infof("[bake-image] Chunk size: %d, overlap: %d, semantic: %s",
                  chunkSize, chunkOverlap, semanticChunking);

        Path workDir = null;
        try {
            // 1) Start Docling Serve container on fixed port 5001
            LOG.info("=== Starting Docling Serve container ===");
            this.doclingContainer = new GenericContainer<>(DockerImageName.parse(DOCLING_IMAGE))
                    .withExposedPorts(5001)
                    .withCreateContainerCmdModifier(cmd -> {
                        cmd.withHostConfig(
                            new HostConfig().withPortBindings(
                                new PortBinding(Ports.Binding.bindPort(5001), new ExposedPort(5001))
                            )
                        );
                    })
                    .waitingFor(Wait.forHttp("/health").forPort(5001));
            this.doclingContainer.start();
            LOG.info("[bake-image] Docling Serve started at: http://localhost:5001");

            // 2) Start pgvector container
            LOG.info("=== Starting pgvector container ===");
            this.pgContainer = new PostgreSQLContainer<>(DockerImageName.parse(this.baseImageRef))
                    .withDatabaseName(DB_NAME)
                    .withUsername("postgres")
                    .withPassword("postgres");
            this.pgContainer.start();

            String jdbcUrl = this.pgContainer.getJdbcUrl();
            String user = this.pgContainer.getUsername();
            String pass = this.pgContainer.getPassword();
            LOG.infof("[bake-image] PGVector started: %s", jdbcUrl);

            // 3) Setup embedding store and model
            LOG.info("=== Setting up embedding infrastructure ===");
            DataSource ds = makeDataSource(jdbcUrl, user, pass);

            PgVectorEmbeddingStore store = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(ds)
                    .table("rag_documents")
                    .dimension(EMBEDDING_DIMENSIONS)
                    .useIndex(true)
                    .indexListSize(100)
                    .build();

            EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();

            DocumentSplitter splitter;
            if (semanticChunking) {
                LOG.infof("[bake-image] Using semantic chunking (Markdown headers), max chunk=%d", chunkSize);
                splitter = new MarkdownSemanticSplitter(chunkSize, chunkOverlap);
            } else {
                LOG.infof("[bake-image] Using recursive chunking, size=%d, overlap=%d", chunkSize, chunkOverlap);
                splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
            }

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .documentSplitter(splitter)
                    .build();

            // 4) Create work directory for temporary files
            workDir = Files.createTempDirectory("rag-bake-" + System.nanoTime());

            // 5) Process documentation sources
            List<DocumentationSource> sources = createDocumentationSources();

            for (DocumentationSource source : sources) {
                try {
                    LOG.infof("=== Processing documentation source: %s ===", source.getLibraryName());

                    // Prepare source (clone repos, etc.)
                    source.prepare(workDir);

                    // List documents to process
                    List<DocumentInfo> documents = source.listDocuments(maxGuides);
                    LOG.infof("[%s] Found %d documents to process",
                              source.getLibraryName(), documents.size());

                    // Process each document
                    int processed = 0;
                    int total = documents.size();

                    for (DocumentInfo docInfo : documents) {
                        try {
                            Document doc = source.processDocument(docInfo, doclingService);
                            ingestor.ingest(doc);

                            processed++;
                            if (processed % 10 == 0 || processed == total) {
                                LOG.infof("[%s] Processed %d / %d documents",
                                          source.getLibraryName(), processed, total);
                            }
                        } catch (Exception e) {
                            LOG.errorf(e, "[%s] Failed to process %s - skipping",
                                       source.getLibraryName(), docInfo.title());
                        }
                    }

                    LOG.infof("[%s] Successfully ingested %d / %d documents",
                              source.getLibraryName(), processed, total);

                    // Cleanup source resources
                    source.cleanup();

                } catch (Exception e) {
                    LOG.errorf(e, "[bake-image] Failed to process source %s - skipping",
                               source.getLibraryName());
                }
            }

            // 6) Dump database to SQL
            LOG.info("=== Dumping database ===");
            Path initDir = Files.createDirectories(workDir.resolve("init"));
            Path dump = initDir.resolve("01-rag.sql");

            // Dump inside container to /tmp/rag.sql then copy to host
            String inside = "/tmp/rag.sql";
            var result = this.pgContainer.execInContainer(
                    "bash", "-lc",
                    "PGPASSWORD=" + this.pgContainer.getPassword() +
                            " pg_dump -U " + this.pgContainer.getUsername() +
                            " -d " + DB_NAME +
                            " --no-owner --no-privileges --format=plain -f " + inside
            );

            if (result.getExitCode() != 0) {
                throw new IllegalStateException("pg_dump failed: " + result.getStderr());
            }

            this.pgContainer.copyFileFromContainer(inside, dump.toString());
            LOG.infof("[bake-image] Dumped SQL -> %s", dump);

            // 7) Build and push the image with Jib
            LOG.info("=== Building Docker image ===");
            FileEntriesLayer initLayer = FileEntriesLayer.builder()
                    .setName("initdb-sql")
                    .addEntryRecursive(initDir, AbsoluteUnixPath.get("/docker-entrypoint-initdb.d"))
                    .build();

            JibContainerBuilder jib = Jib.from(baseImageRef).addFileEntriesLayer(initLayer);

            String targetImageRef = buildImageReference();
            LOG.infof("[bake-image] Creating image: %s", targetImageRef);

            Containerizer containerizer;
            if (push) {
                // Multi-platform
                jib.setPlatforms(Set.of(new Platform("amd64", "linux"), new Platform("arm64", "linux")));

                RegistryImage registry = RegistryImage.named(targetImageRef);
                if (registryUsername != null && registryPassword != null) {
                    registry.addCredential(registryUsername, registryPassword);
                }
                containerizer = Containerizer.to(registry);
            } else {
                containerizer = Containerizer.to(DockerDaemonImage.named(targetImageRef));
            }

            if (latest) {
                LOG.info("[bake-image] Also tagging as :latest");
                containerizer.withAdditionalTag("latest");
            }

            containerizer
                    .setToolName("bake-image")
                    .setAllowInsecureRegistries(false);

            jib.containerize(containerizer);
            LOG.infof("[bake-image] Image ready: %s", targetImageRef);

        } catch (Exception e) {
            LOG.error("[bake-image] Failed", e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        } finally {
            // Cleanup
            if (doclingContainer != null) {
                LOG.info("[bake-image] Stopping Docling container");
                try {
                    doclingContainer.stop();
                } catch (Throwable t) {
                    LOG.warn("Failed to stop Docling container", t);
                }
            }
            if (pgContainer != null) {
                LOG.info("[bake-image] Stopping PGVector container");
                try {
                    pgContainer.stop();
                } catch (Throwable t) {
                    LOG.warn("Failed to stop PGVector container", t);
                }
            }
            if (workDir != null) {
                try {
                    deleteRecursive(workDir);
                } catch (Throwable ignore) {
                }
            }

            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.infof("[bake-image] Completed in %d ms (%.2f minutes)", ms, ms / 60000.0);
        }
    }

    /**
     * Create documentation sources based on --doc-source flag
     */
    private List<DocumentationSource> createDocumentationSources() {
        List<DocumentationSource> sources = new ArrayList<>();

        switch (docSource) {
            case QUARKUS:
                sources.add(new QuarkusDocumentationSource(quarkusVersion));
                break;
            case HIBERNATE:
                sources.add(new HibernateDocumentationSource(quarkusVersion));
                break;
            case ALL:
                sources.add(new QuarkusDocumentationSource(quarkusVersion));
                sources.add(new HibernateDocumentationSource(quarkusVersion));
                break;
        }

        return sources;
    }

    /**
     * Build Docker image reference based on doc source
     */
    private String buildImageReference() {
        String libraryName = switch (docSource) {
            case QUARKUS -> "quarkus";
            case HIBERNATE -> "hibernate";
            case ALL -> "all";
        };

        return "ghcr.io/quarkusio/chappie-ingestion-" + libraryName + ":" + quarkusVersion;
    }

    private static DataSource makeDataSource(String jdbc, String user, String pass) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(jdbc);
        ds.setUser(user);
        ds.setPassword(pass);
        return ds;
    }

    private static String extractTitleFromUrl(String url) {
        String[] parts = url.split("/");
        if (parts.length > 0) {
            String last = parts[parts.length - 1];
            return last.isEmpty() && parts.length > 1 ? parts[parts.length - 2] : last;
        }
        return "unknown";
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            List<Path> paths = s.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
