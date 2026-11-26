/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RecoverySystem;
import android.os.StatFs;
import android.os.SystemProperties;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Settings > System > System update: a local updater. Pick a ROM zip (or open one from a file
 * manager), the page checks its OTA metadata against this phone and build, copies it to
 * /data/ota_package, verifies its signature against the build's OTA keys
 * (/system/etc/security/otacerts.zip) and reboots to recovery, which installs it
 * (RecoverySystem.installPackage: uncrypt maps the file for recovery while shutting down).
 *
 * <p>Non-A/B devices only (o1s is non-A/B); A/B payloads are refused with a message.
 */
public class PickedSystemUpdate extends SettingsPreferenceFragment {

    private static final String TAG = "PickedSystemUpdate";

    private static final String KEY_CURRENT = "picked_update_current";
    private static final String KEY_CHOOSE = "picked_update_choose";
    private static final String KEY_SELECTED = "picked_update_selected";
    private static final String KEY_INSTALL = "picked_update_install";

    private static final int REQUEST_PICK = 1;
    private static final int MIN_BATTERY = 30;
    private static final long SPACE_MARGIN = 200L * 1024 * 1024;

    private static final File OTA_DIR = new File("/data/ota_package");
    private static final File OTA_FILE = new File(OTA_DIR, "picked_update.zip");
    private static final String METADATA = "META-INF/com/android/metadata";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mCancelled;

    private Uri mUri;
    private String mName;
    private long mSize = -1;
    private Map<String, String> mMetadata;
    private String mProblem;
    private boolean mWarnWipe;

