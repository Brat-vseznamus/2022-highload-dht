package ok.dht;

import java.nio.file.Path;
import java.util.List;

public final class ServiceConfig {
    private final int selfPort;
    private final String selfUrl;
    private final List<String> clusterUrls;
    private final Path workingDir;

    private final int inspectorPort;

    public ServiceConfig(
            int selfPort,
            String selfUrl,
            List<String> clusterUrls,
            Path workingDir,
            int inspectorPort
    ) {
        this.selfPort = selfPort;
        this.selfUrl = selfUrl;
        this.clusterUrls = clusterUrls;
        this.workingDir = workingDir;
        this.inspectorPort = inspectorPort;
    }

    public ServiceConfig(
            int selfPort,
            String selfUrl,
            List<String> clusterUrls,
            Path workingDir
    ) {
        this.selfPort = selfPort;
        this.selfUrl = selfUrl;
        this.clusterUrls = clusterUrls;
        this.workingDir = workingDir;
        this.inspectorPort = selfPort;
    }

    public int selfPort() {
        return selfPort;
    }

    public String selfUrl() {
        return selfUrl;
    }

    public List<String> clusterUrls() {
        return clusterUrls;
    }

    public Path workingDir() {
        return workingDir;
    }

    public int getInspectorPort() {
        return inspectorPort;
    }
}
