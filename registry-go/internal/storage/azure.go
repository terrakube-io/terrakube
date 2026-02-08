package storage

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/terrakube-io/terrakube/registry-go/internal/git"
	"github.com/terrakube-io/terrakube/registry-go/internal/utils"
)

type AzureStorageService struct {
	Client        *azblob.Client
	ContainerName string
	Hostname      string
	GitService    git.GitService
}

func NewAzureStorageService(accountName, accountKey, containerName, hostname string) (*AzureStorageService, error) {
	// Create a credential object using the account name and key
	credential, err := azblob.NewSharedKeyCredential(accountName, accountKey)
	if err != nil {
		return nil, fmt.Errorf("invalid azure credentials: %v", err)
	}

	// Create the client
	url := fmt.Sprintf("https://%s.blob.core.windows.net/", accountName)
	client, err := azblob.NewClientWithSharedKeyCredential(url, credential, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create azure client: %v", err)
	}

	return &AzureStorageService{
		Client:        client,
		ContainerName: containerName,
		Hostname:      hostname,
		GitService:    git.NewService(),
	}, nil
}

func (s *AzureStorageService) SearchModule(org, module, provider, version, source, vcsType, accessToken, tagPrefix, folder string) (string, error) {
	key := fmt.Sprintf("registry/%s/%s/%s/%s/module.zip", org, module, provider, version)
	path := fmt.Sprintf("%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip", s.Hostname, org, module, provider, version)

	// Check if object exists
	// We can use DownloadStream with a range of 0 to check existence or GetProperties
	// GetProperties is better
	blobClient := s.Client.ServiceClient().NewContainerClient(s.ContainerName).NewBlobClient(key)
	_, err := blobClient.GetProperties(context.TODO(), nil)

	if err == nil {
		return path, nil
	}

	log.Printf("Module %s not found in Azure storage, initiating clone...", key)

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

	_, err = s.Client.UploadFile(context.TODO(), s.ContainerName, key, file, nil)
	if err != nil {
		return "", fmt.Errorf("failed to upload to Azure Blob Storage: %w", err)
	}

	log.Printf("Uploaded module to Azure: %s", key)
	return path, nil
}

func (s *AzureStorageService) DownloadModule(org, module, provider, version string) (io.ReadCloser, error) {
	key := fmt.Sprintf("registry/%s/%s/%s/%s/module.zip", org, module, provider, version)

	// DownloadStream returns a DownloadStreamResponse which contains Body (io.ReadCloser)
	resp, err := s.Client.DownloadStream(context.TODO(), s.ContainerName, key, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to download module from Azure: %w", err)
	}

	return resp.Body, nil
}
