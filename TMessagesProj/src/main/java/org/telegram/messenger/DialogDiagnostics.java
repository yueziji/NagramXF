package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.diagnostics.DialogDiagnosticCursor;
import org.telegram.messenger.diagnostics.DialogDiagnosticJournal;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Diagnostic-build observer. All database access here is SELECT-only; probe results are never applied. */
public final class DialogDiagnostics {
    public static final int NETWORK = 1, CACHE = 2, PEER = 3, PROBE = 4, RESET = 5;
    private static final AtomicLong ids = new AtomicLong();
    private static final Set<Integer> probes = new HashSet<>();
    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static DialogDiagnosticJournal journal;
    private static DispatchQueue queue;
    private static SharedPreferences preferences;

    public static synchronized void start() {
        if (!BuildConfig.DIALOG_DIAGNOSTICS || initialized || ApplicationLoader.applicationContext == null) {
            return;
        }
        initialized = true;
        try {
            Context context = ApplicationLoader.applicationContext;
            preferences = context.getSharedPreferences("dialog_diagnostics", Context.MODE_PRIVATE);
            enabled = preferences.getBoolean("enabled", true);
            journal = new DialogDiagnosticJournal(new File(context.getFilesDir(), "dialog-diagnostics"));
            queue = new DispatchQueue("dialogDiagnostics");
            event(-1, "process_start", "version", BuildConfig.VERSION_CODE, "enabled", enabled);
        } catch (Exception ignored) {
            // Diagnostics must never break login or normal chat loading, or forward data into general logs.
            enabled = false;
        }
    }

    public static boolean isEnabled() {
        start();
        return enabled && journal != null && queue != null;
    }

    public static boolean isFull() {
        start();
        return journal != null && journal.isFull();
    }

    private static boolean isRecording() {
        return isEnabled() && !journal.isFull();
    }

    private static void observe(Runnable observation) {
        if (!isRecording()) return;
        try {
            observation.run();
        } catch (Exception ignored) {
            journal.droppedEvent();
        }
    }

    public static void setEnabled(boolean value) {
        start();
        if (preferences == null || journal == null) {
            return;
        }
        if (!value) {
            event(-1, "recording_paused");
        }
        enabled = value;
        preferences.edit().putBoolean("enabled", value).apply();
        if (value) {
            event(-1, "recording_resumed");
        }
    }

    public static void event(int account, String event, Object... fields) {
        if (!isEnabled()) {
            return;
        }
        if (journal.isFull()) {
            journal.droppedEvent();
            return;
        }
        try {
            String line = journal.encode(account, event, fields);
            queue.postRunnable(() -> journal.append(line));
        } catch (Exception ignored) {
            journal.droppedEvent();
        }
    }

    private static DialogDiagnosticJournal.Reference peer(int account, long id) {
        return journal.peer(account, id);
    }

    private static DialogDiagnosticJournal.Reference message(int account, long id, int mid) {
        return journal.message(account, id, mid);
    }

    private static long peerId(TLRPC.InputPeer peer) {
        if (peer == null) return 0;
        if (peer.user_id != 0) return peer.user_id;
        if (peer.chat_id != 0) return -peer.chat_id;
        return -peer.channel_id;
    }

    private static long dialogId(TLRPC.Dialog dialog) {
        if (dialog instanceof TLRPC.TL_dialogFolder) {
            return DialogObject.makeFolderDialogId(((TLRPC.TL_dialogFolder) dialog).folder.id);
        }
        return dialog.id != 0 ? dialog.id : DialogObject.getPeerDialogId(dialog.peer);
    }

    public static void loadAttempt(int account, int folder, int cacheOffset, int limit, boolean cache,
                                   boolean loading, boolean resetting, boolean end, boolean serverEnd) {
        event(account, "load_attempt", "folder", folder, "cache_offset", cacheOffset, "limit", limit,
                "cache", cache, "loading", loading, "resetting", resetting, "end", end, "server_end", serverEnd);
        cursorState(account, folder);
    }

