package io.terrakube.api.plugin.storage.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ByteRangeTest {

    @Test
    void parsesClosedRange() {
        ByteRange r = ByteRange.parse("bytes=0-99").orElseThrow();
        assertEquals(0, r.getStart());
        assertEquals(99, r.getEnd());
        assertFalse(r.isSuffix());
        assertEquals("bytes=0-99", r.toHttpHeaderValue());
    }

    @Test
    void parsesOpenEndedRange() {
        ByteRange r = ByteRange.parse("bytes=100-").orElseThrow();
        assertEquals(100, r.getStart());
        assertEquals(-1, r.getEnd());
        assertEquals("bytes=100-", r.toHttpHeaderValue());
    }

    @Test
    void parsesSuffixRange() {
        ByteRange r = ByteRange.parse("bytes=-256").orElseThrow();
        assertTrue(r.isSuffix());
        assertEquals(256, r.getSuffixLength());
        assertEquals("bytes=-256", r.toHttpHeaderValue());
    }

    @Test
    void rejectsMultiRange() {
        assertEquals(Optional.empty(), ByteRange.parse("bytes=0-9,20-29"));
    }

    @Test
    void rejectsNullBlankAndMalformed() {
        assertEquals(Optional.empty(), ByteRange.parse(null));
        assertEquals(Optional.empty(), ByteRange.parse("  "));
        assertEquals(Optional.empty(), ByteRange.parse("lines=0-9"));
        assertEquals(Optional.empty(), ByteRange.parse("bytes=abc"));
    }

    @Test
    void rejectsInvertedRange() {
        assertEquals(Optional.empty(), ByteRange.parse("bytes=10-5"));
    }

    @Test
    void rejectsZeroLengthSuffix() {
        assertEquals(Optional.empty(), ByteRange.parse("bytes=-0"));
    }
}
