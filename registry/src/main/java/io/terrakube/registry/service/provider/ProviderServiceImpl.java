package io.terrakube.registry.service.provider;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.graphql.GraphQLRequest;
import io.terrakube.registry.controller.model.provider.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class ProviderServiceImpl implements ProviderService {

    @Autowired
    TerrakubeClient terrakubeClient;

    private static final String SEARCH_PROVIDER_VERSIONS="{ \n" +
            "  organization(filter: \"name==%s\") {\n" +
            "    edges {\n" +
            "      node {\n" +
            "        id\n" +
            "        name\n" +
            "        provider(filter: \"name==%s\") {\n" +
            "            edges{\n" +
            "                node{\n" +
            "                    id\n" +
            "                    name\n" +
            "                    version{\n" +
            "                        edges{\n" +
            "                            node{\n" +
            "                                id\n" +
            "                                versionNumber\n" +
            "                                protocols\n" +
            "                                implementation{\n" +
            "                                    edges{\n" +
            "                                        node{\n" +
            "                                            id\n" +
            "                                            os\n" +
            "                                            arch\n" +
            "                                        }\n" +
            "                                    }\n" +
            "                                }\n" +
            "                            }\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    private static final String SEARCH_PROVIDER_IMPLEMENTATIONS="{ \n" +
            "  organization(filter: \"name==%s\") {\n" +
            "    edges {\n" +
            "      node {\n" +
            "        id\n" +
            "        name\n" +
            "        provider(filter: \"name==%s\") {\n" +
            "            edges{\n" +
            "                node{\n" +
            "                    id\n" +
            "                    name\n" +
            "                    version(filter: \"versionNumber==%s\"){\n" +
            "                        edges{\n" +
            "                            node{\n" +
            "                                id\n" +
            "                                versionNumber\n" +
            "                                protocols\n" +
            "                                implementation(filter: \"os==%s;arch==%s\"){\n" +
            "                                    edges{\n" +
            "                                        node{\n" +
            "                                            id\n" +
            "                                            os\n" +
            "                                            arch\n" +
            "                                            filename\n" +
            "                                            downloadUrl\n" +
            "                                            shasumsUrl\n" +
            "                                            shasumsSignatureUrl\n" +
            "                                            shasum\n" +
            "                                            keyId\n" +
            "                                            asciiArmor\n" +
            "                                            trustSignature\n" +
            "                                            source\n" +
            "                                            sourceUrl\n" +
            "                                        }\n" +
            "                                    }\n" +
            "                                }\n" +
            "                            }\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    @Override
    public List<VersionDTO> getAvailableVersions(String organization, String provider) {
        log.info("Organization Provider: {} {}", organization, provider);
        List<VersionDTO> versionDTOList = new ArrayList<>();

        GraphQLRequest query = new GraphQLRequest();
        query.setQuery(String.format(SEARCH_PROVIDER_VERSIONS, organization, provider));
        terrakubeClient.searchOrganizationProviders(query).getData().getOrganization().getEdges().forEach(organizationEdge -> {
            organizationEdge.getNode().getProvider().getEdges().forEach(providerEdge -> {
                providerEdge.getNode().getVersion().getEdges().forEach(versionEdge -> {
                    VersionDTO versionDTO = new VersionDTO();
                    versionDTO.setVersion(versionEdge.getNode().getVersionNumber());
                    versionDTO.setProtocols(Arrays.asList(versionEdge.getNode().getProtocols().split(",")));
                    List<PlatformDTO> platformDTOList = new ArrayList<>();
                    versionEdge.getNode().getImplementation().getEdges().forEach(implementationEdge -> {
                        PlatformDTO platformDTO = new PlatformDTO();
                        platformDTO.setOs(implementationEdge.getNode().getOs());
                        platformDTO.setArch(implementationEdge.getNode().getArch());
                        platformDTOList.add(platformDTO);
                    });
                    versionDTO.setPlatforms(platformDTOList);
                    versionDTOList.add(versionDTO);
                });
            });
        });

        return versionDTOList;
    }

    @Override
    public FileDTO getFileInformation(String organization, String provider, String version, String os, String arch) {
        FileDTO fileDTO = new FileDTO();
        GraphQLRequest query = new GraphQLRequest();
        query.setQuery(String.format(SEARCH_PROVIDER_IMPLEMENTATIONS, organization, provider, version, os, arch));
        terrakubeClient.searchOrganizationProviders(query).getData().getOrganization().getEdges().forEach(organizationEdge -> {
            organizationEdge.getNode().getProvider().getEdges().forEach(providerEdge -> {
                providerEdge.getNode().getVersion().getEdges().forEach(versionEdge -> {
                    fileDTO.setProtocols(Arrays.asList(versionEdge.getNode().getProtocols().split(",")));
                    versionEdge.getNode().getImplementation().getEdges().forEach(implementationEdge -> {

                        fileDTO.setOs(implementationEdge.getNode().getOs());
                        fileDTO.setArch(implementationEdge.getNode().getArch());
                        fileDTO.setFilename(implementationEdge.getNode().getFilename());
                        fileDTO.setDownload_url(implementationEdge.getNode().getDownloadUrl());
                        fileDTO.setShasums_url(implementationEdge.getNode().getShasumsUrl());
                        fileDTO.setShasums_signature_url(implementationEdge.getNode().getShasumsSignatureUrl());
                        fileDTO.setShasum(implementationEdge.getNode().getShasum());

                        GpgPublicKeys gpgPublicKeys = new GpgPublicKeys();
                        gpgPublicKeys.setKey_id(implementationEdge.getNode().getKeyId());
                        gpgPublicKeys.setAscii_armor(implementationEdge.getNode().getAsciiArmor());
                        gpgPublicKeys.setTrust_signature(implementationEdge.getNode().getTrustSignature());
                        gpgPublicKeys.setSource(implementationEdge.getNode().getSource());
                        gpgPublicKeys.setSource_url(implementationEdge.getNode().getSourceUrl());

                        SigningKeys signingKeys = new SigningKeys();
                        signingKeys.setGpg_public_keys(List.of(gpgPublicKeys));

                        fileDTO.setSigning_keys(signingKeys);
                    });
                });
            });
        });

        return fileDTO;
    }
}
