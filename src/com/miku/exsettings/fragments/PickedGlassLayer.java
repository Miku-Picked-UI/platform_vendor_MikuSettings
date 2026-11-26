/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.os.Bundle;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

/**
 * Settings > Picked features > Look & feel > Glass Layer: the native port of DrDisagree's Glass
 * Layer (Settings.Secure picked_glass_layer*, applied live).
 */
public class PickedGlassLayer extends SettingsPreferenceFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_glass_layer);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
