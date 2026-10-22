package io.terrakube.api.plugin.execution;

import io.terrakube.api.plugin.security.encryption.EncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JobVariablesServiceTest {
    private JobVariablesService service() {
        EncryptionService encryption = new EncryptionService();
        ReflectionTestUtils.setField(encryption, "internalToken", "test-key-for-job-variable-encryption");
        return new JobVariablesService(encryption);
    }

    @Test
    void encryptsBeforePersistenceAndPreservesRawValuesAcrossReload() {
        JobVariablesService service = service();
        Map<String, String> inputs = Map.of("key", "line one\nline two\n", "empty", "", "list", "[\"a\",\"b\"]");
        String encrypted = service.encrypt(inputs);
        assertNotNull(encrypted);
        assertFalse(encrypted.contains("line one"));
        Map<String, String> reloaded = service().decrypt(encrypted);
        Map<String, String> terraform = new HashMap<>(Map.of("key", "old", "other", "retained"));
        Map<String, String> environment = new HashMap<>(Map.of("TF_VAR_key", "old", "TF_IN_AUTOMATION", "1"));
        service().merge(reloaded, terraform, environment);
        assertEquals(Map.of("other", "retained"), terraform);
        inputs.forEach((key, value) -> assertEquals(value, environment.get("TF_VAR_" + key)));
        assertEquals("1", environment.get("TF_IN_AUTOMATION"));
        assertNotEquals(encrypted, service.encrypt(inputs));
    }

    @Test
    void leavesExistingJobsUnchangedAndKeepsJobsIsolated() {
        JobVariablesService service = service();
        Map<String, String> env = new HashMap<>();
        service.merge(service.decrypt(null), new HashMap<>(), env);
        assertTrue(env.isEmpty());
        assertNull(service.encrypt(Map.of()));
        for (String value : new String[]{"first", "second"}) {
            env = new HashMap<>();
            service.merge(service.decrypt(service.encrypt(Map.of("key", value))), new HashMap<>(), env);
            assertEquals(Map.of("TF_VAR_key", value), env);
        }
    }

    @Test
    void rejectsInvalidOrOversizedVariablesWithoutLeakingValues() {
        for (Map<String, String> inputs : java.util.List.of(
                Map.of("bad name", "private-marker"), Map.of("key", "private-marker\0"),
                Map.of("key", "private-marker".repeat(6000)))) {
            Exception failure = assertThrows(IllegalArgumentException.class, () -> service().encrypt(inputs));
            assertFalse(failure.getMessage().contains("private-marker"));
        }
    }

    @Test
    void rejectsMoreThanTheMaximumNumberOfVariablesButAcceptsTheMaximum() {
        Map<String, String> atLimit = new HashMap<>();
        for (int i = 0; i < JobVariablesService.MAX_VARIABLES; i++) atLimit.put("v" + i, "x");
        assertNotNull(service().encrypt(atLimit));
        atLimit.put("one_more", "x");
        assertThrows(IllegalArgumentException.class, () -> service().encrypt(atLimit));
    }

    @Test
    void decryptionFailureStopsDispatchAndDoesNotLeakCiphertext() {
        Exception failure = assertThrows(IllegalStateException.class,
                () -> service().decrypt("private-invalid-ciphertext"));
        assertEquals("Could not read encrypted job variables", failure.getMessage());
        assertNull(failure.getCause());
    }
}
