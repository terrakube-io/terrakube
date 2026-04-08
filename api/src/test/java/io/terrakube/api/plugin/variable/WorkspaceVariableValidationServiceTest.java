package io.terrakube.api.plugin.variable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.workspace.Workspace;
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
        incompleteVariable.setIncomplete(true);

        Variable completeVariable = new Variable();
        completeVariable.setKey("AWS_REGION");
        completeVariable.setIncomplete(false);

        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(incompleteVariable, completeVariable)));

        assertThatThrownBy(() -> validationService.validateWorkspaceVariables(workspace))
                .isInstanceOf(IncompleteVariableException.class)
                .hasMessageContaining("TF_API_TOKEN")
                .hasMessageContaining("Run blocked because this workspace still has incomplete sensitive variables.")
                .hasMessageContaining("Complete or delete these variables before retrying:")
                .hasMessageContaining("Open the workspace Variables page to update them.");
    }
}
