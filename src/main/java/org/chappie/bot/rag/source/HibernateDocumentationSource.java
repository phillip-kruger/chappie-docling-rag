package org.chappie.bot.rag.source;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import io.quarkiverse.docling.runtime.client.DoclingService;

/**
 * Hibernate ORM documentation source - fetches and converts Hibernate documentation from docs.jboss.org
 */
public class HibernateDocumentationSource implements DocumentationSource {

    private static final Logger LOG = Logger.getLogger(HibernateDocumentationSource.class);
    private static final String LIBRARY_NAME = "hibernate-orm";

    private final String hibernateVersion;
    private final String quarkusVersion;

    /**
     * Mapping of Quarkus versions to Hibernate versions
     * TODO: This should be externalized to a configuration file or derived from Quarkus BOM
     */
    private static final Map<String, String> QUARKUS_TO_HIBERNATE_VERSION = Map.of(
        "3.15.0", "6.4.4.Final",
        "3.15.1", "6.4.4.Final",
        "3.16.0", "6.4.9.Final",
        "3.17.0", "6.6.1.Final",
        "3.18.0", "6.6.3.Final"
    );

    /**
     * Hibernate documentation sections to process
     */
    private static final List<DocSection> DOC_SECTIONS = List.of(
        new DocSection(
            "introduction",
            "Introduction to Hibernate ORM",
            "introduction/html_single/Hibernate_Introduction.html",
            "introduction, getting-started, overview"
        ),
        new DocSection(
            "userguide",
            "Hibernate User Guide",
            "userguide/html_single/Hibernate_User_Guide.html",
            "configuration, mapping, persistence, queries, transactions"
        ),
        new DocSection(
            "quickstart",
            "Hibernate Quickstart",
            "quickstart/html_single/Hibernate_Getting_Started.html",
            "quickstart, tutorial, getting-started"
        )
    );

    public HibernateDocumentationSource(String quarkusVersion) {
        this.quarkusVersion = quarkusVersion;
        this.hibernateVersion = resolveHibernateVersion(quarkusVersion);
    }

    /**
     * Constructor with explicit Hibernate version (for testing or manual override)
     */
    public HibernateDocumentationSource(String quarkusVersion, String hibernateVersion) {
        this.quarkusVersion = quarkusVersion;
        this.hibernateVersion = hibernateVersion;
    }

    private static String resolveHibernateVersion(String quarkusVersion) {
        // Try exact match
        String version = QUARKUS_TO_HIBERNATE_VERSION.get(quarkusVersion);
        if (version != null) {
            return version;
        }

        // Try major.minor match (e.g., "3.15.x" -> "3.15.0")
        if (quarkusVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            String majorMinor = quarkusVersion.substring(0, quarkusVersion.lastIndexOf('.'));
            for (Map.Entry<String, String> entry : QUARKUS_TO_HIBERNATE_VERSION.entrySet()) {
                if (entry.getKey().startsWith(majorMinor)) {
                    LOG.infof("[Hibernate] Using Hibernate %s for Quarkus %s (matched %s)",
                              entry.getValue(), quarkusVersion, entry.getKey());
                    return entry.getValue();
                }
            }
        }

        // Default fallback
        String defaultVersion = "6.4.4.Final";
        LOG.warnf("[Hibernate] No mapping found for Quarkus %s, using default Hibernate version: %s",
                  quarkusVersion, defaultVersion);
        return defaultVersion;
    }

    @Override
    public String getLibraryName() {
        return LIBRARY_NAME;
    }

    @Override
    public String getLibraryVersion() {
        return hibernateVersion;
    }

    @Override
    public void prepare(Path workDir) throws Exception {
        LOG.infof("=== Preparing Hibernate documentation (version: %s) ===", hibernateVersion);
        // No preparation needed - we fetch docs directly from docs.jboss.org
    }

    @Override
    public List<DocumentInfo> listDocuments(int maxDocuments) throws Exception {
        LOG.infof("=== Listing Hibernate documentation sections ===");

        List<DocSection> sectionsToProcess = DOC_SECTIONS;
        if (maxDocuments > 0 && DOC_SECTIONS.size() > maxDocuments) {
            sectionsToProcess = DOC_SECTIONS.subList(0, maxDocuments);
            LOG.infof("[Hibernate] Limiting to first %d sections (out of %d)", maxDocuments, DOC_SECTIONS.size());
        }

        List<DocumentInfo> documents = new ArrayList<>();
        for (DocSection section : sectionsToProcess) {
            Metadata metadata = new Metadata();
            metadata.put("library", LIBRARY_NAME);
            metadata.put("library_version", hibernateVersion);
            metadata.put("quarkus_version", quarkusVersion);
            metadata.put("title", section.title);
            metadata.put("topics", section.topics);
            metadata.put("categories", "orm,persistence,database,hibernate");

            // Hibernate is used by these Quarkus extensions
            metadata.put("extensions", "quarkus-hibernate-orm,quarkus-hibernate-orm-panache");

            // Add quarkus_extensions for compatibility with existing filtering
            metadata.put("quarkus_extensions", "quarkus-hibernate-orm,quarkus-hibernate-orm-panache");

            // Store URL pattern for processing
            String baseUrl = "https://docs.jboss.org/hibernate/orm/" + getVersionPath() + "/";
            metadata.put("_url_path", baseUrl + section.urlPath);

            documents.add(new DocumentInfo(section.id, section.title, metadata));
        }

        LOG.infof("[Hibernate] Found %d documentation sections to process", documents.size());
        return documents;
    }

    @Override
    public Document processDocument(DocumentInfo docInfo, DoclingService doclingService) throws Exception {
        String title = docInfo.title();
        Metadata metadata = docInfo.metadata();

        String url = metadata.getString("_url_path");
        if (url == null) {
            throw new IllegalStateException("Document URL not found in metadata for: " + title);
        }

        LOG.infof("[Hibernate] Fetching and converting: %s", url);

        try {
            URI uri = URI.create(url);
            ConvertDocumentResponse resp = doclingService.convertFromUri(uri, OutputFormat.MARKDOWN);
            String markdownContent = resp.getDocument().getMarkdownContent();

            metadata.put("url", url);
            metadata.remove("_url_path");

            LOG.infof("[Hibernate] Converted %s -> %d chars", url, markdownContent.length());

            return Document.from(markdownContent, metadata);
        } catch (Exception e) {
            LOG.errorf(e, "[Hibernate] Failed to fetch/convert %s", url);
            throw e;
        }
    }

    @Override
    public void cleanup() {
        // No cleanup needed
    }

    /**
     * Get version path for URL construction (e.g., "6.4" from "6.4.4.Final")
     */
    private String getVersionPath() {
        // Extract major.minor from version like "6.4.4.Final"
        String[] parts = hibernateVersion.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return hibernateVersion;
    }

    /**
     * Represents a Hibernate documentation section
     */
    private record DocSection(
        String id,
        String title,
        String urlPath,
        String topics
    ) {}
}