    public static void cursorState(int account, int folder) {
        observe(() -> {
            long[] offset = UserConfig.getInstance(account).getDialogLoadOffsets(folder);
            long did = offset[UserConfig.i_dialogsLoadOffsetChannelId] != 0 ? -offset[UserConfig.i_dialogsLoadOffsetChannelId]
                    : offset[UserConfig.i_dialogsLoadOffsetChatId] != 0 ? -offset[UserConfig.i_dialogsLoadOffsetChatId]
                    : offset[UserConfig.i_dialogsLoadOffsetUserId];
            int mid = (int) offset[UserConfig.i_dialogsLoadOffsetId];
            event(account, "cursor_state", "folder", folder, "offset_date", (int) offset[UserConfig.i_dialogsLoadOffsetDate],
                    "offset_peer", peer(account, did), "offset_message", message(account, did, mid),
                    "exhausted", mid == Integer.MAX_VALUE, "unset", mid == -1,
                    "loaded_count", UserConfig.getInstance(account).getTotalDialogsCount(folder));
        });
    }

    public static long listRequest(int account, int source, int folder, TLRPC.TL_messages_getDialogs request) {
        if (!isRecording()) return 0;
        long id = ids.incrementAndGet();
        observe(() -> {
            long did = peerId(request.offset_peer);
            event(account, "list_request", "request", id, "source", source, "folder", folder,
                    "explicit_folder", (request.flags & 2) != 0, "exclude_pinned", request.exclude_pinned,
                    "offset_date", request.offset_date, "offset_peer", peer(account, did),
                    "offset_message", message(account, did, request.offset_id), "limit", request.limit);
        });
        return id;
    }

    public static void cursorDecision(int account, int folder, long[] previous, TLRPC.Message selected) {
        observe(() -> {
            long oldPeer = previous[UserConfig.i_dialogsLoadOffsetChannelId] != 0 ? -previous[UserConfig.i_dialogsLoadOffsetChannelId]
                    : previous[UserConfig.i_dialogsLoadOffsetChatId] != 0 ? -previous[UserConfig.i_dialogsLoadOffsetChatId]
                    : previous[UserConfig.i_dialogsLoadOffsetUserId];
            long newPeer = selected == null || selected.peer_id == null ? 0 : DialogObject.getPeerDialogId(selected.peer_id);
            event(account, "cursor_decision", "folder", folder, "candidate_present", selected != null,
                    "date", selected == null ? 0 : selected.date, "peer", peer(account, newPeer),
                    "same_message_id", selected != null && selected.id == previous[UserConfig.i_dialogsLoadOffsetId],
                    "same_peer", oldPeer == newPeer);
            cursorState(account, folder);
        });
    }

    public static void beforeDatabaseReset(int account, List<Long> peers) {
        observe(() -> {
            event(account, "database_reset_begin", "count", peers.size());
            for (long did : peers) event(account, "database_reset_peer", "peer", peer(account, did));
        });
    }

    public static void listResponse(int account, int source, int folder, long request, TLObject response, TLRPC.TL_error error) {
        observe(() -> {
            if (response instanceof TLRPC.messages_Dialogs) {
                TLRPC.messages_Dialogs result = (TLRPC.messages_Dialogs) response;
                event(account, "list_response", "request", request, "source", source, "folder", folder,
                        "count", result.dialogs.size(), "messages", result.messages.size(), "total", result.count,
                        "slice", result instanceof TLRPC.TL_messages_dialogsSlice,
                        "complete", result instanceof TLRPC.TL_messages_dialogs);
                observePage(account, source, folder, request, result.dialogs, result.messages);
            } else {
                event(account, "list_error", "request", request, "source", source, "folder", folder,
                        "code", error == null ? 0 : error.code, "response_present", response != null);
            }
        });
    }

    private static ArrayList<DialogDiagnosticCursor.Message> messages(List<TLRPC.Message> messages) {
        ArrayList<DialogDiagnosticCursor.Message> result = new ArrayList<>(messages.size());
        for (TLRPC.Message m : messages) {
            if (m.peer_id != null) {
                result.add(new DialogDiagnosticCursor.Message(DialogObject.getPeerDialogId(m.peer_id), m.id, m.date));
            }
        }
        return result;
    }

    private static DialogDiagnosticCursor.Message tail(List<TLRPC.Dialog> dialogs, List<DialogDiagnosticCursor.Message> messages) {
        ArrayList<DialogDiagnosticCursor.Entry> entries = new ArrayList<>(dialogs.size());
        for (TLRPC.Dialog d : dialogs) {
            if (d instanceof TLRPC.TL_dialog) {
                entries.add(new DialogDiagnosticCursor.Entry(dialogId(d), d.top_message, d.pinned));
            }
        }
        return DialogDiagnosticCursor.tail(entries, messages);
    }

