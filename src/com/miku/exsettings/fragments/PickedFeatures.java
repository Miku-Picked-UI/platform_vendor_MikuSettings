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
import android.os.PowerManager;
import android.os.SystemProperties;
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
    private static final String KEY_GOOGLE_SPOOF_WEAR = "picked_google_spoof_wear";
    private static final String PROP_GOOGLE_SPOOF_WEAR = "persist.sys.picked.google_spoof_wear";

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

        // The fingerprint icon is only drawn for under-display fingerprint sensors.
        final Preference udfpsIcon = findPreference(KEY_UDFPS_ICON);
        if (udfpsIcon != null) {
            udfpsIcon.setVisible(hasUdfps() && PickedUdfpsIcons.isAvailable(getContext()));
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

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
