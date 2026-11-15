/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.os.PowerProfile;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Date;
import java.util.Locale;

/**
 * Settings > Picked features > Charging > Battery health: cycle count, health, capacity, dates,
 * and live temperature, voltage, current and power (refreshed every second while open), with
 * the min/max temperature and peak charging power seen while the page was open.
 *
 * Uses the health HAL through BatteryManager first and falls back to the battery's power_supply
 * nodes (Samsung: battery_cycle, fg_asoc; generic: cycle_count, charge_full[_design]).
 */
public class PickedBatteryHealth extends SettingsPreferenceFragment {

    private static final String PS = "/sys/class/power_supply/battery/";
    private static final long REFRESH_MS = 1000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refreshLive();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private float mMinTemp = Float.MAX_VALUE, mMaxTemp = -Float.MAX_VALUE, mPeakWatts;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_battery_health);
        refreshStatic();
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    private Intent battery() {
        return getContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private static String readNode(String name) {
        try (BufferedReader r = new BufferedReader(new FileReader(PS + name))) {
            final String s = r.readLine();
            return s != null ? s.trim() : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private static long readLong(String name) {
        final String s = readNode(name);
        if (s == null) return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void set(String key, CharSequence summary) {
        final Preference p = findPreference(key);
        if (p != null) p.setSummary(summary);
    }

    private void refreshStatic() {
        final Context c = getContext();
        final BatteryManager bm = c.getSystemService(BatteryManager.class);
        final Intent b = battery();
        final String unknown = getString(R.string.picked_bh_unknown);

        // Cycles
        int cycles = b != null ? b.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) : -1;
        if (cycles <= 0) cycles = (int) readLong("battery_cycle");
        if (cycles <= 0) cycles = (int) readLong("cycle_count");
        set("picked_bh_cycles", cycles >= 0 ? Integer.toString(cycles) : unknown);

        // Health %
        int health = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH);
        if (health <= 0 || health > 100) {
            health = (int) readLong("fg_asoc");
        }
        if (health <= 0 || health > 100) {
            final long full = readLong("charge_full");
            final long design = readLong("charge_full_design");
            health = full > 0 && design > 0 ? (int) Math.min(100, full * 100 / design) : -1;
        }
        final String status = b != null ? healthStatus(b.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN)) : unknown;
        set("picked_bh_health", health > 0
                ? getString(R.string.picked_bh_health_value, health, status) : status);

        // Capacity
        final double design = new PowerProfile(c).getBatteryCapacity();
        final int pct = health > 0 ? health : 100;
        set("picked_bh_capacity", design > 0
                ? getString(R.string.picked_bh_capacity_value, (int) design,
                        (int) Math.round(design * pct / 100.0))
                : unknown);

        set("picked_bh_technology", b != null && b.getStringExtra(
                BatteryManager.EXTRA_TECHNOLOGY) != null
                ? b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) : unknown);

        set("picked_bh_manufactured", date(bm.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_MANUFACTURING_DATE), unknown));
        set("picked_bh_first_use", date(bm.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_FIRST_USAGE_DATE), unknown));
    }

    private String date(long seconds, String unknown) {
        if (seconds <= 0 || seconds == Long.MIN_VALUE) return unknown;
        return DateFormat.getLongDateFormat(getContext()).format(new Date(seconds * 1000));
    }

    private String healthStatus(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return getString(R.string.picked_bh_good);
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return getString(R.string.picked_bh_overheat);
            case BatteryManager.BATTERY_HEALTH_COLD: return getString(R.string.picked_bh_cold);
            case BatteryManager.BATTERY_HEALTH_DEAD: return getString(R.string.picked_bh_dead);
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return getString(R.string.picked_bh_over_voltage);
            default: return getString(R.string.picked_bh_unknown);
        }
    }

    private void refreshLive() {
        final Intent b = battery();
        if (b == null) return;
        final float temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        final float volts = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;
        final boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));

        if (temp > 0) {
            mMinTemp = Math.min(mMinTemp, temp);
            mMaxTemp = Math.max(mMaxTemp, temp);
            set("picked_bh_temperature", String.format(Locale.getDefault(),
                    "%.1f °C   (%.1f – %.1f °C)", temp, mMinTemp, mMaxTemp));
        }
        set("picked_bh_voltage", String.format(Locale.getDefault(), "%.2f V", volts));

        // CURRENT_NOW is µA by the HAL contract, but Samsung kernels pass on mA: anything
        // below 20 A in raw units is taken as mA. Negative = discharging.
        final int raw = getContext().getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (raw != Integer.MIN_VALUE) {
            final float ma = Math.abs(raw) > 20_000 ? raw / 1000f : raw;
            final float watts = Math.abs(ma) / 1000f * volts;
            if (plugged) mPeakWatts = Math.max(mPeakWatts, watts);
            set("picked_bh_current", String.format(Locale.getDefault(), "%+.0f mA", ma));
            set("picked_bh_power", plugged
                    ? getString(R.string.picked_bh_power_charging, watts, mPeakWatts)
                    : getString(R.string.picked_bh_power_discharging, watts));
        }
        set("picked_bh_level", level + " %");
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
