package io.terrakube.api.rs;

/** Lifecycle of a module or provider version in the private registry. */
public enum VersionStatus {
    /** Served normally. */
    active,
    /** Still served, with a warning. */
    deprecated,
    /** No longer served. The row is kept so the refresh jobs do not re-import the version. */
    removed,
}
