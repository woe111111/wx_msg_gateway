package local.mdnode.wecomprobe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class MessageStore extends SQLiteOpenHelper {
    private static MessageStore instance;
    static final int LIMIT = 10000;
    static final class Full extends RuntimeException { }
    static synchronized MessageStore get(Context context) {
        if (instance == null) instance = new MessageStore(context.getApplicationContext());
        return instance;
    }
    private MessageStore(Context context) {
        super(context, "reader.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, groups_json TEXT NOT NULL, started_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, record_id TEXT NOT NULL UNIQUE, "
                + "session_id TEXT NOT NULL, group_name TEXT NOT NULL, sender_name TEXT, direction TEXT NOT NULL, "
                + "text TEXT NOT NULL, display_time TEXT, observed_at_ms INTEGER NOT NULL, observation_kind TEXT NOT NULL, "
                + "identity_quality TEXT NOT NULL)");
        db.execSQL("CREATE INDEX messages_session ON messages(session_id,seq)");
        db.execSQL("CREATE TABLE tracker_state (session_id TEXT NOT NULL, group_name TEXT NOT NULL, "
                + "entries_json TEXT NOT NULL, PRIMARY KEY(session_id,group_name))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database migration");
    }

    synchronized MessageTracker.Result save(String session, List<String> groups, MessageExtractor.Result scan,
                                           long observedAt) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues sessionValues = new ContentValues();
            sessionValues.put("id", session); sessionValues.put("groups_json", new JSONArray(groups).toString());
            sessionValues.put("started_ms", observedAt);
            db.insertWithOnConflict("sessions", null, sessionValues, SQLiteDatabase.CONFLICT_IGNORE);
            boolean baseline = true;
            List<MessageTracker.Entry> previous = new ArrayList<>();
            try (Cursor c = db.query("tracker_state", new String[]{"entries_json"},
                    "session_id=? AND group_name=?", new String[]{session, scan.group}, null, null, null)) {
                if (c.moveToFirst()) {
                    baseline = false;
                    JSONArray data = new JSONArray(c.getString(0));
                    for (int i=0; i<data.length(); i++) {
                        JSONObject entry = data.getJSONObject(i);
                        previous.add(new MessageTracker.Entry(entry.getString("id"), entry.getString("key")));
                    }
                }
            }
            MessageTracker.Result result = MessageTracker.reconcile(previous, scan.messages, baseline);
            long count;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM messages", null)) { c.moveToFirst(); count=c.getLong(0); }
            if (count + result.added.size() > LIMIT) throw new Full();
            for (MessageTracker.Added item : result.added) {
                ContentValues row = new ContentValues();
                row.put("record_id", item.entry.id); row.put("session_id", session); row.put("group_name", scan.group);
                row.put("sender_name", item.message.sender); row.put("direction", item.message.direction);
                row.put("text", item.message.text); row.put("display_time", item.message.displayTime);
                row.put("observed_at_ms", observedAt); row.put("observation_kind", item.kind);
                row.put("identity_quality", item.quality);
                db.insertOrThrow("messages", null, row);
            }
            // An empty window must not erase useful identity anchors.
            if (!scan.messages.isEmpty() && !sameCheckpoint(previous, result.checkpoint)) {
                JSONArray entries = new JSONArray();
                for (MessageTracker.Entry entry : result.checkpoint) {
                    entries.put(new JSONObject().put("id", entry.id).put("key", entry.key));
                }
                ContentValues checkpoint = new ContentValues();
                checkpoint.put("session_id", session); checkpoint.put("group_name", scan.group);
                checkpoint.put("entries_json", entries.toString());
                db.insertWithOnConflict("tracker_state", null, checkpoint, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    private static boolean sameCheckpoint(List<MessageTracker.Entry> before, List<MessageTracker.Entry> after) {
        if (before.size() != after.size()) return false;
        for (int i=0; i<before.size(); i++) {
            if (!before.get(i).id.equals(after.get(i).id) || !before.get(i).key.equals(after.get(i).key)) return false;
        }
        return true;
    }

    synchronized JSONObject export() throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().query("messages", null, null, null, null, null, "seq ASC")) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (String column : new String[]{"record_id", "session_id", "group_name", "sender_name", "direction",
                        "text", "display_time", "observation_kind", "identity_quality"}) {
                    int index = c.getColumnIndexOrThrow(column);
                    row.put(column, c.isNull(index) ? JSONObject.NULL : c.getString(index));
                }
                row.put("observed_at_ms", c.getLong(c.getColumnIndexOrThrow("observed_at_ms")));
                row.put("local_sequence", c.getLong(c.getColumnIndexOrThrow("seq")));
                row.put("schema_version", 1); row.put("message_type", "text");
                row.put("source", "android_accessibility"); row.put("wecom_version", "5.0.13");
                row.put("layout_profile", MessageExtractor.PROFILE);
                rows.put(row);
            }
        }
        return new JSONObject().put("schema_version", 1).put("exported_at_ms", System.currentTimeMillis())
                .put("identity_notice", "UI sequence inference; not a server message ID or complete message stream")
                .put("records", rows);
    }
}
