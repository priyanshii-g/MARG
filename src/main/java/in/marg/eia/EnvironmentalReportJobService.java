package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/** Coordinates validation and asynchronous DOCX rendering for the report MCP tools. */
@Service
public class EnvironmentalReportJobService implements AutoCloseable {

    private final EnvironmentalReportValidator validator;
    private final EnvironmentalReportDocxWriter writer;
    private final EnvironmentalReportPdfRenderer pdfRenderer;
    private final EnvironmentalReportEvidenceAssembler evidenceAssembler;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

    public EnvironmentalReportJobService(
            EnvironmentalReportValidator validator,
            EnvironmentalReportDocxWriter writer,
            EnvironmentalReportPdfRenderer pdfRenderer,
            EnvironmentalReportEvidenceAssembler evidenceAssembler
    ) {
        this.validator = validator;
        this.writer = writer;
        this.pdfRenderer = pdfRenderer;
        this.evidenceAssembler = evidenceAssembler;
    }

    public JobReceipt start(GenerateReportRequest request) {
        return start(request, List.of(), "");
    }

    public JobReceipt start(GenerateReportRequest request, List<ValidationIssue> reviewWarnings,
                            String contentReviewSummary) {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        GenerateReportRequest assembledRequest;
        List<ValidationIssue> issues;
        try {
            assembledRequest = evidenceAssembler.assemble(request);
            Set<String> templateTokens = writer.templateTokens();
            issues = validator.validate(assembledRequest, templateTokens);
        } catch (IOException exception) {
            assembledRequest = request;
            issues = List.of(new ValidationIssue("TEMPLATE_UNAVAILABLE",
                    "The configured environmental report template could not be read.", null, null));
        }

        String projectName = assembledRequest == null || assembledRequest.projectName() == null ? "" : assembledRequest.projectName();
        List<ValidationIssue> allIssues = new java.util.ArrayList<>(issues);
        if (reviewWarnings != null) allIssues.addAll(reviewWarnings);
        if (!issues.isEmpty()) {
            JobRecord rejected = new JobRecord(jobId, JobState.REJECTED, projectName, now, now,
                    null, null, null, allIssues, "Report was not written because validation failed.");
            jobs.put(jobId, rejected);
            return receipt(rejected);
        }

        JobRecord queued = new JobRecord(jobId, JobState.QUEUED, projectName, now, now,
                null, null, null, allIssues,
                (contentReviewSummary == null || contentReviewSummary.isBlank() ? "" : contentReviewSummary + " ")
                        + "Report request accepted; writing and PDF pagination are queued.");
        jobs.put(jobId, queued);
        GenerateReportRequest requestToRender = assembledRequest;
        executor.execute(() -> render(jobId, requestToRender));
        return receipt(queued);
    }

    /** Records a rejected LLM draft without creating a DOCX or PDF artifact. */
    public JobReceipt rejectDraft(String projectName, List<ValidationIssue> issues, String message) {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        JobRecord rejected = new JobRecord(jobId, JobState.REJECTED, projectName == null ? "" : projectName,
                now, now, null, null, null, issues == null ? List.of() : List.copyOf(issues), message);
        jobs.put(jobId, rejected);
        return receipt(rejected);
    }

    public void verifyPdfRendererAvailable() throws IOException, InterruptedException {
        pdfRenderer.verifyAvailable();
    }

    public Optional<JobStatus> status(String jobId) {
        return Optional.ofNullable(jobs.get(jobId)).map(this::toStatus);
    }

    public Optional<Path> completedDocument(String jobId) {
        JobRecord record = jobs.get(jobId);
        return record == null || (record.state() != JobState.COMPLETED && record.state() != JobState.FAILED)
                ? Optional.empty() : Optional.ofNullable(record.output());
    }

    public Optional<Path> completedPdf(String jobId) {
        JobRecord record = jobs.get(jobId);
        return record == null || record.state() != JobState.COMPLETED ? Optional.empty() : Optional.ofNullable(record.pdfOutput());
    }

