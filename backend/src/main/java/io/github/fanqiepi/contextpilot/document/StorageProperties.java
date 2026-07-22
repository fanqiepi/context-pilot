package io.github.fanqiepi.contextpilot.document;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
@ConfigurationProperties(prefix = "contextpilot.storage")
public class StorageProperties {

    private String root = "../data/uploads";

    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Path rootPath() {
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("contextpilot.storage.root must not be blank");
        }
        return Path.of(root);
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
}
