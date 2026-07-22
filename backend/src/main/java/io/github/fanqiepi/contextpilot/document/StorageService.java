package io.github.fanqiepi.contextpilot.document;

import java.io.InputStream;

public interface StorageService {

    StoredObject store(String storageKey, InputStream content);

    InputStream open(String storageKey);

    boolean exists(String storageKey);

    void delete(String storageKey);
}
