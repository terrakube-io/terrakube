package io.terrakube.executor.service.logs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveLogLineTest {

    @Test
    void aLineWithinTheLimitIsKeptWhole() {
        String line = "a".repeat(100);

        assertSame(line, LiveLogLine.cap(line, 100));
    }

    @Test
    void aLineOneByteOverTheLimitIsCutAndSaysHowMuchWasLeftOut() {
        String line = "a".repeat(101);

        String capped = LiveLogLine.cap(line, 100);

        assertTrue(capped.startsWith("a".repeat(100) + " ... [1 bytes left out of the live view"));
    }

    @Test
    void theLimitCountsBytesNotCharacters() {
        // 'é' is two bytes in UTF-8, so 60 of them are 120 bytes.
        String line = "é".repeat(60);

        String capped = LiveLogLine.cap(line, 100);

        assertTrue(capped.startsWith("é".repeat(50) + " ... ["));
        assertTrue(capped.contains("[20 bytes left out"));
    }

    @Test
    void aMultiByteCharacterIsNeverCutInTheMiddle() {
        // The limit falls inside the second byte of the last emoji (4 bytes each).
        String line = "😀".repeat(10);

        String capped = LiveLogLine.cap(line, 18);

        assertTrue(capped.startsWith("😀".repeat(4) + " ... ["));
        assertTrue(capped.contains("[24 bytes left out"));
        assertEquals(capped, new String(capped.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void aLimitOfZeroOrLessKeepsEveryLineWhole() {
        String line = "x".repeat(1_000_000);

        assertSame(line, LiveLogLine.cap(line, 0));
        assertSame(line, LiveLogLine.cap(line, -1));
    }

    @Test
    void aMissingLineStaysMissing() {
        assertNull(LiveLogLine.cap(null, 100));
    }
}
