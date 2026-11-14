/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.preference.ListPreference;
import androidx.preference.TwoStatePreference;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.miku.exsettings.preferences.CustomSeekBarPreference;
import com.miku.exsettings.utils.PickedCharging;

/**
 * Settings for the features Miku-Picked UI picked from Infinity X, AxionOS, uwuAOSP and
 * Evolution X. Most preferences store their value directly in Settings.System/Secure.
 */
public class PickedFeatures extends SettingsPreferenceFragment {

    private static final String KEY_CUTOUT_APPS = "picked_cutout_apps";
    private static final String KEY_UDFPS_ICON = "picked_udfps_icon";
    private static final String KEY_EMOJI_STYLE = "picked_emoji_style";
    private static final String PROP_EMOJI_STYLE = "persist.sys.ax_emoji_style";
    private static final String EMOJI_XML_PREFIX = "fonts_customization_emoji_";
    private static final int REQUEST_CUSTOM_EMOJI = 1001;
    private static final int REQUEST_PIF_CONFIG = 1002;
    private static final int REQUEST_KEYBOX = 1003;
    private static final String KEY_GOOGLE_SPOOF_WEAR = "picked_google_spoof_wear";
    private static final String PROP_GOOGLE_SPOOF_WEAR = "persist.sys.picked.google_spoof_wear";
    // System-wide Liquid Glass (ported from PenguinOS): renderengine reads these live.
    private static final String KEY_LIQUID_GLASS = "picked_liquid_glass";
    private static final String KEY_LIQUID_GLASS_REFRACTION = "picked_liquid_glass_refraction";
    private static final String PROP_LIQUID_GLASS = "persist.sys.penguin.liquid_glass";
    private static final String PROP_LIQUID_GLASS_REFRACTION =
            "persist.sys.penguin.liquid_glass_refraction";
    private static final String KEY_LIQUID_GLASS_STYLE = "picked_liquid_glass_style";
    private static final String PROP_LIQUID_GLASS_STYLE = "persist.sys.penguin.liquid_glass_style";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_features);

        // Full screen apps only make sense on devices with a display cutout.
        final String cutout = getResources().getString(
                com.android.internal.R.string.config_mainBuiltInDisplayCutout);
        if (TextUtils.isEmpty(cutout)) {
            final Preference cutoutApps = findPreference(KEY_CUTOUT_APPS);
            if (cutoutApps != null) {
                cutoutApps.setEnabled(false);
                cutoutApps.setSummary(R.string.picked_cutout_apps_unsupported);
            }
        }

        // Emoji style: a system property read by SystemFonts at boot. The list shows the
        // styles installed as /product/etc/fonts_customization_emoji_<style>.xml.
        final ListPreference emoji = findPreference(KEY_EMOJI_STYLE);
        if (emoji != null) {
            setupEmojiStyles(emoji);
            emoji.setValue(SystemProperties.get(PROP_EMOJI_STYLE, "android"));
            emoji.setOnPreferenceChangeListener((pref, newValue) -> {
                if (PickedCustomEmoji.STYLE.equals(newValue)) {
                    // Pick the font first; the style is set once it is installed.
                    pickCustomEmojiFont();
                    return false;
                }
                applyEmojiStyle((String) newValue);
                return true;
            });
        }

        // Google spoof for wearables: a system property read by android.os.Build when zygote
        // starts (GoogleSpoofForWear by 73sydney as a built-in toggle).
        final TwoStatePreference wearSpoof = findPreference(KEY_GOOGLE_SPOOF_WEAR);
        if (wearSpoof != null) {
            wearSpoof.setChecked(SystemProperties.getBoolean(PROP_GOOGLE_SPOOF_WEAR, false));
            wearSpoof.setOnPreferenceChangeListener((pref, newValue) -> {
                SystemProperties.set(PROP_GOOGLE_SPOOF_WEAR, (Boolean) newValue ? "1" : "0");
                showRebootDialog(R.string.picked_google_spoof_wear_reboot_title,
                        R.string.picked_google_spoof_wear_reboot_message);
                return true;
            });
        }

        // System-wide Liquid Glass: SurfaceFlinger picks the properties up on the next frame.
        final TwoStatePreference liquidGlass = findPreference(KEY_LIQUID_GLASS);
        if (liquidGlass != null) {
            liquidGlass.setChecked(SystemProperties.getBoolean(PROP_LIQUID_GLASS, true));
            liquidGlass.setOnPreferenceChangeListener((pref, newValue) -> {
                SystemProperties.set(PROP_LIQUID_GLASS, (Boolean) newValue ? "1" : "0");
                return true;
            });
        }
        final ListPreference glassStyle = findPreference(KEY_LIQUID_GLASS_STYLE);
        if (glassStyle != null) {
            glassStyle.setValue(
                    "cos".equals(SystemProperties.get(PROP_LIQUID_GLASS_STYLE)) ? "cos" : "ios");
            glassStyle.setOnPreferenceChangeListener((pref, newValue) -> {
                SystemProperties.set(PROP_LIQUID_GLASS_STYLE, (String) newValue);
                return true;
            });
        }
        final CustomSeekBarPreference refraction = findPreference(KEY_LIQUID_GLASS_REFRACTION);
        if (refraction != null) {
            refraction.setValue(Math.max(0, Math.min(100,
                    SystemProperties.getInt(PROP_LIQUID_GLASS_REFRACTION, 60))));
            refraction.setOnPreferenceChangeListener((pref, newValue) -> {
                SystemProperties.set(PROP_LIQUID_GLASS_REFRACTION,
                        Integer.toString((Integer) newValue));
                return true;
            });
        }

        // [Miku-Picked UI] Charging: LineageOS' health and PowerShare HALs, read live.
        setupCharging();

        // [FlopKernel] Mali GPU driver switch, on kernels with the Mali KMD selector.
        setupGpuDriver();

        // [Evolution X / AxionOS] Play Integrity: Pixel fingerprint, keybox, bootloader spoof.
        setupIntegrity();

        // The fingerprint icon is only drawn for under-display fingerprint sensors.
        final Preference udfpsIcon = findPreference(KEY_UDFPS_ICON);
        if (udfpsIcon != null) {
            udfpsIcon.setVisible(hasUdfps() && PickedUdfpsIcons.isAvailable(getContext()));
        }
    }

    // [FlopKernel / Exynos_Valhall_GPUDriver] Mali GPU driver switch. The kernel reports the
    // KMD its mali.version token selected as ro.boot.mali_version (the device mounts the
    // matching userspace driver from it); picked_mali_switch patches the token in the boot
    // partition when sys.picked.mali.request is set, and answers in sys.picked.mali.status.
    private static final String PROP_MALI_ACTIVE = "ro.boot.mali_version";
    private static final String PROP_MALI_REQUEST = "sys.picked.mali.request";
    private static final String PROP_MALI_STATUS = "sys.picked.mali.status";
    private static final long MALI_SWITCH_TIMEOUT_MS = 15000;
    private static final long MALI_SWITCH_POLL_MS = 250;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private void setupGpuDriver() {
        final Preference category = findPreference("picked_category_gpu");
        final ListPreference pref = findPreference("picked_gpu_driver");
        if (category == null || pref == null) return;
        final String active = SystemProperties.get(PROP_MALI_ACTIVE, "");
        if (active.isEmpty()) {
            // The kernel has no Mali KMD selector: nothing to switch.
            category.setVisible(false);
            return;
        }
        updateGpuDriverSummary(pref, active);
        pref.setOnPreferenceChangeListener((p, newValue) -> {
            final String target = (String) newValue;
            if (target.equals(pref.getValue())) return false;
            new AlertDialog.Builder(getContext())
                    .setTitle(getString(R.string.picked_gpu_driver_confirm_title,
                            gpuDriverLabel(pref, target)))
                    .setMessage(R.string.picked_gpu_driver_confirm_message)
                    .setPositiveButton(R.string.picked_gpu_driver_switch,
                            (d, w) -> applyGpuDriver(pref, active, target))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return false; // set once the helper has patched the boot image
        });
    }

    /** The driver the boot image now selects: a successful request, or the active one. */
    private static String pendingGpuDriver(String active) {
        final String status = SystemProperties.get(PROP_MALI_STATUS, "");
        for (String prefix : new String[] {"ok:", "unchanged:"}) {
            if (status.startsWith(prefix)) return status.substring(prefix.length());
        }
        return active;
    }

    private void updateGpuDriverSummary(ListPreference pref, String active) {
        final String pending = pendingGpuDriver(active);
        pref.setValue(pending);
        pref.setSummary(pending.equals(active)
                ? getString(R.string.picked_gpu_driver_summary, gpuDriverLabel(pref, active))
                : getString(R.string.picked_gpu_driver_summary_pending,
                        gpuDriverLabel(pref, active), gpuDriverLabel(pref, pending)));
    }

    private static CharSequence gpuDriverLabel(ListPreference pref, String value) {
        final int i = pref.findIndexOfValue(value);
        return i >= 0 ? pref.getEntries()[i] : value;
    }

    private void applyGpuDriver(ListPreference pref, String active, String target) {
        // Clear the last answer first, so a stale one isn't taken for this request's.
        SystemProperties.set(PROP_MALI_STATUS, "");
        SystemProperties.set(PROP_MALI_REQUEST, target + ":" + SystemClock.elapsedRealtime());
        pref.setEnabled(false);
        pref.setSummary(R.string.picked_gpu_driver_applying);
        final long deadline = SystemClock.uptimeMillis() + MALI_SWITCH_TIMEOUT_MS;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (getContext() == null) return;
                final String status = SystemProperties.get(PROP_MALI_STATUS, "");
                final boolean done = status.startsWith("ok:") || status.startsWith("unchanged:")
                        || status.startsWith("error:");
                if (!done && SystemClock.uptimeMillis() < deadline) {
                    mHandler.postDelayed(this, MALI_SWITCH_POLL_MS);
                    return;
                }
                pref.setEnabled(true);
                updateGpuDriverSummary(pref, active);
                if (status.startsWith("ok:")) {
                    new AlertDialog.Builder(getContext())
                            .setTitle(getString(R.string.picked_gpu_driver_done_title,
                                    gpuDriverLabel(pref, target)))
                            .setMessage(R.string.picked_gpu_driver_done_message)
                            .setPositiveButton(R.string.picked_emoji_reboot_now, (d, w) ->
                                    getContext().getSystemService(PowerManager.class).reboot(null))
                            .setNegativeButton(R.string.picked_emoji_reboot_later, null)
                            .show();
                } else if (!status.startsWith("unchanged:")) {
                    final String reason = status.startsWith("error:")
                            ? status.substring("error:".length()) : "no answer from the helper";
                    Toast.makeText(getContext(),
                            getString(R.string.picked_gpu_driver_failed, reason),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // [Evolution X / AxionOS] Play Integrity: the switches store their value straight in
    // Settings.System (spoof_pif_enabled, spoof_trickystore_enabled), which
    // PickedSpoofManager watches. These wire the fingerprint and keybox rows.
    private static final String SECURE_PIF_CONFIG = "spoof_pif_config";
    private static final String SECURE_PIF_REFRESH = "spoof_pif_refresh_request";
    private static final String SECURE_KEYBOX = "spoof_trickystore_keybox";
    // The raw keybox holds private keys and can't be read back (SettingsProvider blocks it),
    // so the key count is kept here for the summary.
    private static final String SECURE_KEYBOX_COUNT = "spoof_trickystore_keybox_count";

    private void setupIntegrity() {
        final Preference fingerprint = findPreference("picked_pif_fingerprint");
        if (fingerprint != null) {
            updatePifFingerprintSummary(fingerprint);
            fingerprint.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(getContext())
                        .setTitle(R.string.picked_pif_fingerprint_title)
                        .setItems(new CharSequence[] {
                                getString(R.string.picked_pif_update),
                                getString(R.string.picked_pif_import)}, (d, which) -> {
                            if (which == 0) {
                                Settings.Secure.putString(getContentResolver(), SECURE_PIF_REFRESH,
                                        Long.toString(System.currentTimeMillis()));
                                Toast.makeText(getContext(), R.string.picked_pif_updating,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                pickDocument(REQUEST_PIF_CONFIG);
                            }
                        })
                        .show();
                return true;
            });
        }

        final Preference keybox = findPreference("picked_trickystore_keybox");
        if (keybox != null) {
            updateKeyboxSummary(keybox);
            keybox.setOnPreferenceClickListener(p -> {
                final boolean loaded = Settings.Secure.getInt(getContentResolver(),
                        SECURE_KEYBOX_COUNT, 0) > 0;
                final CharSequence[] items = loaded
                        ? new CharSequence[] {getString(R.string.picked_trickystore_keybox_import),
                                getString(R.string.picked_trickystore_keybox_remove)}
                        : new CharSequence[] {getString(R.string.picked_trickystore_keybox_import)};
                new AlertDialog.Builder(getContext())
                        .setTitle(R.string.picked_trickystore_keybox_title)
                        .setItems(items, (d, which) -> {
                            if (which == 0) {
                                pickDocument(REQUEST_KEYBOX);
                            } else {
                                Settings.Secure.putString(getContentResolver(), SECURE_KEYBOX, "");
                                Settings.Secure.putInt(getContentResolver(), SECURE_KEYBOX_COUNT, 0);
                                updateKeyboxSummary(keybox);
                                Toast.makeText(getContext(),
                                        R.string.picked_trickystore_keybox_removed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
                return true;
            });
        }
    }

    private void updatePifFingerprintSummary(Preference pref) {
        final String config = Settings.Secure.getString(getContentResolver(), SECURE_PIF_CONFIG);
        if (TextUtils.isEmpty(config)) {
            pref.setSummary(R.string.picked_pif_fingerprint_none);
            return;
        }
        final String trimmed = config.trim();
        if (!trimmed.startsWith("{")) {
            pref.setSummary(R.string.picked_pif_fingerprint_custom);
            return;
        }
        try {
            final org.json.JSONObject json = new org.json.JSONObject(trimmed);
            final String model = json.optString("MODEL", json.optString("PRODUCT", "?"));
            final String patch = json.optString("SECURITY_PATCH", "?");
            pref.setSummary(getString(json.optBoolean("manually_imported", false)
                    ? R.string.picked_pif_fingerprint_manual
                    : R.string.picked_pif_fingerprint_auto, model, patch));
        } catch (Exception e) {
            pref.setSummary(R.string.picked_pif_fingerprint_custom);
        }
    }

    private void updateKeyboxSummary(Preference pref) {
        final int count = Settings.Secure.getInt(getContentResolver(), SECURE_KEYBOX_COUNT, 0);
        pref.setSummary(count > 0
                ? getString(R.string.picked_trickystore_keybox_loaded, count)
                : getString(R.string.picked_trickystore_keybox_none));
    }

    private static String decodeKeybox(String payload) {
        final String trimmed = payload.trim();
        if (trimmed.startsWith("<")) return trimmed;
        try {
            final String xml = new String(android.util.Base64.decode(trimmed,
                    android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8).trim();
            return xml.startsWith("<") ? xml : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static int countKeyboxKeys(String xml) {
        int count = 0, from = 0;
        while ((from = xml.indexOf("<Key ", from)) >= 0) {
            count++;
            from += 5;
        }
        return count;
    }

    private void pickDocument(int requestCode) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/json", "application/xml", "text/xml", "text/plain",
                        "application/octet-stream"});
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.picked_emoji_custom_failed, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private String readTextFile(android.net.Uri uri, int maxBytes) {
        try (java.io.InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) return null;
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void importPifConfig(android.net.Uri uri) {
        final String content = readTextFile(uri, 256 * 1024);
        boolean ok = false;
        String toStore = null;
        if (content != null) {
            final String trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                try {
                    final org.json.JSONObject json = new org.json.JSONObject(trimmed);
                    if (!TextUtils.isEmpty(json.optString("FINGERPRINT"))) {
                        json.put("manually_imported", true);
                        toStore = json.toString();
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
            } else if (trimmed.contains("FINGERPRINT")) {
                toStore = trimmed; // a pif.prop the user keeps as-is
                ok = true;
            }
        }
        if (ok) {
            Settings.Secure.putString(getContentResolver(), SECURE_PIF_CONFIG, toStore);
            final Preference pref = findPreference("picked_pif_fingerprint");
            if (pref != null) updatePifFingerprintSummary(pref);
            Toast.makeText(getContext(), R.string.picked_pif_imported, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), R.string.picked_pif_import_failed, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void importKeybox(android.net.Uri uri) {
        final String content = readTextFile(uri, 512 * 1024);
        final String xml = content == null ? "" : decodeKeybox(content);
        final int keys = xml.isEmpty() ? 0 : countKeyboxKeys(xml);
        if (keys > 0 && xml.contains("<Keybox") && xml.contains("<Certificate")) {
            Settings.Secure.putString(getContentResolver(), SECURE_KEYBOX, content.trim());
            Settings.Secure.putInt(getContentResolver(), SECURE_KEYBOX_COUNT, keys);
            final Preference pref = findPreference("picked_trickystore_keybox");
            if (pref != null) updateKeyboxSummary(pref);
            Toast.makeText(getContext(), R.string.picked_trickystore_keybox_imported,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), R.string.picked_trickystore_keybox_invalid,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void applyEmojiStyle(String style) {
        SystemProperties.set(PROP_EMOJI_STYLE, style);
        showRebootDialog(R.string.picked_emoji_reboot_title, R.string.picked_emoji_reboot_message);
    }

    private void showRebootDialog(int title, int message) {
        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.picked_emoji_reboot_now, (d, w) ->
                        getContext().getSystemService(PowerManager.class).reboot(null))
                .setNegativeButton(R.string.picked_emoji_reboot_later, null)
                .show();
    }

    private void pickCustomEmojiFont() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf",
                        "application/x-font-otf", "application/font-sfnt",
                        "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_CUSTOM_EMOJI);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.picked_emoji_custom_failed, Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PIF_CONFIG) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importPifConfig(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_KEYBOX) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importKeybox(data.getData());
            }
            return;
        }
        if (requestCode != REQUEST_CUSTOM_EMOJI) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (!PickedCustomEmoji.install(getContext(), data.getData())) {
            Toast.makeText(getContext(), R.string.picked_emoji_custom_failed, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        final ListPreference emoji = findPreference(KEY_EMOJI_STYLE);
        if (emoji != null) emoji.setValue(PickedCustomEmoji.STYLE);
        applyEmojiStyle(PickedCustomEmoji.STYLE);
    }

    private void setupEmojiStyles(ListPreference pref) {
        final List<String> values = new ArrayList<>();
        final List<CharSequence> entries = new ArrayList<>();
        values.add("android");
        entries.add(getString(R.string.picked_emoji_android));
        final String[] files = new File("/product/etc").list();
        if (files != null) {
            Arrays.sort(files);
            for (String f : files) {
                if (!f.startsWith(EMOJI_XML_PREFIX) || !f.endsWith(".xml")) continue;
                final String style = f.substring(EMOJI_XML_PREFIX.length(), f.length() - 4);
                if (style.isEmpty() || !style.matches("[a-z0-9_]+")
                        || PickedCustomEmoji.STYLE.equals(style)) continue;
                values.add(style);
                entries.add(emojiLabel(style));
            }
        }
        // Custom: an emoji font picked from the user's files (PickedCustomEmoji).
        values.add(PickedCustomEmoji.STYLE);
        entries.add(getString(PickedCustomEmoji.isInstalled(getContext())
                ? R.string.picked_emoji_custom_installed : R.string.picked_emoji_custom));
        pref.setEntries(entries.toArray(new CharSequence[0]));
        pref.setEntryValues(values.toArray(new CharSequence[0]));
    }

    private static String emojiLabel(String style) {
        switch (style) {
            case "ios": return "iOS";
            case "blobmoji": return "Blobmoji";
            case "openmoji": return "OpenMoji";
            case "twemoji": return "Twemoji";
            default:
                return Character.toUpperCase(style.charAt(0)) + style.substring(1).replace('_', ' ');
        }
    }

    private boolean hasUdfps() {
        final FingerprintManager fpm = getContext().getSystemService(FingerprintManager.class);
        if (fpm == null) return false;
        try {
            for (FingerprintSensorPropertiesInternal props : fpm.getSensorPropertiesInternal()) {
                if (props.isAnyUdfpsType()) return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private void setupCharging() {
        final TwoStatePreference fastWireless = findPreference("picked_wireless_fast_charge");
        if (fastWireless != null) {
            if (!PickedCharging.isWirelessFastChargeSupported()) {
                fastWireless.setVisible(false);
            } else {
                fastWireless.setChecked(PickedCharging.isWirelessFastChargeEnabled());
                fastWireless.setOnPreferenceChangeListener((pref, newValue) ->
                        PickedCharging.setWirelessFastChargeEnabled((Boolean) newValue));
            }
        }
        final TwoStatePreference powerShare = findPreference("picked_powershare");
        final CustomSeekBarPreference minBattery = findPreference("picked_powershare_min_battery");
        final boolean powerShareSupported = PickedCharging.isPowerShareSupported();
        if (powerShare != null) {
            if (!powerShareSupported) {
                powerShare.setVisible(false);
            } else {
                powerShare.setChecked(PickedCharging.isPowerShareEnabled());
                powerShare.setOnPreferenceChangeListener((pref, newValue) ->
                        PickedCharging.setPowerShareEnabled((Boolean) newValue));
            }
        }
        if (minBattery != null) {
            final int level = powerShareSupported ? PickedCharging.getPowerShareMinBattery() : -1;
            if (level < 0) {
                minBattery.setVisible(false);
            } else {
                minBattery.setValue(level);
                minBattery.setOnPreferenceChangeListener((pref, newValue) -> {
                    PickedCharging.setPowerShareMinBattery((Integer) newValue);
                    return true;
                });
            }
        }
        // The category always has the lockscreen charging info switch, so it stays visible.
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
