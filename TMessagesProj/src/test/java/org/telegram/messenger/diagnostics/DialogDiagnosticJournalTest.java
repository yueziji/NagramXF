package org.telegram.messenger.diagnostics;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class DialogDiagnosticJournalTest {
    private File directory() throws Exception {
        return Files.createTempDirectory("nagram-dialog-diagnostic-test-").toFile();
    }

    private String peer(DialogDiagnosticJournal journal, int account, long id) {
        Matcher matcher = Pattern.compile("\"peer\":\"(p_[a-f0-9]+)\"")
                .matcher(journal.encode(account, "sample", "peer", journal.peer(account, id)));
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    @Test
    public void anonymousReferencesSurviveRestartButDoNotIdentifyOtherAccountsOrInstalls() throws Exception {
        File directory = directory();
        DialogDiagnosticJournal first = new DialogDiagnosticJournal(directory);
        String alias = peer(first, 0, 998877665544332211L);
        DialogDiagnosticJournal restarted = new DialogDiagnosticJournal(directory);
        assertEquals(alias, peer(restarted, 0, 998877665544332211L));
        assertNotEquals(alias, peer(restarted, 1, 998877665544332211L));
        assertNotEquals(alias, peer(restarted, 0, 998877665544332212L));
        assertNotEquals(alias, peer(new DialogDiagnosticJournal(directory()), 0, 998877665544332211L));
        assertFalse(alias.contains("998877665544332211"));
    }

    @Test
    public void restartAppendsToFirstLoginTraceAndExportContainsOnlyJournal() throws Exception {
        File directory = directory();
        DialogDiagnosticJournal first = new DialogDiagnosticJournal(directory);
        first.append(first.encode(0, "first_login", "count", 100));
        byte[] original = Files.readAllBytes(new File(directory, "trace.jsonl").toPath());
        DialogDiagnosticJournal restarted = new DialogDiagnosticJournal(directory);
        restarted.append(restarted.encode(0, "after_restart", "peer", restarted.peer(0, 76543210987654L)));
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        restarted.exportTo(exported);
        String text = exported.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith(new String(original, StandardCharsets.UTF_8)));
        assertTrue(text.contains("\"event\":\"after_restart\""));
        assertTrue(text.contains("\"event\":\"export_status\""));
        assertFalse(text.contains("76543210987654"));
        assertFalse(text.contains("anonymization.bin"));
        assertEquals(3, text.lines().count());
    }

    @Test
    public void arbitraryTextObjectsCredentialsAndHeaderOverridesAreRejected() throws Exception {
        DialogDiagnosticJournal journal = new DialogDiagnosticJournal(directory());
        Object poison = new Object() {
            @Override public String toString() { throw new AssertionError("Must not stringify objects"); }
        };
        assertThrows(IllegalArgumentException.class, () -> journal.encode(0, "sample", "value", poison));
        assertThrows(IllegalArgumentException.class, () -> journal.encode(0, "sample", "value", "private chat content"));
        assertThrows(IllegalArgumentException.class, () -> journal.encode(0, "sample", "access_hash", 42L));
        assertThrows(IllegalArgumentException.class, () -> journal.encode(0, "sample", "api_key", 42));
        assertThrows(IllegalArgumentException.class, () -> journal.encode(0, "sample", "event", 42));
        assertThrows(IllegalArgumentException.class, () -> journal.encode(0, "invalid\nname"));
    }

    @Test
    public void limitKeepsInitialEvidenceAndPersistsAcrossRestart() throws Exception {
        File directory = directory();
        DialogDiagnosticJournal journal = new DialogDiagnosticJournal(directory, 512);
        String first = journal.encode(0, "first_login", "count", 100);
        journal.append(first);
        for (int i = 0; i < 20; i++) journal.append(journal.encode(0, "later", "count", i));
        assertTrue(journal.isFull());
        assertTrue(journal.droppedCount() > 0);
        File trace = new File(directory, "trace.jsonl");
        assertTrue(trace.length() <= 512);
        byte[] saved = Files.readAllBytes(trace.toPath());
        assertTrue(new String(saved, StandardCharsets.UTF_8).startsWith(first));
        DialogDiagnosticJournal restarted = new DialogDiagnosticJournal(directory, 512);
        assertTrue(restarted.isFull());
        restarted.append(restarted.encode(0, "after_restart"));
        assertArrayEquals(saved, Files.readAllBytes(trace.toPath()));
    }

    @Test
    public void invalidExistingSeedIsNotOverwritten() throws Exception {
        File directory = directory();
        File seed = new File(directory, "anonymization.bin");
        Files.write(seed.toPath(), new byte[]{1, 2, 3});
        assertThrows(java.io.IOException.class, () -> new DialogDiagnosticJournal(directory));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(seed.toPath()));
    }
}
