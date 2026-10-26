package io.terrakube.executor.plugin.tfstate;

/**
 * Names of the parts of a step's output while it runs: {@code <step>.parts/000001.part}. Zero padded so
 * the listing order of every backend is the order of the output.
 */
public final class OutputPartNames {

    public static final String FOLDER_SUFFIX = ".parts";

    private OutputPartNames() {
    }

    public static String folder(String stepId) {
        return stepId + FOLDER_SUFFIX;
    }

    public static String part(int partNumber) {
        return String.format("%06d.part", partNumber);
    }
}
