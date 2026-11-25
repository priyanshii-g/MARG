package in.marg.eia;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marg.eia")
public record EnvironmentalKnowledgeProperties(
        Path knowledgeDirectory,
        int retrievalLimit,
        int chunkSize,
        int chunkOverlap,
        boolean autoIndex
) {
    public EnvironmentalKnowledgeProperties {
        retrievalLimit = retrievalLimit < 1 ? 8 : retrievalLimit;
        chunkSize = chunkSize < 200 ? 1200 : chunkSize;
        chunkOverlap = chunkOverlap < 0 || chunkOverlap >= chunkSize ? Math.min(200, chunkSize / 5) : chunkOverlap;
    }
}
