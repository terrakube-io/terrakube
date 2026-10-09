package io.terrakube.registry.service.git;

import java.io.File;
import java.io.IOException;

public interface GitService {

    /**
     * Clones the module at its tag, packs it into a temporary zip and hands that zip to the handler.
     * The clone and the zip are deleted once the handler returns or fails.
     */
    void withModuleZip(ModuleVersionDownload download, ModuleZipHandler handler) throws IOException;

    @FunctionalInterface
    interface ModuleZipHandler {
        void accept(File moduleZip) throws IOException;
    }
}
