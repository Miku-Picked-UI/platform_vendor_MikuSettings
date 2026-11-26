/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.miku.exsettings.preferences.CustomSecureDecimalSeekBarPreference;

/**
 * Settings > Picked features > Water back arrow: the switch plus the BackWaterSim sliders
 * (Settings.Secure picked_back_water_*, read by SystemUI BackPanel on each swipe).
 */
public class PickedBackWater extends SettingsPreferenceFragment {

    // key, divisor, default. Keep in sync with BackPanel.kt WaterTunable.
    private static final Object[][] TUNABLES = {
        {"picked_back_water_arrow", 100, 100},
        {"picked_back_water_wobble", 100, 255},
        {"picked_back_water_viscosity", 1000, 174},
        {"picked_back_water_pop_bounce", 100, 38},
        {"picked_back_water_pop_splash", 100, 123},
        {"picked_back_water_jiggle", 100, 114},
        {"picked_back_water_follow_stiffness", 1, 701},
        {"picked_back_water_follow_damping", 1000, 488},
        {"picked_back_water_drag_stretch", 100, 52},
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_back_water);
        for (Object[] t : TUNABLES) {
            final CustomSecureDecimalSeekBarPreference p = findPreference((String) t[0]);
            if (p != null) p.setDivisor((Integer) t[1]);
        }
        final Preference reset = findPreference("picked_back_water_reset");
        reset.setOnPreferenceClickListener(p -> {
            for (Object[] t : TUNABLES) {
                final String key = (String) t[0];
                final int def = (Integer) t[2];
                Settings.Secure.putInt(getContentResolver(), key, def);
                final CustomSecureDecimalSeekBarPreference s = findPreference(key);
                if (s != null) s.refresh(def);
            }
            return true;
        });
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
