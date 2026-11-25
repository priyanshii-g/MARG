package in.marg.eia;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marg.mode2")
public record Mode2ProjectProperties(Path projectsDirectory) {
    public Mode2ProjectProperties {
        projectsDirectory = projectsDirectory == null ? Path.of("data/mode2/projects") : projectsDirectory;
    }
}
