package io.terrakube.executor.service.terraform;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the start of a rendered plan, up to a byte limit, for the copy that is published to the job
 * context (what the pull request comment shows). The lines are fed one by one while the plan is streamed
 * to the step output, so the whole plan is never held in memory here. Lines past the limit are counted and
 * replaced by a closing note that says how much was left out.
 */
final class RenderedPlanText implements Consumer<String> {

    // The closing line of a plan is the last thing printed, so it is the first thing a cut loses. The pull
    // request comment reads its header from it, so it is remembered and put back after the note.
    private static final Pattern SUMMARY = Pattern.compile(
            "Plan: (?:\\d+ to import, )?\\d+ to add, \\d+ to change, \\d+ to destroy\\."
                    + "|No changes\\. Your infrastructure matches the configuration\\.");

    private final long maxBytes;
    private final StringBuilder kept = new StringBuilder();
    private long keptBytes;
    private long omittedLines;
    private long omittedBytes;
    private boolean truncated;
    private String summaryLine;

    /** @param maxBytes the most bytes of plan text kept, 0 or less keeps everything. */
    RenderedPlanText(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public void accept(String line) {
        if (truncated) {
            // Past the cut only the size of what is left out matters, and it is an approximate label, so
            // the characters are counted instead of encoding every remaining line.
            omittedLines++;
            omittedBytes += line.length() + 1L;
            remember(line);
            return;
        }
        long bytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (maxBytes <= 0 || keptBytes + bytes <= maxBytes) {
            kept.append(line).append('\n');
            keptBytes += bytes;
            return;
        }
        truncated = true;
        omittedLines++;
        omittedBytes += bytes;
        remember(line);
    }

    private void remember(String line) {
        Matcher matcher = SUMMARY.matcher(line);
        if (matcher.find()) {
            summaryLine = matcher.group();
        }
    }

    /** A plan that was cut is never blank, even when its first line alone was over the limit. */
    boolean isBlank() {
        return !truncated && kept.toString().isBlank();
    }

    boolean isTruncated() {
        return truncated;
    }

    String text() {
        if (!truncated) {
            return kept.toString();
        }
        String note = String.format(
                "%n... %d more lines (%d KiB) of the plan are not shown here. The full plan is in the output of this step in Terrakube.%n",
                omittedLines, (omittedBytes + 1023) / 1024);
        return kept + note + (summaryLine == null ? "" : System.lineSeparator() + summaryLine + System.lineSeparator());
    }
}
