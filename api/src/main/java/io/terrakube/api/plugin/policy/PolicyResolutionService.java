package io.terrakube.api.plugin.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyExemptionContext;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.PolicyAttachmentRepository;
import io.terrakube.api.repository.PolicyExemptionRepository;
import io.terrakube.api.repository.PolicySetRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyAttachment;
import io.terrakube.api.rs.policy.PolicyExemption;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class PolicyResolutionService {

    private final PolicySetRepository policySetRepository;
    private final PolicyAttachmentRepository policyAttachmentRepository;
    private final PolicyExemptionRepository policyExemptionRepository;
    private final GlobalVarRepository globalVarRepository;
    private final VariableRepository variableRepository;
    private final TagRepository tagRepository;
    private final WorkspaceTagRepository workspaceTagRepository;
    private final TokenService tokenService;

    /**
     * Cumulatively resolves all applicable PolicySets across:
     * 1. Global (organization-wide baseline)
     * 2. Project-scoped attachments
     * 3. Tag-scoped attachments
     * 4. Workspace-scoped attachments
     *
     * Deduplicates by PolicySet ID and injects non-sensitive Category.POLICY variables.
     */
    public List<PolicyContext> resolvePoliciesForJob(Job job) {
        if (job == null || job.getOrganization() == null || job.getWorkspace() == null) {
            return new ArrayList<>();
        }

        Map<UUID, PolicySet> cumulativePolicyMap = new LinkedHashMap<>();

        // 1. Global policies
        List<PolicySet> globalPolicies = policySetRepository.findByOrganizationAndGlobalTrue(job.getOrganization());
        if (globalPolicies != null) {
            for (PolicySet ps : globalPolicies) {
                cumulativePolicyMap.put(ps.getId(), ps);
            }
        }

        // 2. Project-scoped attachments
        if (job.getWorkspace().getProject() != null) {
            List<PolicyAttachment> projectAttachments = policyAttachmentRepository.findByProject(job.getWorkspace().getProject());
            if (projectAttachments != null) {
                for (PolicyAttachment pa : projectAttachments) {
                    if (pa.getPolicySet() != null) {
                        cumulativePolicyMap.putIfAbsent(pa.getPolicySet().getId(), pa.getPolicySet());
                    }
                }
            }
        }

        // 3. Tag-scoped attachments
        List<WorkspaceTag> workspaceTags = workspaceTagRepository.findByWorkspace(job.getWorkspace());
        if (workspaceTags != null && !workspaceTags.isEmpty()) {
            List<UUID> tagUuids = workspaceTags.stream()
                    .map(WorkspaceTag::getTagId)
                    .filter(Objects::nonNull)
                    .map(idStr -> {
                        try {
                            return UUID.fromString(idStr);
                        } catch (IllegalArgumentException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (!tagUuids.isEmpty()) {
                List<Tag> matchingTags = tagRepository.findAllById(tagUuids);
                if (!matchingTags.isEmpty()) {
                    List<PolicyAttachment> tagAttachments = policyAttachmentRepository.findByTagIn(matchingTags);
                    if (tagAttachments != null) {
                        for (PolicyAttachment pa : tagAttachments) {
                            if (pa.getPolicySet() != null) {
                                cumulativePolicyMap.putIfAbsent(pa.getPolicySet().getId(), pa.getPolicySet());
                            }
                        }
                    }
                }
            }
        }

        // 4. Workspace-scoped attachments
        List<PolicyAttachment> workspaceAttachments = policyAttachmentRepository.findByWorkspace(job.getWorkspace());
        if (workspaceAttachments != null) {
            for (PolicyAttachment pa : workspaceAttachments) {
                if (pa.getPolicySet() != null) {
                    cumulativePolicyMap.putIfAbsent(pa.getPolicySet().getId(), pa.getPolicySet());
                }
            }
        }

        if (cumulativePolicyMap.isEmpty()) {
            return new ArrayList<>();
        }

        // Resolve non-sensitive POLICY variables (Workspace overrides Organization)
        Map<String, String> policyInputs = resolvePolicyVariables(job);

        List<PolicySet> sortedPolicySets = cumulativePolicyMap.values().stream()
                .sorted(Comparator.comparing(PolicySet::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<PolicyContext> policyContextList = new ArrayList<>();
        for (PolicySet ps : sortedPolicySets) {
            String accessToken = null;
            if (ps.getVcs() != null) {
                try {
                    accessToken = tokenService.getAccessToken(ps.getRepository(), ps.getVcs());
                } catch (JsonProcessingException | NoSuchAlgorithmException | InvalidKeySpecException
                         | URISyntaxException e) {
                    log.error("Failed to fetch access token for policy set {} ({}) repository {}, error {}",
                            ps.getName(), ps.getId(), ps.getRepository(), e.getMessage());
                }
            }

            PolicyContext context = PolicyContext.builder()
                    .policyId(ps.getId().toString())
                    .policyName(ps.getName())
                    .enforcementLevel(ps.getEnforcementLevel() != null ? ps.getEnforcementLevel().name() : "HARD_MANDATORY")
                    .shadowEnforcementLevel(ps.getShadowEnforcementLevel() != null ? ps.getShadowEnforcementLevel().name() : null)
                    .overrideTeam(ps.getOverrideTeam())
                    .vcsType(ps.getVcs() != null ? ps.getVcs().getVcsType().toString() : "PUBLIC")
                    .connectionType(ps.getVcs() != null ? ps.getVcs().getConnectionType().toString() : null)
                    .accessToken(accessToken)
                    .repository(ps.getRepository())
                    .branch(ps.getBranch() != null ? ps.getBranch() : "main")
                    .folder(ps.getFolder() != null ? ps.getFolder() : "/")
                    .opaVersion(ps.getOpaVersion())
                    .inputs(new HashMap<>(policyInputs))
                    .build();

            policyContextList.add(context);
        }

        log.info("Resolved {} cumulative policies for Job {}", policyContextList.size(), job.getId());
        return policyContextList;
    }

    /**
     * Resolves active exemptions for this workspace and project.
     */
    public List<PolicyExemptionContext> resolveExemptionsForJob(Job job) {
        if (job == null || job.getOrganization() == null || job.getWorkspace() == null) {
            return new ArrayList<>();
        }

        Date now = new Date();
        List<PolicyExemption> exemptions = policyExemptionRepository.findActiveExemptionsForWorkspace(
                job.getOrganization(),
                job.getWorkspace(),
                job.getWorkspace().getProject(),
                now
        );

        if (exemptions == null || exemptions.isEmpty()) {
            return new ArrayList<>();
        }

        List<PolicyExemptionContext> exemptionContexts = new ArrayList<>();
        for (PolicyExemption pe : exemptions) {
            exemptionContexts.add(PolicyExemptionContext.builder()
                    .exemptionId(pe.getId().toString())
                    .policySetId(pe.getPolicySet().getId().toString())
                    .ruleId(pe.getRuleId())
                    .ticketReference(pe.getTicketReference())
                    .justification(pe.getJustification())
                    .expiresAt(pe.getExpiresAt())
                    .build());
        }

        log.info("Resolved {} active exemptions for Job {}", exemptionContexts.size(), job.getId());
        return exemptionContexts;
    }

    /**
     * Merges Organization-level Globalvar (Category.POLICY) with Workspace-level Variable (Category.POLICY).
     * Excludes any sensitive variables.
     */
    private Map<String, String> resolvePolicyVariables(Job job) {
        Map<String, String> inputs = new HashMap<>();

        // Org global variables
        List<Globalvar> orgVars = globalVarRepository.findByOrganization(job.getOrganization());
        if (orgVars != null) {
            for (Globalvar gv : orgVars) {
                if (Category.POLICY.equals(gv.getCategory()) && !gv.isSensitive() && gv.getValue() != null) {
                    inputs.put(gv.getKey(), gv.getValue());
                }
            }
        }

        // Workspace variables override org variables
        List<Variable> wsVars = variableRepository.findByWorkspace(job.getWorkspace()).orElse(new ArrayList<>());
        for (Variable v : wsVars) {
            if (Category.POLICY.equals(v.getCategory()) && !v.isSensitive() && v.getValue() != null) {
                inputs.put(v.getKey(), v.getValue());
            }
        }

        return inputs;
    }
}
