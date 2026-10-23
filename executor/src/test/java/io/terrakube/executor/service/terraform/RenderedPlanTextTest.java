package io.terrakube.executor.service.terraform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderedPlanTextTest {

    @Test
    void keepsAPlanThatFitsWithoutANote() {
        RenderedPlanText text = new RenderedPlanText(1000);
        text.accept("first");
        text.accept("second");

        assertEquals("first\nsecond\n", text.text());
        assertFalse(text.isTruncated());
    }

    @Test
    void aPlanExactlyAtTheLimitIsNotCut() {
        RenderedPlanText text = new RenderedPlanText(13);
        text.accept("first");
        text.accept("second");

        assertEquals("first\nsecond\n", text.text());
        assertFalse(text.isTruncated());
    }

    @Test
    void cutsAtTheLimitAndSaysHowMuchWasLeftOut() {
        RenderedPlanText text = new RenderedPlanText(13);
        text.accept("first");
        text.accept("second");
        text.accept("third");
        text.accept("fourth");

        assertTrue(text.isTruncated());
        assertTrue(text.text().startsWith("first\nsecond\n"), text.text());
        assertTrue(text.text().contains("... 2 more lines (1 KiB) of the plan are not shown here"), text.text());
        assertFalse(text.text().contains("third"));
    }

    @Test
    void aLongLineThatDoesNotFitStopsEverythingAfterIt() {
        RenderedPlanText text = new RenderedPlanText(20);
        text.accept("short");
        text.accept("x".repeat(100));
        text.accept("tiny");

        assertTrue(text.isTruncated());
        assertFalse(text.text().contains("tiny"), "a later short line must not jump the cut: " + text.text());
    }

    @Test
    void countsBytesNotCharacters() {
        RenderedPlanText text = new RenderedPlanText(10);
        text.accept("\u00e9\u00e9\u00e9\u00e9");
        text.accept("\u00e9");

        assertTrue(text.isTruncated());
    }

    @Test
    void zeroOrLessKeepsEverything() {
        RenderedPlanText text = new RenderedPlanText(0);
        for (int i = 0; i < 1000; i++) {
            text.accept("line " + i);
        }

        assertFalse(text.isTruncated());
        assertTrue(text.text().endsWith("line 999\n"));
    }

    @Test
    void anEmptyPlanIsBlank() {
        RenderedPlanText text = new RenderedPlanText(100);
        text.accept("");
        text.accept("   ");

        assertTrue(text.isBlank());
    }

    @Test
    void aFirstLineOverTheLimitStillCountsAsACutPlan() {
        RenderedPlanText text = new RenderedPlanText(20);
        text.accept("x".repeat(100));
        text.accept("more");

        assertTrue(text.isTruncated());
        assertFalse(text.isBlank(), "a cut plan must be published, otherwise the comment reads the whole console");
        assertTrue(text.text().contains("2 more lines"), text.text());
    }

    @Test
    void theClosingSummaryLineSurvivesTheCut() {
        RenderedPlanText text = new RenderedPlanText(13);
        text.accept("first");
        text.accept("second");
        text.accept("third");
        text.accept("Plan: 3 to add, 1 to change, 0 to destroy.");

        assertTrue(text.text().trim().endsWith("Plan: 3 to add, 1 to change, 0 to destroy."), text.text());
    }

    @Test
    void aPlanThatIsNotCutIsNotGivenASecondSummary() {
        RenderedPlanText text = new RenderedPlanText(1000);
        text.accept("Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Plan: 1 to add, 0 to change, 0 to destroy.\n", text.text());
    }
}
