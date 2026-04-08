package io.terrakube.api.plugin.variable;

import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Variable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceVariableValidationService {

    public static final String INCOMPLETE_VARIABLE_STEP_NAME = "Incomplete sensitive variables";
    public static final String INCOMPLETE_VARIABLE_TITLE = "Run blocked because this workspace still has incomplete sensitive variables.";

    private final VariableRepository variableRepository;

    public WorkspaceVariableValidationService(VariableRepository variableRepository) {
        this.variableRepository = variableRepository;
    }

    public void validateWorkspaceVariables(Workspace workspace) {
        List<Variable> incompleteVariables = getIncompleteVariables(workspace);
        if (incompleteVariables.isEmpty()) {
            return;
        }

        throw new IncompleteVariableException(buildIncompleteVariableMessage(incompleteVariables));
    }

    public List<Variable> getIncompleteVariables(Workspace workspace) {
        return variableRepository.findByWorkspace(workspace)
                .orElse(List.of())
                .stream()
                .filter(Variable::isIncomplete)
                .sorted(Comparator.comparing(Variable::getKey, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    public String buildIncompleteVariableMessage(Workspace workspace) {
        return buildIncompleteVariableMessage(getIncompleteVariables(workspace));
    }

    public String buildIncompleteVariableMessage(List<Variable> incompleteVariables) {
        List<String> lines = new ArrayList<>();
        lines.add(INCOMPLETE_VARIABLE_TITLE);
        lines.add("");
        lines.add("Complete or delete these variables before retrying:");

        for (Variable variable : incompleteVariables) {
            String category = variable.getCategory() == null ? "terraform" : variable.getCategory().name().toLowerCase();
            lines.add("- " + variable.getKey() + " (" + category + ")");
        }

        lines.add("");
        lines.add("Open the workspace Variables page to update them.");
        return String.join("\n", lines);
    }
}
