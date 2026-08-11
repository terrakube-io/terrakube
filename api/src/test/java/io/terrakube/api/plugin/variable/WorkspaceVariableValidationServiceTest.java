package io.terrakube.api.plugin.variable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceVariableValidationServiceTest {

    @Mock
    private VariableRepository variableRepository;

    private WorkspaceVariableValidationService validationService;

    @BeforeEach
    void setUp() {
        validationService = new WorkspaceVariableValidationService(variableRepository);
    }

    @Test
    void shouldFailWhenWorkspaceContainsIncompleteVariables() {
        Workspace workspace = new Workspace();

        Variable incompleteVariable = new Variable();
        incompleteVariable.setKey("TF_API_TOKEN");
        incompleteVariable.setCategory(Category.ENV);
        incompleteVariable.setIncomplete(true);

        Variable completeVariable = new Variable();
        completeVariable.setKey("AWS_REGION");
        completeVariable.setCategory(Category.TERRAFORM);
        completeVariable.setIncomplete(false);

        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(incompleteVariable, completeVariable)));

        assertThatThrownBy(() -> validationService.validateWorkspaceVariables(workspace))
                .isInstanceOf(IncompleteVariableException.class)
                .hasMessageContaining("TF_API_TOKEN")
                .hasMessageContaining("Run blocked because this workspace still has incomplete sensitive variables.")
                .hasMessageContaining("Complete or delete these variables before retrying:")
                .hasMessageContaining("Open the workspace Variables page to update them.");
    }

    @Test
    void shouldFailWhenWorkspaceContainsVariablesWithNoCategory() {
        Workspace workspace = new Workspace();
        workspace.setName("acme-workspace");

        Variable invalidCategoryVariable = new Variable();
        invalidCategoryVariable.setKey("LEGACY_VAR");
        invalidCategoryVariable.setCategory(null);
        invalidCategoryVariable.setIncomplete(false);

        Variable validVariable = new Variable();
        validVariable.setKey("AWS_REGION");
        validVariable.setCategory(Category.TERRAFORM);
        validVariable.setIncomplete(false);

        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(invalidCategoryVariable, validVariable)));

        assertThatThrownBy(() -> validationService.validateWorkspaceVariables(workspace))
                .isInstanceOf(InvalidVariableCategoryException.class)
                .hasMessageContaining("acme-workspace")
                .hasMessageContaining("LEGACY_VAR")
                .hasMessageNotContaining("AWS_REGION")
                .hasMessageContaining("Run blocked because this workspace has variables with no category (must be TERRAFORM or ENV).")
                .hasMessageContaining("Open the workspace Variables page to update them.");
    }

    @Test
    void shouldPreferInvalidCategoryOverIncompleteWhenBothPresent() {
        Workspace workspace = new Workspace();

        Variable invalidCategoryVariable = new Variable();
        invalidCategoryVariable.setKey("LEGACY_VAR");
        invalidCategoryVariable.setCategory(null);
        invalidCategoryVariable.setIncomplete(false);

        Variable incompleteVariable = new Variable();
        incompleteVariable.setKey("TF_API_TOKEN");
        incompleteVariable.setCategory(Category.ENV);
        incompleteVariable.setIncomplete(true);

        when(variableRepository.findByWorkspace(workspace))
                .thenReturn(Optional.of(List.of(invalidCategoryVariable, incompleteVariable)));

        assertThatThrownBy(() -> validationService.validateWorkspaceVariables(workspace))
                .isInstanceOf(InvalidVariableCategoryException.class);
    }

    @Test
    void shouldPassWhenAllVariablesAreCompleteAndHaveAValidCategory() {
        Workspace workspace = new Workspace();

        Variable validVariable = new Variable();
        validVariable.setKey("AWS_REGION");
        validVariable.setCategory(Category.ENV);
        validVariable.setIncomplete(false);

        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(validVariable)));

        validationService.validateWorkspaceVariables(workspace);
    }
}
