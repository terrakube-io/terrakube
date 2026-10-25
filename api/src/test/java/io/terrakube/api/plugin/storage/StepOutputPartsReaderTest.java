package io.terrakube.api.plugin.storage;

import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputPart;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepOutputPartsReaderTest {

    /** A storage with a few parts that counts how many are open at once. */
    private static final class FakeParts {
        final Map<String, String> parts = new TreeMap<>();
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();
        final List<String> opened = new ArrayList<>();
        final StorageTypeService storage = mock(StorageTypeService.class);

        FakeParts() {
            try {
                when(storage.listStepOutputParts(any(), any(), any())).thenAnswer(invocation -> {
                    List<StepOutputPart> listed = new ArrayList<>();
                    // Listed in reverse on purpose: the reader must sort.
                    parts.forEach((name, content) -> listed.add(0, new StepOutputPart(name, content.length())));
                    return listed;
                });
                when(storage.openStepOutputPart(any(), any(), any(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
                    String partName = invocation.getArgument(3);
                    long offset = invocation.getArgument(4);
                    long length = invocation.getArgument(5);
                    opened.add(partName + "@" + offset + "+" + length);
                    int now = open.incrementAndGet();
                    maxOpen.accumulateAndGet(now, Math::max);
                    byte[] bytes = parts.get(partName).substring((int) offset, (int) (offset + length)).getBytes(StandardCharsets.UTF_8);
                    return new ByteArrayInputStream(bytes) {
                        private boolean closed;

                        @Override
                        public void close() throws IOException {
                            if (!closed) {
                                closed = true;
                                open.decrementAndGet();
                            }
                            super.close();
                        }
                    };
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        FakeParts with(String name, String content) {
            parts.put(name, content);
            return this;
        }
    }

    private static String read(StepOutputStream out) throws IOException {
        try (out) {
            return new String(out.getContent().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static FakeParts threeParts() {
        return new FakeParts().with("000001.part", "aaaaa").with("000002.part", "bbbbb").with("000003.part", "ccc");
    }

    @Test
    void onlyAGeneratedPartNameIsAccepted() throws IOException {
        StepOutputPartsReader.requirePartName("000001.part");
        for (String bad : new String[]{null, "", "1.part", "000001.parts", "../000001.part", "000001.part/../x", "000001.part\n"}) {
            assertThrows(IOException.class, () -> StepOutputPartsReader.requirePartName(bad), String.valueOf(bad));
        }
    }

    @Test
    void partsThatWereAlreadyListedAreNotListedAgain() throws IOException {
        FakeParts fake = new FakeParts();
        fake.parts.put("000001.part", "abc");
        fake.parts.put("000002.part", "def");
        List<StepOutputPart> listed = StepOutputPartsReader.contiguous(fake.storage.listStepOutputParts("o", "j", "s"));

        try (StepOutputStream out = StepOutputPartsReader.open(fake.storage, "o", "j", "s", null, listed)) {
            assertEquals("abcdef", new String(out.getContent().readAllBytes(), StandardCharsets.UTF_8));
        }

        verify(fake.storage, times(1)).listStepOutputParts(any(), any(), any());
    }

    @Test
    void joinsThePartsInOrderWhateverTheListingOrder() throws IOException {
        StepOutputStream out = StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", null);

        assertFalse(out.isPartial());
        assertEquals(13, out.getContentLength());
        assertEquals("aaaaabbbbbccc", read(out));
    }

    @Test
    void aRangeInsideOnePartReadsOnlyThatPart() throws IOException {
        FakeParts storage = threeParts();

        StepOutputStream out = StepOutputPartsReader.open(storage.storage, "o", "j", "s", ByteRange.parse("bytes=6-8").orElseThrow());

        assertTrue(out.isPartial());
        assertEquals("bytes 6-8/13", out.getContentRange());
        assertEquals("bbb", read(out));
        assertEquals(List.of("000002.part@1+3"), storage.opened);
    }

    @Test
    void aRangeAcrossPartBoundariesIsJoined() throws IOException {
        FakeParts storage = threeParts();

        StepOutputStream out = StepOutputPartsReader.open(storage.storage, "o", "j", "s", ByteRange.parse("bytes=3-11").orElseThrow());

        assertEquals("bytes 3-11/13", out.getContentRange());
        assertEquals(9, out.getContentLength());
        assertEquals("aabbbbbcc", read(out));
        assertEquals(List.of("000001.part@3+2", "000002.part@0+5", "000003.part@0+2"), storage.opened);
    }

    @Test
    void anOpenEndedAndASuffixRangeAreMapped() throws IOException {
        assertEquals("bbccc", read(StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", ByteRange.parse("bytes=8-").orElseThrow())));
        assertEquals("ccc", read(StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", ByteRange.parse("bytes=-3").orElseThrow())));
        assertEquals("aaaaabbbbbccc", read(StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", ByteRange.parse("bytes=-100").orElseThrow())));
        assertEquals("c", read(StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", ByteRange.parse("bytes=12-99").orElseThrow())));
    }

    @Test
    void aRangePastTheEndIsMissing() {
        assertFalse(StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", ByteRange.parse("bytes=13-").orElseThrow()).isExists());
    }

    @Test
    void aGapEndsTheOutputThere() throws IOException {
        FakeParts storage = new FakeParts().with("000001.part", "aaaaa").with("000002.part", "bbbbb").with("000004.part", "dddd");

        StepOutputStream out = StepOutputPartsReader.open(storage.storage, "o", "j", "s", null);

        assertEquals(10, out.getContentLength());
        assertEquals("aaaaabbbbb", read(out));
    }

    @Test
    void outputStartingAfterTheFirstPartIsNotServed() {
        FakeParts storage = new FakeParts().with("000002.part", "bbbbb").with("000003.part", "ccc");

        assertFalse(StepOutputPartsReader.open(storage.storage, "o", "j", "s", null).isExists());
    }

    @Test
    void namesThatAreNotPartsAreIgnored() throws IOException {
        FakeParts storage = new FakeParts().with("000001.part", "aaaaa").with("notes.txt", "zzz");

        assertEquals("aaaaa", read(StepOutputPartsReader.open(storage.storage, "o", "j", "s", null)));
    }

    @Test
    void noPartsIsMissing() {
        assertFalse(StepOutputPartsReader.open(new FakeParts().storage, "o", "j", "s", null).isExists());
    }

    @Test
    void onlyOnePartIsOpenAtATimeAndAllAreClosed() throws IOException {
        FakeParts storage = threeParts();

        read(StepOutputPartsReader.open(storage.storage, "o", "j", "s", null));

        assertEquals(1, storage.maxOpen.get());
        assertEquals(0, storage.open.get());
    }

    @Test
    void closingEarlyClosesThePartInUseAndOpensNoMore() throws IOException {
        FakeParts storage = threeParts();
        StepOutputStream out = StepOutputPartsReader.open(storage.storage, "o", "j", "s", null);
        assertEquals('a', out.getContent().read());

        out.close();

        assertEquals(0, storage.open.get());
        assertEquals(1, storage.opened.size());
        assertEquals(-1, out.getContent().read());
    }

    @Test
    void readsOneByteAtATimeToo() throws IOException {
        StepOutputStream out = StepOutputPartsReader.open(threeParts().storage, "o", "j", "s", null);
        StringBuilder all = new StringBuilder();
        int b;
        while ((b = out.getContent().read()) >= 0) {
            all.append((char) b);
        }
        assertEquals("aaaaabbbbbccc", all.toString());
    }
}