    public static void observePage(int account, int source, int folder, long request,
                                   List<TLRPC.Dialog> dialogs, List<TLRPC.Message> rawMessages) {
        if (!isRecording()) return;
        try {
            ArrayList<DialogDiagnosticCursor.Message> messages = messages(rawMessages);
            DialogDiagnosticCursor.Message oldest = DialogDiagnosticCursor.oldest(messages);
            DialogDiagnosticCursor.Message tail = tail(dialogs, messages);
            event(account, "page", "request", request, "source", source, "folder", folder, "count", dialogs.size());
            if (oldest != null) {
                event(account, "oldest_candidate", "request", request, "source", source,
                        "peer", peer(account, oldest.peer), "message", message(account, oldest.peer, oldest.id), "date", oldest.date);
            }
            if (tail != null) {
                event(account, "tail_candidate", "request", request, "source", source,
                        "peer", peer(account, tail.peer), "message", message(account, tail.peer, tail.id), "date", tail.date,
                        "matches_oldest", tail.samePosition(oldest));
            }
            int position = 0;
            for (TLRPC.Dialog d : dialogs) {
                long did = dialogId(d);
                int date = 0;
                boolean topPresent = false;
                for (DialogDiagnosticCursor.Message m : messages) {
                    if (m.peer == did && m.id == d.top_message) {
                        date = m.date;
                        topPresent = true;
                        break;
                    }
                }
                event(account, "page_dialog", "request", request, "source", source, "position", position++,
                        "peer", peer(account, did), "message", message(account, did, d.top_message),
                        "folder", d.folder_id, "pinned", d.pinned, "date", date, "top_present", topPresent,
                        "regular", d instanceof TLRPC.TL_dialog);
            }
        } catch (Exception ignored) {
            event(account, "observation_failed", "source", source, "request", request);
        }
    }

    public static void processing(int account, int folder, int loadType, boolean migrate, TLRPC.messages_Dialogs dialogs) {
        observe(() -> {
            event(account, "processing", "folder", folder, "load_type", loadType, "migrate", migrate, "count", dialogs.dialogs.size());
            if (loadType == 1) {
                observePage(account, CACHE, folder, ids.incrementAndGet(), dialogs.dialogs, dialogs.messages);
            }
        });
    }

