package org.telegram.messenger.diagnostics;

import java.util.List;

/** Cursor selection for the independent read-only probe, never for normal application loading. */
public final class DialogDiagnosticCursor {
    public static final class Entry {
        public final long peer;
        public final int topMessage;
        public final boolean pinned;

        public Entry(long peer, int topMessage, boolean pinned) {
            this.peer = peer;
            this.topMessage = topMessage;
            this.pinned = pinned;
        }
    }

    public static final class Message {
        public final long peer;
        public final int id;
        public final int date;

        public Message(long peer, int id, int date) {
            this.peer = peer;
            this.id = id;
            this.date = date;
        }

        public boolean samePosition(Message other) {
            return other != null && peer == other.peer && id == other.id && date == other.date;
        }
    }

    public static Message tail(List<Entry> dialogs, List<Message> messages) {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            Entry dialog = dialogs.get(i);
            if (dialog.pinned || dialog.peer == 0 || dialog.topMessage <= 0) {
                continue;
            }
            for (Message message : messages) {
                if (message.peer == dialog.peer && message.id == dialog.topMessage && message.date > 0) {
                    return message;
                }
            }
        }
        return null;
    }

    public static Message oldest(List<Message> messages) {
        Message oldest = null;
        for (Message message : messages) {
            if (message.date > 0 && (oldest == null || message.date < oldest.date)) {
                oldest = message;
            }
        }
        return oldest;
    }

    private DialogDiagnosticCursor() {
    }
}
