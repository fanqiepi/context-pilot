package io.github.fanqiepi.contextpilot.projectsource;

import java.time.Instant;

/** Deliberately narrow port, never a generic tool gateway. */
public interface ProjectSourcePort {
    String resolveRef(String ref, Instant deadline);
    ProjectDocumentSnapshot readDocument(String commit, String path, Instant deadline);
}
