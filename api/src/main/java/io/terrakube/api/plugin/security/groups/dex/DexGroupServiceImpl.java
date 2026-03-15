package io.terrakube.api.plugin.security.groups.dex;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.rs.federated.Federated;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@AllArgsConstructor
@Slf4j
@Service
@ConditionalOnProperty(prefix = "io.terrakube.api.groups", name = "type", havingValue = "DEX")
public class DexGroupServiceImpl implements GroupService {

    RedisTemplate redisTemplate;

    AccessRepository accessRepository;

    FederatedRepository federatedRepository;

    private static final String REDIS_ORG_LIMITED = "org_%s_%s";

    @Override
    public boolean isMember(User user, String group) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        boolean isMember = false;
        for (String groupName : toStringArray((java.util.ArrayList) principal.getTokenAttributes().get("groups"))) {
            if (groupName.equals(group))
                isMember = true;
        }
        log.debug("{} is member {} {}", principal.getTokenAttributes().get("name"), group, isMember);
        return isMember;
    }

    @Override
    public boolean isServiceMember(User user, String group) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        boolean isMember = principal.getTokenAttributes().get("iss").equals("TerrakubeInternal")? true: false;
        boolean isFederated = isFederatedAccount(user);
        if(!isMember) {
            for (String groupName : toStringArray((java.util.ArrayList) principal.getTokenAttributes().get("groups"))) {
                if (groupName.equals(group))
                    isMember = true;
                if (isFederated && isFederatedMember(user, group))
                    isMember = true;
            }
            log.debug("{} is member {} {}", principal.getTokenAttributes().get("name"), group, isMember);
        }else{
            log.debug("TerrakubeInternal Client Service Group Membership");
        }
        return isMember;
    }

    private boolean isFederatedAccount(User user) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        String issuer = principal.getTokenAttributes().get("iss").toString();
        String audience = principal.getTokenAttributes().get("aud").toString();
        Federated federated = federatedRepository.findByIssuerUrlAndAudience(issuer, audience).orElse(null);
        if (federated != null) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isFederatedMember(User user, String group) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        String issuer = principal.getTokenAttributes().get("iss").toString();
        String audience = principal.getTokenAttributes().get("aud").toString();
        Federated federated = federatedRepository.findByIssuerUrlAndAudience(issuer, audience).orElse(null);
        if (federated != null) {
            return federated.getName().equals(group);
        }
        return false;
    }

    private String[] toStringArray(java.util.ArrayList array) {
        if (array == null)
            return new String[0];

        String[] arr = new String[array.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = (String) array.get(i);
        }
        return arr;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isMemberWithLimitedAccessV1(User user, Object elideEntity){
        List<Access> accessList = null;

        String email = (String) ((JwtAuthenticationToken) user.getPrincipal()).getTokenAttributes().get("email");

        if (elideEntity instanceof Organization) {
            Organization organization = (Organization) elideEntity;

            if (redisTemplate.hasKey(String.format(REDIS_ORG_LIMITED, organization.getId(), email))) {
                return (Boolean) redisTemplate.opsForValue().get(String.format(REDIS_ORG_LIMITED, organization.getId(), email));
            } else {
                for (Workspace workspace : organization.getWorkspace()) {
                    accessList = workspace.getAccess();
                    if (!accessList.isEmpty())
                        for (Access team : accessList) {
                            boolean isMember = isMember(user, team.getName());
                            log.info("isMember {} {}", team.getName(), isMember);
                            if (isMember) {
                                redisTemplate.opsForValue().set(String.format(REDIS_ORG_LIMITED, organization.getId(), email), Boolean.TRUE, 15, TimeUnit.MINUTES);
                                return true;
                            }
                        }
                }

                redisTemplate.opsForValue().set(String.format(REDIS_ORG_LIMITED, organization.getId(), email), Boolean.FALSE, 15, TimeUnit.MINUTES);
            }
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isMemberWithLimitedAccessV2(User user, Organization organization){
        List<String> groups = (List<String>)((JwtAuthenticationToken) user.getPrincipal()).getTokenAttributes().get("groups");
        Optional<List<Access>> accessList = accessRepository.findAllByWorkspaceOrganizationIdAndNameIn(organization.getId(), groups);
        log.debug("Groups Size: {}, IsPresent: {},  Group Access {}", groups.size(), accessList.isPresent(), accessList.get().isEmpty());
        return !accessList.get().isEmpty();
    }
}
