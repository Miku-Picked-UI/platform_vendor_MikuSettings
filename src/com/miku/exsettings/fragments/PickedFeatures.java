/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.preference.ListPreference;
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
                SystemProperties.set(PROP_EMOJI_STYLE, (String) newValue);
                new AlertDialog.Builder(getContext())
                        .setTitle(R.string.picked_emoji_reboot_title)
                        .setMessage(R.string.picked_emoji_reboot_message)
                        .setPositiveButton(R.string.picked_emoji_reboot_now, (d, w) ->
                                getContext().getSystemService(PowerManager.class).reboot(null))
                        .setNegativeButton(R.string.picked_emoji_reboot_later, null)
                        .show();
                return true;
            });
        }

        // The fingerprint icon is only drawn for under-display fingerprint sensors.
        final Preference udfpsIcon = findPreference(KEY_UDFPS_ICON);
        if (udfpsIcon != null) {
            udfpsIcon.setVisible(hasUdfps() && PickedUdfpsIcons.isAvailable(getContext()));
        }
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
                if (style.isEmpty() || !style.matches("[a-z0-9_]+")) continue;
                values.add(style);
                entries.add(emojiLabel(style));
            }
        }
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
