package org.benwiley.stm4flip;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only access to the offline STM schedule database (built on the phone by DataUpdater/GtfsImporter). */
final class StmData {

    private static final String DB_NAME = "stm.db";
    private final SQLiteDatabase db;

    private StmData(SQLiteDatabase db) { this.db = db; }

    static File dbFile(Context ctx) { return ctx.getDatabasePath(DB_NAME); }

    /** Opens the installed database, or returns null if there isn't a usable one (missing / corrupt). */
    static StmData openIfValid(Context ctx) {
        File f = dbFile(ctx);
        if (!f.exists()) return null;
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM days", null)) {
                if (c.moveToFirst() && c.getInt(0) > 0) return new StmData(db);
            }
            db.close();
        } catch (Exception ignored) { }
        f.delete();
        return null;
    }

    void close() {
        try { db.close(); } catch (Exception ignored) { }
    }

    /** Value from the meta table (zip_md5, server_sig, ...), or "" if absent / table missing. */
    String meta(String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return c.moveToFirst() && c.getString(0) != null ? c.getString(0) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** True if the feed's calendar includes this date (otherwise the data is out of date). */
    boolean coversDay(Calendar day) {
        return lookup(day, 0) >= 0;
    }

    // ------------------------------------------------------------------ calendar

    /** {today's profile, yesterday's profile}; stale[0] is set if the feed doesn't cover today's date. */
    int[] profiles(Calendar now, boolean[] stale) {
        Calendar y = (Calendar) now.clone();
        y.add(Calendar.DAY_OF_MONTH, -1);
        return new int[]{profileFor(now, stale), profileFor(y, stale)};
    }

    private int profileFor(Calendar day, boolean[] stale) {
        int p = lookup(day, 0);
        if (p >= 0) return p;
        stale[0] = true;  // not covered by the data: reuse the most recent earlier week with the same weekday
        for (int k = 1; k <= 26; k++) {
            p = lookup(day, -7 * k);
            if (p >= 0) return p;
        }
        for (int k = 1; k <= 26; k++) {   // date is before the data starts: use the nearest later week instead
            p = lookup(day, 7 * k);
            if (p >= 0) return p;
        }
        return -1;
    }

    private int lookup(Calendar day, int offsetDays) {
        Calendar c = (Calendar) day.clone();
        c.add(Calendar.DAY_OF_MONTH, offsetDays);
        int d = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
        try (Cursor cur = db.rawQuery("SELECT profile FROM days WHERE date=" + d, null)) {
            return cur.moveToFirst() ? cur.getInt(0) : -1;
        }
    }

    // ------------------------------------------------------------------ stops, lines, times

    /** Every bus stop with its lines (no schedule times), sorted by latitude. Loaded once so all markers can be drawn up front. */
    List<Transit.Stop> allStops() {
        Map<Integer, Transit.Stop> byId = new HashMap<>();
        List<Transit.Stop> list = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id,name,lat,lon FROM stops ORDER BY lat", null)) {
            while (c.moveToNext()) {
                Transit.Stop s = new Transit.Stop();
                s.id = c.getInt(0);
                s.name = c.getString(1);
                s.lat = c.getDouble(2);
                s.lon = c.getDouble(3);
                byId.put(s.id, s);
                list.add(s);
            }
        }
        try (Cursor c = db.rawQuery("SELECT id,stop_id,route,label,dir,letter FROM lines", null)) {
            while (c.moveToNext()) {
                Transit.Stop s = byId.get(c.getInt(1));
                if (s == null) continue;
                Transit.Line l = new Transit.Line();
                l.id = c.getInt(0);
                l.stopId = s.id;
                l.route = c.getString(2);
                l.label = c.getString(3);
                l.dir = c.getInt(4);
                l.letter = c.getString(5);
                s.lines.add(l);
            }
        }
        for (Transit.Stop s : list) s.routesText = Transit.routesText(s);
        return list;
    }

    /** Stops in the box, with their lines and today's/yesterday's departure times loaded. */
    List<Transit.Stop> stopsIn(double south, double north, double west, double east, int pToday, int pYesterday) {
        Map<Integer, Transit.Stop> byId = new LinkedHashMap<>();
        String q = "SELECT id,name,lat,lon FROM stops WHERE lat BETWEEN " + south + " AND " + north
                + " AND lon BETWEEN " + west + " AND " + east;
        try (Cursor c = db.rawQuery(q, null)) {
            while (c.moveToNext()) {
                Transit.Stop s = new Transit.Stop();
                s.id = c.getInt(0);
                s.name = c.getString(1);
                s.lat = c.getDouble(2);
                s.lon = c.getDouble(3);
                byId.put(s.id, s);
            }
        }
        List<Integer> stopIds = new ArrayList<>(byId.keySet());
        Map<Integer, Transit.Line> lineById = new HashMap<>();
        for (int i = 0; i < stopIds.size(); i += 400) {
            String in = join(stopIds.subList(i, Math.min(i + 400, stopIds.size())));
            try (Cursor c = db.rawQuery("SELECT id,stop_id,route,label,dir,letter FROM lines WHERE stop_id IN (" + in + ")", null)) {
                while (c.moveToNext()) {
                    Transit.Line l = new Transit.Line();
                    l.id = c.getInt(0);
                    l.stopId = c.getInt(1);
                    l.route = c.getString(2);
                    l.label = c.getString(3);
                    l.dir = c.getInt(4);
                    l.letter = c.getString(5);
                    lineById.put(l.id, l);
                    byId.get(l.stopId).lines.add(l);
                }
            }
        }
        List<Integer> lineIds = new ArrayList<>(lineById.keySet());
        for (int i = 0; i < lineIds.size(); i += 400) {
            String in = join(lineIds.subList(i, Math.min(i + 400, lineIds.size())));
            String sql = "SELECT s.line_id, s.profile, b.data FROM sched s JOIN blobs b ON b.id = s.blob_id"
                    + " WHERE s.line_id IN (" + in + ") AND s.profile IN (" + pToday + "," + pYesterday + ")";
            try (Cursor c = db.rawQuery(sql, null)) {
                while (c.moveToNext()) {
                    Transit.Line l = lineById.get(c.getInt(0));
                    int profile = c.getInt(1);
                    int[] times = Transit.decode(c.getBlob(2));
                    if (profile == pToday) l.today = times;
                    if (profile == pYesterday) l.yesterday = times;
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String join(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }
}
