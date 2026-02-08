package storage

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/feature/s3/manager"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/terrakube-io/terrakube/registry-go/internal/git"
	"github.com/terrakube-io/terrakube/registry-go/internal/utils"
)

type AWSStorageService struct {
	Client     *s3.Client
	BucketName string
	Region     string
	Hostname   string
	GitService git.GitService
}

func NewAWSStorageService(ctx context.Context, region, bucket, hostname, endpoint, accessKey, secretKey string) (*AWSStorageService, error) {
	// Custom resolver for endpoint if provided (e.g. MinIO)
	customResolver := aws.EndpointResolverWithOptionsFunc(func(service, region string, options ...interface{}) (aws.Endpoint, error) {
		if endpoint != "" {
			return aws.Endpoint{
				PartitionID:   "aws",
				URL:           endpoint,
				SigningRegion: region,
			}, nil
		}
		return aws.Endpoint{}, &aws.EndpointNotFoundError{}
	})

	cfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion(region),
		config.WithEndpointResolverWithOptions(customResolver),
		config.WithCredentialsProvider(aws.CredentialsProviderFunc(func(ctx context.Context) (aws.Credentials, error) {
			return aws.Credentials{
				AccessKeyID:     accessKey,
				SecretAccessKey: secretKey,
			}, nil
		})),
	)
	if err != nil {
		return nil, fmt.Errorf("unable to load SDK config: %v", err)
	}

	client := s3.NewFromConfig(cfg, func(o *s3.Options) {
		if endpoint != "" {
			o.UsePathStyle = true // Use path style for things like MinIO
		}
	})

	return &AWSStorageService{
		Client:     client,
		BucketName: bucket,
		Region:     region,
		Hostname:   hostname,
		GitService: git.NewService(),
	}, nil
}

func (s *AWSStorageService) SearchModule(org, module, provider, version, source, vcsType, accessToken, tagPrefix, folder string) (string, error) {
	key := fmt.Sprintf("registry/%s/%s/%s/%s/module.zip", org, module, provider, version)
	path := fmt.Sprintf("%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip", s.Hostname, org, module, provider, version)

	// Check if object exists
	_, err := s.Client.HeadObject(context.TODO(), &s3.HeadObjectInput{
		Bucket: aws.String(s.BucketName),
		Key:    aws.String(key),
	})

	if err == nil {
		return path, nil
	}

	log.Printf("Module %s not found in storage, initiating clone...", key)

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

	uploader := manager.NewUploader(s.Client)
	_, err = uploader.Upload(context.TODO(), &s3.PutObjectInput{
		Bucket:      aws.String(s.BucketName),
		Key:         aws.String(key),
		Body:        file,
		ContentType: aws.String("application/zip"),
	})
	if err != nil {
		return "", fmt.Errorf("failed to upload to S3: %w", err)
	}

	log.Printf("Uploaded module to S3: %s", key)
	return path, nil
}

func (s *AWSStorageService) DownloadModule(org, module, provider, version string) (io.ReadCloser, error) {
	key := fmt.Sprintf("registry/%s/%s/%s/%s/module.zip", org, module, provider, version)

	output, err := s.Client.GetObject(context.TODO(), &s3.GetObjectInput{
		Bucket: aws.String(s.BucketName),
		Key:    aws.String(key),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to download module from S3: %w", err)
	}

	return output.Body, nil
}
