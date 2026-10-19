package io.github.fanqiepi.contextpilot.projectsource;

/** Safe error code only; never retain upstream response bodies or credentials. */
public final class ProjectSourceException extends RuntimeException {
    public ProjectSourceException(String code) { super(code); }
    public String code() { return getMessage(); }
}
