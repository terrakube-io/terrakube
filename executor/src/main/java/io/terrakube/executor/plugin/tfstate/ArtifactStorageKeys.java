package io.terrakube.executor.plugin.tfstate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where a plan step's artifacts bundle lives in every storage backend:
 * {@code plan-artifacts/<organization>/<workspace>/<job>/<step>/plan-artifacts.tar.gz}. It is a top
 * level prefix of its own, next to but apart from state and plan history, so a lifecycle rule can
 * expire these (much larger) bundles without touching either.
 *
 * <p>The apply step finds the bundle through the location the plan step recorded on the job. That
 * value is only used to learn the step id: the key is rebuilt from the current job's own
 * organization, workspace and job ids, so a job record that points elsewhere is refused instead of
 * followed.
 */
public final class ArtifactStorageKeys {

    public static final String PREFIX = "plan-artifacts";
    public static final String FILE_NAME = "plan-artifacts.tar.gz";

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private ArtifactStorageKeys() {
    }

    public static String key(String organizationId, String workspaceId, String jobId, String stepId) {
        return String.join("/", PREFIX, organizationId, workspaceId, jobId, stepId, FILE_NAME);
    }

    /**
     * The storage key for a recorded location (a bucket URL or a file path).
     *
     * @throws ArtifactVerificationException when the location is not this job's artifacts bundle.
     */
    public static String keyFromLocation(String location, String organizationId, String workspaceId, String jobId)
            throws ArtifactVerificationException {
        List<String> segments = segments(location);
        int size = segments.size();
        if (size < 6) {
            throw notThisJobs(location);
        }
        List<String> tail = segments.subList(size - 6, size);
        String stepId = tail.get(4);
        boolean matches = PREFIX.equals(tail.get(0))
                && organizationId.equals(tail.get(1))
                && workspaceId.equals(tail.get(2))
                && jobId.equals(tail.get(3))
                && SAFE_SEGMENT.matcher(stepId).matches() && !stepId.equals("..") && !stepId.equals(".")
                && FILE_NAME.equals(tail.get(5));
        if (!matches) {
            throw notThisJobs(location);
        }
        return key(organizationId, workspaceId, jobId, stepId);
    }

    private static List<String> segments(String location) {
        String path = location;
        if (location.contains("://")) {
            try {
                path = URI.create(location).getPath();
            } catch (IllegalArgumentException e) {
                path = "";
            }
        }
        List<String> segments = new ArrayList<>();
        if (path != null) {
            for (String segment : path.replace('\\', '/').split("/")) {
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
            }
        }
        return segments;
    }

    private static ArtifactVerificationException notThisJobs(String location) {
        return new ArtifactVerificationException("The recorded plan artifacts location is not this job's artifacts bundle: " + location);
    }
}
