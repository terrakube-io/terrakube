package io.terrakube.api.plugin.security.user.dex;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.rs.federated.Federated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "io.terrakube.api.users", name = "type", havingValue = "DEX")
public class DexAuthenticatedUserImpl implements AuthenticatedUser {

    @Value("${io.terrakube.owner}")
    private String instanceOwner;

    @Autowired
    private GroupService groupService;

    @Autowired
    private FederatedRepository federatedRepository;

    private JwtAuthenticationToken getSecurityPrincipal(User user) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        return principal;
    }

    @Override
    public String getEmail(User user) {
        return (String) getSecurityPrincipal(user).getTokenAttributes().get("email");
    }

    @Override
    public String getApplication(User user) {
        log.debug("isServiceAccount {}", user.getPrincipal().getClass().getName());
        return (String) getSecurityPrincipal(user).getTokenAttributes().get("name");
    }

    @Override
    public boolean isServiceAccount(User user) {
        log.debug("isServiceAccount/PAT {}", getSecurityPrincipal(user).getTokenAttributes().get("iss").equals("Terrakube") || getSecurityPrincipal(user).getTokenAttributes().get("iss").equals("TerrakubeInternal"));
        boolean isFederated = isFederatedAccount(user);
        if (isFederated)
            return true;
        return getSecurityPrincipal(user).getTokenAttributes().get("iss").equals("Terrakube") || getSecurityPrincipal(user).getTokenAttributes().get("iss").equals("TerrakubeInternal");
    }

    @Override
    public boolean isFederatedAccount(User user) {
        String issuer = getSecurityPrincipal(user).getTokenAttributes().get("iss").toString();
        Object audienceObj = getSecurityPrincipal(user).getTokenAttributes().get("aud");
        String audience = "";

        if (audienceObj instanceof String) {
            audience = (String) audienceObj;
        } else if (audienceObj instanceof java.util.List) {
            java.util.List<String> audienceList = (java.util.List<String>) audienceObj;
            if (!audienceList.isEmpty()) {
                audience = audienceList.get(0);
            }
        }

        Federated federated = federatedRepository.findByIssuerUrlAndAudience(issuer, audience).orElse(null);
        return federated != null;
    }

    @Override
    public boolean isServiceAccountInternal(User user) {
        log.debug("isServiceAccountInternal {}", getSecurityPrincipal(user).getTokenAttributes().get("iss").equals("TerrakubeInternal"));
        return getSecurityPrincipal(user).getTokenAttributes().get("iss").equals("TerrakubeInternal");
    }

    @Override
    public boolean isSuperUser(User user) {
        boolean isServiceAccount=isServiceAccount(user);
        boolean isSuperUser;
        String applicationName="";
        String userName="";
        if (isServiceAccount){
            applicationName = getApplication(user);
            isSuperUser = groupService.isServiceMember(user, instanceOwner);
        }else{
            userName = getEmail(user);
            isSuperUser = groupService.isMember(user, instanceOwner);
        }

        log.debug("{} is super user", isServiceAccount ? applicationName : userName);
        return isSuperUser;
    }

}
