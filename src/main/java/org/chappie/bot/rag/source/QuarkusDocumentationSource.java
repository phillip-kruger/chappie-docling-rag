package org.chappie.bot.rag.source;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.chappie.bot.rag.AsciiDocMetadataExtractor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jboss.logging.Logger;

import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import io.quarkiverse.docling.runtime.client.DoclingService;

/**
 * Quarkus documentation source - hybrid approach using AsciiDoc metadata and Docling HTML conversion
 */
public class QuarkusDocumentationSource implements DocumentationSource {

    private static final Logger LOG = Logger.getLogger(QuarkusDocumentationSource.class);
    private static final String LIBRARY_NAME = "quarkus";

    private final String quarkusVersion;
    private Path quarkusRepoDir;
    private String versionForUrl;

    public QuarkusDocumentationSource(String quarkusVersion) {
        this.quarkusVersion = quarkusVersion;
    }

    @Override
    public String getLibraryName() {
        return LIBRARY_NAME;
    }

    @Override
    public String getLibraryVersion() {
        return quarkusVersion;
    }

    @Override
    public void prepare(Path workDir) throws Exception {
        LOG.info("=== Cloning Quarkus repository ===");
        quarkusRepoDir = workDir.resolve("quarkus-repo");
        Files.createDirectories(quarkusRepoDir);

        LOG.infof("[Quarkus] Cloning quarkusio/quarkus to: %s", quarkusRepoDir);

        try {
            Git git = Git.cloneRepository()
                    .setURI("https://github.com/quarkusio/quarkus.git")
                    .setDirectory(quarkusRepoDir.toFile())
                    .setBranch("refs/tags/" + quarkusVersion)
                    .setDepth(1)  // Shallow clone for faster download
                    .call();
            git.close();

            LOG.infof("[Quarkus] Cloned Quarkus %s successfully", quarkusVersion);
        } catch (GitAPIException e) {
            LOG.errorf(e, "[Quarkus] Failed to clone Quarkus repository at tag %s", quarkusVersion);
            throw new RuntimeException("Git clone failed", e);
        }

        // Determine version string for HTML URLs (e.g., "3.15" from "3.15.0")
        versionForUrl = quarkusVersion;
        if (versionForUrl.matches("\\d+\\.\\d+\\.\\d+")) {
            // Extract major.minor from major.minor.patch
            versionForUrl = versionForUrl.substring(0, versionForUrl.lastIndexOf('.'));
        }
        LOG.infof("[Quarkus] Using version %s for HTML URLs", versionForUrl);
    }

    @Override
    public List<DocumentInfo> listDocuments(int maxDocuments) throws Exception {
        LOG.info("=== Finding AsciiDoc guides in cloned repository ===");
        Path docsDir = quarkusRepoDir.resolve("docs/src/main/asciidoc");
        List<Path> adocFiles = new ArrayList<>();

        try (var stream = Files.walk(docsDir)) {
            stream.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".adoc"))
                 .filter(p -> !p.getFileName().toString().startsWith("_"))  // Exclude includes
                 .filter(p -> !p.toString().contains("/includes/"))  // Exclude includes directory
                 .filter(p -> !p.toString().contains("/_includes/"))  // Exclude _includes directory
                 .filter(p -> !p.toString().contains("/_templates/"))  // Exclude _templates directory
                 .forEach(adocFiles::add);
        }

        adocFiles.sort(Comparator.comparing(Path::toString));

        if (maxDocuments > 0 && adocFiles.size() > maxDocuments) {
            LOG.infof("[Quarkus] Limiting to first %d guides (out of %d)", maxDocuments, adocFiles.size());
            adocFiles = adocFiles.subList(0, maxDocuments);
        }

        LOG.infof("[Quarkus] Found %d AsciiDoc guides to process", adocFiles.size());

        // Convert to DocumentInfo
        List<DocumentInfo> documents = new ArrayList<>();
        for (Path adocPath : adocFiles) {
            Metadata metadata = new Metadata();
            metadata.put("library", LIBRARY_NAME);
            metadata.put("library_version", quarkusVersion);
            metadata.put("quarkus_version", quarkusVersion);

            // Set repo_path (relative path from repo root)
            String repoPath = quarkusRepoDir.relativize(adocPath).toString();
            metadata.put("repo_path", repoPath);

            // Extract title from filename
            String fileName = adocPath.getFileName().toString();
            String title = fileName.substring(0, fileName.lastIndexOf('.'));
            metadata.put("title", title);

            // Extract AsciiDoc metadata (topics, categories, extensions, summary)
            Map<String, String> adocMeta = AsciiDocMetadataExtractor.extractMetadata(adocPath);

            // Add topics (most important for matching!)
            String topics = adocMeta.get("topics");
            if (topics != null && !topics.isEmpty()) {
                metadata.put("topics", topics);
            }

            // Add categories
            String categories = adocMeta.get("categories");
            if (categories != null && !categories.isEmpty()) {
                metadata.put("categories", categories);
            }

            // Add extensions
            String extensions = adocMeta.get("extensions");
            if (extensions != null && !extensions.isEmpty()) {
                metadata.put("extensions", extensions);
            }

            // Add summary
            String summary = adocMeta.get("summary");
            if (summary != null && !summary.isEmpty()) {
                metadata.put("summary", summary);
            }

            // Store path for later processing
            metadata.put("_adoc_path", adocPath.toString());

            documents.add(new DocumentInfo(title, title, metadata));
        }

        return documents;
    }

    @Override
    public Document processDocument(DocumentInfo docInfo, DoclingService doclingService) throws Exception {
        String title = docInfo.title();
        Metadata metadata = docInfo.metadata();

        // Build versioned HTML URL
        String htmlUrl = "https://quarkus.io/version/" + versionForUrl + "/guides/" + title;

        // Use Docling to fetch and convert HTML from quarkus.io to Markdown
        // Try versioned URL first, fallback to latest if it fails
        ConvertDocumentResponse resp = null;
        String actualUrl = htmlUrl;
        try {
            URI htmlUri = URI.create(htmlUrl);
            resp = doclingService.convertFromUri(htmlUri, OutputFormat.MARKDOWN);
            LOG.infof("[Quarkus] Fetched versioned URL: %s", htmlUrl);
        } catch (Exception e) {
            // Fallback to latest (non-versioned) URL
            String latestUrl = "https://quarkus.io/guides/" + title;
            LOG.warnf("[Quarkus] Versioned URL failed (%s), trying latest URL: %s",
                      e.getMessage(), latestUrl);
            try {
                URI latestUri = URI.create(latestUrl);
                resp = doclingService.convertFromUri(latestUri, OutputFormat.MARKDOWN);
                actualUrl = latestUrl;
                LOG.infof("[Quarkus] Successfully fetched latest URL: %s", latestUrl);
            } catch (Exception fallbackEx) {
                // Both URLs failed, re-throw to be caught by outer exception handler
                LOG.errorf(fallbackEx, "[Quarkus] Both versioned and latest URLs failed for %s", title);
                throw fallbackEx;
            }
        }

        String markdownContent = resp.getDocument().getMarkdownContent();
        metadata.put("url", actualUrl);

        // Remove internal metadata
        metadata.remove("_adoc_path");

        LOG.infof("[Quarkus] Converted %s -> %d chars", actualUrl, markdownContent.length());

        return Document.from(markdownContent, metadata);
    }

    @Override
    public void cleanup() {
        // Git repo cleanup is handled by the main command (temp directory deletion)
    }
}
