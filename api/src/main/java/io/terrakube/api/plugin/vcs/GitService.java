package io.terrakube.api.plugin.vcs;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@AllArgsConstructor
public class GitService {

    private AzDevOpsTokenService azDevOpsTokenService;
    private GitHubAppTokenRepository gitHubAppTokenRepository;

    /**
     * Return the credentials provider for a given VCS connection type
     * @param gitPath
     * @param vcs
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     * @throws JsonProcessingException
     * @throws URISyntaxException
     */
    public CredentialsProvider getCredentialsProvider(String gitPath, Vcs vcs) throws NoSuchAlgorithmException, InvalidKeySpecException, JsonProcessingException, URISyntaxException, GitAPIException {
        log.info("Generating Git credentials provider for VCS {}", vcs.getVcsType().toString());
        CredentialsProvider credentialsProvider = null;
        switch (vcs.getVcsType()) {
            case GITHUB:
                if (vcs.getConnectionType() == VcsConnectionType.OAUTH) {
                    credentialsProvider = new UsernamePasswordCredentialsProvider(
                            vcs.getAccessToken(),
                            ""
                    );
                } else { // This is a special case for GitHub Apps
                    URI uri = new URI(gitPath);
                    String[] ownerAndRepo = Arrays.copyOfRange(uri.getPath().replaceAll("\\.git$", "").split("/"), 1, 3);

                    log.info("Getting access token for user/organization {} and vcs {}", ownerAndRepo[0], vcs.getId());
                    GitHubAppToken gitHubAppToken = gitHubAppTokenRepository.findByAppIdAndOwner(vcs.getClientId(), ownerAndRepo[0]);

                        credentialsProvider = new UsernamePasswordCredentialsProvider(
                                "x-access-token",
                                gitHubAppToken.getToken()
                        );
                }
                break;
            case BITBUCKET:
                credentialsProvider = new UsernamePasswordCredentialsProvider(
                        "x-token-auth",
                        vcs.getAccessToken()
                );
                break;
            case GITLAB:
                credentialsProvider = new UsernamePasswordCredentialsProvider(
                        "oauth2",
                        vcs.getAccessToken()
                );
                break;
            case AZURE_DEVOPS:
                credentialsProvider = new UsernamePasswordCredentialsProvider(
                        "dummy",
                        vcs.getAccessToken()
                );
                break;
            case AZURE_SP_MI:
                credentialsProvider = new UsernamePasswordCredentialsProvider(
                        "dummy",
                        azDevOpsTokenService.getAzureDefaultToken()
                );
                break;
            default:
                credentialsProvider = null;
                break;
        }

        return credentialsProvider;
    }

    /**
     * Return if a token is valid for a given repository using a VCS connection
     * @param gitPath
     * @param vcs
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     * @throws URISyntaxException
     * @throws JsonProcessingException
     * @throws GitAPIException
     */
    public boolean isAccessTokenValid(String gitPath, Vcs vcs) throws NoSuchAlgorithmException, InvalidKeySpecException, URISyntaxException, JsonProcessingException, GitAPIException {
        List<String> branches = getRepositoryBranches(gitPath, vcs);
        if (branches.isEmpty()) {
            log.error("Token no longer valid for repository {}", gitPath);
            return false;
        } else {
            log.info("Token still valid for repository {}", gitPath);
            return true;
        }
    }

    /**
     * This method will simply fetch all branches for a given repository if the app is not able to connect the access token is invalid or the repository doesn't exist
     * @param gitPath
     * @param vcs
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     * @throws URISyntaxException
     * @throws JsonProcessingException
     * @throws GitAPIException
     */
    private List<String> getRepositoryBranches(String gitPath, Vcs vcs) throws NoSuchAlgorithmException, InvalidKeySpecException, URISyntaxException, JsonProcessingException, GitAPIException {
        List<String> branchList = new ArrayList<>();
        CredentialsProvider credentialsProvider = getCredentialsProvider(gitPath, vcs);
        try {
            Map<String, Ref> remoteRefs = Git.lsRemoteRepository()
                    .setHeads(true)
                    .setRemote(gitPath)
                    .setCredentialsProvider(credentialsProvider)
                    .callAsMap();

            remoteRefs.forEach((key, _) -> branchList.add(key.replace("refs/heads/", "")));

        } catch (TransportException e) {
            log.error("Error connecting to remote repository: {}", e.getMessage());
        }
        return branchList;
    }


}
