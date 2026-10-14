package org.benwiley.stm4flip;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns the STM static GTFS zip into the compact schedule format the app reads
 * (same output as tools/build_gtfs_db.py). Pure Java; results go to a {@link Sink}.
 *
 * stop_times.txt is ~200 MB, so it is streamed with a hand-rolled byte parser and kept in one packed long[].
 */
final class GtfsImporter {

    interface Sink {
        void stop(int id, String name, double lat, double lon);
        void line(int id, int stopId, String route, String label, int dir, String letter);
        void blob(int id, byte[] data);
        void sched(int lineId, int profile, int blobId);
        void day(int yyyymmdd, int profile);
    }

    interface Progress { void update(String msg); }

    static void run(File zip, Sink sink, Progress prog) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            new GtfsImporter(zf, sink, prog).go();
        }
    }

    // ------------------------------------------------------------------ state

    private final ZipFile zf;
    private final Sink sink;
    private final Progress prog;

    private GtfsImporter(ZipFile zf, Sink sink, Progress prog) { this.zf = zf; this.sink = sink; this.prog = prog; }

    private static final Map<String, String> CARDINAL = new HashMap<>();
    static {
        CARDINAL.put("nord", "N"); CARDINAL.put("sud", "S"); CARDINAL.put("est", "E"); CARDINAL.put("ouest", "O");
        CARDINAL.put("north", "N"); CARDINAL.put("south", "S"); CARDINAL.put("east", "E"); CARDINAL.put("west", "O");
    }

    // ------------------------------------------------------------------ small CSV files

    private static final class Csv {
        Map<String, Integer> idx = new HashMap<>();
        String[] f = new String[0];
        String get(String col) {
            Integer i = idx.get(col);
            return (i == null || i >= f.length) ? "" : f[i];
        }
    }

    private interface RowHandler { void row(Csv r); }

    private boolean readCsv(String name, boolean required, RowHandler h) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) {
            if (required) throw new IOException(name + " n'existe pas");
            return false;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8), 1 << 16)) {
            String line = br.readLine();
            if (line == null) return true;
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            Csv c = new Csv();
            String[] head = splitCsv(line);
            for (int i = 0; i < head.length; i++) c.idx.put(head[i].trim(), i);
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                c.f = splitCsv(line);
                h.row(c);
            }
        }
        return true;
    }

    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else q = false;
                } else sb.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(ch);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static int toDate(String s) { return toInt(s); }

    // ------------------------------------------------------------------ main

    private void go() throws IOException {
        // ---- routes (buses only)
        prog.update("MaJ de routes…");
        final List<String> routeIds = new ArrayList<>();
        final List<String> routeLabels = new ArrayList<>();
        final Map<String, Integer> routeIdx = new HashMap<>();
        readCsv("routes.txt", true, r -> {
            String t = r.get("route_type").trim();
            if (t.isEmpty() || t.equals("3")) {
                String label = r.get("route_short_name").trim();
                routeIdx.put(r.get("route_id"), routeIds.size());
                routeIds.add(r.get("route_id"));
                routeLabels.add(label.isEmpty() ? r.get("route_id") : label);
            }
        });
        final Map<String, String> directions = new HashMap<>();
        readCsv("directions.txt", false, r ->
                directions.put(r.get("route_id") + "|" + toInt(r.get("direction_id")), r.get("direction")));

        // ---- trips
        prog.update("MaJ de trajets…");
        ZipEntry te = zf.getEntry("trips.txt");
        if (te == null) throw new IOException("trips.txt n'existe pas");
        final LongIntMap tripMap = new LongIntMap((int) Math.min(3_000_000, Math.max(1024, te.getSize() / 40)));
        final List<String> svcIds = new ArrayList<>();
        final Map<String, Integer> svcIdx = new HashMap<>();
        final Map<Integer, Map<String, Integer>> votes = new HashMap<>();   // route*2+dir -> headsign -> count
        readCsv("trips.txt", true, r -> {
            Integer ri = routeIdx.get(r.get("route_id"));
            if (ri == null) return;
            int d = toInt(r.get("direction_id")) == 0 ? 0 : 1;
            String sid = r.get("service_id");
            Integer si = svcIdx.get(sid);
            if (si == null) { si = svcIds.size(); svcIds.add(sid); svcIdx.put(sid, si); }
            byte[] kb = r.get("trip_id").getBytes(StandardCharsets.UTF_8);
            tripMap.put(tripKey(kb, 0, kb.length), (si << 13) | (ri << 1) | d);
            Map<String, Integer> m = votes.get(ri * 2 + d);
            if (m == null) { m = new LinkedHashMap<>(); votes.put(ri * 2 + d, m); }
            String hs = r.get("trip_headsign").trim();
            Integer c = m.get(hs);
            m.put(hs, c == null ? 1 : c + 1);
        });
        if (svcIds.size() >= 4096 || routeIds.size() >= 4096) throw new IOException("trop de donées");

        // ---- direction letters
        final String[] letters = new String[routeIds.size() * 2];
        for (int ri = 0; ri < routeIds.size(); ri++) {
            for (int d = 0; d < 2; d++) {
                String hs = "";
                Map<String, Integer> m = votes.get(ri * 2 + d);
                if (m != null) {
                    int best = -1;
                    for (Map.Entry<String, Integer> en : m.entrySet())
                        if (en.getValue() > best) { best = en.getValue(); hs = en.getKey(); }
                }
                String l = CARDINAL.get(firstWord(directions.get(routeIds.get(ri) + "|" + d)));
                if (l == null) l = CARDINAL.get(firstWord(hs));
                if (l == null) l = hs.isEmpty() ? String.valueOf(d) : hs.substring(0, 1).toUpperCase();
                letters[ri * 2 + d] = l;
            }
        }
        votes.clear();

        // ---- calendar -> schedule profiles
        prog.update("MaJ du calendrier…");
        final int nSvc = svcIds.size();
        final boolean[][] weekdays = new boolean[nSvc][];
        final int[] startD = new int[nSvc], endD = new int[nSvc];
        final int[] minMax = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        readCsv("calendar.txt", false, r -> {
            int a = toDate(r.get("start_date")), b = toDate(r.get("end_date"));
            minMax[0] = Math.min(minMax[0], a);
            minMax[1] = Math.max(minMax[1], b);
            Integer si = svcIdx.get(r.get("service_id"));
            if (si == null) return;
            weekdays[si] = new boolean[]{
                    r.get("monday").equals("1"), r.get("tuesday").equals("1"), r.get("wednesday").equals("1"),
                    r.get("thursday").equals("1"), r.get("friday").equals("1"), r.get("saturday").equals("1"),
                    r.get("sunday").equals("1")};
            startD[si] = a;
            endD[si] = b;
        });
        final Map<Integer, List<int[]>> exceptions = new HashMap<>();
        readCsv("calendar_dates.txt", false, r -> {
            int d = toDate(r.get("date"));
            minMax[0] = Math.min(minMax[0], d);
            minMax[1] = Math.max(minMax[1], d);
            Integer si = svcIdx.get(r.get("service_id"));
            if (si == null) return;
            List<int[]> l = exceptions.get(d);
            if (l == null) { l = new ArrayList<>(); exceptions.put(d, l); }
            l.add(new int[]{si, toInt(r.get("exception_type"))});
        });
        if (minMax[0] > minMax[1]) throw new IOException("Aucune info sur le calendrier");

        final List<BitSet> profiles = new ArrayList<>();
        final Map<BitSet, Integer> profileOf = new HashMap<>();
        final List<int[]> days = new ArrayList<>();
        Calendar cal = new GregorianCalendar(minMax[0] / 10000, (minMax[0] / 100) % 100 - 1, minMax[0] % 100);
        while (true) {
            int date = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH);
            if (date > minMax[1]) break;
            int wd = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0
            BitSet active = new BitSet(nSvc);
            for (int s = 0; s < nSvc; s++)
                if (weekdays[s] != null && startD[s] <= date && date <= endD[s] && weekdays[s][wd]) active.set(s);
            List<int[]> ex = exceptions.get(date);
            if (ex != null) for (int[] e : ex) active.set(e[0], e[1] == 1);
            Integer p = profileOf.get(active);
            if (p == null) { p = profiles.size(); profiles.add(active); profileOf.put(active, p); }
            days.add(new int[]{date, p});
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        final int nProf = profiles.size();
        final boolean[][] profHas = new boolean[nProf][nSvc];
        for (int p = 0; p < nProf; p++)
            for (int s = profiles.get(p).nextSetBit(0); s >= 0; s = profiles.get(p).nextSetBit(s + 1)) profHas[p][s] = true;

        // ---- bus stops (no parent station, numeric id)
        prog.update("MaJ des arrêts…");
        final BitSet validStop = new BitSet();
        final Map<Integer, Object[]> stopInfo = new HashMap<>();
        readCsv("stops.txt", true, r -> {
            String lt = r.get("location_type").trim();
            String id = r.get("stop_id").trim();
            if ((lt.isEmpty() || lt.equals("0")) && r.get("parent_station").trim().isEmpty()
                    && isDigits(id)) {
                int sid = Integer.parseInt(id);
                validStop.set(sid);
                stopInfo.put(sid, new Object[]{r.get("stop_name").trim(),
                        Double.parseDouble(r.get("stop_lat")), Double.parseDouble(r.get("stop_lon"))});
            }
        });

        // ---- stop_times: stream into a packed array
        ZipEntry se = zf.getEntry("stop_times.txt");
        if (se == null) throw new IOException("stop_times.txt n'existe pas");
        final long total = se.getSize();
        long[] entries = new long[(int) Math.min(40_000_000L, Math.max(1 << 20, total / 28))];
        int n = 0;
        LongIntMap tripleMap = new LongIntMap(32768);
        IntList tripleStop = new IntList(), tripleRd = new IntList();

        try (InputStream in = zf.getInputStream(se)) {
            byte[] buf = new byte[1 << 17];
            int have = 0, cTrip = -1, cDep = -1, cArr = -1, cStop = -1, cPick = -1;
            boolean header = true;
            long read = 0, lastReport = 0;
            while (true) {
                int r = in.read(buf, have, buf.length - have);
                if (r < 0 && have == 0) break;
                if (r > 0) { have += r; read += r; }
                int lineStart = 0;
                int limit = have;
                for (int i = 0; i < limit; i++) {
                    if (buf[i] != '\n') continue;
                    int end = i;
                    if (end > lineStart && buf[end - 1] == '\r') end--;
                    if (header) {
                        String[] h = new String(buf, lineStart, end - lineStart, StandardCharsets.UTF_8)
                                .replace("\uFEFF", "").split(",");
                        for (int k = 0; k < h.length; k++) {
                            String name = h[k].trim();
                            if (name.equals("trip_id")) cTrip = k;
                            else if (name.equals("departure_time")) cDep = k;
                            else if (name.equals("arrival_time")) cArr = k;
                            else if (name.equals("stop_id")) cStop = k;
                            else if (name.equals("pickup_type")) cPick = k;
                        }
                        if (cTrip < 0 || cStop < 0 || (cDep < 0 && cArr < 0))
                            throw new IOException("colonnes inattendues dans stop_times.txt");
                        header = false;
                    } else if (end > lineStart) {
                        // parse one data row
                        long tkey = 0;
                        int stopId = -1, dep = -1, arr = -1;
                        boolean noPickup = false;
                        int field = 0, fs = lineStart;
                        for (int j = lineStart; j <= end; j++) {
                            if (j == end || buf[j] == ',') {
                                if (field == cTrip) tkey = tripKey(buf, fs, j - fs);
                                else if (field == cStop) stopId = parseInt(buf, fs, j);
                                else if (field == cDep) dep = parseMinutes(buf, fs, j);
                                else if (field == cArr) arr = parseMinutes(buf, fs, j);
                                else if (field == cPick) noPickup = (j - fs == 1 && buf[fs] == '1');
                                field++;
                                fs = j + 1;
                            }
                        }
                        int info = tripMap.get(tkey, -1);
                        int minutes = dep >= 0 ? dep : arr;
                        if (info >= 0 && !noPickup && stopId >= 0 && minutes >= 0 && minutes < 4096 && validStop.get(stopId)) {
                            int rd = info & 0x1FFF;
                            int svc = info >>> 13;
                            long tk = ((long) stopId << 13) | rd;
                            int tid = tripleMap.get(tk, -1);
                            if (tid < 0) { tid = tripleStop.size; tripleMap.put(tk, tid); tripleStop.add(stopId); tripleRd.add(rd); }
                            if (n == entries.length) entries = Arrays.copyOf(entries, n + n / 2);
                            entries[n++] = ((long) tid << 24) | ((long) svc << 12) | minutes;
                        }
                    }
                    lineStart = i + 1;
                }
                // keep the unfinished last line for the next round
                int rest = have - lineStart;
                if (r < 0) { if (rest == 0) break; buf[have++] = '\n'; continue; }   // file without trailing newline
                System.arraycopy(buf, lineStart, buf, 0, rest);
                have = rest;
                if (read - lastReport > (4 << 20)) {
                    lastReport = read;
                    prog.update("MaJ de l'horaire " + (total > 0 ? (int) (100 * read / total) : 0) + "%");
                }
            }
        }

        prog.update("Triage d'horaire…");
        Arrays.sort(entries, 0, n);

        // ---- write lines / schedules
        final Map<BlobKey, Integer> blobIds = new HashMap<>();
        final BitSet usedStops = new BitSet();
        int lineId = 0;
        int[] tmp = new int[1024];
        byte[] enc = new byte[4096];
        int i = 0;
        int nTriples = tripleStop.size, doneTriples = 0;
        while (i < n) {
            int tid = (int) (entries[i] >>> 24);
            int j = i;
            while (j < n && (int) (entries[j] >>> 24) == tid) j++;
            int stopId = tripleStop.get(tid), rd = tripleRd.get(tid);
            List<int[]> rows = new ArrayList<>();   // {profile, blobId}
            for (int p = 0; p < nProf; p++) {
                int cnt = 0;
                for (int k = i; k < j; k++) if (profHas[p][(int) ((entries[k] >>> 12) & 0xFFF)]) cnt++;
                if (cnt == 0) continue;
                if (tmp.length < cnt) tmp = new int[cnt * 2];
                int c = 0;
                for (int k = i; k < j; k++) if (profHas[p][(int) ((entries[k] >>> 12) & 0xFFF)]) tmp[c++] = (int) (entries[k] & 0xFFF);
                Arrays.sort(tmp, 0, cnt);
                if (enc.length < cnt * 3) enc = new byte[cnt * 3 + 16];
                int len = 0, prev = 0;
                for (int k = 0; k < cnt; k++) {
                    int x = tmp[k] - prev;
                    prev = tmp[k];
                    while (x > 0x7F) { enc[len++] = (byte) ((x & 0x7F) | 0x80); x >>>= 7; }
                    enc[len++] = (byte) x;
                }
                byte[] blob = Arrays.copyOf(enc, len);
                BlobKey key = new BlobKey(blob);
                Integer bid = blobIds.get(key);
                if (bid == null) { bid = blobIds.size() + 1; blobIds.put(key, bid); sink.blob(bid, blob); }
                rows.add(new int[]{p, bid});
            }
            if (!rows.isEmpty()) {
                lineId++;
                int ri = rd >> 1, d = rd & 1;
                sink.line(lineId, stopId, routeIds.get(ri), routeLabels.get(ri), d, letters[rd]);
                for (int[] row : rows) sink.sched(lineId, row[0], row[1]);
                usedStops.set(stopId);
            }
            i = j;
            if ((++doneTriples & 511) == 0) prog.update("Calcul d'horaire " + (100 * doneTriples / Math.max(1, nTriples)) + "%");
        }
        for (int s = usedStops.nextSetBit(0); s >= 0; s = usedStops.nextSetBit(s + 1)) {
            Object[] si = stopInfo.get(s);
            sink.stop(s, (String) si[0], (Double) si[1], (Double) si[2]);
        }
        for (int[] d : days) sink.day(d[0], d[1]);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isDigits(String s) {
        if (s.isEmpty() || s.length() >= 10) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private static String firstWord(String text) {
        if (text == null) return "";
        String[] parts = text.trim().toLowerCase().split("[\\s\\-\u2013]+");
        return parts.length == 0 ? "" : parts[0];
    }

    /** Numeric ids map to themselves; anything else to a 64-bit hash with the top bit set. */
    static long tripKey(byte[] b, int off, int len) {
        if (len > 0 && len <= 18) {
            long v = 0;
            boolean digits = true;
            for (int i = 0; i < len; i++) {
                int c = b[off + i];
                if (c < '0' || c > '9') { digits = false; break; }
                v = v * 10 + (c - '0');
            }
            if (digits) return v;
        }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < len; i++) { h ^= (b[off + i] & 0xff); h *= 0x100000001b3L; }
        return h | Long.MIN_VALUE;
    }

    private static int parseInt(byte[] b, int from, int to) {
        if (from >= to) return -1;
        int v = 0;
        for (int i = from; i < to; i++) {
            int c = b[i];
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
            if (v > 100_000_000) return -1;
        }
        return v;
    }

    /** "HH:MM:SS" (HH may be >= 24) -> minutes since midnight, or -1. */
    private static int parseMinutes(byte[] b, int from, int to) {
        int i = from, h = 0;
        while (i < to && b[i] >= '0' && b[i] <= '9') h = h * 10 + (b[i++] - '0');
        if (i == from || i >= to || b[i] != ':') return -1;
        i++;
        int m = 0, digits = 0;
        while (i < to && b[i] >= '0' && b[i] <= '9' && digits < 2) { m = m * 10 + (b[i++] - '0'); digits++; }
        return h * 60 + m;
    }

    private static final class BlobKey {
        final byte[] d;
        final int h;
        BlobKey(byte[] d) { this.d = d; this.h = Arrays.hashCode(d); }
        @Override public int hashCode() { return h; }
        @Override public boolean equals(Object o) { return o instanceof BlobKey && Arrays.equals(d, ((BlobKey) o).d); }
    }

    private static final class IntList {
        int[] a = new int[1024];
        int size;
        void add(int v) { if (size == a.length) a = Arrays.copyOf(a, size * 2); a[size++] = v; }
        int get(int i) { return a[i]; }
    }

    /** Open-addressing long -> int map (avoids millions of boxed objects). */
    private static final class LongIntMap {
        long[] keys;
        int[] vals;
        boolean[] used;
        int mask, size;
        LongIntMap(int expected) {
            int cap = 16;
            while (cap < expected * 2) cap <<= 1;
            alloc(cap);
        }
        private void alloc(int cap) { keys = new long[cap]; vals = new int[cap]; used = new boolean[cap]; mask = cap - 1; }
        private static int hash(long k) {
            k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33;
            return (int) k;
        }
        int get(long k, int def) {
            int i = hash(k) & mask;
            while (used[i]) { if (keys[i] == k) return vals[i]; i = (i + 1) & mask; }
            return def;
        }
        void put(long k, int v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = hash(k) & mask;
            while (used[i]) { if (keys[i] == k) { vals[i] = v; return; } i = (i + 1) & mask; }
            used[i] = true; keys[i] = k; vals[i] = v; size++;
        }
        private void grow() {
            long[] ok = keys; int[] ov = vals; boolean[] ou = used;
            alloc(ok.length * 2);
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], ov[i]);
        }
    }
}
