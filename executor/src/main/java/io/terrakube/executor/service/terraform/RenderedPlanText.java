package io.terrakube.executor.service.terraform;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Keeps the start of a rendered plan, up to a byte limit, for the copy that is published to the job
 * context (what the pull request comment shows). The lines are fed one by one while the plan is streamed
 * to the step output, so the whole plan is never held in memory here. Lines past the limit are counted and
 * replaced by a closing note that says how much was left out.
 */
final class RenderedPlanText implements Consumer<String> {

    private final long maxBytes;
    private final StringBuilder kept = new StringBuilder();
    private long keptBytes;
    private long omittedLines;
    private long omittedBytes;
    private boolean truncated;

    /** @param maxBytes the most bytes of plan text kept, 0 or less keeps everything. */
    RenderedPlanText(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public void accept(String line) {
        long bytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (!truncated && (maxBytes <= 0 || keptBytes + bytes <= maxBytes)) {
            kept.append(line).append('\n');
            keptBytes += bytes;
            return;
        }
        truncated = true;
        omittedLines++;
        omittedBytes += bytes;
    }

    boolean isBlank() {
        return kept.toString().isBlank();
    }

    boolean isTruncated() {
        return truncated;
    }

    String text() {
        if (!truncated) {
            return kept.toString();
        }
        return kept + String.format(
                "\n... %d more lines (%d KiB) of the plan are not shown here. The full plan is in the output of this step in Terrakube.\n",
                omittedLines, (omittedBytes + 1023) / 1024);
    }
}
