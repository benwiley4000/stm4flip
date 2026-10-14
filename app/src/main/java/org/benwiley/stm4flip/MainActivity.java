package org.benwiley.stm4flip;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.SharedPreferences;

public class MainActivity extends Activity {

    // ---- tweakables -------------------------------------------------------------------------
    static final int THEME = Color.parseColor("#0085CA");        // STM blue (bar color, stop dots)
    static final int LIVE_BLUE = THEME;     // live ETAs
    static final int GREY = Color.parseColor("#666666");
    static final int ORANGE_DOT = Color.parseColor("#F28C00");
    static final int LIGHTER_GREY_DOT = Color.parseColor("#9E9E9E");
    static final String USER_AGENT = "Stm4Flip/1.0 (therealbenwiley@gmail.com)";
    static final double DEFAULT_ZOOM = 15;
    static final double STOP_MARKERS_MIN_ZOOM = 13;     // 2 levels out from default: below this, no list / no requests
    static final double LIST_MIN_ZOOM = 15;     // List of arriving buses
    static final double ROUTES_ZOOM = 16;       // stop bubbles (route list only) from this zoom
    static final double NAMES_ZOOM = 18;        // bubbles also show the intersection from this zoom
    static final int UPDATE_KEY = KeyEvent.KEYCODE_9;   // starts a data update when one is offered
    static final long UPDATE_CHECK_INTERVAL_MS = 6 * 3600_000L;
    static final int MAX_ROWS = 8;              // never more list rows than this
    static final float MAX_LIST_FRACTION = 0.45f;  // list never takes more than this share of the height
    static final long REFRESH_MS = 60_000;
    static final long RT_MAX_AGE_MS = 30_000;
    // -----------------------------------------------------------------------------------------

    private static final GeoPoint MONTREAL = new GeoPoint(45.5017, -73.5673);

    private MapView map;
    private LinearLayout listPanel;
    private TextView topBar;
    private TextView bottomBar;
    private StopOverlay overlay;
    private LocationManager locationManager;
    private GeoPoint lastCenter = MONTREAL;
    private GeoPoint initialPosition = MONTREAL;
    private boolean userMoved = false;
    private int rowH;
    private float dp;

    private volatile StmData data;
    private volatile RtFeed.Snapshot rt;

    // "grabbing latest data" screen that blocks everything until the schedule data is usable
    private enum Gate { NONE, CHECKING, CONFIRM_INITIAL, CONFIRM_UPDATE, WORKING, ERROR }
    private Gate gate = Gate.CHECKING;          // blocked until the data check says otherwise
    private LinearLayout gateView;
    private TextView gateText, gateKey;

