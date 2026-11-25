package in.marg.eia;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Filesystem-backed Mode 2 projects with project-isolated, page-grounded BM25 retrieval. */
@Service
public class Mode2ProjectDocumentService {

    private static final long MAX_PDF_BYTES = 50L * 1024 * 1024;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern PROJECT_ID = Pattern.compile("M2P-[A-F0-9]{32}");
    private static final String PROJECTS_COLLECTION = "mode2_projects";
    private static final String DOCUMENTS_COLLECTION = "mode2_project_documents";
    private static final System.Logger LOGGER = System.getLogger(Mode2ProjectDocumentService.class.getName());
    private static final List<String> STOP_WORDS = List.of("the", "and", "for", "with", "from", "that", "this",
            "are", "was", "were", "has", "have", "had", "into", "about", "which", "their", "such", "under");

    private final Path projectsDirectory;
    private final PageTextChunker pageTextChunker;
    private final MongoTemplate mongo;
    private final ConcurrentHashMap<String, ProjectIndex> indexes = new ConcurrentHashMap<>();

    @Autowired
    public Mode2ProjectDocumentService(Mode2ProjectProperties properties, PageTextChunker pageTextChunker,
                                       MongoTemplate mongo) {
        this.projectsDirectory = properties.projectsDirectory().toAbsolutePath().normalize();
        this.pageTextChunker = pageTextChunker;
        this.mongo = mongo;
    }

    /** Filesystem-only constructor retained for isolated service tests. */
    public Mode2ProjectDocumentService(Mode2ProjectProperties properties, PageTextChunker pageTextChunker) {
        this(properties, pageTextChunker, null);
    }

