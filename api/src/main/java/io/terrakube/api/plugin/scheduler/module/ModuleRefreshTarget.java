package io.terrakube.api.plugin.scheduler.module;

import java.util.UUID;

import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;

// What a refresh needs to read the tags, loaded before ls-remote so no transaction stays open during it.
record ModuleRefreshTarget(UUID id, String name, String organizationName, String source, String tagPrefix, Vcs vcs,
        Ssh ssh) {
}
