package org.telegram.messenger.diagnostics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** An append-only, bounded journal. Neither arbitrary strings nor TL objects can be recorded. */
public final class DialogDiagnosticJournal {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,47}");
    private static final Pattern SENSITIVE = Pattern.compile(".*(password|secret|auth_key|access_hash|api_key|app_hash|username|phone|text|body).*");

    public static final class Reference {
        private final String value;

        private Reference(String value) {
            this.value = value;
        }
    }

    private final File trace;
    private final File limitMarker;
    private final byte[] anonymizationSeed;
    private final ThreadLocal<Mac> macs;
    private final String run;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final long maximumBytes;
    private volatile boolean full;

    public DialogDiagnosticJournal(File directory) throws IOException {
        this(directory, MAX_BYTES);
    }

    public DialogDiagnosticJournal(File directory, long maximumBytes) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create diagnostic directory");
        }
        this.maximumBytes = maximumBytes;
        trace = new File(directory, "trace.jsonl");
        limitMarker = new File(directory, "limit-reached");
        File seedFile = new File(directory, "anonymization.bin");
        if (seedFile.exists()) {
            if (seedFile.length() != 32) throw new IOException("Invalid diagnostic seed");
            anonymizationSeed = Files.readAllBytes(seedFile.toPath());
            if (anonymizationSeed.length != 32) {
                throw new IOException("Invalid diagnostic seed");
            }
        } else {
            anonymizationSeed = new byte[32];
            new SecureRandom().nextBytes(anonymizationSeed);
            try (FileOutputStream output = new FileOutputStream(seedFile)) {
                output.write(anonymizationSeed);
            }
        }
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        run = hex(random, random.length);
        full = limitMarker.exists() || trace.length() >= maximumBytes;
        macs = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(anonymizationSeed, "HmacSHA256"));
                return mac;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Cannot anonymize diagnostic reference");
            }
        });
    }

    public Reference peer(int account, long peer) {
        return reference(account, peer, 0, (byte) 1);
    }

    public Reference message(int account, long peer, int message) {
        return reference(account, peer, message, (byte) 2);
    }

    private Reference reference(int account, long peer, int message, byte kind) {
        byte[] input = ByteBuffer.allocate(17).put(kind).putInt(account).putLong(peer).putInt(message).array();
        return new Reference((kind == 1 ? "p_" : "m_") + hex(macs.get().doFinal(input), 12));
    }

    public String encode(int account, String event, Object... fields) {
        requireName(event);
        if ((fields.length & 1) != 0) {
            throw new IllegalArgumentException("Diagnostic fields must be pairs");
        }
        StringBuilder line = new StringBuilder(256)
                .append("{\"schema\":1,\"time_ms\":").append(System.currentTimeMillis())
                .append(",\"run\":\"").append(run).append("\",\"seq\":").append(sequence.incrementAndGet())
                .append(",\"account\":").append(account).append(",\"event\":\"").append(event).append('"');
        for (int i = 0; i < fields.length; i += 2) {
            if (!(fields[i] instanceof String)) {
                throw new IllegalArgumentException("Invalid diagnostic field name");
            }
            String name = (String) fields[i];
            requireName(name);
            if (name.matches("schema|time_ms|run|seq|account|event")) throw new IllegalArgumentException("Reserved diagnostic field");
            Object value = fields[i + 1];
            line.append(",\"").append(name).append("\":");
            if (value instanceof Reference) {
                line.append('"').append(((Reference) value).value).append('"');
            } else if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
                line.append(value);
            } else {
                // In particular, never call toString() on strings, exceptions, requests or responses.
                throw new IllegalArgumentException("Unsupported diagnostic field type");
            }
        }
        return line.append("}\n").toString();
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches() || SENSITIVE.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid diagnostic name");
        }
    }

    public synchronized void append(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (full || trace.length() + bytes.length > maximumBytes) {
            try {
                if (!limitMarker.exists()) limitMarker.createNewFile();
            } catch (IOException ignored) {
                // The trace still remains bounded if the marker cannot be persisted.
            }
            full = true;
            dropped.incrementAndGet();
            return;
        }
        try (FileOutputStream output = new FileOutputStream(trace, true)) {
            output.write(bytes);
        } catch (IOException ignored) {
            dropped.incrementAndGet();
        }
    }

    public void droppedEvent() {
        dropped.incrementAndGet();
    }

    public boolean isFull() {
        return full;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public synchronized void exportTo(OutputStream output) throws IOException {
        if (trace.exists()) {
            try (FileInputStream input = new FileInputStream(trace)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
        }
        output.write(encode(-1, "export_status", "full", full, "dropped", dropped.get()).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String hex(byte[] bytes, int length) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[length * 2];
        for (int i = 0; i < length; i++) {
            result[i * 2] = digits[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }
}
