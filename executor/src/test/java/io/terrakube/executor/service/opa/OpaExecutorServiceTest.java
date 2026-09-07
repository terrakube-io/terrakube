package io.terrakube.executor.service.opa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.PolicyContext;
import io.terrakube.executor.service.mode.PolicyExemptionContext;
import io.terrakube.executor.service.opa.model.OpaEvaluationResult;
import io.terrakube.executor.service.opa.model.PolicyViolation;
import io.terrakube.executor.service.opa.model.ViolationStatus;
import io.terrakube.executor.service.terraform.JobContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OpaExecutorServiceTest {

    private OpaBinaryService opaBinaryService;
    private ThreadPoolTaskExecutor opaEvaluationExecutor;
    private ObjectMapper objectMapper;
    private JobContextService jobContextService;
    private TerraformState terraformState;
    private OpaExecutorServiceImpl opaExecutorService;

    @BeforeEach
    void setUp() {
        opaBinaryService = Mockito.mock(OpaBinaryService.class);
        opaEvaluationExecutor = new ThreadPoolTaskExecutor();
        opaEvaluationExecutor.setCorePoolSize(2);
        opaEvaluationExecutor.setMaxPoolSize(4);
        opaEvaluationExecutor.setThreadNamePrefix("test-opa-");
        opaEvaluationExecutor.initialize();

        objectMapper = new ObjectMapper();
        jobContextService = Mockito.mock(JobContextService.class);
        terraformState = Mockito.mock(TerraformState.class);

        opaExecutorService = new OpaExecutorServiceImpl(
                opaBinaryService,
                opaEvaluationExecutor,
                objectMapper,
                jobContextService,
                terraformState
        );
    }

    @Test
    void testParseOpaJsonOutput_WithDenyAndSoftMandatory() {
        PolicyContext policyContext = PolicyContext.builder()
                .policyId("pol-1")
                .policyName("azure-tagging-rules")
                .enforcementLevel("HARD_MANDATORY")
                .build();

        String rawJson = "{\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"expressions\": [\n" +
                "        {\n" +
                "          \"value\": {\n" +
                "            \"deny\": [\n" +
                "              {\n" +
                "                \"rule_id\": \"azure_mandatory_tags\",\n" +
                "                \"resource\": \"azurerm_resource_group.rg1\",\n" +
                "                \"msg\": \"Resource azurerm_resource_group.rg1 is missing mandatory tags\"\n" +
                "              }\n" +
                "            ],\n" +
                "            \"soft_mandatory\": [\n" +
                "              {\n" +
                "                \"rule_id\": \"azure_environment_name\",\n" +
                "                \"resource\": \"azurerm_resource_group.rg1\",\n" +
                "                \"msg\": \"Resource Environment tag must be dev, staging or prod\"\n" +
                "              }\n" +
                "            ],\n" +
                "            \"warn\": [\n" +
                "              {\n" +
                "                \"rule_id\": \"azure_cost_center_recommended\",\n" +
                "                \"resource\": \"azurerm_resource_group.rg1\",\n" +
                "                \"msg\": \"CostCenter tag is recommended\"\n" +
                "              }\n" +
                "            ]\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<String> logs = new ArrayList<>();
        Consumer<String> logConsumer = logs::add;

        OpaEvaluationResult result = opaExecutorService.parseOpaJsonOutput(policyContext, rawJson, 0, logConsumer);

        assertNotNull(result);
        assertEquals(2, result.getViolations().size()); // 1 deny + 1 soft_mandatory
        assertEquals(1, result.getWarningRules());

        PolicyViolation denyViolation = result.getViolations().get(0);
        assertEquals("azure_mandatory_tags", denyViolation.getRuleId());
        assertEquals("azurerm_resource_group.rg1", denyViolation.getAddress());
        assertTrue(denyViolation.getMessage().contains("missing mandatory tags"));

        PolicyViolation softViolation = result.getViolations().get(1);
        assertEquals("azure_environment_name", softViolation.getRuleId());
    }

    @Test
    void testApplyExemptions_ActiveExemptionBypassesViolation() {
        PolicyContext policyContext = PolicyContext.builder()
                .policyId("pol-azure-networking")
                .policyName("azure-network-rules")
                .enforcementLevel("HARD_MANDATORY")
                .build();

        PolicyViolation violation = PolicyViolation.builder()
                .ruleId("azure_apim_no_public_network")
                .address("azurerm_api_management.gateway")
                .message("Public network access is prohibited")
                .status(ViolationStatus.FAILED)
                .build();

        OpaEvaluationResult result = OpaEvaluationResult.builder()
                .policySetId(policyContext.getPolicyId())
                .policySetName(policyContext.getPolicyName())
                .enforcementLevel(policyContext.getEnforcementLevel())
                .violations(new ArrayList<>(List.of(violation)))
                .build();

        Date futureDate = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30));
        PolicyExemptionContext activeExemption = PolicyExemptionContext.builder()
                .exemptionId("ex-100")
                .policySetId("pol-azure-networking")
                .ruleId("azure_apim_no_public_network")
                .ticketReference("SEC-8842")
                .justification("Approved third-party integration gateway for Partner Corp")
                .expiresAt(futureDate)
                .build();

        List<String> logs = new ArrayList<>();
        opaExecutorService.applyExemptions(result, policyContext.getPolicyId(), List.of(activeExemption), logs::add);

        // Violation should be removed from blocking violations
        assertTrue(result.getViolations().isEmpty());
        // Added to exempted violations
        assertEquals(1, result.getExemptedViolations().size());
        PolicyViolation exempted = result.getExemptedViolations().get(0);
        assertEquals(ViolationStatus.EXEMPTED, exempted.getStatus());
        assertEquals("SEC-8842", exempted.getTicketReference());
        assertEquals("Approved third-party integration gateway for Partner Corp", exempted.getJustification());
        assertEquals(futureDate, exempted.getExpiresAt());

        // Explicit notice in logs
        assertTrue(logs.stream().anyMatch(l -> l.contains("[EXEMPTED]") && l.contains("SEC-8842")));
    }


    @Test
    void testApplyExemptions_ExpiredExemptionDoesNotBypass() {
        PolicyContext policyContext = PolicyContext.builder()
                .policyId("pol-azure-networking")
                .policyName("azure-network-rules")
                .enforcementLevel("HARD_MANDATORY")
                .build();

        PolicyViolation violation = PolicyViolation.builder()
                .ruleId("azure_apim_no_public_network")
                .address("azurerm_api_management.gateway")
                .message("Public network access is prohibited")
                .status(ViolationStatus.FAILED)
                .build();

        OpaEvaluationResult result = OpaEvaluationResult.builder()
                .policySetId(policyContext.getPolicyId())
                .policySetName(policyContext.getPolicyName())
                .enforcementLevel(policyContext.getEnforcementLevel())
                .violations(new ArrayList<>(List.of(violation)))
                .build();

        // Expired yesterday
        Date pastDate = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
        PolicyExemptionContext expiredExemption = PolicyExemptionContext.builder()
                .exemptionId("ex-99")
                .policySetId("pol-azure-networking")
                .ruleId("azure_apim_no_public_network")
                .ticketReference("SEC-OLD")
                .justification("Old exception")
                .expiresAt(pastDate)
                .build();

        List<String> logs = new ArrayList<>();
        opaExecutorService.applyExemptions(result, policyContext.getPolicyId(), List.of(expiredExemption), logs::add);

        // Fails closed: violation remains active and blocking
        assertEquals(1, result.getViolations().size());
        assertTrue(result.getExemptedViolations().isEmpty());
    }

    @Test
    void testApplyExemptions_UnmatchedRuleDoesNotBypass() {
        PolicyContext policyContext = PolicyContext.builder()
                .policyId("pol-azure-networking")
                .policyName("azure-network-rules")
                .enforcementLevel("HARD_MANDATORY")
                .build();

        PolicyViolation violation = PolicyViolation.builder()
                .ruleId("azure_apim_no_public_network")
                .address("azurerm_api_management.gateway")
                .message("Public network access is prohibited")
                .status(ViolationStatus.FAILED)
                .build();

        OpaEvaluationResult result = OpaEvaluationResult.builder()
                .policySetId(policyContext.getPolicyId())
                .policySetName(policyContext.getPolicyName())
                .enforcementLevel(policyContext.getEnforcementLevel())
                .violations(new ArrayList<>(List.of(violation)))
                .build();

        PolicyExemptionContext unrelatedExemption = PolicyExemptionContext.builder()
                .exemptionId("ex-200")
                .policySetId("pol-azure-networking")
                .ruleId("unrelated_rule_id")
                .ticketReference("SEC-9999")
                .justification("Other rule")
                .build();

        List<String> logs = new ArrayList<>();
        opaExecutorService.applyExemptions(result, policyContext.getPolicyId(), List.of(unrelatedExemption), logs::add);

        assertEquals(1, result.getViolations().size());
        assertTrue(result.getExemptedViolations().isEmpty());
    }
}
