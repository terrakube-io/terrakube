package io.terrakube.api.plugin.scheduler.job.tcl;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TemplateRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.template.Template;

@ExtendWith(MockitoExtension.class)
public class TclServiceTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    TemplateRepository templateRepository;
    VcsRepository vcsRepository;

    @BeforeEach
    public void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        stepRepository = mock(StepRepository.class, new FailUnkownMethod<StepRepository>());
        templateRepository = mock(TemplateRepository.class, new FailUnkownMethod<TemplateRepository>());
        vcsRepository = mock(VcsRepository.class, new FailUnkownMethod<VcsRepository>());
    }

    private TclService subject() {
        return new TclService(jobRepository, stepRepository, templateRepository, vcsRepository);
    }

    private String encodeYaml(String yaml) {
        return Base64.getEncoder().encodeToString(yaml.getBytes());
    }

    private Template templateWithTcl(String tcl) {
        Template template = new Template();
        template.setId(UUID.randomUUID());
        template.setTcl(tcl);
        return template;
    }

    @Test
    public void isTemplatePlanOnly_withOnlyTerraformPlan_returnsTrue() {
        String yaml = """
            flow:
              - type: terraformPlan
                step: 100
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertTrue(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withOnlyTerraformPlanDestroy_returnsTrue() {
        String yaml = """
            flow:
              - type: terraformPlanDestroy
                step: 100
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertTrue(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withBothPlanTypes_returnsTrue() {
        String yaml = """
            flow:
              - type: terraformPlan
                step: 100
              - type: terraformPlanDestroy
                step: 200
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertTrue(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withTerraformApply_returnsFalse() {
        String yaml = """
            flow:
              - type: terraformPlan
                step: 100
              - type: terraformApply
                step: 200
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertFalse(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withApproval_returnsFalse() {
        String yaml = """
            flow:
              - type: terraformPlan
                step: 100
              - type: approval
                step: 200
                team: TEAM
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertFalse(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withCustomScripts_returnsFalse() {
        String yaml = """
            flow:
              - type: customScripts
                step: 100
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertFalse(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withTerraformDestroy_returnsFalse() {
        String yaml = """
            flow:
              - type: terraformDestroy
                step: 100
            """;
        UUID templateId = UUID.randomUUID();
        Template template = templateWithTcl(encodeYaml(yaml));

        doReturn(template).when(templateRepository).getReferenceById(templateId);

        Assertions.assertFalse(subject().isTemplatePlanOnly(templateId.toString()));
    }

    @Test
    public void isTemplatePlanOnly_withNullTemplateId_returnsFalse() {
        Assertions.assertFalse(subject().isTemplatePlanOnly(null));
    }

    @Test
    public void isTemplatePlanOnly_withEmptyTemplateId_returnsFalse() {
        Assertions.assertFalse(subject().isTemplatePlanOnly(""));
    }

    @Test
    public void getFlowTypeForStep_returnsCorrectFlowType() {
        String yaml = """
            flow:
              - type: terraformPlan
                step: 100
              - type: approval
                step: 200
              - type: terraformApply
                step: 300
            """;

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStatus(JobStatus.pending);

        Job job = new Job();
        job.setId(1);
        job.setTcl(encodeYaml(yaml));
        job.setStep(Collections.singletonList(step));

        TclService tclService = subject();

        Assertions.assertEquals("terraformPlan", tclService.getFlowTypeForStep(job, 100));
        Assertions.assertEquals("approval", tclService.getFlowTypeForStep(job, 200));
        Assertions.assertEquals("terraformApply", tclService.getFlowTypeForStep(job, 300));
    }

    @Test
    public void getFlowTypeForStep_withNonExistentStep_returnsNull() {
        String yaml = """
            flow:
              - type: terraformPlan
                step: 100
            """;

        Job job = new Job();
        job.setId(1);
        job.setTcl(encodeYaml(yaml));

        Assertions.assertNull(subject().getFlowTypeForStep(job, 999));
    }

    @Test
    public void getFlowTypeForStep_withNullTcl_returnsNull() {
        Job job = new Job();
        job.setId(1);
        job.setTcl(null);

        Assertions.assertNull(subject().getFlowTypeForStep(job, 100));
    }
}
