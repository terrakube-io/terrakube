package io.terrakube.api.plugin.token.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/.well-known/openid-configuration")
public class OpenIdConfigurationController {

    @Value("${io.terrakube.hostname}")
    String hostname;

    @Value("${io.terrakube.dynamic.credentials.hostname}")
    String overrideHostname;

    OpenIdConfiguration openIdConfiguration;

    @GetMapping(produces = "application/json")
    public ResponseEntity<OpenIdConfiguration> openIdConfigurationEndpoint() {
        if (this.openIdConfiguration == null) {
            this.openIdConfiguration = getDefaultOpenIdConfiguration();
        }

        return ResponseEntity.of(Optional.ofNullable(this.openIdConfiguration));
    }


    private OpenIdConfiguration getDefaultOpenIdConfiguration() {
        log.info("Loading default OpenId Configuration data...");
        OpenIdConfiguration openIdData = new OpenIdConfiguration();

        String issuer = String.format("https://%s", overrideHostname.isEmpty() ? hostname: overrideHostname);

        openIdData.setIssuer(issuer);
        openIdData.setJwksUri(issuer + "/.well-known/jwks");
        openIdData.setResponseTypesSupported(new ArrayList<>());
        openIdData.getResponseTypesSupported().add("id_token");

        openIdData.setClaimsSupported(new ArrayList<>());
        openIdData.getClaimsSupported().add("sub");
        openIdData.getClaimsSupported().add("aud");
        openIdData.getClaimsSupported().add("exp");
        openIdData.getClaimsSupported().add("iat");
        openIdData.getClaimsSupported().add("iss");
        openIdData.getClaimsSupported().add("jti");
        openIdData.getClaimsSupported().add("terrakube_workspace_id");
        openIdData.getClaimsSupported().add("terrakube_organization_id");
        openIdData.getClaimsSupported().add("terrakube_job_id");
        openIdData.getClaimsSupported().add("terrakube_workspace_name");
        openIdData.getClaimsSupported().add("terrakube_organization_name");

        openIdData.setIdTokenSigningAlgValuesSupported(new ArrayList<>());
        openIdData.getIdTokenSigningAlgValuesSupported().add("RS256");

        openIdData.setScopesSupported(new ArrayList<>());

        openIdData.getScopesSupported().add("openid");

        openIdData.setSubjectTypesSupported(new ArrayList<>());
        openIdData.getSubjectTypesSupported().add("public");

        return openIdData;
    }

}

@Getter
@Setter
class OpenIdConfiguration {

    @JsonProperty("issuer")
    private String issuer;

    @JsonProperty("jwks_uri")
    private String jwksUri;

    @JsonProperty("response_types_supported")
    private List<String> responseTypesSupported;

    @JsonProperty("claims_supported")
    private List<String> claimsSupported;

    @JsonProperty("id_token_signing_alg_values_supported")
    private List<String> idTokenSigningAlgValuesSupported;

    @JsonProperty("scopes_supported")
    private List<String> scopesSupported;

    @JsonProperty("subject_types_supported")
    private List<String> subjectTypesSupported;
}
