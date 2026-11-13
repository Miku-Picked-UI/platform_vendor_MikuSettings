/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.WindowManager;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

/**
 * [Miku-Picked UI] Screenshot detection tester: registers for Android's screenshot detection
 * (Activity#registerScreenCaptureCallback, Android 14+) like an app would and counts the
 * screenshots it's told about, so "Hide screenshots from apps" can be checked. It can also mark
 * itself secure (FLAG_SECURE), like a banking app, to check "Allow screenshots in secure apps".
 */
public class PickedScreenshotTester extends SettingsPreferenceFragment {

    private static final String KEY_STATUS = "screenshot_tester_status";
    private static final String KEY_SECURE = "screenshot_tester_secure";

    private Preference mStatus;
    private SwitchPreferenceCompat mSecure;
    private int mCount;
    private long mLast;
    private boolean mRegistered;

    private final Activity.ScreenCaptureCallback mCallback = () -> {
        mCount++;
        mLast = System.currentTimeMillis();
        updateStatus();
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_screenshot_tester);
        mStatus = findPreference(KEY_STATUS);
        mSecure = findPreference(KEY_SECURE);
        mSecure.setChecked(false);
        mSecure.setOnPreferenceChangeListener((p, value) -> {
            applySecure((Boolean) value);
            return true;
        });
        updateStatus();
    }

    @Override
    public void onStart() {
        super.onStart();
        final Activity activity = getActivity();
        if (activity != null && !mRegistered) {
            try {
                activity.registerScreenCaptureCallback(activity.getMainExecutor(), mCallback);
                mRegistered = true;
            } catch (RuntimeException e) {
                mStatus.setSummary(R.string.picked_screenshot_tester_unavailable);
            }
        }
        applySecure(mSecure.isChecked());
    }

    @Override
    public void onStop() {
        final Activity activity = getActivity();
        if (activity != null && mRegistered) {
            activity.unregisterScreenCaptureCallback(mCallback);
            mRegistered = false;
        }
        // Only this screen is secure; never leave the Settings window that way.
        applySecure(false);
        super.onStop();
    }

    private void applySecure(boolean secure) {
        final Activity activity = getActivity();
        if (activity == null) return;
        if (secure) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void updateStatus() {
        if (mStatus == null) return;
        if (mCount == 0) {
            mStatus.setTitle(R.string.picked_screenshot_tester_none);
        } else {
            mStatus.setTitle(getResources().getQuantityString(
                    R.plurals.picked_screenshot_tester_count, mCount, mCount));
        }
        mStatus.setSummary(mCount == 0
                ? getString(R.string.picked_screenshot_tester_how)
                : getString(R.string.picked_screenshot_tester_last,
                        DateFormat.getTimeFormat(getContext()).format(mLast)));
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
