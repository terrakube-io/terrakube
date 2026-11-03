package io.terrakube.api.plugin.scheduler.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleRefreshTransactionsTest {

    @Test
    void storedErrorsDropUrlUserInfo() {
        assertThat(ModuleRefreshTransactions.withoutUserInfo(
                "other: https://oauth2:glpat-secret@gitlab.com/org/repo.git: not authorized, see http://bob@host/x"))
                .isEqualTo("other: https://gitlab.com/org/repo.git: not authorized, see http://host/x");
        assertThat(ModuleRefreshTransactions.withoutUserInfo("timeout: ssh://git@github.com/org/repo.git"))
                .isEqualTo("timeout: ssh://github.com/org/repo.git");
        assertThat(ModuleRefreshTransactions.withoutUserInfo("no_tags: The repository has no tags."))
                .isEqualTo("no_tags: The repository has no tags.");
    }
}
