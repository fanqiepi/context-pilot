package io.github.fanqiepi.contextpilot.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

@Service
public class LocalFileStorageService implements StorageService {

    private static final int BUFFER_SIZE = 8192;

    private final Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.root = properties.rootPath().toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(String storageKey, InputStream content) {
        Path target = resolve(storageKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyAndDigest(content, temporary, digest);
            moveIntoPlace(temporary, target);
            temporary = null;
            return new StoredObject(storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new StorageException("Could not store file", exception);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey), StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new StorageException("Could not open stored file", exception);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new StorageException("Could not delete stored file", exception);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new StorageException("Storage key must not be blank");
        }
        Path relative;
        try {
            relative = Path.of(storageKey);
        } catch (RuntimeException exception) {
            throw new StorageException("Storage key is invalid", exception);
        }
        if (relative.isAbsolute()) {
            throw new StorageException("Absolute storage keys are not allowed");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new StorageException("Storage key escapes the storage root");
        }
        return resolved;
    }

    private long copyAndDigest(InputStream content, Path target, MessageDigest digest) throws IOException {
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = new DigestOutputStream(
                Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING),
                digest)) {
            int count;
            while ((count = content.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                size += count;
            }
        }
        return size;
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new StorageException("Storage key already exists");
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The original storage failure remains the actionable error.
        }
    }
}
