package io.terrakube.executor.service.terraform.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the archive filename format and binary layout terraform-client actually uses, without
 * touching the filesystem or the real host OS/arch (see issue #3621's design notes on why
 * Terraform and OpenTofu are not symmetric here).
 */
class BinaryArchiveLocatorTest {

    @Test
    void terraformArchiveNameHasNoVPrefixAndSitsDirectlyUnderDownload() {
        assertEquals("terraform_1.9.0_linux_amd64.zip",
                BinaryArchiveLocator.archiveFileName("1.9.0", false, "linux", "amd64"));
    }

    @Test
    void tofuArchiveNameKeepsTheVPrefixFromTheResolvedVersion() {
        // resolveTofuVersion() returns GitHub's own tag format (e.g. "v1.13.0"), unlike
        // Terraform's bare semver - the locator must not add or strip it.
        assertEquals("tofu_v1.13.0_linux_amd64.zip",
                BinaryArchiveLocator.archiveFileName("v1.13.0", true, "linux", "amd64"));
    }

    @Test
    void archiveNameVariesByOsAndArch() {
        assertEquals("terraform_1.9.0_darwin_arm64.zip",
                BinaryArchiveLocator.archiveFileName("1.9.0", false, "darwin", "arm64"));
        assertEquals("tofu_v1.13.0_windows_amd64.zip",
                BinaryArchiveLocator.archiveFileName("v1.13.0", true, "windows", "amd64"));
    }

    @Test
    void archFallsBackToRawOsArchExceptAarch64() {
        String previous = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "aarch64");
            assertEquals("arm64", BinaryArchiveLocator.arch());
            System.setProperty("os.arch", "amd64");
            assertEquals("amd64", BinaryArchiveLocator.arch());
        } finally {
            if (previous != null) {
                System.setProperty("os.arch", previous);
            }
        }
    }

    @Test
    void archThrowsWhenOsArchIsUnavailable() {
        String previous = System.getProperty("os.arch");
        try {
            System.clearProperty("os.arch");
            assertThrows(IllegalStateException.class, BinaryArchiveLocator::arch);
        } finally {
            if (previous != null) {
                System.setProperty("os.arch", previous);
            }
        }
    }

    @Test
    void osNameMapsToLinuxDarwinOrWindows() {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            assertEquals("linux", BinaryArchiveLocator.os());
            System.setProperty("os.name", "Mac OS X");
            assertEquals("darwin", BinaryArchiveLocator.os());
            System.setProperty("os.name", "Windows 11");
            assertEquals("windows", BinaryArchiveLocator.os());
            System.setProperty("os.name", "SomeOtherUnix");
            assertEquals("linux", BinaryArchiveLocator.os());
        } finally {
            System.setProperty("os.name", previous);
        }
    }

    @Test
    void locateBuildsTheDownloadAndBinaryPathsTerraformClientUses(@TempDir Path home) {
        BinaryArchiveLocator locator = new BinaryArchiveLocator(home.toString());

        BinaryArchiveLocator.Archive terraform = locator.locate("1.9.0", false);
        File expectedTerraformArchive = new File(home.toFile(),
                ".terraform-spring-boot/download/terraform_1.9.0_" + BinaryArchiveLocator.os() + "_" + BinaryArchiveLocator.arch() + ".zip");
        assertEquals(expectedTerraformArchive.getPath(), terraform.file().getPath());
        assertEquals(new File(home.toFile(), ".terraform-spring-boot/terraform/1.9.0").getPath(),
                terraform.binaryVersionDirectory().getPath());
        assertEquals("terraform", terraform.executableName());
    }

    @Test
    void locateBuildsTheTofuDownloadAndBinaryPathsUnderTheirOwnSubdirectories(@TempDir Path home) {
        BinaryArchiveLocator locator = new BinaryArchiveLocator(home.toString());

        BinaryArchiveLocator.Archive tofu = locator.locate("v1.13.0", true);
        File expectedTofuArchive = new File(home.toFile(),
                ".terraform-spring-boot/download/tofu/tofu_v1.13.0_" + BinaryArchiveLocator.os() + "_" + BinaryArchiveLocator.arch() + ".zip");
        assertEquals(expectedTofuArchive.getPath(), tofu.file().getPath());
        assertEquals(new File(home.toFile(), ".terraform-spring-boot/tofu/v1.13.0").getPath(),
                tofu.binaryVersionDirectory().getPath());
        assertEquals("tofu", tofu.executableName());
    }
}
