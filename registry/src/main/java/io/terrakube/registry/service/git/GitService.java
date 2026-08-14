package io.terrakube.registry.service.git;

import java.io.File;

public interface GitService {

    File getCloneRepositoryByTag(ModuleVersionDownload download);
}
