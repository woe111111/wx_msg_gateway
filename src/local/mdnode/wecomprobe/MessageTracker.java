package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local UI identity inference, never a server message ID or exactly-once claim. */
final class MessageTracker {
    static final class Entry {
        final String id, key;
        Entry(String id, String key) { this.id = id; this.key = key; }
    }
    static final class Added {
        final Entry entry;
        final MessageExtractor.Message message;
        final String kind, quality;
        Added(Entry entry, MessageExtractor.Message message, String kind, String quality) {
            this.entry = entry; this.message = message; this.kind = kind; this.quality = quality;
        }
    }
    static final class Result {
        final List<Entry> checkpoint = new ArrayList<>();
        final List<Added> added = new ArrayList<>();
        boolean ambiguous;
    }

    static Result reconcile(List<Entry> previous, List<MessageExtractor.Message> current, boolean baseline) {
        Result result = new Result();
        int best = 0, oldStart = 0, newStart = 0, candidates = 0;
        for (int p = 0; p < previous.size(); p++) {
            for (int c = 0; c < current.size(); c++) {
                int length = 0;
                while (p + length < previous.size() && c + length < current.size()
                        && previous.get(p + length).key.equals(current.get(c + length).key())) length++;
                if (length > best) {
                    best = length; oldStart = p; newStart = c; candidates = 1;
                } else if (length > 0 && length == best) candidates++;
            }
        }
        // Equal whole windows are reused, even with repeated text. A new identical window is inherently unidentifiable.
        boolean unique = best > 0 && candidates == 1;
        result.ambiguous = !baseline && !current.isEmpty() && !unique;
        for (int c = 0; c < current.size(); c++) {
            MessageExtractor.Message message = current.get(c);
            Entry entry;
            if (unique && c >= newStart && c < newStart + best) {
                entry = previous.get(oldStart + c - newStart);
            } else {
                entry = new Entry(UUID.randomUUID().toString(), message.key());
                result.added.add(new Added(entry, message, baseline ? "baseline" : "first_seen",
                        result.ambiguous ? "ambiguous" : "ui_sequence"));
            }
            result.checkpoint.add(entry);
        }
        return result;
    }
}
