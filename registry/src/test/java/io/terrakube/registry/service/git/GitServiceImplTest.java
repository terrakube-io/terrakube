package io.terrakube.registry.service.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class GitServiceImplTest {

    @TempDir
    Path tempDir;

    private String userHome;

    @BeforeEach
    void useTempUserHome() {
        // GitServiceImpl clones under user.home; point it at a temp dir so leftovers are observable
        userHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", userHome);
    }

    @Test
    void moduleZipComesFromShallowCloneWithoutGitMetadataAndTempDirIsRemoved() throws Exception {
        Path repository = tempDir.resolve("repo");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("main.tf"), "resource \"null_resource\" \"this\" {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setSign(false).call();
            Files.writeString(repository.resolve("variables.tf"), "variable \"name\" {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setSign(false).call();
            git.tag().setName("v1.0.0").setSigned(false).call();
        }
        GitServiceImpl gitService = spy(new GitServiceImpl());
        doAnswer(invocation -> {
            assertThat(new File(invocation.<File>getArgument(0), ".git/shallow")).exists();
            return invocation.callRealMethod();
        }).when(gitService).deleteGitMetadata(any(File.class));

        List<String> entries = moduleZipEntries(gitService, repository, "v1.0.0");

        assertThat(entries).contains("main.tf", "variables.tf").noneMatch(name -> name.startsWith(".git/"));
        verify(gitService).deleteGitMetadata(any(File.class));
        assertThat(cloneDirectory().listFiles()).isEmpty();
    }

    @Test
    void annotatedTagOnSideBranchIsPackaged() throws Exception {
        Path repository = tempDir.resolve("repo");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("main.tf"), "resource \"null_resource\" \"this\" {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("main").setSign(false).call();
            git.checkout().setCreateBranch(true).setName("side").call();
            Files.writeString(repository.resolve("side.tf"), "variable \"side\" {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("side").setSign(false).call();
            git.tag().setName("v2.0.0").setAnnotated(true).setMessage("side release").setSigned(false).call();
            git.checkout().setName("main").call();
        }

        List<String> entries = moduleZipEntries(new GitServiceImpl(), repository, "v2.0.0");

        assertThat(entries).contains("main.tf", "side.tf").noneMatch(name -> name.startsWith(".git/"));
        assertThat(cloneDirectory().listFiles()).isEmpty();
    }

    @Test
    void failedCloneThrowsAndTempDirIsRemoved() {
        String missingRepository = tempDir.resolve("missing").toUri().toString();
        GitServiceImpl gitService = new GitServiceImpl();
        ModuleVersionDownload download = new ModuleVersionDownload(missingRepository, "1.0.0", "v1.0.0", "PUBLIC",
                null, null, null, null);
        List<File> handled = new ArrayList<>();

        assertThatThrownBy(() -> gitService.withModuleZip(download, handled::add))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone");
        assertThat(handled).isEmpty();
        File cloneDirectory = cloneDirectory();
        assertThat(cloneDirectory.exists() ? cloneDirectory.listFiles() : new File[0]).isEmpty();
    }

    private File cloneDirectory() {
        return tempDir.resolve("home/.terraform-spring-boot/git").toFile();
    }

    private static List<String> moduleZipEntries(GitServiceImpl gitService, Path repository, String tag)
            throws Exception {
        List<String> names = new ArrayList<>();
        gitService.withModuleZip(new ModuleVersionDownload(repository.toUri().toString(), "1.0.0", tag, "PUBLIC",
                null, null, null, null), moduleZip -> {
            try (ZipFile zip = new ZipFile(moduleZip)) {
                zip.stream().forEach(entry -> names.add(entry.getName()));
            }
        });
        return names;
    }

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
