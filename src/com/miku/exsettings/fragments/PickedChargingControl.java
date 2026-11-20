/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.format.DateFormat;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.util.Calendar;

/**
 * Settings > Picked features > Battery > Charging control, for frameworks/base
 * PickedChargingControlService (Settings.System picked_charging_control_*).
 */
public class PickedChargingControl extends SettingsPreferenceFragment {

    private static final String KEY_MODE = "picked_charging_control_mode";
    private static final String KEY_LIMIT = "picked_charging_control_limit";
    private static final String KEY_START = "picked_charging_control_start_time";
    private static final String KEY_TARGET = "picked_charging_control_target_time";

    // Keep in sync with PickedChargingControlService.
    private static final String HAL = "vendor.lineage.health.IChargingControl/default";
    private static final int MODE_LIMIT = 1;
    private static final int MODE_CUSTOM = 3;
    private static final int DEFAULT_START_TIME = 22 * 3600;
    private static final int DEFAULT_TARGET_TIME = 6 * 3600;

    private Preference mLimit;
    private Preference mStart;
    private Preference mTarget;

    /** Whether the device has the Lineage health HAL's charging control. */
    public static boolean isSupported() {
        try {
            return ServiceManager.isDeclared(HAL);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_charging_control);

        mLimit = findPreference(KEY_LIMIT);
        mStart = findPreference(KEY_START);
        mTarget = findPreference(KEY_TARGET);
        final ListPreference mode = findPreference(KEY_MODE);
        mode.setOnPreferenceChangeListener((pref, value) -> {
            updateVisibility(Integer.parseInt((String) value));
            return true;
        });
        mStart.setOnPreferenceClickListener(p -> pickTime(KEY_START, DEFAULT_START_TIME));
        mTarget.setOnPreferenceClickListener(p -> pickTime(KEY_TARGET, DEFAULT_TARGET_TIME));
        updateVisibility(getMode());
        updateTimeSummaries();
    }

    private int getMode() {
        return Settings.System.getInt(getContext().getContentResolver(), KEY_MODE, MODE_LIMIT);
    }

    private void updateVisibility(int mode) {
        mLimit.setVisible(mode == MODE_LIMIT);
        mStart.setVisible(mode == MODE_CUSTOM);
        mTarget.setVisible(mode == MODE_CUSTOM);
    }

    private int getTime(String key, int def) {
        return Settings.System.getInt(getContext().getContentResolver(), key, def);
    }

    private boolean pickTime(String key, int def) {
        final Context context = getContext();
        final int seconds = getTime(key, def);
        new TimePickerDialog(context, (view, hour, minute) -> {
            final ContentResolver cr = context.getContentResolver();
            Settings.System.putInt(cr, key, hour * 3600 + minute * 60);
            if (getActivity() != null) updateTimeSummaries();
        }, seconds / 3600, (seconds % 3600) / 60, DateFormat.is24HourFormat(context)).show();
        return true;
    }

    private void updateTimeSummaries() {
        mStart.setSummary(formatTime(getTime(KEY_START, DEFAULT_START_TIME)));
        mTarget.setSummary(formatTime(getTime(KEY_TARGET, DEFAULT_TARGET_TIME)));
    }

    private String formatTime(int secondOfDay) {
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, secondOfDay / 3600);
        c.set(Calendar.MINUTE, (secondOfDay % 3600) / 60);
        return DateFormat.getTimeFormat(getContext()).format(c.getTime());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
