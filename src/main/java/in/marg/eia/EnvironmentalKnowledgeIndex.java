package in.marg.eia;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/** Page-preserving local PDF library and deterministic BM25 retriever. */
@Service
public class EnvironmentalKnowledgeIndex {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "from", "that", "this",
            "are", "was", "were", "has", "have", "had", "into", "about", "which", "their", "shall",
            "such", "under", "over", "within", "project", "proposal", "report", "section");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(km|kilomet(?:er|re)s?|m|met(?:er|re)s?|ha|hectares?|sq\\.?\\s*km|%|percent|years?|months?|days?)\\b");

    private final EnvironmentalKnowledgeProperties properties;
    private final PageTextChunker pageTextChunker;
    private volatile List<IndexedChunk> chunks = List.of();
    private volatile List<LibraryDocument> documents = List.of();
    private volatile String lastIndexError;
    private volatile boolean indexing;

    public EnvironmentalKnowledgeIndex(EnvironmentalKnowledgeProperties properties, PageTextChunker pageTextChunker) {
        this.properties = properties;
        this.pageTextChunker = pageTextChunker;
    }

    @PostConstruct
    public void initialize() {
        if (!properties.autoIndex()) return;
        indexing = true;
        Thread.startVirtualThread(() -> {
            try { reindex(); }
            catch (IOException exception) { lastIndexError = exception.getMessage(); }
        });
    }

    /** Indexes only PDFs directly inside the configured directory; nested application/build folders are ignored. */
    public synchronized IndexStatus reindex() throws IOException {
        indexing = true;
        try {
        Path directory = properties.knowledgeDirectory().toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IOException("Environmental knowledge directory does not exist: " + directory);
        }
        List<Path> pdfs;
        try (var paths = Files.list(directory)) {
            pdfs = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().toList();
        }
        List<IndexedChunk> nextChunks = new ArrayList<>();
        List<LibraryDocument> nextDocuments = new ArrayList<>();
        for (Path pdf : pdfs) {
            try {
                LibraryDocument document = readDocument(pdf, nextChunks);
                nextDocuments.add(document);
            } catch (IOException | RuntimeException exception) {
                // One damaged/encrypted PDF must not hide other usable library sources.
                nextDocuments.add(new LibraryDocument(documentId(pdf), pdf.getFileName().toString(),
                        classify(pdf.getFileName().toString()).documentClass(), 0, "UNREADABLE: " + exception.getMessage()));
            }
        }
        chunks = List.copyOf(nextChunks);
        documents = List.copyOf(nextDocuments);
        lastIndexError = null;
        indexing = false;
        return status();
        } finally {
            indexing = false;
        }
    }

    public IndexStatus status() {
        return new IndexStatus(properties.knowledgeDirectory().toAbsolutePath().normalize().toString(),
                documents.size(), chunks.size(), documents, lastIndexError, indexing);
    }

    public List<SearchHit> search(String query, int requestedLimit, boolean includeSampleReports) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> terms = tokenize(query).stream().filter(term -> !STOP_WORDS.contains(term)).distinct().toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        List<IndexedChunk> corpus = chunks.stream()
                .filter(chunk -> includeSampleReports || chunk.documentClass() != DocumentClass.SAMPLE_EIA)
                .toList();
        if (corpus.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> docFrequency = new HashMap<>();
        for (String term : terms) {
            int count = 0;
            for (IndexedChunk chunk : corpus) {
                if (chunk.termFrequency().containsKey(term)) count++;
            }
            docFrequency.put(term, count);
        }
        double averageLength = corpus.stream().mapToInt(IndexedChunk::tokenCount).average().orElse(1.0);
        int limit = requestedLimit < 1 ? properties.retrievalLimit() : Math.min(requestedLimit, 50);
        return corpus.stream().map(chunk -> {
                    double score = bm25(terms, docFrequency, chunk, corpus.size(), averageLength);
                    return new SearchHit(chunk.evidenceId(), chunk.documentId(), chunk.title(), chunk.fileName(),
                            chunk.pageNumber(), chunk.chunkId(), chunk.excerpt(), chunk.documentClass(),
                            chunk.sourceVersion(), chunk.jurisdiction(), chunk.applicability(), score,
                            extractNumericValues(chunk.excerpt() + " " + chunk.title()));
                })
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed()
                        .thenComparing(SearchHit::fileName).thenComparingInt(SearchHit::pageNumber))
                .limit(limit).toList();
    }

    public Optional<SearchHit> byEvidenceId(String evidenceId) {
        return chunks.stream().filter(chunk -> chunk.evidenceId().equals(evidenceId)).findFirst()
                .map(chunk -> new SearchHit(chunk.evidenceId(), chunk.documentId(), chunk.title(), chunk.fileName(),
                        chunk.pageNumber(), chunk.chunkId(), chunk.excerpt(), chunk.documentClass(),
                        chunk.sourceVersion(), chunk.jurisdiction(), chunk.applicability(), 0,
                        extractNumericValues(chunk.excerpt() + " " + chunk.title())));
    }

    private LibraryDocument readDocument(Path path, List<IndexedChunk> destination) throws IOException {
        String fileName = path.getFileName().toString();
        DocumentMetadata metadata = classify(fileName);
        String documentId = documentId(path);
        int pageCount;
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            pageCount = pdf.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(pageCount);
            stripper.setPageEnd("\f");
            String[] pageTexts = stripper.getText(pdf).split("\\f", -1);
            int globalChunk = 0;
            for (int page = 1; page <= Math.min(pageCount, pageTexts.length); page++) {
                String pageText = pageTexts[page - 1];
                if (pageText == null || pageText.isBlank()) continue;
                List<PageTextChunker.PageChunk> pageChunks = pageTextChunker.chunkPage(page, pageText,
                        properties.chunkSize(), properties.chunkOverlap(), globalChunk);
                for (PageTextChunker.PageChunk chunk : pageChunks) {
                    String chunkId = documentId + "-P" + page + "-C" + chunk.chunkIndex();
                    destination.add(indexChunk(documentId, fileName, metadata, page, chunkId, chunk.text()));
                }
                globalChunk += pageChunks.size();
            }
        }
        return new LibraryDocument(documentId, fileName, metadata.documentClass(), pageCount, null);
    }

    private IndexedChunk indexChunk(String documentId, String fileName, DocumentMetadata metadata,
                                    int page, String chunkId, String excerpt) {
        List<String> tokens = tokenize(metadata.title() + " " + fileName + " " + excerpt);
        Map<String, Integer> frequency = new HashMap<>();
        tokens.forEach(token -> frequency.merge(token, 1, Integer::sum));
        String evidenceId = "DOC-" + documentId.substring(4) + "-P" + page + "-C" + chunkId.substring(chunkId.lastIndexOf('-') + 1);
        return new IndexedChunk(evidenceId, documentId, metadata.title().isBlank() ? fileName : metadata.title(),
                fileName, page, chunkId, excerpt, metadata.documentClass(), metadata.version(), metadata.jurisdiction(),
                metadata.applicability(), tokens.size(), frequency);
    }

    private double bm25(List<String> terms, Map<String, Integer> df, IndexedChunk chunk, int n, double avgLength) {
        double score = 0;
        double k1 = 1.5;
        double b = 0.75;
        for (String term : terms) {
            int tf = chunk.termFrequency().getOrDefault(term, 0);
            if (tf == 0) continue;
            double idf = Math.log(1 + (n - df.getOrDefault(term, 0) + 0.5) / (df.getOrDefault(term, 0) + 0.5));
            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * chunk.tokenCount() / avgLength));
        }
        return score;
    }

    private DocumentMetadata classify(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        if (normalized.contains("sample") || normalized.contains("esia") || normalized.contains("eia report")) {
            return new DocumentMetadata(DocumentClass.SAMPLE_EIA,
                    "Sample EIA / ESIA report (format reference): " + fileName,
                    "Publication/version not verified from filename", "India / project-specific sample",
                    "Formatting reference only; exclude from project-fact and legal-applicability support.");
        }
        if (normalized.contains("eia bpg") || normalized.contains("best practice")) {
            return new DocumentMetadata(DocumentClass.INDIAN_GUIDANCE,
                    "Environmental Impact Assessment Guidance Manual for Highways: " + fileName,
                    "2017 indicated by filename; verify edition", "India",
                    "Guidance only; not itself a project-specific statutory approval.");
        }
        if (normalized.contains("eia notification")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "Environment Impact Assessment Notification, 2006 and amendments: " + fileName,
                    "Uploaded compilation covers amendments through March 2025; PARIVESH lists a consolidated compilation through July 2026 (23 July 2026), so this local version is out of date. Official portal: https://parivesh.nic.in/",
                    "India", "Candidate reference only; obtain current official text and assess project-specific triggers.");
        }
        if (normalized.contains("regulations air")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "Biological Diversity Rules, 2024 (Gazette notification dated 22 October 2024): " + fileName,
                    "Gazette No. 607, 22 October 2024 (filename is misleading)", "India",
                    "Candidate reference only; applicability depends on biological resources/access and project facts.");
        }
        if (normalized.contains("biodiversity")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "Biological Diversity (Amendment) Act, 2023: " + fileName,
                    "2023 amendment; current parent Act/rules must also be checked", "India",
                    "Candidate reference only; project-specific applicability not determined.");
        }
        if (normalized.contains("forest act.pdf")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "Compensatory Afforestation Fund Act, 2016: " + fileName,
                    "2016 Act; current rules and amendments require verification", "India",
                    "Potentially relevant if forest diversion/compensatory afforestation is involved; facts and authority review required.");
        }
        if (normalized.contains("forest act2")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "MoEFCC Gazette order under the Van (Sanrakshan Evam Samvardhan) Adhiniyam, 1980: " + fileName,
                    "S.O. 5075(E), 29 November 2023", "India",
                    "Reference concerns survey/forest-land treatment; verify current law and applicability to the proposed activity.");
        }
        if (normalized.contains("van")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "MoEFCC clarification concerning the Van (Sanrakshan Evam Samvardhan) Adhiniyam, 1980: " + fileName,
                    "2025 document; exact issue/date must be read from the source", "India",
                    "Candidate reference only; verify the current Act, rules, guidance and project-specific trigger.");
        }
        if (normalized.contains("wetland")) {
            return new DocumentMetadata(DocumentClass.LEGAL_SOURCE,
                    "Wetlands (Conservation and Management) Rules, 2017: " + fileName,
                    "G.S.R. 1203(E), 26 September 2017; current amendments require verification", "India",
                    "Potentially relevant if a notified wetland or regulated activity is involved; GIS proximity alone is not applicability.");
        }
        return new DocumentMetadata(DocumentClass.PROJECT_DOCUMENT, "Project document: " + fileName,
                "Version/date not supplied in index metadata", "Project-specific", "Claim applies only to supplied document context.");
    }

    private static List<String> tokenize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher matcher = WORD.matcher(normalized);
        List<String> words = new ArrayList<>();
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    private static String documentId(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(path.getFileName().toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "DOC-" + java.util.HexFormat.of().formatHex(digest).substring(0, 12).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<EnvironmentalReportModels.NumericValue> extractNumericValues(String excerpt) {
        List<EnvironmentalReportModels.NumericValue> values = new ArrayList<>();
        Matcher matcher = NUMBER_WITH_UNIT.matcher(excerpt);
        while (matcher.find()) {
            try {
                java.math.BigDecimal value = new java.math.BigDecimal(matcher.group(1).replace(",", ""));
                values.add(new EnvironmentalReportModels.NumericValue(value, normalizeUnit(matcher.group(2))));
            } catch (NumberFormatException ignored) { }
        }
        Matcher legalYear = Pattern.compile("(?i)(?:act|notification|rules|adhiniyam)\\s*,?\\s*((?:19|20)\\d{2})\\b").matcher(excerpt);
        while (legalYear.find()) {
            try { values.add(new EnvironmentalReportModels.NumericValue(new java.math.BigDecimal(legalYear.group(1)), "year")); }
            catch (NumberFormatException ignored) { }
        }
        return values.stream().distinct().toList();
    }

    private static String normalizeUnit(String raw) {
        String unit = raw.toLowerCase(Locale.ROOT).replace(" ", "").replace(".", "");
        if (unit.startsWith("kilomet")) return "km";
        if (unit.equals("m") || unit.startsWith("meter") || unit.startsWith("metre")) return "m";
        if (unit.equals("ha") || unit.startsWith("hectare")) return "ha";
        if (unit.startsWith("sqkm")) return "km2";
        if (unit.equals("percent")) return "%";
        if (unit.startsWith("year")) return "year";
        if (unit.startsWith("month")) return "months";
        if (unit.startsWith("day")) return "days";
        return unit;
    }

    public enum DocumentClass { LEGAL_SOURCE, INDIAN_GUIDANCE, PROJECT_DOCUMENT, SAMPLE_EIA }
    public record LibraryDocument(String documentId, String fileName, DocumentClass documentClass,
                                  int pages, String processingIssue) {}
    public record IndexStatus(String directory, int documentCount, int chunkCount,
                              List<LibraryDocument> documents, String lastIndexError, boolean indexing) {}
    public record SearchHit(String evidenceId, String documentId, String sourceTitle, String fileName,
                           int pageNumber, String chunkId, String excerpt, DocumentClass documentClass,
                           String sourceVersion, String jurisdiction, String applicability, double score,
                           List<EnvironmentalReportModels.NumericValue> numericValues) {}
    private record DocumentMetadata(DocumentClass documentClass, String title, String version,
                                    String jurisdiction, String applicability) {}
    private record IndexedChunk(String evidenceId, String documentId, String title, String fileName,
                                int pageNumber, String chunkId, String excerpt, DocumentClass documentClass,
                                String sourceVersion, String jurisdiction, String applicability,
                                int tokenCount, Map<String, Integer> termFrequency) {}
}
