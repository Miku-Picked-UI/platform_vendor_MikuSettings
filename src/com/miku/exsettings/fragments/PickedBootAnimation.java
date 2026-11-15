/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Settings > Picked features > Style > Boot animation. */
public class PickedBootAnimation extends SettingsPreferenceFragment {

    private static final String KEY_STYLE = "picked_bootanim_style";
    private static final String KEY_PREVIEW = "picked_bootanim_preview";
    private static final String KEY_IMPORT = "picked_bootanim_import";
    private static final String KEY_REMOVE = "picked_bootanim_remove";
    private static final String KEY_CLOCK = "picked_bootanim_clock";
    private static final int REQUEST_IMPORT = 1;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ListPreference mStyle;
    private Preference mRemove;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_bootanimation);

        mStyle = findPreference(KEY_STYLE);
        mStyle.setOnPreferenceChangeListener((pref, value) -> {
            PickedBootAnimationStore.setStyle((String) value);
            return true;
        });

        findPreference(KEY_PREVIEW).setOnPreferenceClickListener(p -> {
            showPreview();
            return true;
        });
        findPreference(KEY_IMPORT).setOnPreferenceClickListener(p -> {
            pickFile();
            return true;
        });
        mRemove = findPreference(KEY_REMOVE);
        mRemove.setOnPreferenceClickListener(p -> {
            PickedBootAnimationStore.removeCustom();
            refresh();
            return true;
        });

        final TwoStatePreference clock = findPreference(KEY_CLOCK);
        clock.setChecked(SystemProperties.getBoolean(PickedBootAnimationStore.PROP_CLOCK, false));
        clock.setOnPreferenceChangeListener((pref, value) -> {
            SystemProperties.set(PickedBootAnimationStore.PROP_CLOCK,
                    (Boolean) value ? "1" : "0");
            return true;
        });

        refresh();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        final List<String> entries = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        entries.add(getString(R.string.picked_bootanim_default));
        values.add(PickedBootAnimationStore.STYLE_DEFAULT);
        for (String style : PickedBootAnimationStore.bundledStyles()) {
            entries.add(styleLabel(style));
            values.add(style);
        }
        final boolean hasCustom = PickedBootAnimationStore.hasCustom();
        if (hasCustom) {
            entries.add(getString(R.string.picked_bootanim_custom));
            values.add(PickedBootAnimationStore.STYLE_CUSTOM);
        }
        mStyle.setEntries(entries.toArray(new CharSequence[0]));
        mStyle.setEntryValues(values.toArray(new CharSequence[0]));
        String current = PickedBootAnimationStore.getStyle();
        if (!values.contains(current)) current = PickedBootAnimationStore.STYLE_DEFAULT;
        mStyle.setValue(current);
        mRemove.setVisible(hasCustom);
    }

    private String styleLabel(String style) {
        switch (style) {
            case "pulse": return getString(R.string.picked_bootanim_pulse);
            case "orbit": return getString(R.string.picked_bootanim_orbit);
            case "glitch": return getString(R.string.picked_bootanim_glitch);
            default:
                return style.isEmpty() ? style
                        : style.substring(0, 1).toUpperCase(Locale.ROOT) + style.substring(1);
        }
    }

    private void showPreview() {
        final File file = PickedBootAnimationStore.fileForStyle(mStyle.getValue());
        final int maxSide = getResources().getDisplayMetrics().widthPixels / 2;
        final AlertDialog loading = new AlertDialog.Builder(getContext())
                .setMessage(R.string.picked_bootanim_loading)
                .setCancelable(false)
                .show();
        final android.content.Context context = getContext();
        mExecutor.execute(() -> {
            final AnimationDrawable anim =
                    PickedBootAnimationStore.loadPreview(context, file, maxSide);
            mHandler.post(() -> {
                loading.dismiss();
                if (getActivity() == null) return;
                if (anim == null) {
                    Toast.makeText(getContext(), R.string.picked_bootanim_preview_failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                final ImageView view = new ImageView(getContext());
                view.setBackgroundColor(Color.BLACK);
                view.setAdjustViewBounds(true);
                view.setImageDrawable(anim);
                new AlertDialog.Builder(getContext())
                        .setView(view)
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener(d -> anim.stop())
                        .show();
                anim.start();
            });
        });
    }

    private void pickFile() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/zip", "application/x-zip-compressed", "image/gif",
                        "video/*", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.picked_bootanim_import_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final AlertDialog working = new AlertDialog.Builder(getContext())
                .setMessage(R.string.picked_bootanim_importing)
                .setCancelable(false)
                .show();
        final android.content.Context appContext = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            final boolean ok = PickedBootAnimationStore.importFrom(appContext, uri);
            mHandler.post(() -> {
                working.dismiss();
                if (ok) PickedBootAnimationStore.setStyle(PickedBootAnimationStore.STYLE_CUSTOM);
                if (getActivity() == null) return;
                Toast.makeText(getContext(), ok ? R.string.picked_bootanim_import_done
                        : R.string.picked_bootanim_import_failed, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
