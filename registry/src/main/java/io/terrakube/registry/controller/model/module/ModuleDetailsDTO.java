package io.terrakube.registry.controller.model.module;

import java.util.List;

/**
 * What the UI shows on a module's detail page: the inputs, outputs and resources declared by the
 * module's own .tf files, the names of its submodules, and (for a submodule) its README.
 */
public record ModuleDetailsDTO(
        List<String> submodules,
        List<Variable> variables,
        List<Output> outputs,
        List<Resource> resources,
        String readme) {

    /** {@code type} and {@code defaultValue} are the raw HCL expressions, exactly as written. */
    public record Variable(String name, String type, String description, String defaultValue) {
    }

    public record Output(String name, String description) {
    }

    public record Resource(String type, String name) {
    }
}
