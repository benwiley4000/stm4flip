package org.benwiley.stm4flip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure-Java core logic: data model, ETA computation (live + schedule) and list-row selection. */
final class Transit {

    static final int MAX_ETAS = 3;
    static final int STATIC_CANDIDATES = 8;   // scheduled times considered per line, so flagged slots can be found
    static final int MAX_LIVE_MINUTES = 59;   // live ETAs further out than this are dropped; the scheduled time is shown instead
    private static final int[] EMPTY = new int[0];

    static final class Line {
        int id, stopId, dir;
        String route, label, letter;
        int[] today = EMPTY;       // departure minutes since midnight, today's schedule
        int[] yesterday = EMPTY;   // yesterday's schedule (for trips running past midnight)
        int[] etaSec = EMPTY;      // seconds from now (filled by computeEtas)
        int[] clockMin = EMPTY;    // same moments as minutes since midnight (for showing scheduled times as clock times)
        boolean[] live = new boolean[0];
        int[] flag = new int[0];   // per ETA: 0 normal, 1 bus passes without stopping here, 2 trip cancelled
    }

    static final class Stop {
        int id;
        String name;
        double lat, lon;
        final List<Line> lines = new ArrayList<>();
        String routesText = "";
    }

    static final class Row {
        String label;              // e.g. "24N"
        int[] etaSec;
        int[] clockMin;
        boolean[] live;
        int[] flag;
    }

    static final class TripInfo {
        String route;
        int dir;
        int[] stops, minutes;
    }

    /** Scheduled slots to mark: key = stop|route|dir|day|minute (day 0 = today's schedule, 1 = yesterday's). */
    static final class Flags {
        final Set<String> canceled = new HashSet<>(), skipped = new HashSet<>();
        int flagFor(int stop, String route, int dir, int day, int minute) {
            String k = key(stop, route, dir, day, minute);
            if (canceled.contains(k)) return 2;
            return skipped.contains(k) ? 1 : 0;
        }
    }

    static String key(int stop, String route, int dir, int day, int minute) {
        return stop + "|" + route + "|" + dir + "|" + day + "|" + minute;
    }

