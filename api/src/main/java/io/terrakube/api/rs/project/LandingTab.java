package io.terrakube.api.rs.project;

/** The tabs a project can open on, as validated on {@code Project} and on {@code Organization}. */
public final class LandingTab {

    public static final String PATTERN = "overview|workspaces|variables|policies|teams|general";
    public static final String MESSAGE = "Landing tab must be one of overview, workspaces, variables, policies, teams, general";

    private LandingTab() {
    }
}
