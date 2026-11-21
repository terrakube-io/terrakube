package io.terrakube.api.rs.webhook;

/**
 * Who may apply from a "terrakube apply" pull request comment. Ordered from the least to the most
 * strict, so an organization minimum can be compared with a workspace setting.
 */
public enum PrApplyRequirement {
    /** The comment author has write access to the repository (GitHub) or the Developer role (GitLab). */
    WRITE,
    /** {@link #WRITE}, and the pull request is approved under the VCS's own rules on its current head commit. */
    APPROVED;

    /** The stricter of the two; a null value counts as {@link #WRITE}, the default. */
    public static PrApplyRequirement strictest(PrApplyRequirement a, PrApplyRequirement b) {
        PrApplyRequirement first = a == null ? WRITE : a;
        PrApplyRequirement second = b == null ? WRITE : b;
        return first.compareTo(second) >= 0 ? first : second;
    }
}
