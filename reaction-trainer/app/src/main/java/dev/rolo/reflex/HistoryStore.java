package dev.rolo.reflex;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every finished round, kept in one small JSON file in the app's private storage.
 * Reads happen in memory; writes go to disk on a background thread via {@link AtomicFile},
 * so a crash mid-write can never corrupt the history.
 */
public final class HistoryStore {
    private static final String TAG = "HistoryStore";
    private static final String FILE = "history.json";
    private static final int VERSION = 1;
    /** Oldest rounds beyond this are dropped, per mode. */
    private static final int MAX_PER_MODE = 1000;

    private static HistoryStore instance;

    private final File dir;
    private final AtomicFile file;
    private final ArrayList<Session> sessions = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static synchronized HistoryStore get(Context context) {
        if (instance == null) instance = new HistoryStore(context.getApplicationContext());
        return instance;
    }

    /** Tests only: forget the loaded instance so the next {@link #get} reads from disk again. */
    static synchronized void resetForTests() {
        instance = null;
    }

    /** Tests only: blocks until every queued write has reached disk. */
    void awaitWritesForTests() throws Exception {
        io.submit(new Runnable() {
            @Override
            public void run() {}
        }).get();
    }

    private HistoryStore(Context context) {
        dir = context.getFilesDir();
        file = new AtomicFile(new File(dir, FILE));
        load();
    }

    public synchronized List<Session> all() {
        return new ArrayList<>(sessions);
    }

    /** Rounds of one mode, oldest first. */
    public synchronized List<Session> forMode(Mode mode) {
        ArrayList<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (s.mode == mode) out.add(s);
        }
        return out;
    }

    public synchronized void add(Session session) {
        sessions.add(session);
        int count = 0;
        for (Session s : sessions) {
            if (s.mode == session.mode) count++;
        }
        for (int i = 0; i < sessions.size() && count > MAX_PER_MODE; ) {
            if (sessions.get(i).mode == session.mode) {
                sessions.remove(i);
                count--;
            } else {
                i++;
            }
        }
        saveAsync();
    }

    public synchronized void clear(Mode mode) {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (sessions.get(i).mode == mode) sessions.remove(i);
        }
        saveAsync();
    }

    private void load() {
        byte[] data;
        try {
            data = file.readFully();
        } catch (FileNotFoundException e) {
            return; // first run
        } catch (IOException e) {
            Log.w(TAG, "Could not read history", e);
            return;
        }
        try {
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray list = root.getJSONArray("sessions");
            for (int i = 0; i < list.length(); i++) {
                Session s = fromJson(list.getJSONObject(i));
                if (s != null) sessions.add(s);
            }
        } catch (JSONException e) {
            // Keep the unreadable file aside rather than overwriting it on the next save.
            Log.w(TAG, "History file is unreadable; moving it aside", e);
            sessions.clear();
            File bad = new File(dir, FILE + ".unreadable-" + System.currentTimeMillis());
            if (!file.getBaseFile().renameTo(bad)) Log.w(TAG, "Could not move unreadable history aside");
        }
    }

    private void saveAsync() {
        final String json;
        try {
            JSONArray list = new JSONArray();
            for (Session s : sessions) list.put(toJson(s));
            json = new JSONObject().put("version", VERSION).put("sessions", list).toString();
        } catch (JSONException e) {
            Log.e(TAG, "Could not encode history", e);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                write(json);
            }
        });
    }

    private void write(String json) {
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            Log.e(TAG, "Could not save history", e);
            if (out != null) file.failWrite(out);
        }
    }

    private static JSONObject toJson(Session s) throws JSONException {
        JSONArray rt = new JSONArray();
        for (int i = 0; i < s.count(); i++) rt.put(s.time(i));
        return new JSONObject()
                .put("mode", s.mode.id)
                .put("t", s.timestamp)
                .put("rt", rt)
                .put("correct", s.correct)
                .put("errors", s.errors)
                .put("misses", s.misses)
                .put("falseStarts", s.falseStarts);
    }

    private static Session fromJson(JSONObject o) throws JSONException {
        Mode mode = Mode.fromId(o.getString("mode"));
        if (mode == null) return null; // a mode from a newer version; skip it
        JSONArray rt = o.getJSONArray("rt");
        int[] times = new int[rt.length()];
        for (int i = 0; i < times.length; i++) times[i] = rt.getInt(i);
        return new Session(mode, o.getLong("t"), times, o.optInt("correct"), o.optInt("errors"),
                o.optInt("misses"), o.optInt("falseStarts"));
    }
}