    public synchronized ProjectRecord createProject(String name) throws IOException {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 200) {
            throw new IllegalArgumentException("Project name must contain 1 to 200 characters.");
        }
        Files.createDirectories(projectsDirectory);
        String projectId = "M2P-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        Path projectPath = projectPath(projectId);
        Files.createDirectories(projectPath.resolve("documents"));
        ProjectRecord project = new ProjectRecord(projectId, normalizedName, Instant.now().toString());
        writeProperties(projectPath.resolve("project.properties"), Map.of(
                "projectId", project.projectId(), "name", project.name(), "createdAt", project.createdAt()));
        mirrorProjectMetadata(project);
        indexes.put(projectId, new ProjectIndex(project, List.of(), List.of()));
        return project;
    }

    public List<ProjectRecord> listProjects() throws IOException {
        if (!Files.isDirectory(projectsDirectory)) return List.of();
        try (var directories = Files.list(projectsDirectory)) {
            List<ProjectRecord> projects = new ArrayList<>();
            for (Path path : directories.filter(Files::isDirectory).toList()) {
                Path manifest = path.resolve("project.properties");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    Properties values = readProperties(manifest);
                    ProjectRecord project = new ProjectRecord(values.getProperty("projectId"), values.getProperty("name"),
                            values.getProperty("createdAt"));
                    projects.add(project);
                } catch (RuntimeException exception) {
                    // A damaged project manifest should not prevent listing other workspaces.
                }
            }
            return projects.stream().sorted(Comparator.comparing(ProjectRecord::createdAt)).toList();
        }
    }

    public List<ProjectDocumentRecord> listDocuments(String projectId) throws IOException {
        return loadIndex(projectId).documents();
    }

    public ProjectRecord getProject(String projectId) throws IOException {
        return requireProject(projectId);
    }

    public synchronized ProjectDocumentRecord uploadDocument(String projectId, MultipartFile file) throws IOException {
        ProjectRecord project = requireProject(projectId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A non-empty PDF file is required.");
        if (file.getSize() > MAX_PDF_BYTES) throw new IllegalArgumentException("PDF files must be 50 MB or smaller.");
        String original = Path.of(file.getOriginalFilename() == null ? "" : file.getOriginalFilename())
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (original.isBlank() || !original.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF documents are accepted.");
        }

        Path documentDirectory = projectPath(projectId).resolve("documents");
        Files.createDirectories(documentDirectory);
        Path temporary = Files.createTempFile(documentDirectory, ".mode2-upload-", ".tmp");
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            long actualSize = Files.size(temporary);
            if (actualSize == 0 || actualSize > MAX_PDF_BYTES) {
                throw new IllegalArgumentException("PDF files must be non-empty and 50 MB or smaller.");
            }
            String digest = sha256(temporary);
            String documentId = "M2D-" + digest.substring(0, 24).toUpperCase(Locale.ROOT);
            Path pdfPath = documentDirectory.resolve(documentId + ".pdf");
            Path metadataPath = documentDirectory.resolve(documentId + ".properties");
            if (Files.isRegularFile(metadataPath) && Files.isRegularFile(pdfPath)) {
                Files.deleteIfExists(temporary);
                return readDocumentRecord(metadataPath);
            }

            Extraction extraction = extract(temporary, projectId, documentId, original);
            moveAtomically(temporary, pdfPath);
            ProjectDocumentRecord record = new ProjectDocumentRecord(projectId, documentId, original,
                    digest, extraction.pageCount(), extraction.chunks().size(), extraction.status(), Instant.now().toString());
            writeProperties(metadataPath, Map.of(
                    "projectId", projectId, "documentId", documentId, "fileName", original,
                    "sha256", digest, "pageCount", String.valueOf(record.pageCount()),
                    "chunkCount", String.valueOf(record.chunkCount()), "processingStatus", record.processingStatus(),
                    "uploadedAt", record.uploadedAt()));
            mirrorDocumentMetadata(record);
            List<ProjectDocumentRecord> documents = new ArrayList<>(loadIndex(projectId).documents());
            documents.add(record);
            List<ProjectChunk> chunks = new ArrayList<>(loadIndex(projectId).chunks());
            chunks.addAll(extraction.chunks());
            indexes.put(projectId, new ProjectIndex(project, List.copyOf(documents), List.copyOf(chunks)));
            return record;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized ProjectDocumentRecord uploadDocumentFromPath(String projectId, Path sourcePdf, String preferredFileName) throws IOException {
        ProjectRecord project = requireProject(projectId);
        if (!Files.isRegularFile(sourcePdf)) throw new IllegalArgumentException("Source PDF does not exist: " + sourcePdf);
        long size = Files.size(sourcePdf);
        if (size == 0 || size > MAX_PDF_BYTES) throw new IllegalArgumentException("PDF must be non-empty and 50 MB or smaller.");
        String original = (preferredFileName == null || preferredFileName.isBlank() ? sourcePdf.getFileName().toString() : preferredFileName)
                .replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        Path documentDirectory = projectPath(projectId).resolve("documents");
        Files.createDirectories(documentDirectory);
        Path temporary = Files.createTempFile(documentDirectory, ".mode2-upload-", ".tmp");
        try {
            Files.copy(sourcePdf, temporary, StandardCopyOption.REPLACE_EXISTING);
            String digest = sha256(temporary);
            String documentId = "M2D-" + digest.substring(0, 24).toUpperCase(Locale.ROOT);
            Path pdfPath = documentDirectory.resolve(documentId + ".pdf");
            Path metadataPath = documentDirectory.resolve(documentId + ".properties");
            if (Files.isRegularFile(metadataPath) && Files.isRegularFile(pdfPath)) {
                Files.deleteIfExists(temporary);
                return readDocumentRecord(metadataPath);
            }
            Extraction extraction = extract(temporary, projectId, documentId, original);
            moveAtomically(temporary, pdfPath);
            ProjectDocumentRecord record = new ProjectDocumentRecord(projectId, documentId, original,
                    digest, extraction.pageCount(), extraction.chunks().size(), extraction.status(), Instant.now().toString());
            writeProperties(metadataPath, Map.of(
                    "projectId", projectId, "documentId", documentId, "fileName", original,
                    "sha256", digest, "pageCount", String.valueOf(record.pageCount()),
                    "chunkCount", String.valueOf(record.chunkCount()), "processingStatus", record.processingStatus(),
                    "uploadedAt", record.uploadedAt()));
            mirrorDocumentMetadata(record);
            List<ProjectDocumentRecord> documents = new ArrayList<>(loadIndex(projectId).documents());
            documents.add(record);
            List<ProjectChunk> chunks = new ArrayList<>(loadIndex(projectId).chunks());
            chunks.addAll(extraction.chunks());
            indexes.put(projectId, new ProjectIndex(project, List.copyOf(documents), List.copyOf(chunks)));
            return record;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public List<ProjectSearchHit> search(String projectId, String query, int requestedLimit) throws IOException {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required.");
        ProjectIndex index = loadIndex(projectId);
        List<String> terms = tokenize(query).stream().filter(term -> !STOP_WORDS.contains(term)).distinct().toList();
        if (terms.isEmpty() || index.chunks().isEmpty()) return List.of();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String term : terms) {
            int count = 0;
            for (ProjectChunk chunk : index.chunks()) if (chunk.termFrequency().containsKey(term)) count++;
            documentFrequency.put(term, count);
        }
        double averageLength = index.chunks().stream().mapToInt(ProjectChunk::tokenCount).average().orElse(1.0);
        int limit = requestedLimit < 1 ? 8 : Math.min(requestedLimit, 50);
        return index.chunks().stream().map(chunk -> new ProjectSearchHit(chunk.evidenceId(), projectId,
                        chunk.documentId(), chunk.sourceTitle(), chunk.fileName(), chunk.pageNumber(), chunk.chunkId(),
                        chunk.excerpt(), score(terms, documentFrequency, chunk, index.chunks().size(), averageLength)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(ProjectSearchHit::score).reversed()
                        .thenComparing(ProjectSearchHit::fileName).thenComparingInt(ProjectSearchHit::pageNumber))
                .limit(limit).toList();
    }

    public ProjectSearchResponse searchResponse(String projectId, String query, int limit) throws IOException {
        ProjectIndex index = loadIndex(projectId);
        return new ProjectSearchResponse(projectId, query, search(projectId, query, limit), index.documents().size(), index.chunks().size());
    }

    private ProjectIndex loadIndex(String projectId) throws IOException {
        validateProjectId(projectId);
        ProjectIndex cached = indexes.get(projectId);
        if (cached != null) return cached;
        synchronized (this) {
            cached = indexes.get(projectId);
            if (cached != null) return cached;
            ProjectRecord project = requireProject(projectId);
            Path documentsDirectory = projectPath(projectId).resolve("documents");
            List<ProjectDocumentRecord> documents = new ArrayList<>();
            List<ProjectChunk> chunks = new ArrayList<>();
            if (Files.isDirectory(documentsDirectory)) {
                try (var paths = Files.list(documentsDirectory)) {
                    for (Path metadata : paths.filter(path -> path.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                        ProjectDocumentRecord record = readDocumentRecord(metadata);
                        Path pdf = documentsDirectory.resolve(record.documentId() + ".pdf");
                        if (!Files.isRegularFile(pdf)) continue;
                        documents.add(record);
                        if ("INDEXED".equals(record.processingStatus())) {
                            chunks.addAll(extract(pdf, projectId, record.documentId(), record.fileName()).chunks());
                        }
                    }
                }
            }
            cached = new ProjectIndex(project, List.copyOf(documents), List.copyOf(chunks));
            indexes.put(projectId, cached);
            return cached;
        }
    }

    private ProjectRecord requireProject(String projectId) throws IOException {
        validateProjectId(projectId);
        Path manifest = projectPath(projectId).resolve("project.properties");
        if (!Files.isRegularFile(manifest)) throw new IllegalArgumentException("Mode 2 project was not found.");
        Properties values = readProperties(manifest);
        return new ProjectRecord(values.getProperty("projectId"), values.getProperty("name"), values.getProperty("createdAt"));
    }

    private Extraction extract(Path pdfPath, String projectId, String documentId, String fileName) throws IOException {
        List<ProjectChunk> chunks = new ArrayList<>();
        int pages;
        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            pages = pdf.getNumberOfPages();
            if (pages == 0) throw new IllegalArgumentException("The PDF contains no pages.");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf);
                if (text == null || text.isBlank()) continue;
                chunkPage(projectId, documentId, fileName, page, text.trim(), chunks);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Uploaded file is not a readable PDF.", exception);
        }
        return new Extraction(pages, List.copyOf(chunks), chunks.isEmpty() ? "NO_EXTRACTABLE_TEXT" : "INDEXED");
    }

    private void chunkPage(String projectId, String documentId, String fileName, int page, String text,
                           List<ProjectChunk> destination) {
        List<PageTextChunker.PageChunk> pageChunks = pageTextChunker.chunkPage(page, text);
        for (PageTextChunker.PageChunk pageChunk : pageChunks) {
            int chunkIndex = pageChunk.chunkIndex();
            String excerpt = pageChunk.text();
            String chunkId = documentId + "-P" + page + "-C" + chunkIndex;
            Map<String, Integer> frequencies = new HashMap<>();
            List<String> words = tokenize(fileName + " " + excerpt);
            words.forEach(word -> frequencies.merge(word, 1, Integer::sum));
            destination.add(new ProjectChunk("M2-EV-" + projectId.substring(4) + "-" + documentId.substring(4)
                    + "-P" + page + "-C" + chunkIndex,
                    projectId, documentId, stripExtension(fileName), fileName, page, chunkId, excerpt,
                    words.size(), Map.copyOf(frequencies)));
        }
    }

    private double score(List<String> terms, Map<String, Integer> df, ProjectChunk chunk, int count, double averageLength) {
        double result = 0;
        for (String term : terms) {
            int frequency = chunk.termFrequency().getOrDefault(term, 0);
            if (frequency == 0) continue;
            int termDocuments = df.getOrDefault(term, 0);
            double idf = Math.log(1 + (count - termDocuments + 0.5) / (termDocuments + 0.5));
            double k1 = 1.5;
            double b = 0.75;
            result += idf * (frequency * (k1 + 1)) /
                    (frequency + k1 * (1 - b + b * chunk.tokenCount() / averageLength));
        }
        return result;
    }

    private static List<String> tokenize(String text) {
        Matcher matcher = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    private Path projectPath(String projectId) {
        validateProjectId(projectId);
        Path path = projectsDirectory.resolve(projectId).normalize();
        if (!path.startsWith(projectsDirectory)) throw new IllegalArgumentException("Invalid project ID.");
        return path;
    }

    private static void validateProjectId(String projectId) {
        if (projectId == null || !PROJECT_ID.matcher(projectId).matches()) throw new IllegalArgumentException("Invalid Mode 2 project ID.");
    }

    private static ProjectDocumentRecord readDocumentRecord(Path path) throws IOException {
        Properties values = readProperties(path);
        return new ProjectDocumentRecord(values.getProperty("projectId"), values.getProperty("documentId"),
                values.getProperty("fileName"), values.getProperty("sha256"),
                Integer.parseInt(values.getProperty("pageCount", "0")),
                Integer.parseInt(values.getProperty("chunkCount", "0")),
                values.getProperty("processingStatus"), values.getProperty("uploadedAt"));
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) { values.load(input); }
        return values;
    }

    private static void writeProperties(Path path, Map<String, String> values) throws IOException {
        Properties properties = new Properties();
        properties.putAll(values);
        Path temporary = Files.createTempFile(path.getParent(), ".mode2-metadata-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { properties.store(output, "MARG Mode 2 metadata"); }
            moveAtomically(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String stripExtension(String fileName) { return fileName.replaceFirst("(?i)\\.pdf$", ""); }

    /** Mongo stores searchable project/document metadata asynchronously; source PDF bytes remain in the configured file store. */
    private void mirrorProjectMetadata(ProjectRecord project) {
        if (mongo == null) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                mongo.save(new Document("_id", project.projectId()).append("projectId", project.projectId())
                        .append("name", project.name()).append("createdAt", project.createdAt()), PROJECTS_COLLECTION);
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.DEBUG, "Could not mirror Mode 2 project metadata to MongoDB: " + exception.getMessage());
            }
        });
    }

    private void mirrorDocumentMetadata(ProjectDocumentRecord document) {
        if (mongo == null) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String storagePath = projectPath(document.projectId()).resolve("documents")
                        .resolve(document.documentId() + ".pdf").toString();
                mongo.save(new Document("_id", document.documentId()).append("projectId", document.projectId())
                        .append("documentId", document.documentId()).append("fileName", document.fileName())
                        .append("sha256", document.sha256()).append("pageCount", document.pageCount())
                        .append("chunkCount", document.chunkCount()).append("processingStatus", document.processingStatus())
                        .append("uploadedAt", document.uploadedAt()).append("storagePath", storagePath), DOCUMENTS_COLLECTION);
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.DEBUG, "Could not mirror Mode 2 document metadata to MongoDB: " + exception.getMessage());
            }
        });
    }

    public record ProjectRecord(String projectId, String name, String createdAt) {}
    public record ProjectDocumentRecord(String projectId, String documentId, String fileName, String sha256,
                                        int pageCount, int chunkCount, String processingStatus, String uploadedAt) {}
    public record ProjectSearchHit(String evidenceId, String projectId, String documentId, String sourceTitle,
                                   String fileName, int pageNumber, String chunkId, String excerpt, double score) {}
    public record ProjectSearchResponse(String projectId, String query, List<ProjectSearchHit> results,
                                        int documentCount, int searchableChunkCount) {}

    private record Extraction(int pageCount, List<ProjectChunk> chunks, String status) {}
    private record ProjectIndex(ProjectRecord project, List<ProjectDocumentRecord> documents, List<ProjectChunk> chunks) {}
    private record ProjectChunk(String evidenceId, String projectId, String documentId, String sourceTitle,
                                String fileName, int pageNumber, String chunkId, String excerpt, int tokenCount,
                                Map<String, Integer> termFrequency) {}
}
