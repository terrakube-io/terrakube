package io.terrakube.api.plugin.storage.model;

/**
 * One part of the output of a step that was archived while the step ran, as listed from storage.
 * {@code name} is the file name inside the step's {@code .parts} folder, for example {@code 000001.part}.
 */
public record StepOutputPart(String name, long size) {
}
