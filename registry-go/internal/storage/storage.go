package storage

type StorageService interface {
	SearchModule(org, module, provider, version, source, vcsType, accessToken, tagPrefix, folder string) (string, error)
}
