package io.terrakube.executor.service.terraform.cache;

import java.io.File;

/**
 * Reconstructs the local archive/binary paths {@code terraform-client} uses - it has no API to
 * ask for them directly. Terraform and OpenTofu aren't symmetric: Terraform's archive name comes
 * straight from HashiCorp's release index ({@code terraform_<version>_<os>_<arch>.zip}, no
 * {@code v}), OpenTofu's is built here as {@code tofu_<version>_<os>_<arch>.zip} where the version
 * keeps the {@code v} its GitHub tags use. Pass the version exactly as {@code TerraformDownloader}
 * resolved it - this class never adds or strips the {@code v}.
 */
public class BinaryArchiveLocator {

    private static final String DOWNLOAD_DIR = "/.terraform-spring-boot/download/";
    private static final String TOFU_DOWNLOAD_DIR = "/.terraform-spring-boot/download/tofu/";
    private static final String TERRAFORM_DIR = "/.terraform-spring-boot/terraform/";
    private static final String TOFU_DIR = "/.terraform-spring-boot/tofu/";

    private final String userHomeDirectory;

    public BinaryArchiveLocator(String userHomeDirectory) {
        this.userHomeDirectory = userHomeDirectory;
    }

    /**
     * {@code downloadDirectory}/{@code productDirectory} depend only on {@code tofu}, not on
     * {@code resolvedVersion} - callers use them to check {@code file}/{@code binaryVersionDirectory}
     * didn't resolve outside them before deleting either.
     */
    public record Archive(File file, File downloadDirectory, File binaryVersionDirectory, File productDirectory, String executableName) {
    }

    /** The archive this exact version/product/OS/arch would use - the same one {@code TerraformDownloader} would fetch. */
    public Archive locate(String resolvedVersion, boolean tofu) {
        String fileName = archiveFileName(resolvedVersion, tofu, os(), arch());
        File downloadDir = new File(userHomeDirectory, systemPath(tofu ? TOFU_DOWNLOAD_DIR : DOWNLOAD_DIR));
        File productDir = new File(userHomeDirectory, systemPath(tofu ? TOFU_DIR : TERRAFORM_DIR));
        File binaryDir = new File(productDir, resolvedVersion);
        return new Archive(new File(downloadDir, fileName), downloadDir, binaryDir, productDir, tofu ? "tofu" : "terraform");
    }

    /** Separate from {@link #locate} so it can be unit tested without touching the filesystem or the real OS/arch. */
    static String archiveFileName(String resolvedVersion, boolean tofu, String os, String arch) {
        String product = tofu ? "tofu" : "terraform";
        return String.format("%s_%s_%s_%s.zip", product, resolvedVersion, os, arch);
    }

    /** {@code linux}/{@code darwin}/{@code windows}, matching {@code TerraformDownloader.getOs()}. */
    static String os() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "darwin";
        }
        if (osName.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    /**
     * {@code amd64}/{@code arm64}/etc, matching {@code TerraformDownloader}'s private {@code getArch()}:
     * the raw JVM {@code os.arch} property, with only {@code aarch64} normalised to {@code arm64}.
     */
    static String arch() {
        String osArch = System.getProperty("os.arch");
        if (osArch == null) {
            throw new IllegalStateException("System architecture not detected");
        }
        return "aarch64".equals(osArch) ? "arm64" : osArch;
    }

    private static String systemPath(String unixStylePath) {
        return unixStylePath.replace('/', File.separatorChar);
    }
}