    private void render(String jobId, GenerateReportRequest request) {
        update(jobId, JobState.WRITING, null, null, null, "Rendering the validated report into the controlled DOCX template.");
        try {
            Path output = writer.write(jobId, request);
            update(jobId, JobState.RENDERING_PDF, output, null, null,
                    "DOCX written; rendering PDF pages, resolving contents page numbers, and checking minimum length.");
            List<String> headings = writer.contentsHeadings();
            Map<String, Integer> desiredPageNumbers = Map.of();
            EnvironmentalReportPdfRenderer.PdfArtifact pdf = null;
            boolean contentsStable = false;
            for (int iteration = 0; iteration < 4; iteration++) {
                if (!desiredPageNumbers.isEmpty()) writer.write(jobId, request, desiredPageNumbers);
                pdf = pdfRenderer.renderForContents(output, headings);
                if (!pdf.headingPages().keySet().containsAll(headings)) {
                    throw new IOException("Could not locate every numbered report heading in the rendered PDF; contents page numbers are incomplete.");
                }
                if (pdf.headingPages().equals(desiredPageNumbers)) {
                    contentsStable = true;
                    break;
                }
                desiredPageNumbers = pdf.headingPages();
            }
            if (!contentsStable || pdf == null) {
                throw new IOException("Contents pagination did not stabilize after four render passes.");
            }
            pdfRenderer.requireMinimumPages(pdf);
            update(jobId, JobState.COMPLETED, output, pdf.path(), pdf.pages(),
                    "Validated DOCX and paginated PDF created with " + pdf.pages() + " pages.");
        } catch (EnvironmentalReportPdfRenderer.ReportLengthException exception) {
            update(jobId, JobState.FAILED, null, exception.pdf(), exception.actualPages(), exception.getMessage());
        } catch (Exception exception) {
            JobRecord record = jobs.get(jobId);
            Path docx = record == null ? null : record.output();
            update(jobId, JobState.FAILED, docx, null, null,
                    "DOCX/PDF rendering failed: " + safeMessage(exception));
        }
    }

    private void update(String jobId, JobState state, Path output, Path pdfOutput, Integer pageCount, String message) {
        jobs.computeIfPresent(jobId, (ignored, previous) -> new JobRecord(previous.jobId(), state,
                previous.projectName(), previous.createdAt(), Instant.now(),
                output == null ? previous.output() : output,
                pdfOutput == null ? previous.pdfOutput() : pdfOutput,
                pageCount == null ? previous.pageCount() : pageCount,
                previous.validationIssues(), message));
    }

    private JobReceipt receipt(JobRecord record) {
        return new JobReceipt(record.jobId(), record.state(), statusUrl(record.jobId()),
                record.output() == null ? null : downloadUrl(record.jobId()),
                record.state() == JobState.COMPLETED ? pdfDownloadUrl(record.jobId()) : null,
                record.pageCount(),
                record.validationIssues(), record.message());
    }

    private JobStatus toStatus(JobRecord record) {
        String fileName = record.output() == null ? null : record.output().getFileName().toString();
        return new JobStatus(record.jobId(), record.state(), record.projectName(), record.createdAt(), record.updatedAt(),
                fileName, record.output() == null ? null : downloadUrl(record.jobId()),
                record.state() == JobState.COMPLETED ? pdfDownloadUrl(record.jobId()) : null,
                record.pageCount(),
                record.validationIssues(), record.message());
    }

    private String statusUrl(String jobId) {
        return "/api/eia/reports/jobs/" + jobId;
    }

    private String downloadUrl(String jobId) {
        return "/api/eia/reports/" + jobId + "/document";
    }

    private String pdfDownloadUrl(String jobId) {
        return "/api/eia/reports/" + jobId + "/pdf";
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)(token|password|secret)=\\S+", "$1=[redacted]");
    }

    @Override
    public void close() {
        executor.close();
    }

    private record JobRecord(
            String jobId,
            JobState state,
            String projectName,
            Instant createdAt,
            Instant updatedAt,
            Path output,
            Path pdfOutput,
            Integer pageCount,
            List<ValidationIssue> validationIssues,
            String message
    ) {}
}
