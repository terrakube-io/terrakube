package config

import (
	"os"
)

// Config holds the application configuration
type Config struct {
	Port                string
	AzBuilderRegistry   string
	AzBuilderApiUrl     string
	RegistryStorageType string
	AwsBucketName       string
	AwsRegion           string
	AwsAccessKey        string
	AwsSecretKey        string
	AwsEndpoint         string
}

func LoadConfig() *Config {
	return &Config{
		Port:                getEnv("PORT", "8080"),
		AzBuilderRegistry:   getEnv("AzBuilderRegistry", "http://localhost:8080"),
		AzBuilderApiUrl:     getEnv("AzBuilderApiUrl", "http://localhost:8081"),
		RegistryStorageType: getEnv("RegistryStorageType", "AWS"),
		AwsBucketName:       getEnv("AwsStorageBucketName", ""),
		AwsRegion:           getEnv("AwsStorageRegion", "us-east-1"),
		AwsAccessKey:        getEnv("AwsStorageAccessKey", ""),
		AwsSecretKey:        getEnv("AwsStorageSecretKey", ""),
		AwsEndpoint:         getEnv("AwsEndpoint", ""),
	}
}

func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}