    private AlertDialog mProgress;
    private ProgressBar mProgressBar;
    private TextView mProgressText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_system_update);
        findPreference(KEY_CURRENT).setSummary(currentBuild());
        findPreference(KEY_CHOOSE).setOnPreferenceClickListener(p -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[] {"application/zip", "application/octet-stream"});
            startActivityForResult(pick, REQUEST_PICK);
            return true;
        });
        findPreference(KEY_INSTALL).setOnPreferenceClickListener(p -> {
            confirmInstall();
            return true;
        });
        updateSelection();

        // A zip opened from a file manager ("Open with System update").
        Activity activity = getActivity();
        Intent intent = activity != null ? activity.getIntent() : null;
        if (savedInstanceState == null && intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            select(intent.getData());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                select(data.getData());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onDestroy() {
        mCancelled = true;
        mExecutor.shutdownNow();
        dismissProgress();
        super.onDestroy();
    }

    private String currentBuild() {
        String version = SystemProperties.get("ro.miku.version", "");
        String display = Build.DISPLAY;
        long utc = SystemProperties.getLong("ro.build.date.utc", 0) * 1000L;
        String date = utc > 0 ? DateFormat.getLongDateFormat(getContext()).format(new Date(utc))
                : "";
        StringBuilder out = new StringBuilder();
        if (!version.isEmpty()) out.append(version).append('\n');
        out.append(display);
        if (!date.isEmpty()) out.append('\n').append(date);
        out.append('\n').append(getString(R.string.picked_update_patch, Build.VERSION.SECURITY_PATCH));
        return out.toString();
    }

    // ---- Selecting a zip: read its name, size and OTA metadata, and check it fits this phone.

    private void select(Uri uri) {
        mUri = uri;
        mName = null;
        mSize = -1;
        mMetadata = null;
        mProblem = null;
        mWarnWipe = false;
        try {
            getContext().getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // A one-time grant (file manager VIEW) is enough while this page is open.
        }
        updateSelection();
        showProgress(R.string.picked_update_reading, false);
        Context context = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            String name = null;
            long size = -1;
            Map<String, String> metadata = null;
            String problem = null;
            try {
                ContentResolver resolver = context.getContentResolver();
                try (Cursor c = resolver.query(uri, new String[] {
                        OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        name = c.getString(0);
                        if (!c.isNull(1)) size = c.getLong(1);
                    }
                }
                metadata = readMetadata(resolver, uri);
                if (metadata == null) problem = context.getString(R.string.picked_update_not_ota);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Can't read " + uri, e);
                problem = context.getString(R.string.picked_update_unreadable);
            }
            if (problem == null) problem = check(context, metadata);
            final String n = name;
            final long s = size;
            final Map<String, String> m = metadata;
            final String p = problem;
            mMain.post(() -> {
                if (!isAdded()) return;
                dismissProgress();
                mName = n;
                mSize = s;
                mMetadata = m;
                mProblem = p;
                mWarnWipe = m != null && "yes".equals(m.get("ota-wipe"));
                updateSelection();
            });
        });
    }

    /** The OTA package's metadata (key=value lines), or null if it has none. */
    private static Map<String, String> readMetadata(ContentResolver resolver, Uri uri)
            throws IOException {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("no descriptor");
            // ZipFile needs random access: open the descriptor through /proc.
            try (ZipFile zip = new ZipFile(new File("/proc/self/fd/" + pfd.getFd()))) {
                ZipEntry entry = zip.getEntry(METADATA);
                if (entry == null) return null;
                Map<String, String> out = new HashMap<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int eq = line.indexOf('=');
                        if (eq > 0) out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
                if (zip.getEntry("payload.bin") != null) out.put("picked-payload", "yes");
                return out;
            }
        }
    }

    /** Why this package can't be installed here, or null if it can. */
    private static String check(Context context, Map<String, String> metadata) {
        if (metadata == null) return null;
        boolean ab = "AB".equals(metadata.get("ota-type"))
                || "yes".equals(metadata.get("picked-payload"));
        if (ab) return context.getString(R.string.picked_update_ab);
        String devices = metadata.get("pre-device");
        if (devices != null && !devices.isEmpty()) {
            String device = SystemProperties.get("ro.product.device", Build.DEVICE);
            String product = SystemProperties.get("ro.build.product", "");
            boolean match = false;
            for (String d : devices.split(",")) {
                d = d.trim();
                if (d.equals(device) || d.equals(product) || d.equals(Build.DEVICE)) match = true;
            }
            if (!match) return context.getString(R.string.picked_update_wrong_device, devices, device);
        }
        long post = parseLong(metadata.get("post-timestamp"));
        long now = SystemProperties.getLong("ro.build.date.utc", 0);
        if (post > 0 && now > 0 && post < now && !"yes".equals(metadata.get("ota-downgrade"))) {
            return context.getString(R.string.picked_update_downgrade);
        }
        if (metadata.containsKey("pre-build") && !metadata.get("pre-build").isEmpty()) {
            // An incremental update only applies to the exact build it was made from.
            String fingerprint = Build.FINGERPRINT;
            boolean match = false;
            for (String b : metadata.get("pre-build").split("\\|")) {
                if (b.trim().equals(fingerprint)) match = true;
            }
            if (!match) return context.getString(R.string.picked_update_wrong_base);
        }
        return null;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateSelection() {
        Preference selected = findPreference(KEY_SELECTED);
        Preference install = findPreference(KEY_INSTALL);
        if (mUri == null) {
            selected.setVisible(false);
            install.setEnabled(false);
            install.setSummary(R.string.picked_update_install_none);
            return;
        }
        selected.setVisible(true);
        selected.setTitle(mName != null ? mName : mUri.getLastPathSegment());
        StringBuilder summary = new StringBuilder();
        if (mSize >= 0) summary.append(Formatter.formatFileSize(getContext(), mSize));
        if (mMetadata != null) {
            String build = mMetadata.get("post-build");
            if (build != null) appendLine(summary, build);
            long post = parseLong(mMetadata.get("post-timestamp")) * 1000L;
            if (post > 0) {
                appendLine(summary, DateFormat.getLongDateFormat(getContext()).format(new Date(post)));
            }
            String patch = mMetadata.get("post-security-patch-level");
            if (patch != null) appendLine(summary, getString(R.string.picked_update_patch, patch));
            appendLine(summary, getString(mMetadata.containsKey("pre-build")
                    ? R.string.picked_update_incremental : R.string.picked_update_full));
        }
        if (mWarnWipe) appendLine(summary, getString(R.string.picked_update_wipe));
        if (mProblem != null) appendLine(summary, mProblem);
        selected.setSummary(summary.toString());
        install.setEnabled(mMetadata != null && mProblem == null);
        install.setSummary(mProblem != null ? R.string.picked_update_install_blocked
                : R.string.picked_update_install_summary);
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) out.append('\n');
        out.append(line);
    }

    // ---- Installing: battery and space checks, copy, signature check, reboot to recovery.

    private void confirmInstall() {
        Context context = getContext();
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (level >= 0 && level < MIN_BATTERY && !battery.isCharging()) {
            message(getString(R.string.picked_update_battery, MIN_BATTERY));
            return;
        }
        long free = new StatFs(OTA_DIR.getPath()).getAvailableBytes();
        if (mSize > 0 && free < mSize + SPACE_MARGIN) {
            message(getString(R.string.picked_update_space,
                    Formatter.formatFileSize(context, mSize + SPACE_MARGIN)));
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.picked_update_confirm_title)
                .setMessage(mWarnWipe ? R.string.picked_update_confirm_wipe
                        : R.string.picked_update_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.picked_update_install, (d, w) -> install())
                .show();
    }

    private void install() {
        mCancelled = false;
        showProgress(R.string.picked_update_copying, true);
        Context context = getContext().getApplicationContext();
        Uri uri = mUri;
        long size = mSize;
        mExecutor.execute(() -> {
            try {
                copy(context.getContentResolver(), uri, size);
                if (mCancelled) {
                    OTA_FILE.delete();
                    return;
                }
                progress(R.string.picked_update_verifying, 0);
                RecoverySystem.verifyPackage(OTA_FILE,
                        percent -> progress(R.string.picked_update_verifying, percent), null);
                if (mCancelled) {
                    OTA_FILE.delete();
                    return;
                }
                progress(R.string.picked_update_rebooting, 100);
                // Reboots into recovery; uncrypt runs during shutdown.
                RecoverySystem.installPackage(context, OTA_FILE);
            } catch (GeneralSecurityException e) {
                Log.w(TAG, "Signature check failed", e);
                OTA_FILE.delete();
                fail(context.getString(R.string.picked_update_bad_signature));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Update failed", e);
                OTA_FILE.delete();
                fail(context.getString(R.string.picked_update_failed, String.valueOf(e.getMessage())));
            }
        });
    }

    private void copy(ContentResolver resolver, Uri uri, long size) throws IOException {
        OTA_FILE.delete();
        try (InputStream in = resolver.openInputStream(uri);
                OutputStream out = new FileOutputStream(OTA_FILE)) {
            if (in == null) throw new IOException("can't open the zip");
            byte[] buffer = new byte[1 << 20];
            long done = 0;
            int lastPercent = -1;
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (mCancelled) return;
                out.write(buffer, 0, read);
                done += read;
                if (size > 0) {
                    int percent = (int) (done * 100 / size);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress(R.string.picked_update_copying, percent);
                    }
                }
            }
            ((FileOutputStream) out).getFD().sync();
        }
        // uncrypt (root, uncrypt domain) reads it; system:cache 0770 dir, file readable.
        OTA_FILE.setReadable(true, false);
    }

    private void progress(int text, int percent) {
        mMain.post(() -> {
            if (mProgressBar == null || !isAdded()) return;
            mProgressBar.setIndeterminate(percent <= 0);
            mProgressBar.setProgress(percent);
            mProgressText.setText(getString(text) + (percent > 0 ? "  " + percent + " %" : ""));
        });
    }

    private void fail(String text) {
        mMain.post(() -> {
            if (!isAdded()) return;
            dismissProgress();
            message(text);
        });
    }

    private void message(String text) {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.system_update_settings_list_item_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showProgress(int text, boolean cancellable) {
        dismissProgress();
        Context context = getContext();
        int pad = (int) (24 * context.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        mProgressText = new TextView(context);
        mProgressText.setText(text);
        mProgressText.setGravity(Gravity.START);
        mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setMax(100);
        box.addView(mProgressText);
        box.addView(mProgressBar);
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setView(box)
                .setCancelable(false);
        if (cancellable) {
            builder.setNegativeButton(android.R.string.cancel, (d, w) -> mCancelled = true);
        }
        mProgress = builder.show();
    }

    private void dismissProgress() {
        if (mProgress != null) mProgress.dismiss();
        mProgress = null;
        mProgressBar = null;
        mProgressText = null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
