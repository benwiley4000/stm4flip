package org.benwiley.stm4flip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal GTFS-realtime TripUpdates client: downloads the STM feed and decodes just the fields
 * we need (route, direction, stop id, arrival time) with a hand-written protobuf reader,
 * so the app needs no protobuf dependency.
 */
final class RtFeed {

    static final String URL = "https://api.stm.info/pub/od/gtfs-rt/ic/v2/tripUpdates";

    private static final String API_KEY = BuildConfig.STM_API_KEY;

    /** Immutable parsed feed. Entry i: stop[i], route[i] (index into routeNames), dir[i] (-1 = unknown), time[i] (epoch s). */
    static final class Snapshot {
        int n;
        int[] stop = new int[4096];
        int[] route = new int[4096];
        byte[] dir = new byte[4096];
        int[] time = new int[4096];
        boolean[] skipped = new boolean[4096];
        String[] routeNames = new String[0];
        long fetchedMs;

        int routeIndex(String name) {
            for (int i = 0; i < routeNames.length; i++) if (routeNames[i].equals(name)) return i;
            return -1;
        }
    }

    static Snapshot fetch() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("apiKey", API_KEY);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            }
            Snapshot s = parse(bo.toByteArray());
            s.fetchedMs = System.currentTimeMillis();
            return s;
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ protobuf

    private static final class Reader {
        final byte[] b;
        int p;
        final int end;
        Reader(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }
        boolean more() { return p < end; }
        long varint() {
            long r = 0;
            int shift = 0;
            while (true) {
                int x = b[p++];
                r |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return r;
                shift += 7;
            }
        }
        void skip(int wireType) {
            switch (wireType) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: { int len = (int) varint(); p += len; break; }
                case 5: p += 4; break;
                default: throw new IllegalStateException("bad wire type " + wireType);
            }
        }
        String string(int len) { String s = new String(b, p, len, java.nio.charset.StandardCharsets.UTF_8); p += len; return s; }
    }

    static Snapshot parse(byte[] buf) {
        Snapshot s = new Snapshot();
        Map<String, Integer> routes = new HashMap<>();
        List<String> names = new ArrayList<>();
        Reader r = new Reader(buf, 0, buf.length);
        while (r.more()) {                                  // FeedMessage
            int tag = (int) r.varint();
            if (tag >>> 3 == 2 && (tag & 7) == 2) {         // entity
                int len = (int) r.varint();
                parseEntity(new Reader(buf, r.p, r.p + len), s, routes, names);
                r.p += len;
            } else r.skip(tag & 7);
        }
        s.routeNames = names.toArray(new String[0]);
        return s;
    }

    private static void parseEntity(Reader r, Snapshot s, Map<String, Integer> routes, List<String> names) {
        while (r.more()) {
            int tag = (int) r.varint();
            if (tag >>> 3 == 3 && (tag & 7) == 2) {         // trip_update
                int len = (int) r.varint();
                parseTripUpdate(new Reader(r.b, r.p, r.p + len), s, routes, names);
                r.p += len;
            } else r.skip(tag & 7);
        }
    }

    private static void parseTripUpdate(Reader r, Snapshot s, Map<String, Integer> routes, List<String> names) {
        String routeId = null;
        int dir = -1;
        List<long[]> updates = new ArrayList<>();           // {stopId, time}
        while (r.more()) {
            int tag = (int) r.varint();
            int f = tag >>> 3, wt = tag & 7;
            if (f == 1 && wt == 2) {                        // TripDescriptor
                int len = (int) r.varint();
                Reader t = new Reader(r.b, r.p, r.p + len);
                while (t.more()) {
                    int tt = (int) t.varint();
                    int tf = tt >>> 3, twt = tt & 7;
                    if (tf == 5 && twt == 2) { int l = (int) t.varint(); routeId = t.string(l); }
                    else if (tf == 6 && twt == 0) dir = (int) t.varint();
                    else t.skip(twt);
                }
                r.p += len;
            } else if (f == 2 && wt == 2) {                 // StopTimeUpdate
                int len = (int) r.varint();
                long[] u = parseStopTimeUpdate(new Reader(r.b, r.p, r.p + len));
                if (u != null) updates.add(u);
                r.p += len;
            } else r.skip(wt);
        }
        // A SKIPPED update has no time of its own: borrow the time of the nearest stop (by stop_sequence) that has one.
        for (long[] u : updates) {
            if (u[2] != 1 || u[1] != 0) continue;
            long best = 0, bestDist = Long.MAX_VALUE;
            for (long[] o : updates) {
                if (o[1] == 0) continue;
                long dist = Math.abs(o[3] - u[3]);
                if (dist < bestDist) { bestDist = dist; best = o[1]; }
            }
            u[1] = best;
        }
        if (routeId == null || updates.isEmpty()) return;
        Integer ri = routes.get(routeId);
        if (ri == null) { ri = names.size(); names.add(routeId); routes.put(routeId, ri); }
        for (long[] u : updates) {
            if (u[1] == 0) continue;
            if (s.n == s.stop.length) {
                int cap = s.n * 2;
                s.stop = Arrays.copyOf(s.stop, cap);
                s.route = Arrays.copyOf(s.route, cap);
                s.dir = Arrays.copyOf(s.dir, cap);
                s.time = Arrays.copyOf(s.time, cap);
                s.skipped = Arrays.copyOf(s.skipped, cap);
            }
            s.stop[s.n] = (int) u[0];
            s.route[s.n] = ri;
            s.dir[s.n] = (byte) dir;
            s.time[s.n] = (int) u[1];
            s.skipped[s.n] = u[2] == 1;
            s.n++;
        }
    }

    /** Returns {stopId, epochTime} or null if unusable (no stop id / no time / skipped). */
    private static long[] parseStopTimeUpdate(Reader r) {
        long arrival = 0, departure = 0, stopId = -1, rel = 0, seq = 0;
        while (r.more()) {
            int tag = (int) r.varint();
            int f = tag >>> 3, wt = tag & 7;
            if ((f == 2 || f == 3) && wt == 2) {            // arrival / departure StopTimeEvent
                int len = (int) r.varint();
                Reader e = new Reader(r.b, r.p, r.p + len);
                long time = 0;
                while (e.more()) {
                    int et = (int) e.varint();
                    if (et >>> 3 == 2 && (et & 7) == 0) time = e.varint();
                    else e.skip(et & 7);
                }
                if (f == 2) arrival = time; else departure = time;
                r.p += len;
            } else if (f == 4 && wt == 2) {
                int len = (int) r.varint();
                String sid = r.string(len);
                try { stopId = Long.parseLong(sid.trim()); } catch (NumberFormatException ex) { stopId = -1; }
            } else if (f == 1 && wt == 0) {
                seq = r.varint();
            } else if (f == 5 && wt == 0) {
                rel = r.varint();
            }
            else r.skip(wt);
        }
        long t = arrival != 0 ? arrival : departure;
        if (stopId < 0 || rel == 2) return null;        // NO_DATA: nothing useful
        if (rel != 1 && t == 0) return null;             // a normal update needs a time
        return new long[]{stopId, t, rel == 1 ? 1 : 0, seq};
    }
}
