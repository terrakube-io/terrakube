package client

import (
	"encoding/json"
	"fmt"
	"strings"
)

const searchProviderVersionsQuery = `
{
  organization(filter: "name==%s") {
    edges {
      node {
        id
        name
        provider(filter: "name==%s") {
            edges{
                node{
                    id
                    name
                    version {
                        edges{
                            node{
                                id
                                versionNumber
                                protocols
                                implementation{
                                    edges{
                                        node{
                                            id
                                            os
                                            arch
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
    }
  }
}`

type ProviderVersion struct {
	Version   string   `json:"version"`
	Protocols []string `json:"protocols"`
	Platforms []struct {
		Os   string `json:"os"`
		Arch string `json:"arch"`
	} `json:"platforms"`
}

type ProviderVersionsResponse struct {
	Organization struct {
		Edges []struct {
			Node struct {
				Provider struct {
					Edges []struct {
						Node struct {
							Version struct {
								Edges []struct {
									Node struct {
										VersionNumber  string `json:"versionNumber"`
										Protocols      string `json:"protocols"`
										Implementation struct {
											Edges []struct {
												Node struct {
													Os   string `json:"os"`
													Arch string `json:"arch"`
												} `json:"node"`
											} `json:"edges"`
										} `json:"implementation"`
									} `json:"node"`
								} `json:"edges"`
							} `json:"version"`
						} `json:"node"`
					} `json:"edges"`
				} `json:"provider"`
			} `json:"node"`
		} `json:"edges"`
	} `json:"organization"`
}

func (c *Client) GetProviderVersions(organization, provider string) ([]ProviderVersion, error) {
	query := fmt.Sprintf(searchProviderVersionsQuery, organization, provider)
	respData, err := c.ExecuteQuery(query, nil)
	if err != nil {
		return nil, err
	}

	var resp ProviderVersionsResponse
	if err := json.Unmarshal(respData, &resp); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	var versions []ProviderVersion
	for _, orgEdge := range resp.Organization.Edges {
		for _, provEdge := range orgEdge.Node.Provider.Edges {
			for _, verEdge := range provEdge.Node.Version.Edges {
				v := ProviderVersion{
					Version: verEdge.Node.VersionNumber,
				}
				if verEdge.Node.Protocols != "" {
					v.Protocols = strings.Split(verEdge.Node.Protocols, ",")
				}
				for _, implEdge := range verEdge.Node.Implementation.Edges {
					v.Platforms = append(v.Platforms, struct {
						Os   string `json:"os"`
						Arch string `json:"arch"`
					}{
						Os:   implEdge.Node.Os,
						Arch: implEdge.Node.Arch,
					})
				}
				versions = append(versions, v)
			}
		}
	}
	return versions, nil
}

const getProviderFileQuery = `
{
  organization(filter: "name==%s") {
    edges {
      node {
        id
        provider(filter: "name==%s") {
            edges{
                node{
                    id
                    version(filter: "versionNumber==%s"){
                        edges{
                            node{
                                id
                                protocols
                                implementation(filter: "os==%s;arch==%s"){
                                    edges{
                                        node{
                                            id
                                            os
                                            arch
                                            filename
                                            downloadUrl
                                            shasumsUrl
                                            shasumsSignatureUrl
                                            shasum
                                            keyId
                                            asciiArmor
                                            trustSignature
                                            source
                                            sourceUrl
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
    }
  }
}`

type GpgPublicKeys struct {
	KeyID          string `json:"key_id"`
	AsciiArmor     string `json:"ascii_armor"`
	TrustSignature string `json:"trust_signature"`
	Source         string `json:"source"`
	SourceUrl      string `json:"source_url"`
}

type SigningKeys struct {
	GpgPublicKeys []GpgPublicKeys `json:"gpg_public_keys"`
}

type ProviderFileDTO struct {
	Protocols           []string    `json:"protocols"`
	Os                  string      `json:"os"`
	Arch                string      `json:"arch"`
	Filename            string      `json:"filename"`
	DownloadURL         string      `json:"download_url"`
	ShasumsURL          string      `json:"shasums_url"`
	ShasumsSignatureURL string      `json:"shasums_signature_url"`
	Shasum              string      `json:"shasum"`
	SigningKeys         SigningKeys `json:"signing_keys"`
}

type ProviderFileResponse struct {
	Organization struct {
		Edges []struct {
			Node struct {
				Provider struct {
					Edges []struct {
						Node struct {
							Version struct {
								Edges []struct {
									Node struct {
										Protocols      string `json:"protocols"`
										Implementation struct {
											Edges []struct {
												Node struct {
													Os                  string `json:"os"`
													Arch                string `json:"arch"`
													Filename            string `json:"filename"`
													DownloadURL         string `json:"downloadUrl"`
													ShasumsURL          string `json:"shasumsUrl"`
													ShasumsSignatureURL string `json:"shasumsSignatureUrl"`
													Shasum              string `json:"shasum"`
													KeyID               string `json:"keyId"`
													AsciiArmor          string `json:"asciiArmor"`
													TrustSignature      string `json:"trustSignature"`
													Source              string `json:"source"`
													SourceUrl           string `json:"sourceUrl"`
												} `json:"node"`
											} `json:"edges"`
										} `json:"implementation"`
									} `json:"node"`
								} `json:"edges"`
							} `json:"version"`
						} `json:"node"`
					} `json:"edges"`
				} `json:"provider"`
			} `json:"node"`
		} `json:"edges"`
	} `json:"organization"`
}

func (c *Client) GetProviderFile(organization, provider, version, os, arch string) (*ProviderFileDTO, error) {
	query := fmt.Sprintf(getProviderFileQuery, organization, provider, version, os, arch)
	respData, err := c.ExecuteQuery(query, nil)
	if err != nil {
		return nil, err
	}

	var resp ProviderFileResponse
	if err := json.Unmarshal(respData, &resp); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	for _, orgEdge := range resp.Organization.Edges {
		for _, provEdge := range orgEdge.Node.Provider.Edges {
			for _, verEdge := range provEdge.Node.Version.Edges {
				for _, implEdge := range verEdge.Node.Implementation.Edges {
					node := implEdge.Node
					dto := &ProviderFileDTO{
						Os:                  node.Os,
						Arch:                node.Arch,
						Filename:            node.Filename,
						DownloadURL:         node.DownloadURL,
						ShasumsURL:          node.ShasumsURL,
						ShasumsSignatureURL: node.ShasumsSignatureURL,
						Shasum:              node.Shasum,
						SigningKeys: SigningKeys{
							GpgPublicKeys: []GpgPublicKeys{
								{
									KeyID:          node.KeyID,
									AsciiArmor:     node.AsciiArmor,
									TrustSignature: node.TrustSignature,
									Source:         node.Source,
									SourceUrl:      node.SourceUrl,
								},
							},
						},
					}
					if verEdge.Node.Protocols != "" {
						dto.Protocols = strings.Split(verEdge.Node.Protocols, ",")
					}
					return dto, nil
				}
			}
		}
	}
	return nil, fmt.Errorf("provider file not found")
}
