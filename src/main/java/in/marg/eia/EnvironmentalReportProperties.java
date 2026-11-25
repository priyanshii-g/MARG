package in.marg.eia;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marg.eia")
public record EnvironmentalReportProperties(Path templatePath, Path outputDirectory,
                                            int minimumPdfPages, String libreOfficeBinary) {
    public EnvironmentalReportProperties {
        minimumPdfPages = minimumPdfPages < 1 ? 8 : minimumPdfPages;
        libreOfficeBinary = libreOfficeBinary == null || libreOfficeBinary.isBlank() ? "libreoffice" : libreOfficeBinary;
    }
}
