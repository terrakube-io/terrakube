package io.terrakube.api.plugin.security.groups.dex;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.plugin.security.request.RequestScopedMemo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.ProjectAccessRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

import io.terrakube.api.rs.federated.Federated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@AllArgsConstructor
@Slf4j
@Service
@ConditionalOnProperty(prefix = "io.terrakube.api.groups", name = "type", havingValue = "DEX")
public class DexGroupServiceImpl implements GroupService {

    RedisTemplate redisTemplate;

    AccessRepository accessRepository;

    ProjectAccessRepository projectAccessRepository;

    FederatedLookupService federatedLookupService;

    private static final String REDIS_ORG_LIMITED = "org_%s_%s";

    private static final String WORKSPACE_ACCESS_MEMO = DexGroupServiceImpl.class.getName() + ".workspaceAccess";

    private static final String PROJECT_ACCESS_MEMO = DexGroupServiceImpl.class.getName() + ".projectAccess";

    @Override
    public boolean isMember(User user, String group) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        boolean isMember = false;
        Object tokenGroups = principal.getTokenAttributes().get("groups");
        if (tokenGroups instanceof Collection<?> values) {
            for (Object groupName : values) {
                if (groupName != null && groupName.toString().equals(group)) {
                    isMember = true;
                    break;
                }
            }
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
            // Federated tokens are issued by an external provider and usually carry no
            // "groups" claim, so this check cannot live inside the loop below.
            if (isFederated && isFederatedMember(user, group))
                isMember = true;

            Object tokenGroups = principal.getTokenAttributes().get("groups");
            if (tokenGroups instanceof Collection<?> values) {
                for (Object groupName : values) {
                    if (groupName != null && groupName.toString().equals(group)) {
                        isMember = true;
                        break;
                    }
                }
            }
            log.debug("{} is member {} {}", principal.getTokenAttributes().get("name"), group, isMember);
        }else{
            log.debug("TerrakubeInternal Client Service Group Membership");
        }
        return isMember;
    }

    private boolean isFederatedAccount(User user) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        return !federatedLookupService.findAllAuthorized(principal.getTokenAttributes()).isEmpty();
    }

    @Override
    public boolean isFederatedMember(User user, String group) {
        JwtAuthenticationToken principal = ((JwtAuthenticationToken) user.getPrincipal());
        return federatedLookupService.findAllAuthorized(principal.getTokenAttributes()).stream()
                .anyMatch(federated -> federated.getName().equals(group));
    }

    private List<String> getEffectiveGroups(User user) {
        JwtAuthenticationToken principal = (JwtAuthenticationToken) user.getPrincipal();
        Map<String, Object> tokenAttributes = principal.getTokenAttributes();
        List<String> groups = new ArrayList<>();

        Object tokenGroups = tokenAttributes.get("groups");
        if (tokenGroups instanceof Collection<?> values) {
            values.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .forEach(groups::add);
        }

        federatedLookupService.findAllAuthorized(tokenAttributes).stream()
                .map(Federated::getName)
                .filter(Objects::nonNull)
                .forEach(groups::add);

        return groups.stream().distinct().toList();
    }

    private String[] toStringArray(Object array) {
        if (array instanceof Collection<?> values) {
            return values.stream().filter(Objects::nonNull).map(Object::toString).toArray(String[]::new);
        }
        return new String[0];
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
        List<String> groups = getEffectiveGroups(user);
        if (groups.isEmpty()) {
            log.debug("No groups found for user in workspace limited access check");
            return false;
        }
        Optional<List<Access>> accessList = RequestScopedMemo.memoize(
                WORKSPACE_ACCESS_MEMO,
                Arrays.asList(organization.getId(), groups),
                () -> accessRepository.findAllByWorkspaceOrganizationIdAndNameIn(organization.getId(), groups));
        log.debug("Groups Size: {}, IsPresent: {}, Group Access {}", groups.size(), accessList.isPresent(), accessList.map(l -> !l.isEmpty()).orElse(false));
        return accessList.map(l -> !l.isEmpty()).orElse(false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isMemberWithProjectAccess(User user, Organization organization){
        List<String> groups = getEffectiveGroups(user);
        if (groups.isEmpty()) {
            log.debug("No groups found for user in project access check");
            return false;
        }
        Optional<List<ProjectAccess>> accessList = RequestScopedMemo.memoize(
                PROJECT_ACCESS_MEMO,
                Arrays.asList(organization.getId(), groups),
                () -> projectAccessRepository.findAllByProjectOrganizationIdAndNameIn(organization.getId(), groups));
        log.debug("ProjectAccess check - Groups Size: {}, IsPresent: {}, HasAccess: {}", groups.size(), accessList.isPresent(), accessList.map(l -> !l.isEmpty()).orElse(false));
        return accessList.map(l -> !l.isEmpty()).orElse(false);
    }
}
