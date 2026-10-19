package io.github.fanqiepi.contextpilot.projectsource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProjectSourcePolicyTests {
    @ParameterizedTest
    @ValueSource(strings = {"../README.md", "/README.md", "a/../README.md", "a/./README.md", "a\\README.md",
            "a//README.md", "%2e%2e/README.md", "README.md?ref=main", " README.md", "a/ README.md",
            "README.md\n", "file.java", "https://example.org/a.md", "a/b/c/d/e/f/g/h/i.md"})
    void rejectsUnsafePaths(String path) {
        assertThatThrownBy(() -> ProjectSourcePolicy.path(path)).isInstanceOf(ProjectSourceException.class);
    }
    @Test void validatesCountAndEncodingAndHash() {
        assertThat(ProjectSourcePolicy.paths(List.of("README.md", "docs/Guide 1.txt"))).hasSize(2);
        assertThatThrownBy(() -> ProjectSourcePolicy.paths(List.of("README.md", "README.md")))
                .hasMessage("SOURCE_PATH_DUPLICATE");
        assertThatThrownBy(() -> ProjectSourcePolicy.text(new byte[] {(byte) 0xc3, 0x28}))
                .hasMessage("SOURCE_ENCODING_INVALID");
        assertThatThrownBy(() -> ProjectSourcePolicy.text(new byte[] {0})).hasMessage("SOURCE_CONTENT_INVALID");
        byte[] bytes = "公开资料".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ProjectDocumentSnapshot(ProjectSourcePolicy.REPOSITORY, "a".repeat(40),
                "README.md", "text/markdown", bytes.length, "0".repeat(64), Instant.now().toString(), "公开资料"))
                .hasMessage("SOURCE_SNAPSHOT_INVALID");
    }
}
