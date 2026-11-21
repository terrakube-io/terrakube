package io.terrakube.api.rs.webhook;

/**
 * Who may apply from a "terrakube apply" pull request comment. Each level has an explicit strength, so an
 * organization minimum can be compared with a workspace setting without depending on the declaration order.
 */
public enum PrApplyRequirement {
    /** The comment author has write access to the repository (GitHub) or the Developer role (GitLab). */
    WRITE(1),
    /**
     * {@link #WRITE}, and the pull request is approved under the VCS's own rules, read for the head commit the
     * apply runs on. Whether an approval of an earlier commit still counts is up to those rules.
     */
    APPROVED(2);

    private final int strength;

    PrApplyRequirement(int strength) {
        this.strength = strength;
    }

    /** The stricter of the two; a null value counts as {@link #WRITE}, the default. */
    public static PrApplyRequirement strictest(PrApplyRequirement a, PrApplyRequirement b) {
        PrApplyRequirement first = a == null ? WRITE : a;
        PrApplyRequirement second = b == null ? WRITE : b;
        return first.strength >= second.strength ? first : second;
    }
}
