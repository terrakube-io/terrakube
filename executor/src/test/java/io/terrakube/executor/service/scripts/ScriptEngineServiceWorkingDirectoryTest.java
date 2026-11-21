package io.terrakube.executor.service.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.bash.BashEngine;
import io.terrakube.executor.service.scripts.groovy.GroovyEngine;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptEngineServiceWorkingDirectoryTest {

    private final ScriptEngineService service = new ScriptEngineService(mock(GroovyEngine.class),
            mock(BashEngine.class), "https://example.com/tools.git", "main");

    @Test
    void scriptsNeedAWorkingDirectory() {
        assertThatThrownBy(() -> service.execute(mock(TerraformJob.class), List.of(), null, line -> { }))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("terraformWorkingDir");
    }

    @Test
    void withoutCommandsThereIsNothingToRunAndNoDirectoryIsNeeded() {
        assertThat(service.execute(mock(TerraformJob.class), null, (File) null, line -> { })).isTrue();
    }
}
