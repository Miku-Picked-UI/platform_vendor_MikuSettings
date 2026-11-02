/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.os.Bundle;
import android.text.TextUtils;

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
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
