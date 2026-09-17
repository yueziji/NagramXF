package org.telegram.messenger.diagnostics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class DialogDiagnosticCursorTest {
    @Test
    public void unrelatedOlderMessageDoesNotMoveProbePastLastDialog() {
        var july = new DialogDiagnosticCursor.Message(10, 5, 700);
        var april = new DialogDiagnosticCursor.Message(99, 3, 400);
        var messages = List.of(july, april);
        var dialogs = List.of(new DialogDiagnosticCursor.Entry(10, 5, false));
        assertSame(july, DialogDiagnosticCursor.tail(dialogs, messages));
        assertSame(april, DialogDiagnosticCursor.oldest(messages));
        assertFalse(july.samePosition(april));
    }

    @Test
    public void sameMessageIdInDifferentPeersDoesNotCollide() {
        var first = new DialogDiagnosticCursor.Message(-10, 42, 500);
        var second = new DialogDiagnosticCursor.Message(-20, 42, 500);
        assertFalse(first.samePosition(second));
        assertSame(second, DialogDiagnosticCursor.tail(
                List.of(new DialogDiagnosticCursor.Entry(-20, 42, false)), List.of(first, second)));
    }

    @Test
    public void serverDialogOrderWinsOverMessageArrayAndDates() {
        var first = new DialogDiagnosticCursor.Message(10, 1, 100);
        var last = new DialogDiagnosticCursor.Message(20, 2, 200);
        assertSame(last, DialogDiagnosticCursor.tail(List.of(
                new DialogDiagnosticCursor.Entry(10, 1, false), new DialogDiagnosticCursor.Entry(20, 2, false)),
                List.of(last, first)));
    }

    @Test
    public void pinnedEmptyAndUnmatchedTailsDoNotMixPeersAndMessages() {
        var valid = new DialogDiagnosticCursor.Message(10, 1, 500);
        var pinned = new DialogDiagnosticCursor.Message(20, 2, 100);
        assertSame(valid, DialogDiagnosticCursor.tail(List.of(
                new DialogDiagnosticCursor.Entry(10, 1, false), new DialogDiagnosticCursor.Entry(20, 2, true),
                new DialogDiagnosticCursor.Entry(30, 3, false), new DialogDiagnosticCursor.Entry(40, 0, false)),
                List.of(valid, pinned)));
        assertNull(DialogDiagnosticCursor.tail(List.of(new DialogDiagnosticCursor.Entry(30, 3, false)), List.of(valid)));
        assertNull(DialogDiagnosticCursor.tail(List.of(), List.of(valid)));
    }
}
