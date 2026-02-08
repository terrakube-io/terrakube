package storage

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"

	"cloud.google.com/go/storage"
	"github.com/terrakube-io/terrakube/registry-go/internal/git"
	"github.com/terrakube-io/terrakube/registry-go/internal/utils"
	"google.golang.org/api/option"
)

type GCPStorageService struct {
	Client     *storage.Client
	BucketName string
	ProjectId  string
	Hostname   string
	GitService git.GitService
}

func NewGCPStorageService(ctx context.Context, projectId, bucketName, credentials, hostname string) (*GCPStorageService, error) {
	var client *storage.Client
	var err error

	if credentials != "" {
		// Try as JSON content first, if fails assume file path?
		// Or simply stick to what the Java version likely does.
		// Usually credentials env var is the JSON content.
		client, err = storage.NewClient(ctx, option.WithCredentialsJSON([]byte(credentials)))
		if err != nil {
			// Fallback: maybe it's a file path?
			client, err = storage.NewClient(ctx, option.WithCredentialsFile(credentials))
		}
	} else {
		// Use default credentials
		client, err = storage.NewClient(ctx)
	}

	if err != nil {
		return nil, fmt.Errorf("failed to create GCP client: %v", err)
	}

	return &GCPStorageService{
		Client:     client,
		BucketName: bucketName,
		ProjectId:  projectId,
		Hostname:   hostname,
		GitService: git.NewService(),
	}, nil
}

func (s *GCPStorageService) SearchModule(org, module, provider, version, source, vcsType, accessToken, tagPrefix, folder string) (string, error) {
	key := fmt.Sprintf("registry/%s/%s/%s/%s/module.zip", org, module, provider, version)
	path := fmt.Sprintf("%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip", s.Hostname, org, module, provider, version)

	ctx := context.TODO()
	bh := s.Client.Bucket(s.BucketName)
	obj := bh.Object(key)

	// Check if object exists
	_, err := obj.Attrs(ctx)
	if err == nil {
		return path, nil
	}

	log.Printf("Module %s not found in GCP storage, initiating clone...", key)

	// Clone
	cloneDir, err := s.GitService.CloneRepository(source, version, vcsType, accessToken, tagPrefix, folder)
	if err != nil {
		return "", fmt.Errorf("failed to clone repository: %w", err)
	}
	defer os.RemoveAll(cloneDir) // Cleanup

	// Zip
	zipPath := cloneDir + ".zip"
	if err := utils.ZipDirectory(cloneDir, zipPath); err != nil {
		return "", fmt.Errorf("failed to zip directory: %w", err)
	}
	defer os.Remove(zipPath) // Cleanup zip file

	// Upload
	file, err := os.Open(zipPath)
	if err != nil {
		return "", fmt.Errorf("failed to open zip file: %w", err)
	}
	defer file.Close()

	wc := obj.NewWriter(ctx)
	if _, err = io.Copy(wc, file); err != nil {
		return "", fmt.Errorf("failed to write to GCP bucket: %w", err)
	}
	if err := wc.Close(); err != nil {
		return "", fmt.Errorf("failed to close GCP writer: %w", err)
	}

	log.Printf("Uploaded module to GCP: %s", key)
	return path, nil
}

func (s *GCPStorageService) DownloadModule(org, module, provider, version string) (io.ReadCloser, error) {
	key := fmt.Sprintf("registry/%s/%s/%s/%s/module.zip", org, module, provider, version)

	rc, err := s.Client.Bucket(s.BucketName).Object(key).NewReader(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to download module from GCP: %w", err)
	}
	return rc, nil
}
