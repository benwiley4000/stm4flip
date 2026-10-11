package org.benwiley.stm4flip;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Keeps the downloaded GTFS zip (cached permanently in app storage) and turns it into the schedule database.
 * Three separate steps so each can happen on its own: download() -> build() -> install().
 */
final class DataUpdater {

    static final String ZIP_URL = "https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip";

    static File zipFile(Context ctx) { return new File(ctx.getFilesDir(), "gtfs_stm.zip"); }
    private static File sigFile(Context ctx) { return new File(ctx.getFilesDir(), "gtfs_stm.zip.sig"); }

    static boolean hasZip(Context ctx) {
        File z = zipFile(ctx);
        return z.exists() && z.length() > 0;
    }

    // ------------------------------------------------------------------ server

    /** Identifies the version of the zip currently on the server ("" if the server gives no usable hint). */
    private static String signature(HttpURLConnection c) {
        String etag = c.getHeaderField("ETag"), lm = c.getHeaderField("Last-Modified");
        etag = etag == null ? "" : etag.trim();
        lm = lm == null ? "" : lm.trim();
        if (!etag.isEmpty() || !lm.isEmpty()) return etag + "|" + lm;
        long len = c.getContentLengthLong();
        return len > 0 ? "len:" + len : "";
    }

    /** Small HEAD request: no download. */
    static String remoteSignature() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(ZIP_URL).openConnection();
        c.setRequestMethod("HEAD");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", MainActivity.USER_AGENT);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return signature(c);
        } finally {
            c.disconnect();
        }
    }

    /** Downloads the zip into the permanent cache. The old cached zip is only replaced once the new one is complete. */
    static void download(Context ctx, GtfsImporter.Progress prog) throws IOException {
        File zip = zipFile(ctx);
        File part = new File(zip.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(ZIP_URL).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", MainActivity.USER_AGENT);
        boolean ok = false;
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("echec de téléchargement (HTTP " + code + ")");
            String sig = signature(c);
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[32768];
                long read = 0, last = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    read += r;
                    if (read - last > 262_144) {
                        last = read;
                        prog.update(String.format(Locale.US, total > 0 ? "Téléchargement %.1f/%.1f MB" : "Downloading %.1f MB",
                                read / 1e6, total / 1e6));
                    }
                }
            }
            if (total > 0 && part.length() != total) throw new IOException("téléchargement non-complet");
            zip.delete();
            if (!part.renameTo(zip)) throw new IOException("impossible de sauvegarder les données");
            try (FileWriter w = new FileWriter(sigFile(ctx))) { w.write(sig); }
            ok = true;
        } finally {
            c.disconnect();
            if (!ok) part.delete();
        }
    }

    // ------------------------------------------------------------------ build

    static String md5(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String readSig(Context ctx) {
        File f = sigFile(ctx);
        if (!f.exists()) return "";
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String s = br.readLine();
            return s == null ? "" : s;
        } catch (IOException e) {
            return "";
        }
    }

    /** Processes the cached zip into a new database file (".new") next to the installed one. Slow. */
    static File build(Context ctx, GtfsImporter.Progress prog) throws IOException {
        File zip = zipFile(ctx);
        File out = StmData.dbFile(ctx);
        File tmp = new File(out.getPath() + ".new");
        out.getParentFile().mkdirs();
        tmp.delete();
        new File(tmp.getPath() + "-journal").delete();
        prog.update("Chargement du fichier...");
        String md5 = md5(zip);
        String sig = readSig(ctx);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(tmp.getPath(), null);
        boolean ok = false;
        try {
            SqlSink sink = new SqlSink(db);
            GtfsImporter.run(zip, sink, prog);
            prog.update("Sauvegarde...");
            sink.finish(md5, sig);
            ok = true;
        } finally {
            db.close();
            if (!ok) tmp.delete();
        }
        return tmp;
    }

    /** Swaps the freshly built database in. The caller must have closed any open handle on the old one. */
    static StmData install(Context ctx, File built) throws IOException {
        File out = StmData.dbFile(ctx);
        out.delete();
        new File(out.getPath() + "-journal").delete();
        if (!built.renameTo(out)) throw new IOException("impossible d'installer les données");
        return StmData.openIfValid(ctx);
    }

    // ------------------------------------------------------------------ SQLite writer

    private static final class SqlSink implements GtfsImporter.Sink {
        private final SQLiteDatabase db;
        private final SQLiteStatement stStop, stLine, stBlob, stSched, stDay;

        SqlSink(SQLiteDatabase db) {
            this.db = db;
            db.execSQL("CREATE TABLE stops(id INTEGER PRIMARY KEY, name TEXT, lat REAL, lon REAL)");
            db.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY, stop_id INTEGER, route TEXT, label TEXT, dir INTEGER, letter TEXT)");
            db.execSQL("CREATE TABLE sched(line_id INTEGER, profile INTEGER, blob_id INTEGER, PRIMARY KEY(line_id, profile)) WITHOUT ROWID");
            db.execSQL("CREATE TABLE blobs(id INTEGER PRIMARY KEY, data BLOB)");
            db.execSQL("CREATE TABLE days(date INTEGER PRIMARY KEY, profile INTEGER)");
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)");
            db.beginTransaction();
            stStop = db.compileStatement("INSERT INTO stops VALUES(?,?,?,?)");
            stLine = db.compileStatement("INSERT INTO lines VALUES(?,?,?,?,?,?)");
            stBlob = db.compileStatement("INSERT INTO blobs VALUES(?,?)");
            stSched = db.compileStatement("INSERT INTO sched VALUES(?,?,?)");
            stDay = db.compileStatement("INSERT INTO days VALUES(?,?)");
        }

        @Override public void stop(int id, String name, double lat, double lon) {
            stStop.bindLong(1, id); stStop.bindString(2, name); stStop.bindDouble(3, lat); stStop.bindDouble(4, lon);
            stStop.executeInsert();
        }
        @Override public void line(int id, int stopId, String route, String label, int dir, String letter) {
            stLine.bindLong(1, id); stLine.bindLong(2, stopId); stLine.bindString(3, route);
            stLine.bindString(4, label); stLine.bindLong(5, dir); stLine.bindString(6, letter);
            stLine.executeInsert();
        }
        @Override public void blob(int id, byte[] data) {
            stBlob.bindLong(1, id); stBlob.bindBlob(2, data);
            stBlob.executeInsert();
        }
        @Override public void sched(int lineId, int profile, int blobId) {
            stSched.bindLong(1, lineId); stSched.bindLong(2, profile); stSched.bindLong(3, blobId);
            stSched.executeInsert();
        }
        @Override public void day(int date, int profile) {
            stDay.bindLong(1, date); stDay.bindLong(2, profile);
            stDay.executeInsert();
        }

        /** md5 identifies exactly which zip this database was built from; sig is the server's version tag for it. */
        void finish(String md5, String sig) {
            db.execSQL("INSERT INTO meta VALUES('zip_md5', ?)", new Object[]{md5});
            db.execSQL("INSERT INTO meta VALUES('server_sig', ?)", new Object[]{sig});
            db.execSQL("INSERT INTO meta VALUES('built_at', ?)", new Object[]{String.valueOf(System.currentTimeMillis())});
            db.setTransactionSuccessful();
            db.endTransaction();
            db.execSQL("CREATE INDEX stops_lat ON stops(lat)");
            db.execSQL("CREATE INDEX lines_stop ON lines(stop_id)");
        }
    }
}
