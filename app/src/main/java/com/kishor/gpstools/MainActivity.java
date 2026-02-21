package com.kishor.gpstools;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // ── Permission codes ──────────────────────────────────────────────────────
    private static final int REQ_LEGACY = 101;
    private static final int REQ_MEDIA  = 102;
    private static final int REQ_MANAGE = 103;

    // ── Supported extensions ──────────────────────────────────────────────────
    private static final Set<String> IMAGE_EXTS = new HashSet<>();
    private static final Set<String> VIDEO_EXTS = new HashSet<>();
    static {
        IMAGE_EXTS.add("jpg");  IMAGE_EXTS.add("jpeg");
        IMAGE_EXTS.add("png");  IMAGE_EXTS.add("heic");
        IMAGE_EXTS.add("heif"); IMAGE_EXTS.add("dng");
        VIDEO_EXTS.add("mp4");  VIDEO_EXTS.add("m4v");
    }

    // ── Time window options (ms) ──────────────────────────────────────────────
    private static final long[] TIME_WINDOW_MS = {
        60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L, 7_200_000L
    };

    // ── UI ────────────────────────────────────────────────────────────────────
    private EditText    etSource, etDest;
    private Spinner     spinnerTime;
    private Button      btnStart, btnStop, btnExportCsv;
    private ProgressBar progressBar;
    private TextView    tvStatus, tvLog;
    private TextView    tvTotal, tvMoved, tvKept, tvAutoTagged;
    private ScrollView  scrollLog;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private ExecutorService       executor;
    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final AtomicBoolean   stopped  = new AtomicBoolean(false);

    private int statTotal = 0, statMoved = 0, statKept = 0, statAutoTagged = 0;
    private final List<CsvRow>    csvRows   = new ArrayList<>();
    private final StringBuilder   logBuf    = new StringBuilder();

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES
    // ═════════════════════════════════════════════════════════════════════════

    private static class PhotoData {
        File    file;
        boolean isImage          = false;
        boolean hasGPS           = false;
        double  lat              = 0, lon = 0;
        long    timestamp        = -1;   // epoch ms; -1 = unknown
        String  dateStr          = "";
        boolean wasMovedToNoGps  = false;
        boolean wasAutoTagged    = false;
        String  gpsSource        = "";
        long    timeDiffMs       = -1;
    }

    private static class CsvRow {
        String  fileName, originalPath, gpsSource, status;
        boolean hasGPS;
        double  lat, lon;
        long    timeDiffMs;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupSpinner();
        setupButtons();
    }

    private void bindViews() {
        etSource     = findViewById(R.id.etSource);
        etDest       = findViewById(R.id.etDest);
        spinnerTime  = findViewById(R.id.spinnerTime);
        btnStart     = findViewById(R.id.btnStart);
        btnStop      = findViewById(R.id.btnStop);
        btnExportCsv = findViewById(R.id.btnExportCsv);
        progressBar  = findViewById(R.id.progressBar);
        tvStatus     = findViewById(R.id.tvStatus);
        tvLog        = findViewById(R.id.tvLog);
        tvTotal      = findViewById(R.id.tvTotal);
        tvMoved      = findViewById(R.id.tvMoved);
        tvKept       = findViewById(R.id.tvKept);
        tvAutoTagged = findViewById(R.id.tvAutoTagged);
        scrollLog    = findViewById(R.id.scrollLog);
    }

    private void setupSpinner() {
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(
            this, R.array.time_windows, android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTime.setAdapter(a);
        spinnerTime.setSelection(2); // default ±15 min
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        btnStop.setOnClickListener(v -> {
            stopped.set(true);
            log("⛔ Stop requested...");
            btnStop.setEnabled(false);
        });
        btnExportCsv.setOnClickListener(v -> exportCsv());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PERMISSIONS
    // ═════════════════════════════════════════════════════════════════════════

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                    .setTitle("All Files Access Required")
                    .setMessage("GPS Tools needs 'All files access' to move and tag photos.\n\nTap OK → enable the toggle for GPS Tools → return here.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                        startActivityForResult(i, REQ_MANAGE);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return;
            }
            startProcessing();

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            List<String> need = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)  != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)   != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (need.isEmpty()) startProcessing();
            else ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_MEDIA);

        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_LEGACY);
            } else {
                startProcessing();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        boolean ok = true;
        for (int g : grants) if (g != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (ok) startProcessing();
        else Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_MANAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
                startProcessing();
            else
                Toast.makeText(this, "All Files Access not granted", Toast.LENGTH_LONG).show();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  START
    // ═════════════════════════════════════════════════════════════════════════

    private void startProcessing() {
        String srcPath = etSource.getText().toString().trim();
        String dstPath = etDest.getText().toString().trim();
        if (srcPath.isEmpty() || dstPath.isEmpty()) {
            Toast.makeText(this, "Enter source and destination paths", Toast.LENGTH_SHORT).show();
            return;
        }
        File srcDir = new File(srcPath);
        if (!srcDir.exists() || !srcDir.isDirectory()) {
            Toast.makeText(this, "Source folder not found:\n" + srcPath, Toast.LENGTH_LONG).show();
            return;
        }

        // Reset
        stopped.set(false);
        statTotal = statMoved = statKept = statAutoTagged = 0;
        csvRows.clear();
        logBuf.setLength(0);
        tvLog.setText("[Ready]\n");

        int idx = spinnerTime.getSelectedItemPosition();
        long windowMs = TIME_WINDOW_MS[idx < TIME_WINDOW_MS.length ? idx : 2];

        btnStart.setVisibility(android.view.View.GONE);
        btnStop.setVisibility(android.view.View.VISIBLE);
        btnStop.setEnabled(true);
        btnExportCsv.setVisibility(android.view.View.GONE);
        progressBar.setProgress(0);
        setStatus("Starting...");

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> runAllPhases(srcDir, new File(dstPath), windowMs));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ORCHESTRATION
    // ═════════════════════════════════════════════════════════════════════════

    private void runAllPhases(File srcDir, File dstDir, long windowMs) {
        try {
            log("━━━━━━━ PHASE 1: SCAN & SEPARATE ━━━━━━━");
            List<PhotoData> all = phase1(srcDir, dstDir);
            if (stopped.get()) { onStopped(); return; }

            log("\n━━━━━━━ PHASE 2: AUTO-TAG GPS ━━━━━━━");
            phase2(all, dstDir, windowMs);
            if (stopped.get()) { onStopped(); return; }

            log("\n━━━━━━━ COMPLETE ━━━━━━━");
            log("Total files:      " + statTotal);
            log("Kept (had GPS):   " + statKept);
            log("Moved (no GPS):   " + statMoved);
            log("Auto-tagged:      " + statAutoTagged);
            log("Unmatched:        " + (statMoved - statAutoTagged));

            handler.post(() -> {
                setStatus("Done! Tap EXPORT CSV to save report.");
                progressBar.setProgress(100);
                btnStart.setVisibility(android.view.View.VISIBLE);
                btnStop.setVisibility(android.view.View.GONE);
                btnExportCsv.setVisibility(android.view.View.VISIBLE);
            });

        } catch (Exception e) {
            log("❌ Fatal: " + e.getMessage());
            handler.post(() -> {
                setStatus("Error: " + e.getMessage());
                btnStart.setVisibility(android.view.View.VISIBLE);
                btnStop.setVisibility(android.view.View.GONE);
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PHASE 1 – SCAN & SEPARATE
    // ═════════════════════════════════════════════════════════════════════════

    private List<PhotoData> phase1(File srcDir, File dstDir) {
        log("Scanning: " + srcDir.getAbsolutePath());
        List<File> raw = new ArrayList<>();
        collectFiles(srcDir, raw);
        statTotal = raw.size();
        updateStats();
        log("Found " + statTotal + " supported files");

        if (statTotal == 0) {
            log("⚠️ No files found. Check path and permissions.");
            return new ArrayList<>();
        }

        dstDir.mkdirs();

        List<PhotoData> all = new ArrayList<>();
        int done = 0;

        for (File f : raw) {
            if (stopped.get()) break;

            boolean isImg = IMAGE_EXTS.contains(getExt(f));
            PhotoData pd  = readPhotoData(f, isImg);

            if (pd.hasGPS) {
                pd.wasMovedToNoGps = false;
                statKept++;
                all.add(pd);
            } else {
                File dest = uniqueDest(dstDir, f.getName());
                if (moveFile(f, dest)) {
                    pd.file = dest;
                    pd.wasMovedToNoGps = true;
                    statMoved++;
                    all.add(pd);
                } else {
                    log("⚠️ Could not move: " + f.getName());
                    all.add(pd);
                }
            }

            done++;
            final int d = done;
            if (done % 10 == 0 || done == statTotal) {
                handler.post(() -> {
                    progressBar.setProgress((int)(d * 40.0 / statTotal));
                    setStatus("Phase 1: " + d + " / " + statTotal);
                    updateStats();
                });
            }
        }

        updateStats();
        log("Phase 1 done — Kept=" + statKept + "  Moved=" + statMoved);
        return all;
    }

    private void collectFiles(File dir, List<File> out) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            if (f.isDirectory()) collectFiles(f, out);
            else {
                String ext = getExt(f);
                if (IMAGE_EXTS.contains(ext) || VIDEO_EXTS.contains(ext)) out.add(f);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PHASE 2 – AUTO-TAG
    // ═════════════════════════════════════════════════════════════════════════

    private void phase2(List<PhotoData> all, File dstDir, long windowMs) {
        // Split into donors (have GPS + date) and receivers (need GPS)
        List<PhotoData> donors = new ArrayList<>();
        List<PhotoData> noGps  = new ArrayList<>();

        for (PhotoData pd : all) {
            if (pd.hasGPS && pd.timestamp != -1) donors.add(pd);
            else if (pd.wasMovedToNoGps)          noGps.add(pd);
        }

        log("GPS donors with date: " + donors.size());
        log("Files needing GPS:    " + noGps.size());

        if (donors.isEmpty() || noGps.isEmpty()) {
            log("⚠️ Nothing to auto-tag.");
            buildCsv(all);
            return;
        }

        // Sort donors by timestamp for binary search
        Collections.sort(donors, (a, b) -> Long.compare(a.timestamp, b.timestamp));

        File autoDir = new File(dstDir, "AutoTagged");
        autoDir.mkdirs();

        int done = 0, noDate = 0, noMatch = 0, videoMatch = 0, writeFail = 0;

        for (PhotoData pd : noGps) {
            if (stopped.get()) break;
            done++;

            if (done % 10 == 0 || done == noGps.size()) {
                final int d = done;
                final int t = statAutoTagged;
                handler.post(() -> {
                    progressBar.setProgress(40 + (int)(d * 55.0 / Math.max(noGps.size(), 1)));
                    setStatus("Phase 2: " + d + " / " + noGps.size() + "  Tagged: " + t);
                });
            }

            if (pd.timestamp == -1) { noDate++; continue; }

            PhotoData best = closestDonor(donors, pd.timestamp, windowMs);
            if (best == null)       { noMatch++; continue; }

            long diffMs  = Math.abs(pd.timestamp - best.timestamp);
            long diffMin = diffMs / 60_000L;

            // ── WRITE GPS ─────────────────────────────────────────────────
            // Images: write GPS into EXIF using ExifInterface (in-place, safe)
            // Videos: patch the ©xyz atom in the MP4 binary directly —
            //         NO remux, preserves rotation/date/all metadata
            boolean success;
            if (pd.isImage) {
                success = writeGpsToImage(pd.file, best.lat, best.lon);
                if (!success) { writeFail++; continue; }
            } else {
                success = writeGpsToVideo(pd.file, best.lat, best.lon);
                if (!success) { writeFail++; continue; }
                videoMatch++;
            }

            // Move to AutoTagged/
            File dest = uniqueDest(autoDir, pd.file.getName());
            if (moveFile(pd.file, dest)) pd.file = dest;

            pd.wasAutoTagged = true;
            pd.hasGPS        = true;
            pd.lat           = best.lat;
            pd.lon           = best.lon;
            pd.gpsSource     = best.file.getName();
            pd.timeDiffMs    = diffMs;
            statAutoTagged++;

            if (statAutoTagged % 50 == 0) {
                log("🏷️ " + statAutoTagged + " tagged... latest: "
                    + pd.file.getName() + " (" + diffMin + " min diff)");
            }
        }

        log("━━ Phase 2 complete ━━");
        log("  ✅ Images GPS written:   " + (statAutoTagged - videoMatch));
        log("  📹 Videos GPS written:   " + videoMatch);
        log("  ⏭️  No date (skipped):    " + noDate);
        log("  🔍 No match in window:   " + noMatch);
        log("  ❌ Write failed:          " + writeFail);
        updateStats();
        buildCsv(all);
    }

    /**
     * Find the closest GPS donor to 'target' within 'windowMs'.
     *
     * Uses binary search to find the insertion point, then scans outward from
     * there until donors are further than windowMs away — catching ALL candidates,
     * not just ±1 neighbour. Returns the closest one, or null if none within window.
     */
    private PhotoData closestDonor(List<PhotoData> sorted, long target, long windowMs) {
        if (sorted.isEmpty()) return null;
        int lo = 0, hi = sorted.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (sorted.get(mid).timestamp < target) lo = mid + 1;
            else hi = mid;
        }
        // lo is now the first index with timestamp >= target
        // Scan left and right until diff > windowMs on both sides
        PhotoData best  = null;
        long bestDiff   = Long.MAX_VALUE;

        // Scan right from lo (timestamps >= target)
        for (int i = lo; i < sorted.size(); i++) {
            long diff = sorted.get(i).timestamp - target; // always >= 0
            if (diff > windowMs) break;
            if (diff < bestDiff) { bestDiff = diff; best = sorted.get(i); }
        }
        // Scan left from lo-1 (timestamps < target)
        for (int i = lo - 1; i >= 0; i--) {
            long diff = target - sorted.get(i).timestamp; // always > 0
            if (diff > windowMs) break;
            if (diff < bestDiff) { bestDiff = diff; best = sorted.get(i); }
        }
        return best; // null if nothing within windowMs
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  METADATA READING
    //  IMAGES → ExifInterface   (datetime is LOCAL time, no timezone in EXIF)
    //  VIDEOS → MediaMetadataRetriever  (datetime is UTC per MP4 spec)
    //
    //  TIMEZONE STRATEGY:
    //  EXIF has no timezone — we treat image datetime as the device's local time.
    //  MP4 METADATA_KEY_DATE is UTC — we parse it as UTC.
    //  To compare them on the same axis we convert both to epoch-ms using the
    //  device's default timezone for images (same device that took both shots).
    //  This is correct: a photo and video shot at the same moment on the same
    //  device will have the same epoch-ms after this treatment.
    // ═════════════════════════════════════════════════════════════════════════

    private PhotoData readPhotoData(File f, boolean isImage) {
        PhotoData d = new PhotoData();
        d.file    = f;
        d.isImage = isImage;
        if (isImage) readImageMeta(f, d);
        else         readVideoMeta(f, d);
        return d;
    }

    /**
     * Images: GPS + datetime from EXIF.
     * EXIF datetime has NO timezone — parsed in the device's local timezone,
     * which is correct because the camera clock is local time.
     */
    private void readImageMeta(File f, PhotoData d) {
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());

            float[] ll = new float[2];
            if (exif.getLatLong(ll) && isValidCoord(ll[0], ll[1])) {
                d.hasGPS = true;
                d.lat    = ll[0];
                d.lon    = ll[1];
            }

            // Prefer DateTimeOriginal (shutter moment), fall back to DateTime
            String ds = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (ds == null) ds = exif.getAttribute(ExifInterface.TAG_DATETIME);
            if (ds != null) {
                // EXIF format: "yyyy:MM:dd HH:mm:ss" — local time, no timezone
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                // Do NOT set timezone — use device default (local time = camera clock)
                try {
                    Date dt = sdf.parse(ds);
                    if (dt != null) { d.timestamp = dt.getTime(); d.dateStr = ds; }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * Videos: GPS + datetime from MediaMetadataRetriever.
     *
     * METADATA_KEY_DATE returns UTC per the ISO 14496-12 spec.
     * Format variations seen in the wild:
     *   "yyyyMMdd'T'HHmmss'Z'"    e.g. "20240315T134200Z"   (explicit Z = UTC)
     *   "yyyyMMdd'T'HHmmss.SSS'Z'"                          (with ms)
     *   "yyyyMMdd'T'HHmmss"                                  (no Z, still UTC)
     *
     * We MUST parse as UTC, then convert to the device local epoch so the
     * timestamp is on the same axis as image timestamps.
     */
    private void readVideoMeta(File f, PhotoData d) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());

            // GPS location
            String loc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (loc != null && !loc.isEmpty()) {
                double[] coords = parseIso6709(loc);
                if (coords != null && isValidCoord((float)coords[0], (float)coords[1])) {
                    d.hasGPS = true;
                    d.lat    = coords[0];
                    d.lon    = coords[1];
                }
            }

            // Date — MP4 spec says UTC
            String ds = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (ds != null && !ds.isEmpty()) {
                d.timestamp = parseVideoDate(ds);
                if (d.timestamp != -1) d.dateStr = ds;
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Parse the date string from METADATA_KEY_DATE into epoch-ms.
     *
     * Samsung Galaxy and most Android cameras store LOCAL time here (not true UTC),
     * identical wall-clock digits to EXIF DateTimeOriginal.
     * We parse both as plain local time (no timezone), so they compare correctly.
     *
     * Format variations: "yyyyMMddTHHmmssZ", "yyyyMMddTHHmmss.SSSZ", "yyyyMMddTHHmmss"
     */
    private long parseVideoDate(String ds) {
        String clean = ds.trim();
        // Strip trailing Z — we treat as local time regardless, same as EXIF
        if (clean.endsWith("Z") || clean.endsWith("z"))
            clean = clean.substring(0, clean.length() - 1);
        // Strip fractional seconds
        int dotIdx = clean.indexOf('.');
        if (dotIdx > 0) clean = clean.substring(0, dotIdx);
        if (clean.length() < 15) return -1;
        clean = clean.substring(0, 15);
        try {
            // Parse as LOCAL time — no setTimeZone() call, same as readImageMeta
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            Date dt = sdf.parse(clean);
            return dt != null ? dt.getTime() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Parse ISO 6709 location string from MediaMetadataRetriever.
     * Examples: "+37.4219-122.0840/"  "+13.0827+080.2707/"  "-33.8688+151.2093/"
     */
    private double[] parseIso6709(String loc) {
        try {
            Matcher m = Pattern.compile("([+-]\\d+\\.?\\d*)([+-]\\d+\\.?\\d*)").matcher(loc);
            if (m.find()) return new double[]{
                Double.parseDouble(m.group(1)),
                Double.parseDouble(m.group(2))
            };
        } catch (Exception ignored) {}
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GPS WRITE — IMAGES
    // ═════════════════════════════════════════════════════════════════════════

    private boolean writeGpsToImage(File f, double lat, double lon) {
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,      toDms(Math.abs(lat)));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,  lat >= 0 ? "N" : "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,     toDms(Math.abs(lon)));
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lon >= 0 ? "E" : "W");
            exif.saveAttributes();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GPS WRITE — VIDEOS (MP4/M4V)
    //
    //  Strategy: read ONLY the moov atom (a few KB) into RAM via RandomAccessFile.
    //  Patch moov in memory (udta/©xyz), then write back.
    //
    //  Case A — moov grows (new ©xyz/udta added):
    //    Stream-copy the whole file into a temp, injecting the patched moov.
    //    mdat (the huge video data) is copied in 256 KB chunks — never fully in RAM.
    //
    //  Case B — moov stays same size or shrinks (©xyz already exists, same-length replace):
    //    Seek to moov offset, overwrite in-place. Zero temp file, instant, any file size.
    //
    //  This preserves 100% of: rotation, creation date, all track metadata, codec info.
    // ═════════════════════════════════════════════════════════════════════════

    private boolean writeGpsToVideo(File f, double lat, double lon) {
        String iso = String.format(Locale.US, "%+.6f%+.6f/", lat, lon);
        byte[] isoBytes;
        try   { isoBytes = iso.getBytes("UTF-8"); }
        catch (Exception e) { isoBytes = iso.getBytes(); }

        byte[] newXyzAtom = buildXyzAtom(isoBytes);

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            long fileLen = raf.length();

            // ── Step 1: Walk top-level boxes to find moov ──────────────────
            long moovOffset = -1;
            long moovLen    = -1;
            long pos = 0;
            while (pos + 8 <= fileLen) {
                raf.seek(pos);
                long boxSize = readUInt32BE(raf);  // fixed: proper unsigned 32-bit
                byte[] nameB = new byte[4];
                raf.readFully(nameB);
                String name = new String(nameB, "ISO-8859-1");
                if (boxSize == 1) {
                    // 64-bit extended size (rare, for >4GB boxes)
                    boxSize = raf.readLong();
                }
                if (boxSize < 8 || pos + boxSize > fileLen) {
                    pos += 8; continue; // guard against corrupt headers
                }
                if ("moov".equals(name)) {
                    moovOffset = pos;
                    moovLen    = boxSize;
                    break;
                }
                pos += boxSize;
            }
            if (moovOffset < 0) { raf.close(); return false; }

            // ── Step 2: Read the entire moov into RAM ──────────────────────
            if (moovLen > 50L * 1024 * 1024) { raf.close(); return false; } // >50MB moov = corrupt
            byte[] moov = new byte[(int) moovLen];
            raf.seek(moovOffset);
            raf.readFully(moov);   // readFully guarantees all bytes read

            // ── Step 3: Patch moov in memory ───────────────────────────────
            byte[] patchedMoov = patchMoovGps(moov, newXyzAtom);
            if (patchedMoov == null) { raf.close(); return false; }

            // ── Step 4: Write back ─────────────────────────────────────────
            if (patchedMoov.length == moov.length) {
                // Exact same size — overwrite in-place (no temp file)
                raf.seek(moovOffset);
                raf.write(patchedMoov);
                raf.close();
                return true;
            }

            // moov changed size — must stream-copy entire file
            raf.close();
            raf = null;
            return streamCopyWithNewMoov(f, moovOffset, moovLen, patchedMoov);

        } catch (Exception e) {
            return false;
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] patchMoovGps(byte[] moov, byte[] newXyzAtom) {
        // Search for udta inside moov (moov header is 8 bytes)
        int udtaOff = findBox(moov, 8, moov.length, 'u','d','t','a');

        byte[] patchedMoov;

        if (udtaOff >= 0) {
            int udtaSize = readInt32(moov, udtaOff);
            if (udtaOff + udtaSize > moov.length) return null; // corrupt
            int udtaEnd  = udtaOff + udtaSize;

            // Look for ©xyz inside udta
            int xyzOff = findBox(moov, udtaOff + 8, udtaEnd, 0xA9,'x','y','z');

            // Extract udta as its own byte array to work on
            byte[] udtaBytes = new byte[udtaSize];
            System.arraycopy(moov, udtaOff, udtaBytes, 0, udtaSize);

            byte[] patchedUdta;
            if (xyzOff >= 0) {
                // ©xyz exists — replace it
                int localXyz  = xyzOff - udtaOff;
                int xyzSize   = readInt32(moov, xyzOff);
                if (xyzOff + xyzSize > udtaEnd) return null; // corrupt
                patchedUdta = spliceBuffer(udtaBytes, localXyz, xyzSize, newXyzAtom);
            } else {
                // No ©xyz — append it inside udta
                patchedUdta = spliceBuffer(udtaBytes, udtaSize, 0, newXyzAtom);
            }
            writeInt32(patchedUdta, 0, patchedUdta.length); // fix udta size

            // Splice patched udta back into moov
            patchedMoov = spliceBuffer(moov, udtaOff, udtaSize, patchedUdta);

        } else {
            // No udta at all — create one with ©xyz and append inside moov
            byte[] newUdta = buildUdtaBox(newXyzAtom);
            patchedMoov = spliceBuffer(moov, moov.length, 0, newUdta);
        }

        writeInt32(patchedMoov, 0, patchedMoov.length); // fix moov size
        return patchedMoov;
    }

    /**
     * Stream-copy the file to a temp, injecting patchedMoov at moovOffset.
     * Uses readFully-style reads to guarantee no bytes are skipped.
     */
    private boolean streamCopyWithNewMoov(File f, long moovOffset, long moovLen,
                                           byte[] patchedMoov) throws IOException {
        File tmp = new File(f.getParent(), "." + f.getName() + ".gpstmp");
        boolean success = false;
        try (FileInputStream  fis = new FileInputStream(f);
             FileOutputStream fos = new FileOutputStream(tmp)) {

            byte[] chunk = new byte[256 * 1024];

            // 1. Copy bytes BEFORE moov
            long remaining = moovOffset;
            while (remaining > 0) {
                int toRead = (int) Math.min(remaining, chunk.length);
                int n = fis.read(chunk, 0, toRead);
                if (n < 0) throw new IOException("EOF before moov");
                fos.write(chunk, 0, n);
                remaining -= n;
            }

            // 2. Write the patched moov
            fos.write(patchedMoov);

            // 3. Skip the original moov bytes in source using read (more reliable than skip)
            long toSkip = moovLen;
            while (toSkip > 0) {
                int toRead = (int) Math.min(toSkip, chunk.length);
                int n = fis.read(chunk, 0, toRead);
                if (n < 0) throw new IOException("EOF while skipping original moov");
                toSkip -= n;
            }

            // 4. Copy everything after moov (mdat etc.) in chunks
            int n;
            while ((n = fis.read(chunk)) != -1) {
                fos.write(chunk, 0, n);
            }
            success = true;
        } finally {
            if (!success) tmp.delete();
        }

        if (!f.delete()) { tmp.delete(); return false; }
        return tmp.renameTo(f);
    }

    // ── Box search (operates on byte[] in memory) ─────────────────────────────

    /**
     * Find a box by 4-byte name within buf[start..end).
     * Walks the box tree correctly — only advances by box.size, never blindly by 4.
     */
    private int findBox(byte[] buf, int start, int end, int c0, int c1, int c2, int c3) {
        int pos = start;
        while (pos + 8 <= end && pos + 8 <= buf.length) {
            int size = readInt32(buf, pos);
            // A valid box must be >= 8 bytes and fit within the search boundary
            if (size < 8 || pos + size > end) {
                break; // corrupt or end of container — stop, don't skip by 4
            }
            if ((buf[pos+4] & 0xFF) == c0 && (buf[pos+5] & 0xFF) == c1
             && (buf[pos+6] & 0xFF) == c2 && (buf[pos+7] & 0xFF) == c3) {
                return pos;
            }
            pos += size;
        }
        return -1;
    }

    private int readInt32(byte[] buf, int off) {
        return ((buf[off]   & 0xFF) << 24) | ((buf[off+1] & 0xFF) << 16)
             | ((buf[off+2] & 0xFF) <<  8) |  (buf[off+3] & 0xFF);
    }

    private void writeInt32(byte[] buf, int off, int val) {
        buf[off]   = (byte)((val >> 24) & 0xFF);
        buf[off+1] = (byte)((val >> 16) & 0xFF);
        buf[off+2] = (byte)((val >>  8) & 0xFF);
        buf[off+3] = (byte)( val        & 0xFF);
    }

    /** Splice: remove removeLen bytes at removeOff, insert insertBytes. */
    private byte[] spliceBuffer(byte[] src, int removeOff, int removeLen, byte[] insert) {
        int newLen = src.length - removeLen + insert.length;
        byte[] out = new byte[newLen];
        System.arraycopy(src,    0,         out, 0,                  removeOff);
        System.arraycopy(insert, 0,         out, removeOff,          insert.length);
        System.arraycopy(src, removeOff + removeLen,
                         out, removeOff + insert.length,
                         src.length - removeOff - removeLen);
        return out;
    }

    /**
     * Read a big-endian unsigned 32-bit integer from RandomAccessFile.
     * Each byte is read as long to prevent sign-extension during shifts.
     */
    private long readUInt32BE(RandomAccessFile raf) throws IOException {
        long b0 = raf.read() & 0xFFL;
        long b1 = raf.read() & 0xFFL;
        long b2 = raf.read() & 0xFFL;
        long b3 = raf.read() & 0xFFL;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    // ── Atom builders ─────────────────────────────────────────────────────────

    /** ©xyz atom: [size 4][©xyz 4][data-len 2][lang 2][ISO 6709 string] */
    private byte[] buildXyzAtom(byte[] isoBytes) {
        int total = 4 + 4 + 2 + 2 + isoBytes.length;
        byte[] box = new byte[total];
        writeInt32(box, 0, total);
        box[4] = (byte)0xA9; box[5] = 'x'; box[6] = 'y'; box[7] = 'z';
        box[8]  = (byte)((isoBytes.length >> 8) & 0xFF);
        box[9]  = (byte)( isoBytes.length       & 0xFF);
        box[10] = 0x15; box[11] = (byte)0xC7;  // language "und"
        System.arraycopy(isoBytes, 0, box, 12, isoBytes.length);
        return box;
    }

    /** udta container wrapping a child atom. */
    private byte[] buildUdtaBox(byte[] child) {
        int total = 4 + 4 + child.length;
        byte[] box = new byte[total];
        writeInt32(box, 0, total);
        box[4] = 'u'; box[5] = 'd'; box[6] = 't'; box[7] = 'a';
        System.arraycopy(child, 0, box, 8, child.length);
        return box;
    }

    /** Convert decimal degrees to EXIF DMS rational string. */
    private String toDms(double coord) {
        int deg = (int) coord;
        double mf = (coord - deg) * 60.0;
        int min = (int) mf;
        int secNum = (int) Math.round((mf - min) * 60.0 * 10000);
        return deg + "/1," + min + "/1," + secNum + "/10000";
    }

    private boolean isValidCoord(float lat, float lon) {
        if (Math.abs(lat) < 0.0001f && Math.abs(lon) < 0.0001f) return false;
        return lat >= -90f && lat <= 90f && lon >= -180f && lon <= 180f;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CSV
    // ═════════════════════════════════════════════════════════════════════════

    private void buildCsv(List<PhotoData> all) {
        csvRows.clear();
        for (PhotoData pd : all) {
            CsvRow r  = new CsvRow();
            r.fileName    = pd.file.getName();
            r.originalPath = pd.file.getAbsolutePath();
            r.hasGPS      = pd.hasGPS;
            r.lat         = pd.lat;
            r.lon         = pd.lon;
            r.gpsSource   = pd.gpsSource;
            r.timeDiffMs  = pd.timeDiffMs;
            r.status      = pd.wasAutoTagged ? "Auto-tagged"
                          : pd.wasMovedToNoGps ? "Moved" : "Kept";
            csvRows.add(r);
        }
    }

    private void exportCsv() {
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File csv   = new File(Environment.getExternalStorageDirectory(), "GPS_Report_" + ts + ".csv");
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(csv))) {
                bw.write("FileName,OriginalPath,HasGPS,GPSLat,GPSLon,GPS_Source,TimeDiff_Minutes,Status\n");
                for (CsvRow r : csvRows) {
                    String diff = r.timeDiffMs >= 0
                        ? String.format(Locale.US, "%.2f", r.timeDiffMs / 60_000.0) : "";
                    bw.write(String.format(Locale.US, "%s,%s,%s,%s,%s,%s,%s,%s\n",
                        esc(r.fileName), esc(r.originalPath), r.hasGPS,
                        r.hasGPS ? String.format(Locale.US, "%.6f", r.lat) : "",
                        r.hasGPS ? String.format(Locale.US, "%.6f", r.lon) : "",
                        esc(r.gpsSource), diff, esc(r.status)));
                }
                final String path = csv.getAbsolutePath();
                handler.post(() -> {
                    Toast.makeText(this, "CSV saved: " + path, Toast.LENGTH_LONG).show();
                    log("📊 CSV: " + path);
                });
            } catch (IOException e) {
                handler.post(() -> Toast.makeText(this,
                    "CSV failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FILE HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private boolean moveFile(File src, File dst) {
        if (src.renameTo(dst)) return true;
        try { copyFile(src, dst); return src.delete(); }
        catch (IOException e) { return false; }
    }

    private void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private File uniqueDest(File dir, String name) {
        File c = new File(dir, name);
        if (!c.exists()) return c;
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext  = dot >= 0 ? name.substring(dot) : "";
        int i = 1;
        do { c = new File(dir, base + "_" + i++ + ext); } while (c.exists());
        return c;
    }

    private String getExt(File f) {
        String n = f.getName();
        int d = n.lastIndexOf('.');
        return d >= 0 ? n.substring(d + 1).toLowerCase(Locale.US) : "";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private void log(String msg) {
        handler.post(() -> {
            logBuf.append(msg).append("\n");
            tvLog.setText(logBuf.toString());
            scrollLog.post(() -> scrollLog.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    private void setStatus(String msg) {
        handler.post(() -> tvStatus.setText(msg));
    }

    private void updateStats() {
        handler.post(() -> {
            tvTotal.setText(String.valueOf(statTotal));
            tvMoved.setText(String.valueOf(statMoved));
            tvKept.setText(String.valueOf(statKept));
            tvAutoTagged.setText(String.valueOf(statAutoTagged));
        });
    }

    private void onStopped() {
        log("⛔ Stopped by user.");
        handler.post(() -> {
            setStatus("Stopped.");
            btnStart.setVisibility(android.view.View.VISIBLE);
            btnStop.setVisibility(android.view.View.GONE);
            if (!csvRows.isEmpty()) btnExportCsv.setVisibility(android.view.View.VISIBLE);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopped.set(true);
        if (executor != null) executor.shutdownNow();
    }
}