    // data updates that happen while the map stays usable
    private final ExecutorService bg = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(final Runnable r) {
            return new Thread(new Runnable() {
                @Override public void run() {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }
            });
        }
    });
    private volatile boolean updating = false;
    private volatile boolean updateAvailable = false;
    private volatile long lastUpdateCheckMs = 0;
    private String updateNote = "";      // shown in front of the normal status text
    private String baseStatus = "Chargement...";
    private int gen = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Runnable queryNow = new Runnable() { @Override public void run() { query(false); } };
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() { query(false); ui.postDelayed(this, REFRESH_MS); }
    };
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { onFix(loc, false); stopLocation(); }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    // ---------------------------------------------------------------- lifecycle / UI

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Configuration.getInstance().setUserAgentValue(USER_AGENT);
        File base = new File(getCacheDir(), "osmdroid");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        getWindow().setStatusBarColor(THEME);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        dp = getResources().getDisplayMetrics().density;

        SharedPreferences sp = getSharedPreferences("lastpos", MODE_PRIVATE);
        if (sp.contains("lat")) {
            initialPosition = new GeoPoint(sp.getFloat("lat", 0f), sp.getFloat("lon", 0f));
        }

        map = new MapView(this);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(10.0);
        map.setMaxZoomLevel(19.0);
        map.getController().setZoom(DEFAULT_ZOOM);
        map.getController().setCenter(initialPosition);
        overlay = new StopOverlay();
        map.getOverlays().add(overlay);
        // when the list panel grows/shrinks the map gets shorter: keep the same point centred
        map.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int orr, int ob) {
                if ((bt - t) != (ob - ot)) map.post(new Runnable() {
                    @Override public void run() { map.getController().setCenter(lastCenter); }
                });
            }
        });

        Paint p = new Paint();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
        Paint.FontMetrics fm = p.getFontMetrics();
        rowH = (int) Math.ceil(fm.descent - fm.ascent) + (int) (2 * dp);

        listPanel = new LinearLayout(this);
        listPanel.setOrientation(LinearLayout.VERTICAL);
        listPanel.setBackgroundColor(Color.WHITE);
        listPanel.setVisibility(View.GONE);

        topBar = new TextView(this);
        topBar.setBackgroundColor(GREY);
        topBar.setTextColor(Color.WHITE);
        topBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        topBar.setPadding(4, 4, 4, 4);
        topBar.setSingleLine(true);
        topBar.setEllipsize(TextUtils.TruncateAt.END);
        updateTopBar();

        bottomBar = new TextView(this);
        bottomBar.setBackgroundColor(THEME);
        bottomBar.setTextColor(Color.WHITE);
        bottomBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bottomBar.setPadding(8, 4, 8, 4);
        bottomBar.setSingleLine(true);
        bottomBar.setEllipsize(TextUtils.TruncateAt.END);
        bottomBar.setText(baseStatus);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(map, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(listPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout content = new FrameLayout(this);
        content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildGate(content);
        setContentView(content);
    }

    @Override protected void onResume() {
        super.onResume();
        map.onResume();
        startLocation();
        ensureData();
        ui.removeCallbacks(autoRefresh);
        ui.postDelayed(autoRefresh, 1000);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(autoRefresh);
        ui.removeCallbacks(queryNow);
        stopLocation();
        map.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        finishAndRemoveTask();   // any hiding closes the activity and removes it from recents
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());   // ends the process and all our threads
    }

    // ---------------------------------------------------------------- keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (gate != Gate.NONE) {
            // Only OK (and 0 on the error screen) do anything; BACK / END behave normally so the user can bail out.
            int k = e.getKeyCode();
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (gate == Gate.CONFIRM_UPDATE && k == KeyEvent.KEYCODE_BACK) {   // cancel: back to the map
                if (e.getAction() == KeyEvent.ACTION_UP) { hideGate(); query(false); }
                return true;
            }
            if (ok) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (gate == Gate.CONFIRM_INITIAL) startBlocking(true);
                    else if (gate == Gate.ERROR) startBlocking(!DataUpdater.hasZip(this));
                    else if (gate == Gate.CONFIRM_UPDATE) {
                        hideGate();
                        query(false);
                        bg.execute(new Runnable() { @Override public void run() { backgroundUpdate(true); } });
                    }
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getKeyCode() == UPDATE_KEY) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && updateAvailable && !updating)
                showGate(Gate.CONFIRM_UPDATE, ASK_UPDATE, "OK");
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isHandled(e.getKeyCode()) || super.dispatchKeyEvent(e);
        int step = Math.min(map.getWidth(), map.getHeight()) / 4;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  pan(-step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: pan(step, 0);  return true;
            case KeyEvent.KEYCODE_DPAD_UP:    pan(0, -step); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  pan(0, step);  return true;
            case KeyEvent.KEYCODE_3:
                setZoomNow(map.getZoomLevelDouble() + 1); return true;
            case KeyEvent.KEYCODE_1:
                setZoomNow(map.getZoomLevelDouble() - 1); return true;
            case KeyEvent.KEYCODE_5:
                recenter(); return true;
// Ignore this for now; if user really needs live update they can exit and re-enter the app
//            case KeyEvent.KEYCODE_0:
//                query(true); return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean isHandled(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_3: case KeyEvent.KEYCODE_5:/* case KeyEvent.KEYCODE_0:*/
            case KeyEvent.KEYCODE_9:
                return true;
        }
        return false;
    }

    private void pan(int dx, int dy) {
        userMoved = true;
        Projection p = map.getProjection();
        Point c = p.toPixels(lastCenter, null);
        IGeoPoint n = p.fromPixels(c.x + dx, c.y + dy);
        lastCenter = new GeoPoint(n.getLatitude(), n.getLongitude());
        map.getController().setCenter(lastCenter);
        scheduleQuery(350);
    }

    private void setZoomNow(double z) {
        map.getController().setZoom((double) Math.round(z));
        scheduleQuery(350);
    }

    private void recenter() {
        if (overlay.me != null) {
            lastCenter = overlay.me;
            map.getController().setCenter(lastCenter);
            scheduleQuery(0);
        } else {
            lastCenter = initialPosition;
            map.getController().setCenter(initialPosition);
            startLocation();
        }
        userMoved = false;
        map.getController().setZoom(DEFAULT_ZOOM);
    }

    private void scheduleQuery(long delayMs) {
        ui.removeCallbacks(queryNow);
        ui.postDelayed(queryNow, delayMs);
    }

    // ---------------------------------------------------------------- location

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }
        Location best = null;
        for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!locationManager.isProviderEnabled(prov)) continue;
                Location l = locationManager.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                locationManager.requestLocationUpdates(prov, 0, 0, locationListener, Looper.getMainLooper());
            } catch (Exception ignored) { }
        }
        if (best != null) onFix(best, true);
    }

    private void stopLocation() {
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) { }
    }

    private void onFix(Location loc, boolean stale) {
        GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        if (stale) {
            initialPosition = p;
        } else {
            overlay.me = p;
        }
        if (!userMoved) {
            lastCenter = p;
            map.getController().setCenter(p);
            map.getController().setZoom(DEFAULT_ZOOM);
            scheduleQuery(0);
        }
        map.invalidate();
        getSharedPreferences("lastpos", MODE_PRIVATE).edit()
                .putFloat("lat", (float) p.getLatitude())
                .putFloat("lon", (float) p.getLongitude())
                .apply();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startLocation();
    }

    // ---------------------------------------------------------------- schedule data (download / staleness)

    private void buildGate(FrameLayout content) {
        gateView = new LinearLayout(this);
        gateView.setOrientation(LinearLayout.VERTICAL);
        gateView.setBackgroundColor(Color.WHITE);
        gateView.setClickable(true);   // swallow touches so the map underneath can't be used

        TextView head = new TextView(this);
        head.setText("STM 4 Flip");
        head.setTextColor(Color.WHITE);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        head.setBackgroundColor(THEME);
        head.setPadding(12, 8, 12, 8);

        gateText = new TextView(this);
        gateText.setTextColor(Color.BLACK);
        gateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        gateText.setGravity(Gravity.CENTER);
        gateText.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));

        gateKey = new TextView(this);
        gateKey.setGravity(Gravity.CENTER);
        gateKey.setTextColor(Color.WHITE);
        gateKey.setTypeface(Typeface.DEFAULT_BOLD);
        gateKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        gateKey.setBackgroundColor(THEME);
        gateKey.setPadding(8, 4, 8, 4);
        gateText.setText("Chargement du réseau d'autobus...");
        gateKey.setVisibility(View.INVISIBLE);

        gateView.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gateView.addView(gateText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        gateView.addView(gateKey, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(gateView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showGate(Gate g, String text, String keyLabel) {
        gate = g;
        gateText.setText(text);
        gateKey.setText(keyLabel);
        gateKey.setVisibility(keyLabel.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        gateView.setVisibility(View.VISIBLE);
        if (g == Gate.WORKING) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void hideGate() {
        gate = Gate.NONE;
        gateView.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static final String ASK_INITIAL = "Il faut télécharger les données de réseau d'autobus (~50MB). OK ?";
    private static final String ASK_UPDATE = "De nouvelles données de réseau sont disponibles (~50MB). Ça va télécharger et installer dans le fond. OK ?";

    private void refreshBottomBar() {
        bottomBar.setText((updateNote.isEmpty() ? "" : updateNote + " | ") + baseStatus);
    }

    private void updateTopBar() {
        topBar.setText("1 = Dézoomer    3 = Zoomer    5 = Réinitialiser");
    }

    private void setNote(final String note) {
        ui.post(new Runnable() { @Override public void run() { updateNote = note; refreshBottomBar(); } });
    }

    /**
     * Called whenever the app comes to the front. Any usable data lets the map open straight away
     * (even if out of date); only having no data at all blocks the screen.
     */
    private void ensureData() {
        if (gate == Gate.WORKING || gate == Gate.ERROR) return;
        if (data != null) {
            if (!updating && System.currentTimeMillis() - lastUpdateCheckMs > UPDATE_CHECK_INTERVAL_MS)
                bg.execute(new Runnable() { @Override public void run() { checkServer(); } });
            return;
        }
        io.execute(new Runnable() {
            @Override public void run() {
                final StmData d = StmData.openIfValid(MainActivity.this);
                List<Transit.Stop> loaded = null;
                if (d != null) {
                    try { loaded = d.allStops(); } catch (Exception ex) { loaded = new ArrayList<>(); }
                }
                final List<Transit.Stop> allStops = loaded;
                final boolean zip = DataUpdater.hasZip(MainActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (gate == Gate.WORKING || data != null) { if (d != null && d != data) d.close(); return; }
                        if (d != null) {
                            overlay.setStops(allStops);
                            data = d;
                            hideGate();
                            query(false);
                            bg.execute(new Runnable() { @Override public void run() { afterDataReady(); } });
                        } else if (zip) {
                            startBlocking(false);              // zip already downloaded earlier: just process it
                        } else {
                            showGate(Gate.CONFIRM_INITIAL, ASK_INITIAL, "OK");
                        }
                    }
                });
            }
        });
    }

    /** Background thread, right after the map is usable: process a newer cached zip, else look for newer data online. */
    private void afterDataReady() {
        if (updating) return;
        StmData d = data;
        if (d == null) return;
        try {
            if (DataUpdater.hasZip(this)) {
                String md5 = DataUpdater.md5(DataUpdater.zipFile(this));
                if (!md5.equals(d.meta("zip_md5"))) { backgroundUpdate(false); return; }
            }
        } catch (Exception ignored) { }
        checkServer();
    }

    /** Background thread: is there newer data on the server (or is ours expired and the server can't be reached)? */
    private void checkServer() {
        StmData d = data;
        if (d == null || updating) return;
        lastUpdateCheckMs = System.currentTimeMillis();
        boolean stale;
        try { stale = !d.coversDay(Calendar.getInstance()); } catch (Exception e) { return; }
        boolean avail;
        try {
            String remote = DataUpdater.remoteSignature();
            String mine = d.meta("server_sig");
            avail = (remote.isEmpty() || mine.isEmpty()) ? stale : !remote.equals(mine);   // can't compare: only offer if expired
        } catch (Exception e) {
            avail = stale;                                                                   // offline: offer only if expired
        }
        updateAvailable = avail;
        setNote(avail ? "9 = MaJ de carte" : "");
    }

    /**
     * Background thread: (optionally download,) process the cached zip, swap the new database in.
     * The map keeps working on the old data meanwhile.
     */
    private void backgroundUpdate(boolean download) {
        if (updating || gate == Gate.WORKING) return;
        updating = true;
        updateAvailable = false;
        setNote("MaJ de carte...");
        try {
            GtfsImporter.Progress p = new GtfsImporter.Progress() {
                @Override public void update(String msg) { setNote(msg); }
            };
            if (download) DataUpdater.download(this, p);
            final File built;
            try {
                built = DataUpdater.build(this, p);
            } catch (java.util.zip.ZipException ze) {
                DataUpdater.zipFile(this).delete();     // corrupt cached zip: next time download it again
                throw ze;
            }
            io.submit(new Callable<Void>() {            // swap on the query thread so no query is running on the old db
                @Override public Void call() throws Exception {
                    StmData old = data;
                    data = null;
                    if (old != null) old.close();
                    StmData fresh = DataUpdater.install(MainActivity.this, built);
                    if (fresh == null) throw new IOException("les nouvelles données n'ont pas pu être lues");
                    overlay.setStops(fresh.allStops());
                    data = fresh;
                    map.postInvalidate();
                    return null;
                }
            }).get();
            updating = false;
            setNote("");
            ui.post(new Runnable() { @Override public void run() { if (gate == Gate.NONE) query(false); } });
            checkServer();                              // does the server have something even newer?
        } catch (Throwable t) {
            updating = false;
            updateAvailable = true;
            setNote("9 = Réessayer MaJ");
            if (data == null) ui.post(new Runnable() { @Override public void run() { ensureData(); } });
        }
    }

    /** Initial load (no data at all yet): blocks the screen, shows progress. download=false when a zip is already cached. */
    private void startBlocking(final boolean download) {
        final String title = download ? "Téléchargement des données les plus à jour..." : "Préparation des données téléchargées...";
        showGate(Gate.WORKING, title + "\n\nPréparation", "");
        updating = true;
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    GtfsImporter.Progress p = new GtfsImporter.Progress() {
                        @Override public void update(final String msg) {
                            ui.post(new Runnable() {
                                @Override public void run() { if (gate == Gate.WORKING) gateText.setText(title + "\n\n" + msg); }
                            });
                        }
                    };
                    if (download) DataUpdater.download(MainActivity.this, p);
                    File built;
                    try {
                        built = DataUpdater.build(MainActivity.this, p);
                    } catch (java.util.zip.ZipException ze) {
                        DataUpdater.zipFile(MainActivity.this).delete();
                        throw ze;
                    }
                    StmData d = DataUpdater.install(MainActivity.this, built);
                    if (d == null) throw new IOException("les nouvelles données n'ont pas pu être lues");
                    overlay.setStops(d.allStops());
                    data = d;
                    updating = false;
                    ui.post(new Runnable() { @Override public void run() { hideGate(); query(false); map.invalidate(); } });
                    checkServer();
                } catch (final Throwable t) {   // includes OutOfMemoryError on very small phones
                    updating = false;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            String why = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                            showGate(Gate.ERROR, "Echec de MaJ:\n" + why + "\n\n9 = réessayer", "Réessayer");
                        }
                    });
                }
            }
        });
    }

    // ---------------------------------------------------------------- query pipeline

    private static final class Result {
        List<Transit.Stop> stops = new ArrayList<>();
        List<Transit.Row> rows = new ArrayList<>();
        String status = "";
    }

    /** Runs on the UI thread: works out the geographic boxes, then does the data work in the background. */
    private void query(final boolean forceLive) {
        final StmData d = data;
        if (gate != Gate.NONE || d == null || map.getWidth() == 0) return;
        ui.removeCallbacks(queryNow);
        final int myGen = ++gen;

        final String zoomPrompt = "Faut zoomer plus pour voir les bus !";

        if (map.getZoomLevelDouble() < STOP_MARKERS_MIN_ZOOM - 0.01) {   // zoomed out too far: show nothing, request nothing
            showRows(new ArrayList<Transit.Row>());
            baseStatus = zoomPrompt;
            refreshBottomBar();
            map.invalidate();
            return;
        }

        // Box k = what's visible if the list panel had k rows (map gets shorter, centre stays put).
        int hFull = map.getHeight() + (listPanel.getVisibility() == View.VISIBLE ? listPanel.getHeight() : 0);
        //int maxRows = Math.max(1, Math.min(MAX_ROWS, (int) (hFull * MAX_LIST_FRACTION) / rowH));
        int maxRows = MAX_ROWS;
        Projection pr = map.getProjection();
        Point c = pr.toPixels(lastCenter, null);
        int w2 = map.getWidth() / 2;
        final double[][] boxes = new double[maxRows + 1][];
        for (int k = 0; k <= maxRows; k++) {
            int hh = Math.max(rowH, (hFull - k * rowH) / 2);
            IGeoPoint nw = pr.fromPixels(c.x - w2, c.y - hh);
            IGeoPoint se = pr.fromPixels(c.x + w2, c.y + hh);
            boxes[k] = new double[]{se.getLatitude(), nw.getLatitude(), nw.getLongitude(), se.getLongitude()};
        }
        final double cLat = lastCenter.getLatitude(), cLon = lastCenter.getLongitude();

        io.execute(new Runnable() {
            @Override public void run() {
                final Result res;
                try {
                    res = compute(d, boxes, cLat, cLon, forceLive);
                } catch (Exception ex) {
                    return;   // e.g. the database was closed for an update while this query was queued
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (myGen != gen || gate != Gate.NONE) return;   // superseded, or data is being replaced
                        showRows(res.rows);
                        baseStatus = listPanel.getVisibility() == View.VISIBLE ? res.status : zoomPrompt;
                        refreshBottomBar();
                        map.invalidate();
                    }
                });
            }
        });
    }

    /** Background thread. */
    private Result compute(StmData d, double[][] boxes, double cLat, double cLon, boolean forceLive) {
        Result res = new Result();
        Calendar now = Calendar.getInstance();
        int nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND);
        boolean[] stale = new boolean[1];
        int[] prof = d.profiles(now, stale);

        double[] b0 = boxes[0];
        List<Transit.Stop> stops = d.stopsIn(b0[0], b0[1], b0[2], b0[3], prof[0], prof[1]);

        RtFeed.Snapshot snap = rt;
        long nowMs = System.currentTimeMillis();
        if (forceLive || snap == null || nowMs - snap.fetchedMs > RT_MAX_AGE_MS) {
            try {
                snap = RtFeed.fetch();
                rt = snap;
            } catch (Exception e) {
                if (snap != null && nowMs - snap.fetchedMs > 180_000) { snap = null; rt = null; }
            }
        }
        Transit.computeEtas(stops, snap, nowMs / 1000, nowSec);

        int maxRows = boxes.length - 1;
        int r = Math.min(rowsIn(stops, boxes[0], cLat, cLon).size(), maxRows);
        List<Transit.Row> rows = rowsIn(stops, boxes[r], cLat, cLon);
        if (rows.size() > maxRows) rows = new ArrayList<>(rows.subList(0, maxRows));

        int liveRows = 0;
        for (Transit.Row row : rows) for (boolean l : row.live) if (l) { liveRows++; break; }
        StringBuilder st = new StringBuilder();
        if (snap != null) {
            st.append("À jour ").append(new SimpleDateFormat("HH'h'mm", Locale.CANADA_FRENCH).format(new Date(snap.fetchedMs)));
        }
        if (stale[0]) st.append(" | vieilles données");
        res.rows = rows;
        res.status = st.toString();
        return res;
    }

    private static List<Transit.Row> rowsIn(List<Transit.Stop> stops, double[] b, double cLat, double cLon) {
        return Transit.rowsFor(stops, b[0], b[1], b[2], b[3], cLat, cLon);
    }

    // ---------------------------------------------------------------- list panel

    private void showRows(List<Transit.Row> rows) {
        listPanel.removeAllViews();
        if (rows.isEmpty() || map.getZoomLevelDouble() < LIST_MIN_ZOOM - 0.01) { listPanel.setVisibility(View.GONE); return; }
        int pad = (int) (4 * dp);
        for (int i = 0; i < rows.size(); i++) {
            Transit.Row row = rows.get(i);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            String label = String.format(Locale.CANADA_FRENCH, "%-5s", row.label);
            sb.append(label);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Color.BLACK), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (int k = 0; k < row.etaSec.length; k++) {
                // Anything arriving within the hour (live or scheduled) is shown as minutes from now, in bold.
                // Later scheduled times are shown as plain clock times. Blue = live, black = scheduled.
                boolean soon = Transit.isSoon(row.etaSec[k]);
                String t = String.format(Locale.CANADA_FRENCH, " %5s",
                        soon ? Transit.fmt(row.etaSec[k]) : Transit.fmtClock(row.clockMin[k]));
                int s = sb.length();
                sb.append(t);
                sb.setSpan(new ForegroundColorSpan(row.live[k] ? LIVE_BLUE : Color.BLACK), s, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (soon) sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView tv = new TextView(this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setIncludeFontPadding(false);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setSingleLine(true);
            tv.setPadding(pad, 0, pad, 0);
            tv.setHeight(rowH);
            tv.setBackgroundColor(i % 2 == 0 ? Color.WHITE : Color.parseColor("#EEF3F7"));
            tv.setText(sb);
            listPanel.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        }
        listPanel.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- map drawing

    /**
     * Every stop in the network, sorted by latitude, plus lazily-built text caches. Built once per database,
     * so drawing a frame only looks up the handful of stops on screen and allocates almost nothing.
     */
    private static final class StopSet {
        final Transit.Stop[] stops;
        final double[] lats;
        final String[][] routeLines;   // bubble without intersection: one route per line
        final float[] routeW;
        final String[][] nameLines;    // bubble with intersection: name, then all routes on one line
        final float[] nameW;
        int cachedForWidth = -1;

        StopSet(List<Transit.Stop> list) {
            List<Transit.Stop> sorted = new ArrayList<>(list);
            Collections.sort(sorted, new Comparator<Transit.Stop>() {
                @Override public int compare(Transit.Stop a, Transit.Stop b) { return Double.compare(a.lat, b.lat); }
            });
            stops = sorted.toArray(new Transit.Stop[0]);
            lats = new double[stops.length];
            for (int i = 0; i < stops.length; i++) lats[i] = stops[i].lat;
            routeLines = new String[stops.length][];
            routeW = new float[stops.length];
            nameLines = new String[stops.length][];
            nameW = new float[stops.length];
        }

        /** Index of the first stop with latitude >= lat. */
        int firstAtOrAbove(double lat) {
            int lo = 0, hi = lats.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (lats[mid] < lat) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        void resetNameCache(int width) {
            Arrays.fill(nameLines, null);
            cachedForWidth = width;
        }
    }

    class StopOverlay extends Overlay {
        private volatile StopSet set;
        GeoPoint me;

        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boxRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint meDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint oldMeDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GeoPoint tmp = new GeoPoint(0.0, 0.0);   // reused, so drawing doesn't allocate per stop
        private final RectF rect = new RectF();
        private int[] vi = new int[512];
        private float[] vx = new float[512], vy = new float[512];

        StopOverlay() {
            dot.setColor(THEME);
            ring.setColor(Color.WHITE);
            box.setColor(Color.WHITE);
            boxRing.setColor(THEME);
            boxRing.setStyle(Paint.Style.STROKE);
            boxRing.setStrokeWidth(1.5f * dp);
            text.setColor(Color.BLACK);
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
            meDot.setColor(ORANGE_DOT);
            oldMeDot.setColor(LIGHTER_GREY_DOT);
        }

        /** Safe from any thread: swaps in the full stop list (called whenever a new database becomes active). */
        void setStops(List<Transit.Stop> list) { set = new StopSet(list); }

        private void buildRoutes(StopSet ss, int i) {
            String rt = ss.stops[i].routesText;
            String[] ls = rt.isEmpty() ? new String[]{""} : rt.split(" ");
            float m = 0;
            for (String ln : ls) m = Math.max(m, text.measureText(ln));
            ss.routeLines[i] = ls;
            ss.routeW[i] = m;
        }

        private void buildNames(StopSet ss, int i, int w) {
            Transit.Stop s = ss.stops[i];
            String l1 = TextUtils.ellipsize(s.name, text, w * 0.8f, TextUtils.TruncateAt.END).toString();
            String l2 = TextUtils.ellipsize(s.routesText, text, w * 0.8f, TextUtils.TruncateAt.END).toString();
            ss.nameLines[i] = new String[]{l1, l2};
            ss.nameW[i] = Math.max(text.measureText(l1), text.measureText(l2));
        }

        @Override public void draw(Canvas c, MapView mv, boolean shadow) {
            if (shadow) return;
            Projection proj = mv.getProjection();
            double zoom = mv.getZoomLevelDouble();
            int w = mv.getWidth(), h = mv.getHeight();
            float r = 4 * dp, pad = 4 * dp;
            Point pt = new Point();
            StopSet ss = set;

            // All stops already exist; below the minimum zoom they are simply not drawn.
            if (ss != null && ss.stops.length > 0 && zoom >= STOP_MARKERS_MIN_ZOOM - 0.01) {
                // Visible area plus a margin (extra below: bubbles grow upward from their stop).
                IGeoPoint a = proj.fromPixels(-150, -80);
                IGeoPoint b = proj.fromPixels(w + 150, h + 300);
                double north = a.getLatitude(), south = b.getLatitude();
                double west = a.getLongitude(), east = b.getLongitude();

                int n = 0;
                for (int i = ss.firstAtOrAbove(south); i < ss.stops.length; i++) {
                    Transit.Stop s = ss.stops[i];
                    if (s.lat > north) break;
                    if (s.lon < west || s.lon > east) continue;
                    tmp.setCoords(s.lat, s.lon);
                    proj.toPixels(tmp, pt);
                    if (n == vi.length) {
                        vi = Arrays.copyOf(vi, n * 2);
                        vx = Arrays.copyOf(vx, n * 2);
                        vy = Arrays.copyOf(vy, n * 2);
                    }
                    vi[n] = i; vx[n] = pt.x; vy[n] = pt.y;
                    n++;
                    c.drawCircle(pt.x, pt.y, r + dp, ring);
                    c.drawCircle(pt.x, pt.y, r, dot);
                }

                if (zoom >= ROUTES_ZOOM - 0.01 && n > 0) {
                    boolean names = zoom >= NAMES_ZOOM - 0.01;
                    if (ss.cachedForWidth != w) ss.resetNameCache(w);
                    Paint.FontMetrics fm = text.getFontMetrics();
                    float lineH = fm.descent - fm.ascent;
                    for (int k = 0; k < n; k++) {
                        int i = vi[k];
                        String[] lines;
                        float tw;
                        if (names) {
                            if (ss.nameLines[i] == null) buildNames(ss, i, w);
                            lines = ss.nameLines[i];
                            tw = ss.nameW[i];
                        } else {
                            if (ss.routeLines[i] == null) buildRoutes(ss, i);
                            lines = ss.routeLines[i];
                            tw = ss.routeW[i];
                        }
                        float x = vx[k], y = vy[k];
                        float bw = tw + 2 * pad, bh = lines.length * lineH + 2 * pad;
                        rect.set(x - bw / 2, y - r - 3 * dp - bh, x + bw / 2, y - r - 3 * dp);
                        c.drawRoundRect(rect, 5 * dp, 5 * dp, box);
                        c.drawRoundRect(rect, 5 * dp, 5 * dp, boxRing);
                        float base = rect.top + pad - fm.ascent;
                        for (String ln : lines) { c.drawText(ln, rect.left + pad, base, text); base += lineH; }
                    }
                }
            }
            if (me != null) {
                proj.toPixels(me, pt);
                c.drawCircle(pt.x, pt.y, 8 * dp, ring);
                c.drawCircle(pt.x, pt.y, 6 * dp, meDot);
            } else {
                proj.toPixels(initialPosition, pt);
                c.drawCircle(pt.x, pt.y, 8 * dp, ring);
                c.drawCircle(pt.x, pt.y, 6 * dp, oldMeDot);
            }
        }
    }
}