    /** Trip ids whose stop times we need: every cancelled trip, and trips skipping a stop we are looking at. */
    static Set<Long> neededTripIds(RtFeed.Snapshot rt, Set<Integer> wantStops) {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rt.canceledN; i++) ids.add(rt.canceledId[i]);
        for (int i = 0; i < rt.n; i++) if (rt.skipped[i] && wantStops.contains(rt.stop[i])) ids.add(rt.tripId[i]);
        return ids;
    }

    private static int dayIndex(int date, int today, int yesterday) {
        if (date == 0 || date == today) return 0;
        return date == yesterday ? 1 : -1;
    }

    static Flags buildFlags(Map<Long, TripInfo> infos, RtFeed.Snapshot rt, Set<Integer> wantStops, int today, int yesterday) {
        Flags f = new Flags();
        for (int i = 0; i < rt.canceledN; i++) {
            TripInfo ti = infos.get(rt.canceledId[i]);
            int day = dayIndex(rt.canceledDate[i], today, yesterday);
            if (ti == null || day < 0) continue;
            for (int j = 0; j < ti.stops.length; j++)
                if (wantStops.contains(ti.stops[j])) f.canceled.add(key(ti.stops[j], ti.route, ti.dir, day, ti.minutes[j]));
        }
        for (int i = 0; i < rt.n; i++) {
            if (!rt.skipped[i] || !wantStops.contains(rt.stop[i])) continue;
            TripInfo ti = infos.get(rt.tripId[i]);
            int day = dayIndex(rt.date[i], today, yesterday);
            if (ti == null || day < 0) continue;
            for (int j = 0; j < ti.stops.length; j++)
                if (ti.stops[j] == rt.stop[i]) f.skipped.add(key(rt.stop[i], ti.route, ti.dir, day, ti.minutes[j]));
        }
        return f;
    }

    private static final Comparator<int[]> BY_FIRST = new Comparator<int[]>() {
        @Override public int compare(int[] x, int[] y) { return x[0] - y[0]; }
    };

    // ------------------------------------------------------------------ ETAs

    static void computeEtas(List<Stop> stops, RtFeed.Snapshot rt, long nowEpochSec, int nowSecOfDay, Flags flags) {
        // index live entries that concern the stops we care about
        Map<Integer, List<Integer>> liveByStop = new HashMap<>();
        if (rt != null) {
            Set<Integer> want = new HashSet<>();
            for (Stop s : stops) want.add(s.id);
            for (int i = 0; i < rt.n; i++) {
                if (want.contains(rt.stop[i])) {
                    List<Integer> l = liveByStop.get(rt.stop[i]);
                    if (l == null) { l = new ArrayList<>(); liveByStop.put(rt.stop[i], l); }
                    l.add(i);
                }
            }
        }
        for (Stop stop : stops) {
            Map<String, Integer> routeCount = new HashMap<>();
            for (Line l : stop.lines) {
                Integer c = routeCount.get(l.route);
                routeCount.put(l.route, c == null ? 1 : c + 1);
            }
            List<Integer> idx = liveByStop.get(stop.id);
            for (Line line : stop.lines) {
                // live (SKIPPED entries aren't live data: the feed gives them no real time)
                List<Integer> liveSecs = new ArrayList<>();
                int ri = (rt == null || idx == null) ? -1 : rt.routeIndex(line.route);
                if (ri >= 0) {
                    for (int i : idx) {
                        if (rt.route[i] != ri || rt.skipped[i]) continue;
                        boolean ok = routeCount.get(line.route) == 1 || rt.dir[i] == line.dir;
                        if (!ok) continue;
                        int sec = (int) (rt.time[i] - nowEpochSec);
                        if (sec >= -30 && sec < (MAX_LIVE_MINUTES + 1) * 60) liveSecs.add(sec);
                    }
                    Collections.sort(liveSecs);
                }
                // schedule: {seconds from now, flag}
                List<int[]> st = new ArrayList<>();
                collect(line.today, 0, 0, nowSecOfDay, stop, line, flags, st);
                collect(line.yesterday, 1440, 1, nowSecOfDay, stop, line, flags, st);
                Collections.sort(st, BY_FIRST);

                // merge: live first; scheduled times after the last live one; flagged slots (skipped / cancelled) always,
                // because their bus isn't in the live list
                int liveCount = Math.min(liveSecs.size(), MAX_ETAS);
                int lastLive = liveCount > 0 ? liveSecs.get(liveCount - 1) : Integer.MIN_VALUE / 2;
                List<int[]> items = new ArrayList<>();   // {seconds, live?, flag}
                for (int i = 0; i < liveCount; i++) items.add(new int[]{liveSecs.get(i), 1, 0});
                for (int[] sch : st) if (sch[1] != 0 || sch[0] > lastLive + 60) items.add(new int[]{sch[0], 0, sch[1]});
                Collections.sort(items, BY_FIRST);
                int n = Math.min(items.size(), MAX_ETAS);
                int[] sec = new int[n];
                boolean[] lv = new boolean[n];
                int[] fl = new int[n];
                for (int i = 0; i < n; i++) {
                    sec[i] = items.get(i)[0];
                    lv[i] = items.get(i)[1] == 1;
                    fl[i] = items.get(i)[2];
                }
                line.etaSec = sec;
                line.live = lv;
                line.flag = fl;
                int[] clk = new int[n];
                for (int q = 0; q < n; q++) {
                    int t = (nowSecOfDay + sec[q]) % 86400;
                    if (t < 0) t += 86400;
                    clk[q] = t / 60;
                }
                line.clockMin = clk;
            }
            stop.routesText = routesText(stop);
        }
    }

    private static void collect(int[] mins, int offsetMin, int day, int nowSec, Stop stop, Line line, Flags flags, List<int[]> out) {
        int c = 0;
        for (int m : mins) {
            int sec = (m - offsetMin) * 60 - nowSec;
            if (sec >= -30) {
                int flag = flags == null ? 0 : flags.flagFor(stop.id, line.route, line.dir, day, m);
                out.add(new int[]{sec, flag});
                if (++c == STATIC_CANDIDATES) break;
            }
        }
    }

    /** The stop's lines in display order (same order as the words in routesText). */
    static List<Line> sortedLines(Stop s) {
        List<Line> ls = new ArrayList<>(s.lines);
        Collections.sort(ls, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) {
                int x = num(a.label) - num(b.label);
                if (x != 0) return x;
                x = a.label.compareTo(b.label);
                return x != 0 ? x : a.dir - b.dir;
            }
        });
        return ls;
    }

    /**
     * One entry per route label of the stop, in routesText order. Bit 1: an arrival at this stop within the next
     * 59 minutes (cancelled trips don't count). Bit 2: the live feed says a trip among the next 3 of this route
     * skips this stop (independent of what the list shows, which may come from a different stop).
     * Call after computeEtas.
     */
    static int[] labelStyles(Stop s, RtFeed.Snapshot rt) {
        List<Line> ls = sortedLines(s);
        Map<String, Integer> routeCount = new HashMap<>();
        for (Line l : ls) {
            Integer c = routeCount.get(l.route);
            routeCount.put(l.route, c == null ? 1 : c + 1);
        }
        int[] out = new int[ls.size()];
        for (int k = 0; k < out.length; k++) {
            Line l = ls.get(k);
            boolean soon = false;
            for (int q = 0; q < l.etaSec.length; q++) {
                if (l.flag[q] != 2 && isSoon(l.etaSec[q])) { soon = true; break; }
            }
            boolean skipped = false;
            if (rt != null) {
                String base = s.id + "|" + l.route;
                skipped = rt.skippedSoonLines.contains(routeCount.get(l.route) == 1 ? base : base + "|" + l.dir);
            }
            out[k] = (soon ? 1 : 0) | (skipped ? 2 : 0);
        }
        return out;
    }

    static String routesText(Stop s) {
        List<Line> ls = sortedLines(s);
        StringBuilder sb = new StringBuilder();
        for (Line l : ls) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(l.label).append(l.letter);
        }
        return sb.toString();
    }

    private static int num(String s) {
        int v = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) v = v * 10 + (s.charAt(i++) - '0');
        return i == 0 ? Integer.MAX_VALUE : v;
    }

    // ------------------------------------------------------------------ list rows

    /**
     * One row per (route, direction) among stops inside the box; ETAs come from the nearest stop of that
     * line that has live data, or the nearest stop if none does. Rows are ordered by soonest arrival.
     */
    static List<Row> rowsFor(List<Stop> stops, double south, double north, double west, double east,
                             double cLat, double cLon) {
        double cosLat = Math.cos(Math.toRadians(cLat));
        Map<String, Line> best = new HashMap<>();
        Map<String, Double> bestDist = new HashMap<>();
        Map<String, Boolean> bestLive = new HashMap<>();
        for (Stop s : stops) {
            if (s.lat < south || s.lat > north || s.lon < west || s.lon > east) continue;
            double dy = s.lat - cLat, dx = (s.lon - cLon) * cosLat, d = dx * dx + dy * dy;
            for (Line l : s.lines) {
                if (l.etaSec.length == 0) continue;
                String key = l.route + "|" + l.dir;
                boolean hasLive = false;
                for (boolean lv : l.live) if (lv) { hasLive = true; break; }
                Double bd = bestDist.get(key);
                // prefer the nearest stop that has live data; otherwise the nearest stop
                boolean better = bd == null
                        || (hasLive && !bestLive.get(key))
                        || (hasLive == bestLive.get(key) && d < bd);
                if (better) { best.put(key, l); bestDist.put(key, d); bestLive.put(key, hasLive); }
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Line l : best.values()) {
            Row r = new Row();
            r.label = l.label + l.letter;
            r.etaSec = l.etaSec;
            r.clockMin = l.clockMin;
            r.live = l.live;
            r.flag = l.flag;
            rows.add(r);
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                int x = a.etaSec[0] - b.etaSec[0];
                return x != 0 ? x : a.label.compareTo(b.label);
            }
        });
        return rows;
    }

    /** True if an arrival this many seconds away is shown as "minutes from now" (within the next 59 minutes). */
    static boolean isSoon(int sec) {
        return sec < (MAX_LIVE_MINUTES + 1) * 60;
    }

    /** Minutes since midnight -> "14:05". */
    static String fmtClock(int minOfDay) {
        int h = (minOfDay / 60) % 24, m = minOfDay % 60;
        return h + "h" + (m < 10 ? "0" : "") + m;
    }

    static String fmt(int sec) {
        int m = sec < 0 ? 0 : sec / 60;
        if (m >= 60) return (m / 60) + "h" + (m % 60 < 10 ? "0" : "") + (m % 60);
        return m + "m";
    }

    /** Inverse of the build script's delta+varint encoding. */
    static int[] decode(byte[] b) {
        int cnt = 0;
        for (byte x : b) if ((x & 0x80) == 0) cnt++;
        int[] out = new int[cnt];
        int p = 0, prev = 0, i = 0;
        while (p < b.length) {
            int v = 0, shift = 0;
            while (true) {
                int x = b[p++];
                v |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) break;
                shift += 7;
            }
            prev += v;
            out[i++] = prev;
        }
        return out;
    }

    /** Varints read one after another, not accumulated (the stop lists in the patterns table). */
    static int[] decodePlain(byte[] b) {
        int cnt = 0;
        for (byte x : b) if ((x & 0x80) == 0) cnt++;
        int[] out = new int[cnt];
        int p = 0, i = 0;
        while (p < b.length) {
            int v = 0, shift = 0;
            while (true) {
                int x = b[p++];
                v |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) break;
                shift += 7;
            }
            out[i++] = v;
        }
        return out;
    }
}
