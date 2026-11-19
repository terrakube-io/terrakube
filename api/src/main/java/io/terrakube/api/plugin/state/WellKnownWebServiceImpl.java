package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.routing.PublicUrls;
import io.terrakube.api.plugin.token.login.TerraformLoginProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/.well-known/terraform.json")
public class WellKnownWebServiceImpl {

    private final String dexClientId;
    private final String dexIssuerUri;
    private final TerraformLoginProperties loginProperties;
    private final PublicUrls publicUrls;
    private final String separateRegistryUrl;

    public WellKnownWebServiceImpl(
            @Value("${io.terrakube.token.client-id}") String dexClientId,
            @Value("${io.terrakube.token.issuer-uri}") String dexIssuerUri,
            TerraformLoginProperties loginProperties,
            PublicUrls publicUrls,
            @Value("${io.terrakube.registry.url:}") String separateRegistryUrl) {
        this.dexClientId = dexClientId;
        this.dexIssuerUri = dexIssuerUri;
        this.loginProperties = loginProperties;
        this.publicUrls = publicUrls;
        this.separateRegistryUrl = separateRegistryUrl == null ? "" : separateRegistryUrl.trim();
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<String> terraformJson() {
        String apiUrl = loginProperties.getApiUrl();
        String loginBlock = loginProperties.isEnabled()
            ? String.format("""
                {
                    "client": "%s",
                    "grant_types": ["authz_code"],
                    "authz": "%s/oauth/authorize",
                    "token": "%s/oauth/token",
                    "ports": [%d, %d]
                  }""", TerraformLoginProperties.CLIENT_ID, apiUrl, apiUrl,
                    TerraformLoginProperties.PORT_LOW, TerraformLoginProperties.PORT_HIGH)
            : String.format("""
                {
                    "client": "%s",
                    "grant_types": ["authz_code", "openid", "profile", "email", "offline_access", "groups"],
                    "authz": "%s/auth?scope=openid+profile+email+offline_access+groups",
                    "token": "%s/token",
                    "ports": [10000, 10001]
                  }""", dexClientId, dexIssuerUri, dexIssuerUri);

        String prefix = publicUrls.apiPathPrefix();
        // On a single-domain host the private registry lives under /api/private-registry, so the one
        // discovery document advertises it too, unless the registry keeps its own hostname.
        String registryServices = publicUrls.isSingleDomain() && separateRegistryUrl.isEmpty()
            ? ",\n  \"modules.v1\": \"" + prefix + "/private-registry/terraform/modules/v1/\""
                + ",\n  \"providers.v1\": \"" + prefix + "/private-registry/terraform/providers/v1/\""
            : "";
        String body = String.format("""
            {
              "login.v1": %1$s,
              "state.v2": "%2$s/remote/state/v2/",
              "tfe.v2": "%2$s/remote/tfe/v2/",
              "tfe.v2.1": "%2$s/remote/tfe/v2/"%3$s
            }""", loginBlock, prefix, registryServices);
        return ResponseEntity.ok(body);
    }
}
