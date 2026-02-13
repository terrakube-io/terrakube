package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.vcs.VcsType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitServiceTest {

    @Mock
    private AzDevOpsTokenService azDevOpsTokenService;

    @Mock
    private GitHubAppTokenRepository gitHubAppTokenRepository;

    @InjectMocks
    private GitService gitService;

    @Test
    void shouldGetCredentialsProviderForGithubOauth() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(VcsConnectionType.OAUTH);
        vcs.setAccessToken("gh_token");

        CredentialsProvider provider = gitService.getCredentialsProvider("https://github.com/org/repo.git", vcs);

        assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
        assertNotNull(provider);
    }

    @Test
    void shouldGetCredentialsProviderForGithubApp() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(VcsConnectionType.STANDALONE);
        vcs.setClientId("client_id");
        vcs.setId(java.util.UUID.randomUUID());

        GitHubAppToken appToken = new GitHubAppToken();
        appToken.setToken("app_token");

        when(gitHubAppTokenRepository.findByAppIdAndOwner("client_id", "org")).thenReturn(appToken);

        CredentialsProvider provider = gitService.getCredentialsProvider("https://github.com/org/repo.git", vcs);

        assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
        assertNotNull(provider);
        verify(gitHubAppTokenRepository).findByAppIdAndOwner("client_id", "org");
    }

    @Test
    void shouldGetCredentialsProviderForBitbucket() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setAccessToken("bb_token");

        CredentialsProvider provider = gitService.getCredentialsProvider("https://bitbucket.org/org/repo.git", vcs);

        assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
        assertNotNull(provider);
    }

    @Test
    void shouldGetCredentialsProviderForGitlab() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        vcs.setAccessToken("gl_token");

        CredentialsProvider provider = gitService.getCredentialsProvider("https://gitlab.com/org/repo.git", vcs);

        assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
        assertNotNull(provider);
    }

    @Test
    void shouldGetCredentialsProviderForAzureDevOps() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_DEVOPS);
        vcs.setAccessToken("az_token");

        CredentialsProvider provider = gitService.getCredentialsProvider("https://dev.azure.com/org/project/_git/repo", vcs);

        assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
        assertNotNull(provider);
    }

    @Test
    void shouldGetCredentialsProviderForAzureSpMi() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_SP_MI);

        when(azDevOpsTokenService.getAzureDefaultToken()).thenReturn("az_sp_token");

        CredentialsProvider provider = gitService.getCredentialsProvider("https://dev.azure.com/org/project/_git/repo", vcs);

        assertInstanceOf(UsernamePasswordCredentialsProvider.class, provider);
        assertNotNull(provider);
        verify(azDevOpsTokenService).getAzureDefaultToken();
    }

    @Test
    void shouldReturnTrueWhenAccessTokenIsValid() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(VcsConnectionType.OAUTH);
        vcs.setAccessToken("gh_token");

        String gitPath = "https://github.com/org/repo.git";

        try (MockedStatic<Git> mockedGit = mockStatic(Git.class)) {
            LsRemoteCommand lsRemoteCommand = mock(LsRemoteCommand.class);
            mockedGit.when(Git::lsRemoteRepository).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setHeads(anyBoolean())).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
            
            Map<String, Ref> remoteRefs = new HashMap<>();
            remoteRefs.put("refs/heads/main", mock(Ref.class));
            when(lsRemoteCommand.callAsMap()).thenReturn(remoteRefs);

            boolean isValid = gitService.isAccessTokenValid(gitPath, vcs);

            assertTrue(isValid);
        }
    }

    @Test
    void shouldReturnFalseWhenAccessTokenIsInvalid() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(VcsConnectionType.OAUTH);
        vcs.setAccessToken("gh_token");

        String gitPath = "https://github.com/org/repo.git";

        try (MockedStatic<Git> mockedGit = mockStatic(Git.class)) {
            LsRemoteCommand lsRemoteCommand = mock(LsRemoteCommand.class);
            mockedGit.when(Git::lsRemoteRepository).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setHeads(anyBoolean())).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);
            
            when(lsRemoteCommand.callAsMap()).thenReturn(Collections.emptyMap());

            boolean isValid = gitService.isAccessTokenValid(gitPath, vcs);

            assertFalse(isValid);
        }
    }

    @Test
    void shouldReturnFalseWhenTransportExceptionOccurs() throws Exception {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(VcsConnectionType.OAUTH);
        vcs.setAccessToken("gh_token");

        String gitPath = "https://github.com/org/repo.git";

        try (MockedStatic<Git> mockedGit = mockStatic(Git.class)) {
            LsRemoteCommand lsRemoteCommand = mock(LsRemoteCommand.class);
            mockedGit.when(Git::lsRemoteRepository).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setHeads(anyBoolean())).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setRemote(anyString())).thenReturn(lsRemoteCommand);
            when(lsRemoteCommand.setCredentialsProvider(any())).thenReturn(lsRemoteCommand);

            when(lsRemoteCommand.callAsMap()).thenThrow(new TransportException("Connection error"));

            boolean isValid = gitService.isAccessTokenValid(gitPath, vcs);

            assertFalse(isValid);
        }
    }
}
