package io.terrakube.registry.controller;

import io.terrakube.registry.configuration.OpenRegistryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/.well-known/terraform.json")
public class WellKnownWebServiceImpl {

    @Autowired
    OpenRegistryProperties openRegistryProperties;

    @GetMapping(produces = "application/json")
    public ResponseEntity<String> terraformJson() {
        String hostname = openRegistryProperties.getHostname();
        String loginBlock = openRegistryProperties.isLoginBrokerEnabled()
            ? String.format("""
                {
                    "client": "terraform-cli",
                    "grant_types": ["authz_code"],
                    "authz": "%s/oauth/authorize",
                    "token": "%s/oauth/token",
                    "ports": [10000, 10010]
                  }""", openRegistryProperties.getLoginApiUrl(), openRegistryProperties.getLoginApiUrl())
            : String.format("""
                {
                    "client": "%s",
                    "grant_types": ["authz_code", "openid", "profile", "email", "offline_access", "groups"],
                    "authz": "%s/auth?scope=openid+profile+email+offline_access+groups",
                    "token": "%s/token",
                    "ports": [10000, 10010]
                  }""", openRegistryProperties.getClientId(),
                        openRegistryProperties.getIssuerUri(), openRegistryProperties.getIssuerUri());

        String body = String.format("""
            {
              "modules.v1": "%s/terraform/modules/v1/",
              "providers.v1": "%s/terraform/providers/v1/",
              "login.v1": %s
            }""", hostname, hostname, loginBlock);

        return ResponseEntity.ok(body);
    }
}
