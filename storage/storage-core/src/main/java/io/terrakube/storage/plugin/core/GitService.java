package io.terrakube.storage.plugin.core;

import java.io.File;

public interface GitService {

    File getCloneRepositoryByTag(ModuleVersionDownload download);
}
