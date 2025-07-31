package io.terrakube.registry.service.git;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.PublicKey;
import java.util.*;

@Slf4j
@Service
public class GitServiceImpl implements GitService {

    private static final String GIT_DIRECTORY = "/.terraform-spring-boot/git/";
    private static final String SSH_REGISTRY_DIRECTORY = "%s/.terraform-spring-boot/ssh/registry/%s/id_%s";

    @Override
    public File getCloneRepositoryByTag(String repository, String tag, String vcsType, String vcsConnectionType,
            String accessToken, String tagPrefix, String folder) {
        File gitCloneRepository = null;
        try {
            String userHomeDirectory = FileUtils.getUserDirectoryPath();
            String tempFolder = UUID.randomUUID().toString();
            String gitRepositoryPath = userHomeDirectory.concat(
                    FilenameUtils.separatorsToSystem(
                            GIT_DIRECTORY + "/" + tempFolder));

            gitCloneRepository = new File(gitRepositoryPath);
            FileUtils.forceMkdir(gitCloneRepository);
            FileUtils.cleanDirectory(gitCloneRepository);

            String correctTag = validateCorrectTag(tag, repository, vcsType, vcsConnectionType, accessToken, tempFolder, tagPrefix);

            log.info("Cloning {} using {}", repository, correctTag);
            Git.cloneRepository()
                    .setURI(repository)
                    .setDirectory(gitCloneRepository)
                    .setBranch("refs/tags/" + correctTag)
                    .setCredentialsProvider(setupCredentials(vcsType, vcsConnectionType, accessToken))
                    .setTransportConfigCallback(setupTransportConfigCallback(vcsType, accessToken, tempFolder))
                    .call();

            if (folder != null && !folder.isEmpty()) {
                gitRepositoryPath = userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                GIT_DIRECTORY + "/" + tempFolder + folder));
                gitCloneRepository = new File(gitRepositoryPath);
            }

        } catch (GitAPIException | IOException ex) {
            log.error(ex.getMessage());
        }
        return gitCloneRepository;
    }

    private CredentialsProvider setupCredentials(String vcsType, String vcsConnectionType, String accessToken) {
        CredentialsProvider credentialsProvider = null;
        switch (vcsType) {
            case "GITHUB":
                if (vcsConnectionType != null && vcsConnectionType.equals("OAUTH"))
                    credentialsProvider = new UsernamePasswordCredentialsProvider(accessToken, "");
                else
                    credentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token", accessToken);
                break;
            case "BITBUCKET":
                credentialsProvider = new UsernamePasswordCredentialsProvider("x-token-auth", accessToken);
                break;
            case "GITLAB":
                credentialsProvider = new UsernamePasswordCredentialsProvider("oauth2", accessToken);
                break;
            case "AZURE_DEVOPS", "AZURE_SP_MI":
                credentialsProvider = new UsernamePasswordCredentialsProvider("dummy", accessToken);
                log.info(accessToken);
                break;
            default:
                credentialsProvider = null;
                break;
        }
        return credentialsProvider;
    }

    private String validateCorrectTag(String originalTag, String repository, String vcsType, String vcsConnectionType, String accessToken,
            String folderName, String tagPrefix) {
        List<String> versionList = new ArrayList<>();
        String finalTag = (tagPrefix == null ? "" : tagPrefix) + originalTag;
        Map<String, Ref> tags = null;
        try {
            tags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(repository)
                    .setCredentialsProvider(setupCredentials(vcsType, vcsConnectionType, accessToken))
                    .setTransportConfigCallback(setupTransportConfigCallback(vcsType, accessToken, folderName))
                    .callAsMap();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        tags.forEach((key, value) -> {
            versionList.add(key.replace("refs/tags/", ""));
        });

        if (versionList.contains((tagPrefix == null ? "" : tagPrefix) + "v" + originalTag)) {
            finalTag = (tagPrefix == null ? "" : tagPrefix) + "v" + originalTag;
        } else if (!versionList.isEmpty()) {
            finalTag = versionList.get(versionList.size() - 1);
        }

        return finalTag;
    }

    private TransportConfigCallback setupTransportConfigCallback(String vcsType, String privateKey, String folderName) {
        if (vcsType.startsWith("SSH")) {
            SshdSessionFactory registrySshFactory = new SshdSessionFactoryBuilder()
                    .setServerKeyDatabase((h, s) -> new ServerKeyDatabase() {
                        @Override
                        public List<PublicKey> lookup(String connectAddress,
                                InetSocketAddress remoteAddress,
                                Configuration config) {
                            return Collections.emptyList();
                        }

                        @Override
                        public boolean accept(String connectAddress,
                                InetSocketAddress remoteAddress,
                                PublicKey serverKey, Configuration config,
                                CredentialsProvider provider) {
                            return true;
                        }

                    })
                    .setPreferredAuthentications("publickey")
                    .setSshDirectory(generateRegistrySshFolder(vcsType, privateKey, folderName))
                    .setHomeDirectory(FS.DETECTED.userHome())
                    .build(new JGitKeyCache());
            return transport -> {
                ((SshTransport) transport).setSshSessionFactory(registrySshFactory);
            };
        } else {
            return null;
        }
    }

    private File generateRegistrySshFolder(String vcsType, String privateKey, String folderName) {
        String sshFileName = vcsType.split("~")[1];
        String sshFilePath = String.format(SSH_REGISTRY_DIRECTORY, FileUtils.getUserDirectoryPath(), folderName,
                sshFileName);
        File sshFile = new File(sshFilePath);
        log.info("Creating new SSH folder for registry {}", sshFilePath);
        try {
            FileUtils.forceMkdirParent(sshFile);
            FileUtils.writeStringToFile(sshFile, privateKey, Charset.defaultCharset());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sshFile.getParentFile();
    }

}
