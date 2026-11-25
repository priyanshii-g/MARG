package in.marg.eia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;

/** Uses a deployment-installed LibreOffice in headless mode to preserve DOCX layout during PDF pagination. */
@Component
public class EnvironmentalReportPdfRenderer {

    private final EnvironmentalReportProperties properties;

    public EnvironmentalReportPdfRenderer(EnvironmentalReportProperties properties) {
        this.properties = properties;
    }

    public PdfArtifact render(Path docx) throws IOException, InterruptedException {
        return render(docx, List.of());
    }

    public PdfArtifact render(Path docx, List<String> headings) throws IOException, InterruptedException {
        PdfArtifact artifact = renderForContents(docx, headings);
        return requireMinimumPages(artifact);
    }

    /** Verifies the configured converter can be launched before the application spends tokens drafting a report. */
    public void verifyAvailable() throws IOException, InterruptedException {
        String binary = properties.libreOfficeBinary();
        Path executable = resolveExecutable(binary);
        if (executable == null || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IOException("PDF rendering requires LibreOffice; configure MARG_LIBREOFFICE_BIN with an executable soffice path.");
        }
        Process process = new ProcessBuilder(executable.toString(), "--headless", "--version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("LibreOffice did not respond to its version check; no report-drafting API request was made.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("LibreOffice failed its executable check; no report-drafting API request was made.");
        }
    }

    private Path resolveExecutable(String binary) {
        Path configured = Path.of(binary);
        if (configured.isAbsolute() || binary.contains("/") || binary.contains("\\")) {
            return configured.toAbsolutePath().normalize();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory).resolve(binary).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    public PdfArtifact requireMinimumPages(PdfArtifact artifact) throws ReportLengthException {
        if (artifact.pages() < properties.minimumPdfPages()) {
            throw new ReportLengthException(artifact.path(), artifact.pages(), properties.minimumPdfPages());
        }
        return artifact;
    }

    /** Converts without applying the minimum-page gate; used to calculate real contents page numbers. */
    public PdfArtifact renderForContents(Path docx, List<String> headings) throws IOException, InterruptedException {
        Path absoluteDocx = docx.toAbsolutePath().normalize();
        Path outputDirectory = absoluteDocx.getParent();
        String baseName = absoluteDocx.getFileName().toString().replaceFirst("(?i)\\.docx$", "");
        Path pdf = outputDirectory.resolve(baseName + ".pdf");
        Files.deleteIfExists(pdf);
        Path profile = outputDirectory.resolve(".lo-profile-" + UUID.randomUUID());
        String profileUri = profile.toUri().toString();
        Process process;
        try {
            process = new ProcessBuilder(properties.libreOfficeBinary(), "--headless", "--nologo", "--nodefault",
                    "--nofirststartwizard", "-env:UserInstallation=" + profileUri,
                    "--convert-to", "pdf", "--outdir", outputDirectory.toString(), absoluteDocx.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            throw new IOException("PDF rendering requires LibreOffice; configure MARG_LIBREOFFICE_BIN with its executable path.", exception);
        }
        try {
            boolean finished = process.waitFor(Duration.ofMinutes(3).toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("LibreOffice PDF conversion exceeded the three-minute limit.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf)) {
                throw new IOException("LibreOffice could not convert the generated DOCX into a PDF.");
            }
            int pages;
            Map<String, Integer> headingPages = new LinkedHashMap<>();
            try (var document = Loader.loadPDF(pdf.toFile())) {
                pages = document.getNumberOfPages();
                if (!headings.isEmpty()) {
                    var stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    stripper.setSortByPosition(true);
                    stripper.setStartPage(1);
                    stripper.setEndPage(pages);
                    stripper.setPageEnd("\f");
                    String[] pageText = stripper.getText(document).split("\\f", -1);
                    for (String heading : headings) {
                        String needle = normalizeForMatch(heading);
                        for (int page = pageText.length - 1; page >= 0; page--) {
                            if (normalizeForMatch(pageText[page]).contains(needle)) {
                                headingPages.put(heading, page + 1);
                                break;
                            }
                        }
                    }
                }
            }
            return new PdfArtifact(pdf, pages, Map.copyOf(headingPages));
        } finally {
            if (Files.exists(profile)) {
                try (var paths = Files.walk(profile)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        }
    }

    private String normalizeForMatch(String text) {
        return Pattern.compile("[^\\p{L}\\p{N}]+").matcher(
                Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").trim();
    }

    public record PdfArtifact(Path path, int pages, Map<String, Integer> headingPages) {}

    public static final class ReportLengthException extends IOException {
        private final Path pdf;
        private final int actualPages;
        private final int minimumPages;

        public ReportLengthException(Path pdf, int actualPages, int minimumPages) {
            super("Rendered report has " + actualPages + " pages; the configured minimum is " + minimumPages
                    + ". Add supported evidence/content and regenerate; the system will not pad with invented text.");
            this.pdf = pdf;
            this.actualPages = actualPages;
            this.minimumPages = minimumPages;
        }

        public Path pdf() { return pdf; }
        public int actualPages() { return actualPages; }
        public int minimumPages() { return minimumPages; }
    }
}
