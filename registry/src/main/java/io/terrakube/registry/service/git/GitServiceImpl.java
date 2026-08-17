package io.terrakube.registry.service.git;

import io.terrakube.storage.plugin.core.GitService;
import io.terrakube.storage.plugin.core.ModuleVersionDownload;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.CloneCommand;
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
    public File getCloneRepositoryByTag(ModuleVersionDownload download) {
        String repository = download.repository();
        String vcsType = download.vcsType();
        String vcsConnectionType = download.vcsConnectionType();
        String accessToken = download.accessToken();
        String tagPrefix = download.tagPrefix();
        String folder = download.folder();
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

            String gitTag = download.gitTag();
            String correctTag = (gitTag != null && !gitTag.isEmpty())
                    ? gitTag
                    : validateCorrectTag(download.version(), repository, vcsType, vcsConnectionType, accessToken, tempFolder, tagPrefix);

            log.info("Cloning {} using {}", repository, correctTag);

            CredentialsProvider credentialsProvider = setupCredentials(vcsType, vcsConnectionType, accessToken);
            TransportConfigCallback transportConfigCallback = setupTransportConfigCallback(vcsType, accessToken, tempFolder);

            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(repository)
                    .setDirectory(gitCloneRepository)
                    .setBranch("refs/tags/" + correctTag);

            if (credentialsProvider != null) {
                cloneCommand.setCredentialsProvider(credentialsProvider);
            }

            if (transportConfigCallback != null) {
                cloneCommand.setTransportConfigCallback(transportConfigCallback);
            }

            cloneCommand.call();

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
        if (accessToken == null || accessToken.isEmpty())
            return null;
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
            case "AZURE_DEVOPS":
                credentialsProvider = new UsernamePasswordCredentialsProvider("dummy", accessToken);
                break;
            case "AZURE_SP_MI":
                credentialsProvider = new UsernamePasswordCredentialsProvider("dummy", getAzureDefaultToken());
                break;
            default:
                credentialsProvider = null;
                break;
        }
        return credentialsProvider;
    }

    @SuppressWarnings("unchecked")
    public String getAzureDefaultToken() {
        log.info("Getting Azure Default Token");
        String AZURE_DEVOPS_SCOPE = "499b84ac-1321-427f-aa17-267ca6975798/.default"; // Azure DevOps scope
        try {
            Class<?> builderClass = Class.forName("com.azure.identity.DefaultAzureCredentialBuilder");
            Object credentialBuilder = builderClass.getDeclaredConstructor().newInstance();

            String proxyHost = System.getProperty("http.proxyHost");
            String proxyPort = System.getProperty("http.proxyPort");
            if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null && !proxyPort.isEmpty()) {
                Class<?> proxyOptionsClass = Class.forName("com.azure.core.http.ProxyOptions");
                Class<?> proxyTypeClass = Class.forName("com.azure.core.http.ProxyOptions$Type");
                Object httpType = Enum.valueOf((Class<Enum>) proxyTypeClass, "HTTP");
                Object proxyOptions = proxyOptionsClass.getConstructor(proxyTypeClass, InetSocketAddress.class)
                        .newInstance(httpType, new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort)));

                String proxyUser = System.getProperty("http.proxyUser");
                String proxyPassword = System.getProperty("http.proxyPassword");
                if (proxyUser != null && !proxyUser.isEmpty() && proxyPassword != null && !proxyPassword.isEmpty()) {
                    proxyOptionsClass.getMethod("setCredentials", String.class, String.class)
                            .invoke(proxyOptions, proxyUser, proxyPassword);
                }

                Class<?> nettyBuilderClass = Class.forName("com.azure.core.http.netty.NettyAsyncHttpClientBuilder");
                Object nettyBuilder = nettyBuilderClass.getDeclaredConstructor().newInstance();
                nettyBuilderClass.getMethod("proxy", proxyOptionsClass).invoke(nettyBuilder, proxyOptions);
                Object httpClient = nettyBuilderClass.getMethod("build").invoke(nettyBuilder);

                Class<?> httpClientClass = Class.forName("com.azure.core.http.HttpClient");
                builderClass.getMethod("httpClient", httpClientClass).invoke(credentialBuilder, httpClient);
            }

            Object credential = builderClass.getMethod("build").invoke(credentialBuilder);
            Class<?> tokenRequestContextClass = Class.forName("com.azure.core.credential.TokenRequestContext");
            Object requestContext = tokenRequestContextClass.getDeclaredConstructor().newInstance();
            tokenRequestContextClass.getMethod("setScopes", List.class)
                    .invoke(requestContext, Collections.singletonList(AZURE_DEVOPS_SCOPE));

            Class<?> tokenCredentialClass = Class.forName("com.azure.core.credential.TokenCredential");
            Object mono = tokenCredentialClass.getMethod("getToken", tokenRequestContextClass).invoke(credential, requestContext);
            if (mono != null) {
                Class<?> monoClass = Class.forName("reactor.core.publisher.Mono");
                Object accessTokenObj = monoClass.getMethod("block").invoke(mono);
                if (accessTokenObj != null) {
                    Class<?> accessTokenClass = Class.forName("com.azure.core.credential.AccessToken");
                    String token = (String) accessTokenClass.getMethod("getToken").invoke(accessTokenObj);
                    log.debug("Azure Default Token: {}", token);
                    return token != null ? token : "";
                }
            }
            throw new Exception("Failed to acquire Azure Managed Identity token. Check your environment configuration.");
        } catch (ClassNotFoundException e) {
            log.warn("Azure Identity SDK not present on classpath: {}", e.getMessage());
            return "";
        } catch (Exception ex) {
            log.error("Error getting Azure Default Token: {}", ex.getMessage());
            return "";
        }
    }

    private String validateCorrectTag(String originalTag, String repository, String vcsType, String vcsConnectionType, String accessToken,
            String folderName, String tagPrefix) {
        List<String> versionList = new ArrayList<>();
        String finalTag = (tagPrefix == null ? "" : tagPrefix) + originalTag;
        log.info("Checking if tag {} exists in repository {}", finalTag, repository);
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

        log.debug("Current repository tags {}", versionList);

        if (versionList.contains((tagPrefix == null ? "" : tagPrefix) + "v" + originalTag)) {
            finalTag = (tagPrefix == null ? "" : tagPrefix) + "v" + originalTag;
            log.info("Tag with letter v exists: {}", finalTag);
        } else if (versionList.contains((tagPrefix == null ? "" : tagPrefix) + originalTag)) {
            finalTag = (tagPrefix == null ? "" : tagPrefix) + originalTag;
            log.info("Tag without letter v exists: {}", finalTag);
        } else {
            log.info("Tag {} does not exist", finalTag);
            throw new RuntimeException(
                    String.format("Tag %s does not exist in repository %s", finalTag, repository));
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
