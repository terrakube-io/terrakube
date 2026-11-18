package io.terrakube.executor.service.terraform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BackendConfigArgumentsTest {

    @Test
    void leavesAValueWithoutBackendConfigAsItIs() {
        String arguments = "-upgrade '-plugin-dir=/opt/my plugins'";
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip(arguments);
        assertSame(arguments, stripped.arguments());
        assertEquals(List.of(), stripped.removed());
    }

    @Test
    void leavesNullAndBlankAlone() {
        assertNull(BackendConfigArguments.strip(null).arguments());
        assertEquals("  ", BackendConfigArguments.strip("  ").arguments());
    }

    @Test
    void removesAnEqualsFormAndNamesItByKeyNotValue() {
        BackendConfigArguments.Stripped stripped =
                BackendConfigArguments.strip("-backend-config=role_arn=arn:aws:iam::1:role/secret-role");
        assertNull(stripped.arguments());
        assertEquals(List.of("role_arn"), stripped.removed());
    }

    @Test
    void removesASeparateValueForm() {
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip("-backend-config cfg.hcl -upgrade");
        assertEquals("-upgrade", stripped.arguments());
        assertEquals(List.of("cfg.hcl"), stripped.removed());
    }

    @Test
    void removesTheDoubleDashForm() {
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip("--backend-config=cfg.hcl --backend-config bucket=b");
        assertNull(stripped.arguments());
        assertEquals(List.of("cfg.hcl", "bucket"), stripped.removed());
    }

    @Test
    void keepsTheOtherWordsExactlyAsWritten() {
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip(
                "-upgrade -backend-config=cfg.hcl '-plugin-dir=/opt/my plugins' -backend-config=\"key=a b\" -reconfigure");
        assertEquals("-upgrade '-plugin-dir=/opt/my plugins' -reconfigure", stripped.arguments());
        assertEquals(List.of("cfg.hcl", "key"), stripped.removed());
    }

    @Test
    void readsAQuotedFlagName() {
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip("'-backend-config=cfg.hcl' -upgrade");
        assertEquals("-upgrade", stripped.arguments());
        assertEquals(List.of("cfg.hcl"), stripped.removed());
    }

    @Test
    void doesNotMistakeAnotherFlagForBackendConfig() {
        String arguments = "-backend=false -backend-config-file=x";
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip(arguments);
        assertSame(arguments, stripped.arguments());
        assertEquals(List.of(), stripped.removed());
    }

    @Test
    void aTrailingFlagWithoutAValueIsRemoved() {
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip("-upgrade -backend-config");
        assertEquals("-upgrade", stripped.arguments());
        assertEquals(List.of("(no value)"), stripped.removed());
    }

    @Test
    void leavesAnUnbalancedQuoteForTerraformToReport() {
        String arguments = "-backend-config='key=a";
        BackendConfigArguments.Stripped stripped = BackendConfigArguments.strip(arguments);
        assertSame(arguments, stripped.arguments());
        assertEquals(List.of(), stripped.removed());
    }
}
