/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [uwuAOSP] Pick the apps that are forced to draw behind the display cutout.
 * Stored as a comma separated package list in Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS,
 * read by com.android.internal.util.CutoutFullscreenController.
 */
public class PickedCutoutApps extends SettingsPreferenceFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context context = getPrefContext();
        final PreferenceScreen screen =
                getPreferenceManager().createPreferenceScreen(context);
        screen.setTitle(R.string.picked_cutout_apps_title);
        setPreferenceScreen(screen);

        final Set<String> enabled = getEnabledApps();
        final PackageManager pm = context.getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> activities = pm.queryIntentActivities(launcher, 0);

        final List<ResolveInfo> apps = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo != null && seen.add(info.activityInfo.packageName)) {
                apps.add(info);
            }
        }
        final Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(
                String.valueOf(a.loadLabel(pm)), String.valueOf(b.loadLabel(pm))));

        for (ResolveInfo info : apps) {
            final String pkg = info.activityInfo.packageName;
            final SwitchPreference pref = new SwitchPreference(context);
            pref.setKey(pkg);
            pref.setPersistent(false);
            pref.setTitle(info.loadLabel(pm));
            pref.setSummary(pkg);
            pref.setIcon(info.loadIcon(pm));
            pref.setChecked(enabled.contains(pkg));
            pref.setOnPreferenceChangeListener((p, newValue) -> {
                final Set<String> apps2 = getEnabledApps();
                if ((Boolean) newValue) {
                    apps2.add(pkg);
                } else {
                    apps2.remove(pkg);
                }
                Settings.System.putString(getContentResolver(),
                        Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS, String.join(",", apps2));
                return true;
            });
            screen.addPreference(pref);
        }
    }

    private Set<String> getEnabledApps() {
        final String value = Settings.System.getString(getContentResolver(),
                Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS);
        final Set<String> apps = new HashSet<>();
        if (!TextUtils.isEmpty(value)) {
            for (String pkg : Arrays.asList(value.split(","))) {
                if (!TextUtils.isEmpty(pkg)) {
                    apps.add(pkg.trim());
                }
            }
        }
        return apps;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
