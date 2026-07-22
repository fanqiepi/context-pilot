package io.github.fanqiepi.contextpilot.document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageServiceTests {

    @TempDir
    private Path temporaryRoot;

    private LocalFileStorageService storageService;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(temporaryRoot);
        storageService = new LocalFileStorageService(properties);
    }

    @Test
    void storesReadsAndDeletesFile() throws Exception {
        byte[] content = "ContextPilot".getBytes(StandardCharsets.UTF_8);
        String key = "knowledge-bases/kb/documents/doc/source.txt";

        StoredObject stored = storageService.store(key, new java.io.ByteArrayInputStream(content));

        assertThat(stored.storageKey()).isEqualTo(key);
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(
                "e7f2893fca4df48439704dccadf1cde1dbd28bac6a813d4f056caff146fe2cad");
        assertThat(storageService.exists(key)).isTrue();
        try (InputStream input = storageService.open(key)) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }

        storageService.delete(key);
        storageService.delete(key);
        assertThat(storageService.exists(key)).isFalse();
    }

    @Test
    void rejectsStorageKeyTraversal() {
        assertThatThrownBy(() -> storageService.store(
                "../outside.txt",
                new java.io.ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("storage root");
    }

    @Test
    void refusesToOverwriteExistingObject() {
        String key = "documents/source.txt";
        storageService.store(key, new java.io.ByteArrayInputStream(new byte[] {1}));

        assertThatThrownBy(() -> storageService.store(
                key,
                new java.io.ByteArrayInputStream(new byte[] {2})))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("already exists");
    }
}
