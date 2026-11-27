package in.marg.eia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated endpoints for adding PDFs to and inspecting the local environmental library. */
@RestController
@RequestMapping("/api/eia/library")
public class EnvironmentalLibraryController {

    private static final long MAX_PDF_BYTES = 50L * 1024 * 1024;
    private final EnvironmentalKnowledgeProperties properties;
    private final EnvironmentalKnowledgeIndex index;

    public EnvironmentalLibraryController(EnvironmentalKnowledgeProperties properties, EnvironmentalKnowledgeIndex index) {
        this.properties = properties;
        this.index = index;
    }

    @GetMapping
    public EnvironmentalKnowledgeIndex.IndexStatus status() {
        return index.status();
    }

    @PostMapping("/documents")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A non-empty PDF file is required.");
        if (file.getSize() > MAX_PDF_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF files must be 50 MB or smaller.");
        String originalName = Path.of(file.getOriginalFilename() == null ? "" : file.getOriginalFilename())
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        if (originalName.isBlank() || !originalName.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF documents are accepted.");
        }
        Path directory = properties.knowledgeDirectory().toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path destination = directory.resolve(originalName).normalize();
        if (!destination.startsWith(directory)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename.");
        Path temporary = Files.createTempFile(directory, ".marg-pdf-upload-", ".tmp");
        try {
            file.transferTo(temporary);
            try (var pdf = Loader.loadPDF(temporary.toFile())) {
                if (pdf.getNumberOfPages() == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The PDF contains no pages.");
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is not a readable PDF.", exception);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return ResponseEntity.ok(Map.of("fileName", originalName, "library", index.reindex()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh() {
        try {
            return ResponseEntity.ok(index.reindex());
        } catch (IOException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "Could not refresh the environmental PDF library."
                            : exception.getMessage()));
        }
    }
}
