package io.terrakube.api.plugin.state;

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

    public WellKnownWebServiceImpl(
            @Value("${io.terrakube.token.client-id}") String dexClientId,
            @Value("${io.terrakube.token.issuer-uri}") String dexIssuerUri,
            TerraformLoginProperties loginProperties) {
        this.dexClientId = dexClientId;
        this.dexIssuerUri = dexIssuerUri;
        this.loginProperties = loginProperties;
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
                    "ports": [10000, 10010]
                  }""", TerraformLoginProperties.CLIENT_ID, apiUrl, apiUrl)
            : String.format("""
                {
                    "client": "%s",
                    "grant_types": ["authz_code", "openid", "profile", "email", "offline_access", "groups"],
                    "authz": "%s/auth?scope=openid+profile+email+offline_access+groups",
                    "token": "%s/token",
                    "ports": [10000, 10010]
                  }""", dexClientId, dexIssuerUri, dexIssuerUri);

        String body = String.format("""
            {
              "login.v1": %s,
              "state.v2": "/remote/state/v2/",
              "tfe.v2": "/remote/tfe/v2/",
              "tfe.v2.1": "/remote/tfe/v2/"
            }""", loginBlock);

        return ResponseEntity.ok(body);
    }
}
