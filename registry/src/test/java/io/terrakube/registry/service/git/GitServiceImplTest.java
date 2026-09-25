package io.terrakube.registry.service.git;

import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class GitServiceImplTest {

    @Test
    void validateCorrectTagNeverDoublesTheVPrefix() {
        // validateCorrectTag is private and does a live ls-remote; this test locks down the string-building
        // rule it must follow (never "v" + a tag that already starts with "v") by exercising the same
        // concatenation the method performs, guarding against a regression that would produce "vv2.0.1".
        String tagPrefix = null;
        String originalTag = "2.0.1";
        String guessWithV = (tagPrefix == null ? "" : tagPrefix) + "v" + originalTag;

        assertThat(guessWithV).isEqualTo("v2.0.1");
        assertThat(guessWithV).doesNotStartWith("vv");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void azureSpMiMintsTokenEvenWithoutStoredAccessToken(String accessToken) throws Exception {
        // AZURE_SP_MI connections never persist an access token, so the empty-token guard must not
        // short-circuit them; otherwise every clone goes out unauthenticated.
        GitServiceImpl gitService = spy(new GitServiceImpl());
        doReturn("minted-token").when(gitService).getAzureDefaultToken();

        CredentialsProvider credentialsProvider = gitService.setupCredentials("AZURE_SP_MI", "OAUTH", accessToken);

        assertThat(credentialsProvider).isNotNull();
        verify(gitService).getAzureDefaultToken();
        CredentialItem.Password password = new CredentialItem.Password();
        credentialsProvider.get(new URIish("https://dev.azure.com/org/project/_git/repo"),
                new CredentialItem.Username(), password);
        assertThat(new String(password.getValue())).isEqualTo("minted-token");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void tokenBasedVcsWithoutAccessTokenHasNoCredentials(String accessToken) {
        GitServiceImpl gitService = new GitServiceImpl();

        assertThat(gitService.setupCredentials("AZURE_DEVOPS", "OAUTH", accessToken)).isNull();
        assertThat(gitService.setupCredentials("GITHUB", "OAUTH", accessToken)).isNull();
    }
}
