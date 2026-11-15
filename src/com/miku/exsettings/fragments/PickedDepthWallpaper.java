/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > Picked features > Style > Depth wallpaper: pick a photo, then a cut-out of its
 * subject; see PickedDepthWallpaperStore.
 */
public class PickedDepthWallpaper extends SettingsPreferenceFragment {

    private static final String KEY_ENABLED = "picked_depth_enabled";
    private static final String KEY_PICK = "picked_depth_pick";
    private static final int REQUEST_PHOTO = 1;
    private static final int REQUEST_SUBJECT = 2;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TwoStatePreference mEnabled;
    private Uri mPhoto;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_depth_wallpaper);
        if (savedInstanceState != null) mPhoto = savedInstanceState.getParcelable("photo");

        mEnabled = findPreference(KEY_ENABLED);
        mEnabled.setOnPreferenceChangeListener((pref, value) -> {
            PickedDepthWallpaperStore.setEnabled(getContext(), (Boolean) value);
            return true;
        });
        findPreference(KEY_PICK).setOnPreferenceClickListener(p -> {
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.picked_depth_pick_title)
                    .setMessage(R.string.picked_depth_how)
                    .setPositiveButton(R.string.picked_depth_choose_photo,
                            (d, w) -> pick(REQUEST_PHOTO, "image/*"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        refresh();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mPhoto != null) outState.putParcelable("photo", mPhoto);
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        final boolean has = PickedDepthWallpaperStore.hasSubject(getContext());
        mEnabled.setEnabled(has);
        mEnabled.setChecked(has && PickedDepthWallpaperStore.isEnabled(getContext()));
    }

    private void pick(int request, String type) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type);
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.picked_depth_err_decode, Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PHOTO && requestCode != REQUEST_SUBJECT) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_PHOTO) {
            mPhoto = data.getData();
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.picked_depth_pick_title)
                    .setMessage(R.string.picked_depth_now_subject)
                    .setPositiveButton(R.string.picked_depth_choose_subject,
                            (d, w) -> pick(REQUEST_SUBJECT, "image/png"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        final Uri photo = mPhoto;
        final Uri subject = data.getData();
        if (photo == null) return;
        final AlertDialog working = new AlertDialog.Builder(getContext())
                .setMessage(R.string.picked_depth_working)
                .setCancelable(false)
                .show();
        final Context app = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            final int result = PickedDepthWallpaperStore.apply(app, photo, subject);
            mHandler.post(() -> {
                working.dismiss();
                if (getActivity() == null) return;
                final int msg;
                switch (result) {
                    case PickedDepthWallpaperStore.OK: msg = R.string.picked_depth_done; break;
                    case PickedDepthWallpaperStore.ERR_NOT_FOUND:
                        msg = R.string.picked_depth_err_not_found; break;
                    case PickedDepthWallpaperStore.ERR_SAVE: msg = R.string.picked_depth_err_save;
                        break;
                    default: msg = R.string.picked_depth_err_decode;
                }
                Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
