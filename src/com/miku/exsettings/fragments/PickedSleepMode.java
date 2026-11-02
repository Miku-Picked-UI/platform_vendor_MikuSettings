/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * [Evolution X] Sleep mode settings.
 *
 * Settings.Secure.SLEEP_MODE_AUTO_TIME is stored as "HH:mm,HH:mm" (since,till), as read by
 * com.android.server.power.SleepModeService.
 */
public class PickedSleepMode extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_AUTO_MODE = "sleep_mode_auto_mode";
    private static final String KEY_SINCE = "sleep_mode_since";
    private static final String KEY_TILL = "sleep_mode_till";

    private static final String DEFAULT_TIME = "22:00,07:00";

    // Must match SleepModeService MODE_*
    private static final int MODE_DISABLED = 0;
    private static final int MODE_NIGHT = 1;
    private static final int MODE_TIME = 2;
    private static final int MODE_MIXED_SUNSET = 3;
    private static final int MODE_MIXED_SUNRISE = 4;

    private ListPreference mAutoMode;
    private Preference mSince;
    private Preference mTill;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_sleep_mode);

        mAutoMode = findPreference(KEY_AUTO_MODE);
        mSince = findPreference(KEY_SINCE);
        mTill = findPreference(KEY_TILL);

        mAutoMode.setOnPreferenceChangeListener(this);
        mSince.setOnPreferenceClickListener(p -> {
            pickTime(true);
            return true;
        });
        mTill.setOnPreferenceClickListener(p -> {
            pickTime(false);
            return true;
        });

        updateTimePreferences(getAutoMode());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mAutoMode) {
            updateTimePreferences(Integer.parseInt((String) newValue));
        }
        return true;
    }

    private int getAutoMode() {
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.SLEEP_MODE_AUTO_MODE, MODE_DISABLED);
    }

    private String[] getTimes() {
        String value = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.SLEEP_MODE_AUTO_TIME);
        if (TextUtils.isEmpty(value) || !value.contains(",")) {
            value = DEFAULT_TIME;
        }
        return value.split(",", 2);
    }

    private void updateTimePreferences(int mode) {
        final String[] times = getTimes();
        // "since" is used when the mode starts at a custom time, "till" when it ends at one.
        mSince.setVisible(mode == MODE_TIME || mode == MODE_MIXED_SUNRISE);
        mTill.setVisible(mode == MODE_TIME || mode == MODE_MIXED_SUNSET);
        mSince.setSummary(formatTime(times[0]));
        mTill.setSummary(formatTime(times[1]));
    }

    private String formatTime(String hhmm) {
        try {
            final LocalTime time = LocalTime.parse(hhmm);
            final String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm a";
            return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()));
        } catch (Exception e) {
            return hhmm;
        }
    }

    private void pickTime(boolean since) {
        final String[] times = getTimes();
        LocalTime current;
        try {
            current = LocalTime.parse(since ? times[0] : times[1]);
        } catch (Exception e) {
            current = since ? LocalTime.of(22, 0) : LocalTime.of(7, 0);
        }
        new TimePickerDialog(getContext(), (view, hour, minute) -> {
            final String picked = String.format(Locale.US, "%02d:%02d", hour, minute);
            final String value = since ? picked + "," + times[1] : times[0] + "," + picked;
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.SLEEP_MODE_AUTO_TIME, value);
            updateTimePreferences(getAutoMode());
        }, current.getHour(), current.getMinute(), DateFormat.is24HourFormat(getContext())).show();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