    // Called on the UI thread after normal list application, with no changes to that application.
    public static void applied(int account, int folder, int loadType, List<TLRPC.Dialog> incoming) {
        observe(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            Set<Long> visible = new HashSet<>();
            for (TLRPC.Dialog d : controller.allDialogs) visible.add(d.id);
            event(account, "list_applied", "folder", folder, "load_type", loadType,
                    "count", controller.allDialogs.size(), "end", controller.isDialogsEndReached(folder),
                    "server_end", controller.isServerDialogsEndReached(folder));
            for (TLRPC.Dialog d : incoming) {
                long did = dialogId(d);
                event(account, "applied_dialog", "folder", folder, "peer", peer(account, did),
                        "in_memory", controller.dialogs_dict.get(did) != null, "in_list", visible.contains(did));
            }
            cursorState(account, folder);
        });
    }

    public static void historyOpen(int account, long did, boolean cache) {
        observe(() -> {
            long request = ids.incrementAndGet();
            event(account, "history_open", "request", request, "peer", peer(account, did), "cache", cache);
            inspectPeer(account, request, did);
        });
    }

    public static void peerLookup(int account, long did, boolean response, TLRPC.TL_messages_peerDialogs result, TLRPC.TL_error error) {
        observe(() -> {
            long request = ids.incrementAndGet();
            event(account, response ? "peer_response" : "peer_request", "request", request, "peer", peer(account, did),
                    "count", result == null ? 0 : result.dialogs.size(), "code", error == null ? 0 : error.code);
            if (result != null) observePage(account, PEER, 0, request, result.dialogs, result.messages);
            inspectPeer(account, request, did);
        });
    }

    private static void inspectPeer(int account, long request, long did) {
        AndroidUtilities.runOnUIThread(() -> observe(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            boolean inList = false;
            for (TLRPC.Dialog d : controller.allDialogs) if (d.id == did) { inList = true; break; }
            event(account, "peer_memory", "request", request, "peer", peer(account, did),
                    "in_memory", controller.dialogs_dict.get(did) != null, "in_list", inList);
        }));
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> databasePeer(account, request, did, storage.getDatabase()));
    }

    // Called on the storage queue, after the existing transaction has committed.
    public static void stored(int account, int check, List<TLRPC.Dialog> dialogs, SQLiteDatabase database) {
        observe(() -> {
            long batch = ids.incrementAndGet();
            event(account, "storage_batch", "request", batch, "check", check, "count", dialogs.size());
            for (TLRPC.Dialog d : dialogs) databasePeer(account, batch, dialogId(d), database);
        });
    }

    private static void databasePeer(int account, long request, long did, SQLiteDatabase database) {
        if (!isRecording()) return;
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT date, last_mid, folder_id FROM dialogs WHERE did = ?", did);
            boolean exists = cursor.next();
            event(account, "database_peer", "request", request, "peer", peer(account, did), "exists", exists,
                    "date", exists ? cursor.intValue(0) : 0, "folder", exists ? cursor.intValue(2) : -1,
                    "message", message(account, did, exists ? cursor.intValue(1) : 0));
        } catch (Exception ignored) {
            event(account, "database_check_failed", "request", request);
        } finally {
            if (cursor != null) cursor.dispose();
        }
    }

    public static void snapshot(int account, Runnable complete) {
        if (!isEnabled() || isFull()) {
            if (complete != null) AndroidUtilities.runOnUIThread(complete);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            long snapshot = ids.incrementAndGet();
            event(account, "snapshot_begin", "request", snapshot, "count", controller.allDialogs.size());
            for (TLRPC.Dialog d : controller.allDialogs) {
                event(account, "memory_dialog", "request", snapshot, "peer", peer(account, d.id),
                        "date", d.last_message_date, "folder", d.folder_id, "pinned", d.pinned);
            }
            cursorState(account, 0);
            cursorState(account, 1);
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                SQLiteCursor cursor = null;
                int count = 0;
                boolean success = false;
                try {
                    cursor = storage.getDatabase().queryFinalized("SELECT did, date, folder_id, last_mid FROM dialogs ORDER BY date DESC LIMIT 20001");
                    while (cursor.next()) {
                        if (++count > 20000) break;
                        long did = cursor.longValue(0);
                        event(account, "database_dialog", "request", snapshot, "peer", peer(account, did),
                                "date", cursor.intValue(1), "folder", cursor.intValue(2),
                                "message", message(account, did, cursor.intValue(3)));
                    }
                    success = true;
                } catch (Exception ignored) {
                    event(account, "database_check_failed", "request", snapshot);
                } finally {
                    if (cursor != null) cursor.dispose();
                }
                event(account, "snapshot_end", "request", snapshot, "count", count, "complete", success && count <= 20000);
                if (complete != null) AndroidUtilities.runOnUIThread(complete);
            });
        });
    }

    public static void export(Context context, Uri destination, int account, Consumer<Boolean> complete) {
        start();
        if (journal == null || queue == null) {
            complete.accept(false);
            return;
        }
        snapshot(account, () -> queue.postRunnable(() -> {
            boolean success = false;
            try (OutputStream output = context.getContentResolver().openOutputStream(destination)) {
                if (output != null) {
                    journal.exportTo(output);
                    success = true;
                }
            } catch (Exception ignored) {
                // Do not log a document URI or the exception's potentially identifying message.
            }
            boolean result = success;
            AndroidUtilities.runOnUIThread(() -> complete.accept(result));
        }));
    }

    public static void probe(int account, Consumer<Boolean> complete) {
        synchronized (probes) {
            if (!isEnabled() || isFull() || !probes.add(account)) {
                complete.accept(false);
                return;
            }
        }
        snapshot(account, () -> new Probe(account, complete).pinned());
    }

    private static final class Probe {
        private final int account;
        private final Consumer<Boolean> complete;
        private final Set<String> positions = new HashSet<>();
        private final long probeId = ids.incrementAndGet();
        private int folder;
        private int pages;
        private TLRPC.InputPeer offsetPeer = new TLRPC.TL_inputPeerEmpty();
        private DialogDiagnosticCursor.Message offset;
        private boolean finished;

        Probe(int account, Consumer<Boolean> complete) {
            this.account = account;
            this.complete = complete;
            event(account, "probe_begin", "request", probeId);
        }

        void pinned() {
            if (!isRecording()) { finish(false); return; }
            TLRPC.TL_messages_getPinnedDialogs request = new TLRPC.TL_messages_getPinnedDialogs();
            request.folder_id = folder;
            AccountInstance.getInstance(account).getConnectionsManager().sendRequest(request, (response, error) -> {
                if (!isRecording()) { finish(false); return; }
                if (response instanceof TLRPC.TL_messages_peerDialogs) {
                    TLRPC.TL_messages_peerDialogs result = (TLRPC.TL_messages_peerDialogs) response;
                    observePage(account, PROBE, folder, ids.incrementAndGet(), result.dialogs, result.messages);
                    next();
                } else {
                    event(account, "probe_error", "request", probeId, "folder", folder, "code", error == null ? 0 : error.code);
                    finish(false);
                }
            });
        }

        void next() {
            if (!isRecording() || pages >= 100) {
                finish(false);
                return;
            }
            TLRPC.TL_messages_getDialogs request = new TLRPC.TL_messages_getDialogs();
            request.flags |= 2;
            request.folder_id = folder;
            request.exclude_pinned = true;
            request.limit = 100;
            request.offset_peer = offsetPeer;
            request.offset_date = offset == null ? 0 : offset.date;
            request.offset_id = offset == null ? 0 : offset.id;
            long requestId = listRequest(account, PROBE, folder, request);
            pages++;
            AccountInstance.getInstance(account).getConnectionsManager().sendRequest(request, (response, error) -> {
                if (!isRecording()) { finish(false); return; }
                listResponse(account, PROBE, folder, requestId, response, error);
                if (!(response instanceof TLRPC.messages_Dialogs)) { finish(false); return; }
                TLRPC.messages_Dialogs result = (TLRPC.messages_Dialogs) response;
                if (!(result instanceof TLRPC.TL_messages_dialogs) && !(result instanceof TLRPC.TL_messages_dialogsSlice)) {
                    finish(false);
                    return;
                }
                if (result instanceof TLRPC.TL_messages_dialogs || result.dialogs.isEmpty()) {
                    event(account, "probe_folder_end", "request", probeId, "folder", folder, "pages", pages);
                    if (folder == 0) {
                        folder = 1;
                        pages = 0;
                        positions.clear();
                        offset = null;
                        offsetPeer = new TLRPC.TL_inputPeerEmpty();
                        pinned();
                    } else {
                        finish(true);
                    }
                    return;
                }
                DialogDiagnosticCursor.Message cursor = tail(result.dialogs, messages(result.messages));
                if (cursor == null || !positions.add(cursor.peer + ":" + cursor.id + ":" + cursor.date)) {
                    event(account, "probe_no_progress", "request", probeId, "folder", folder);
                    finish(false);
                    return;
                }
                TLRPC.InputPeer nextPeer = inputPeer(cursor.peer, result);
                if (nextPeer == null) {
                    event(account, "probe_missing_peer", "request", probeId, "folder", folder);
                    finish(false);
                    return;
                }
                offset = cursor;
                offsetPeer = nextPeer;
                Utilities.stageQueue.postRunnable(this::next, 250);
            });
        }

        private TLRPC.InputPeer inputPeer(long did, TLRPC.messages_Dialogs result) {
            for (TLRPC.Dialog d : result.dialogs) {
                if (d instanceof TLRPC.TL_dialog && dialogId(d) == did) {
                    if (d.peer.chat_id != 0) {
                        TLRPC.TL_inputPeerChat peer = new TLRPC.TL_inputPeerChat();
                        peer.chat_id = d.peer.chat_id;
                        return peer;
                    }
                    if (d.peer.channel_id != 0) {
                        for (TLRPC.Chat chat : result.chats) {
                            if (chat.id == d.peer.channel_id) {
                                TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
                                peer.channel_id = chat.id;
                                peer.access_hash = chat.access_hash;
                                return peer;
                            }
                        }
                    } else {
                        for (TLRPC.User user : result.users) {
                            if (user.id == d.peer.user_id) {
                                TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
                                peer.user_id = user.id;
                                peer.access_hash = user.access_hash;
                                return peer;
                            }
                        }
                    }
                }
            }
            return null;
        }

        private void finish(boolean success) {
            if (finished) return;
            finished = true;
            event(account, "probe_end", "request", probeId, "complete", success, "folder", folder, "pages", pages);
            synchronized (probes) { probes.remove(account); }
            snapshot(account, () -> complete.accept(success));
        }
    }

    private DialogDiagnostics() {
    }
}
