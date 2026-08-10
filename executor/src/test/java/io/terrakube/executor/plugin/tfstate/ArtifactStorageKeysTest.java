package io.terrakube.executor.plugin.tfstate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactStorageKeysTest {

    private static final String KEY = "plan-artifacts/org1/ws1/42/step-uuid/plan-artifacts.tar.gz";

    private String keyFor(String location) {
        return ArtifactStorageKeys.keyFromLocation(location, "org1", "ws1", "42");
    }

    @Test
    void theKeyIsBuiltFromTheIds() {
        assertEquals(KEY, ArtifactStorageKeys.key("org1", "ws1", "42", "step-uuid"));
    }

    @Test
    void everyBackendsLocationFormatResolvesToTheSameKey() {
        // S3 path style and virtual hosted style, Azure blob URL, GCS URL, and a local file path
        assertEquals(KEY, keyFor("https://s3.eu-west-1.amazonaws.com/my-bucket/" + KEY));
        assertEquals(KEY, keyFor("https://my-bucket.s3.amazonaws.com/" + KEY));
        assertEquals(KEY, keyFor("https://acct.blob.core.windows.net/tfstate/" + KEY));
        assertEquals(KEY, keyFor("https://storage.cloud.google.com/my-bucket/" + KEY));
        assertEquals(KEY, keyFor("https://minio.local:9000/bucket/" + KEY));
        assertEquals(KEY, keyFor("/home/cnb/.terraform-spring-boot/local/" + KEY));
    }

    @Test
    void anAzureUrlWithEncodedSlashesResolvesToTheSameKey() {
        // URI.getPath() decodes %2F, so the segments are the same as for the unencoded form
        assertEquals(KEY, keyFor("https://acct.blob.core.windows.net/tfstate/" + KEY.replace("/", "%2F")));
    }

    @Test
    void aBucketNamedLikeThePrefixStillWorks() {
        assertEquals(KEY, keyFor("https://s3.amazonaws.com/plan-artifacts/" + KEY));
    }

    @Test
    void aLocationInAnotherOrganizationWorkspaceOrJobIsRefused() {
        assertThrows(ArtifactVerificationException.class,
                () -> keyFor("https://b/plan-artifacts/other-org/ws1/42/step-uuid/plan-artifacts.tar.gz"));
        assertThrows(ArtifactVerificationException.class,
                () -> keyFor("https://b/plan-artifacts/org1/other-ws/42/step-uuid/plan-artifacts.tar.gz"));
        assertThrows(ArtifactVerificationException.class,
                () -> keyFor("https://b/plan-artifacts/org1/ws1/43/step-uuid/plan-artifacts.tar.gz"));
    }

    @Test
    void aLocationThatIsNotAnArtifactsBundleIsRefused() {
        assertThrows(ArtifactVerificationException.class, () -> keyFor("https://b/tfstate/org1/ws1/42/step/terraformLibrary.tfPlan"));
        assertThrows(ArtifactVerificationException.class, () -> keyFor("https://b/plan-artifacts/org1/ws1/42/step-uuid/other.tar.gz"));
        assertThrows(ArtifactVerificationException.class, () -> keyFor("/etc/passwd"));
        assertThrows(ArtifactVerificationException.class, () -> keyFor(""));
        assertThrows(ArtifactVerificationException.class, () -> keyFor("not a location"));
    }

    @Test
    void aTraversalOrOddStepSegmentIsRefused() {
        assertThrows(ArtifactVerificationException.class, () -> keyFor("https://b/plan-artifacts/org1/ws1/42/../plan-artifacts.tar.gz"));
        assertThrows(ArtifactVerificationException.class, () -> keyFor("https://b/plan-artifacts/org1/ws1/42/%2e%2e/plan-artifacts.tar.gz"));
        assertThrows(ArtifactVerificationException.class, () -> keyFor("https://b/plan-artifacts/org1/ws1/42/a b/plan-artifacts.tar.gz"));
    }

    @Test
    void theKeyNeverComesFromTheRecordedStringItself() {
        // trailing junk after a valid tail does not matter; only the last six segments are read and rebuilt
        assertEquals(KEY, keyFor("https://b/some/prefix/../x/" + KEY));
    }
}
