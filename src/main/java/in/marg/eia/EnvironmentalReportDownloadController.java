package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated HTTP status and download endpoints complementing the MCP job tools. */
@RestController
@RequestMapping("/api/eia/reports")
public class EnvironmentalReportDownloadController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType PDF = MediaType.APPLICATION_PDF;
    private final EnvironmentalReportJobService jobs;

    public EnvironmentalReportDownloadController(EnvironmentalReportJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatus> status(@PathVariable("jobId") String jobId) {
        return jobs.status(jobId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{jobId}/document")
    public ResponseEntity<Resource> download(@PathVariable("jobId") String jobId) throws IOException {
        return downloadArtifact(jobId, false);
    }

    @GetMapping("/{jobId}/pdf")
    public ResponseEntity<Resource> downloadPdf(@PathVariable("jobId") String jobId) throws IOException {
        return downloadArtifact(jobId, true);
    }

    private ResponseEntity<Resource> downloadArtifact(String jobId, boolean pdf) throws IOException {
        JobStatus status = jobs.status(jobId).orElse(null);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        if (pdf && status.state() != JobState.COMPLETED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (!pdf && status.state() != JobState.COMPLETED && status.state() != JobState.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Path path = (pdf ? jobs.completedPdf(jobId) : jobs.completedDocument(jobId)).orElse(null);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(pdf ? PDF : DOCX)
                .contentLength(resource.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(path.getFileName().toString()).build().toString())
                .body(resource);
    }
}
