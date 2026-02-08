package git

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

type GitService interface {
	CloneRepository(source, version, vcsType, accessToken, tagPrefix, folder string) (string, error)
}

type Service struct{}

func NewService() *Service {
	return &Service{}
}

func (s *Service) CloneRepository(source, version, vcsType, accessToken, tagPrefix, folder string) (string, error) {
	tempDir, err := os.MkdirTemp("", "terrakube-registry")
	if err != nil {
		return "", fmt.Errorf("failed to create temp dir: %w", err)
	}

	// Prepare git clone arguments
	// Logic to handle accessToken injection based on vcsType (public, github, gitlab, bitbucket, etc.)
	// This is a simplified version.

	repoURL := source
	if accessToken != "" && vcsType != "PUBLIC" {
		// Basic auth injection for HTTPS
		// Example: https://token@github.com/org/repo.git
		if strings.HasPrefix(source, "https://") {
			repoURL = strings.Replace(source, "https://", fmt.Sprintf("https://oauth2:%s@", accessToken), 1)
		}
	}

	cloneCmd := exec.Command("git", "clone", "--depth", "1", "--branch", tagPrefix+version, repoURL, tempDir)
	cloneCmd.Env = os.Environ() // Pass env for SSH execution if needed

	if output, err := cloneCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("git clone failed: %s: %w", string(output), err)
	}

	// If folder is specified, we might need traverse inside?
	// But usually, we zip the whole repo or subfolder.
	// Logic to return correct path.

	if folder != "" {
		return fmt.Sprintf("%s/%s", tempDir, folder), nil
	}

	return tempDir, nil
}
