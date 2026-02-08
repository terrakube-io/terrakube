package main

import (
	"context"
	"fmt"
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/terrakube-io/terrakube/registry-go/internal/client"
	"github.com/terrakube-io/terrakube/registry-go/internal/config"
	"github.com/terrakube-io/terrakube/registry-go/internal/storage"
)

func main() {
	cfg := config.LoadConfig()

	r := gin.Default()

	// Health check
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status": "UP",
		})
	})

	// Terraform Registry Service Discovery
	r.GET("/.well-known/terraform.json", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"modules.v1":   "/terraform/modules/v1/",
			"providers.v1": "/terraform/providers/v1/",
		})
	})

	apiClient := client.NewClient(cfg.AzBuilderApiUrl)

	// Initialize Storage Service
	var storageService storage.StorageService
	var err error

	switch cfg.RegistryStorageType {
	case "AWS", "AwsStorageImpl":
		storageService, err = storage.NewAWSStorageService(
			context.TODO(), // TODO: Use proper context
			cfg.AwsRegion,
			cfg.AwsBucketName,
			cfg.AzBuilderRegistry,
			cfg.AwsEndpoint,
			cfg.AwsAccessKey,
			cfg.AwsSecretKey,
		)
	case "AZURE", "AzureStorageImpl":
		storageService, err = storage.NewAzureStorageService(
			cfg.AzureStorageAccountName,
			cfg.AzureStorageAccountKey,
			cfg.AzureStorageContainerName,
			cfg.AzBuilderRegistry,
		)
	case "GCP", "GcpStorageImpl":
		storageService, err = storage.NewGCPStorageService(
			context.TODO(),
			cfg.GcpStorageProjectId,
			cfg.GcpStorageBucketName,
			cfg.GcpStorageCredentials,
			cfg.AzBuilderRegistry,
		)
	default:
		log.Fatalf("Unknown RegistryStorageType: %s. Supported values: AWS, AZURE, GCP", cfg.RegistryStorageType)
	}

	if err != nil {
		log.Fatalf("Failed to initialize storage service (%s): %v", cfg.RegistryStorageType, err)
	}

	// List Module Versions
	r.GET("/terraform/modules/v1/:org/:name/:provider/versions", func(c *gin.Context) {
		org := c.Param("org")
		name := c.Param("name")
		provider := c.Param("provider")

		versions, err := apiClient.GetModuleVersions(org, name, provider)
		if err != nil {
			log.Printf("Error fetching versions: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch versions"})
			return
		}

		var versionDTOs []gin.H
		for _, v := range versions {
			versionDTOs = append(versionDTOs, gin.H{"version": v})
		}

		c.JSON(http.StatusOK, gin.H{
			"modules": []gin.H{
				{
					"versions": versionDTOs,
				},
			},
		})
	})

	// Download Module Version
	r.GET("/terraform/modules/v1/:org/:name/:provider/:version/download", func(c *gin.Context) {
		org := c.Param("org")
		name := c.Param("name")
		provider := c.Param("provider")
		version := c.Param("version")

		// Get Module Details for Source/VCS info
		moduleDetails, err := apiClient.GetModule(org, name, provider)
		if err != nil {
			log.Printf("Error fetching module details: %v", err)
			c.JSON(http.StatusNotFound, gin.H{"error": "Module not found"})
			return
		}

		// Prepare args for SearchModule
		source := moduleDetails.Source
		folder := moduleDetails.Folder
		tagPrefix := moduleDetails.TagPrefix
		vcsType := "PUBLIC"
		accessToken := ""

		if moduleDetails.Vcs != nil {
			vcsType = moduleDetails.Vcs.VcsType
			accessToken = moduleDetails.Vcs.AccessToken
			// TODO: Handle OAuth/App Token logic if accessToken is empty but clientId present?
			// The original Java code has complex logic for GitHub App tokens.
			// For this MVP, we use what we get.
		} else if moduleDetails.Ssh != nil {
			vcsType = "SSH~" + moduleDetails.Ssh.SshType
			accessToken = moduleDetails.Ssh.PrivateKey
		}

		path, err := storageService.SearchModule(org, name, provider, version, source, vcsType, accessToken, tagPrefix, folder)
		if err != nil {
			log.Printf("Error searching/processing module: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to process module download"})
			return
		}

		c.Header("X-Terraform-Get", path)
		c.Status(http.StatusNoContent)
	})

	// Download Module Zip (Actual File)
	r.GET("/terraform/modules/v1/download/:org/:name/:provider/:version/module.zip", func(c *gin.Context) {
		org := c.Param("org")
		name := c.Param("name")
		provider := c.Param("provider")
		version := c.Param("version")

		reader, err := storageService.DownloadModule(org, name, provider, version)
		if err != nil {
			log.Printf("Error downloading module zip: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to download module zip"})
			return
		}
		defer reader.Close()

		c.Header("Content-Type", "application/zip")
		c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s-%s-%s-%s.zip\"", org, name, provider, version))

		extraHeaders := map[string]string{
			"X-Terraform-Get": "", // Clear this if it was set
		}

		c.DataFromReader(http.StatusOK, -1, "application/zip", reader, extraHeaders)
	})

	// Terraform Provider Registry Service Discovery
	// Providers endpoints
	r.GET("/terraform/providers/v1/:org/:provider/versions", func(c *gin.Context) {
		org := c.Param("org")
		provider := c.Param("provider")

		versions, err := apiClient.GetProviderVersions(org, provider)
		if err != nil {
			log.Printf("Error fetching provider versions: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch provider versions"})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"versions": versions,
		})
	})

	r.GET("/terraform/providers/v1/:org/:provider/:version/download/:os/:arch", func(c *gin.Context) {
		org := c.Param("org")
		provider := c.Param("provider")
		version := c.Param("version")
		os := c.Param("os")
		arch := c.Param("arch")

		fileData, err := apiClient.GetProviderFile(org, provider, version, os, arch)
		if err != nil {
			log.Printf("Error fetching provider file info: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch provider file info"})
			return
		}

		c.JSON(http.StatusOK, fileData)
	})

	log.Printf("Starting Registry Service on port %s", cfg.Port)
	if err := r.Run(fmt.Sprintf(":%s", cfg.Port)); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
