package org.chappie.bot.rag.source;

import java.nio.file.Path;
import java.util.List;

import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import io.quarkiverse.docling.runtime.client.DoclingService;

/**
 * Interface for different documentation sources (Quarkus, Hibernate, SmallRye, etc.)
 */
public interface DocumentationSource {

    /**
     * Name of the library/project (e.g., "quarkus", "hibernate-orm")
     */
    String getLibraryName();

    /**
     * Version of the library being processed
     */
    String getLibraryVersion();

    /**
     * Prepare the documentation source (clone repo, download docs, etc.)
     *
     * @param workDir Working directory for temporary files
     * @throws Exception if preparation fails
     */
    void prepare(Path workDir) throws Exception;

    /**
     * Get list of documents to process
     *
     * @param maxDocuments Maximum number of documents (0 = all)
     * @return List of document identifiers
     */
    List<DocumentInfo> listDocuments(int maxDocuments) throws Exception;

    /**
     * Process a single document: fetch content, convert with Docling, add metadata
     *
     * @param docInfo Document information
     * @param doclingService Docling service for conversion
     * @return Processed document ready for ingestion
     */
    Document processDocument(DocumentInfo docInfo, DoclingService doclingService) throws Exception;

    /**
     * Cleanup resources
     */
    void cleanup();

    /**
     * Information about a document to be processed
     */
    record DocumentInfo(
        String id,           // Unique identifier (e.g., filename, URL path)
        String title,        // Human-readable title
        Metadata metadata    // Pre-populated metadata (library, version, etc.)
    ) {}
}
