/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > Picked features > Style > Depth wallpaper: pick a photo and its subject is cut out on
 * device (or pick your own cut-out of it as well); see PickedDepthWallpaperStore.
 */
public class PickedDepthWallpaper extends SettingsPreferenceFragment {

    private static final String KEY_ENABLED = "picked_depth_enabled";
    private static final String KEY_PICK = "picked_depth_pick";
    private static final int REQUEST_PHOTO = 1;
    private static final int REQUEST_SUBJECT = 2;
    private static final int REQUEST_PHOTO_AUTO = 3;

    // Built-in wallpapers: Miku UI's own (vendor/miku/wallpaper, MikuWallpapers), the ones
    // whose subject the cut-out finds cleanly. The others are full-frame scenes with no
    // background to cut away, so their "subject" would cover the clock.
    private static final String WALLPAPER_PKG = "com.miku.wallpaper";
    private static final String[] BUILT_IN = {
            "miku_02", "miku_03", "miku_06", "miku_07", "miku_08", "miku_09", "miku_13",
            "miku_14", "miku_15"};

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TwoStatePreference mEnabled;
    private Uri mPhoto;
    // Automatic cut-out waiting for the user to confirm it in the preview.
    private PickedDepthWallpaperStore.Cutout mPending;
    private AlertDialog mDialog;

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
            if (!PickedDepthCutoutClient.isAvailable(getContext())) {
                showOwnCutoutHelp();
                return true;
            }
            final boolean builtIn = !builtInWallpapers().isEmpty();
            final List<CharSequence> items = new ArrayList<>();
            if (builtIn) items.add(getString(R.string.picked_depth_source_builtin));
            items.add(getString(R.string.picked_depth_source_photo));
            items.add(getString(R.string.picked_depth_source_own));
            final int offset = builtIn ? 0 : 1;
            showDialog(new AlertDialog.Builder(getContext())
                    .setTitle(R.string.picked_depth_pick_title)
                    .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                        switch (which + offset) {
                            case 0: showBuiltIn(); break;
                            case 1: pick(REQUEST_PHOTO_AUTO, "image/*"); break;
                            default: showOwnCutoutHelp();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null));
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
        if (mDialog != null) mDialog.dismiss();
        clearPending();
        super.onDestroy();
    }

    private void showDialog(AlertDialog.Builder builder) {
        if (mDialog != null) mDialog.dismiss();
        mDialog = builder.show();
    }

    private void clearPending() {
        if (mPending != null) {
            mPending.recycle();
            mPending = null;
        }
    }

    /** {resource id, thumbnail id} of the built-in wallpapers that are installed. */
    private List<int[]> builtInWallpapers() {
        final List<int[]> list = new ArrayList<>();
        final Resources res = wallpaperResources();
        if (res == null) return list;
        for (String name : BUILT_IN) {
            final int id = res.getIdentifier(name, "drawable", WALLPAPER_PKG);
            if (id == 0) continue;
            final int thumb = res.getIdentifier(name + "_small", "drawable", WALLPAPER_PKG);
            list.add(new int[] {id, thumb != 0 ? thumb : id});
        }
        return list;
    }

    private Resources wallpaperResources() {
        try {
            return getContext().getPackageManager().getResourcesForApplication(WALLPAPER_PKG);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** A grid of the built-in wallpapers; the picked one is cut out like a photo. */
    private void showBuiltIn() {
        final Context context = getContext();
        final Resources res = wallpaperResources();
        final List<int[]> wallpapers = builtInWallpapers();
        if (res == null || wallpapers.isEmpty()) return;
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = Math.round(16 * density);
        final int gap = Math.round(8 * density);
        final int height = Math.round(180 * density);
        final GridView grid = new GridView(context);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(pad, pad, pad, 0);
        grid.setClipToPadding(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return wallpapers.size();
            }

            @Override
            public Object getItem(int position) {
                return wallpapers.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final ImageView image = convertView instanceof ImageView
                        ? (ImageView) convertView : new ImageView(parent.getContext());
                image.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, height));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setClipToOutline(true);
                try {
                    image.setImageDrawable(res.getDrawable(wallpapers.get(position)[1], null));
                } catch (Resources.NotFoundException e) {
                    image.setImageDrawable(null);
                }
                return image;
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (mDialog != null) mDialog.dismiss();
            autoCutout(new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(WALLPAPER_PKG)
                    .appendPath(Integer.toString(wallpapers.get(position)[0]))
                    .build());
        });
        showDialog(new AlertDialog.Builder(context)
                .setTitle(R.string.picked_depth_source_builtin)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** Built-in wallpapers come with no cut-out of their own to pick. */
    private static boolean isBuiltIn(Uri photo) {
        return ContentResolver.SCHEME_ANDROID_RESOURCE.equals(photo.getScheme());
    }

    /** The original flow: the photo, then a cut-out of it made elsewhere. */
    private void showOwnCutoutHelp() {
        showDialog(new AlertDialog.Builder(getContext())
                .setTitle(R.string.picked_depth_pick_title)
                .setMessage(R.string.picked_depth_how)
                .setPositiveButton(R.string.picked_depth_choose_photo,
                        (d, w) -> pick(REQUEST_PHOTO, "image/*"))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void askOwnCutout(Uri photo) {
        mPhoto = photo;
        showDialog(new AlertDialog.Builder(getContext())
                .setTitle(R.string.picked_depth_pick_title)
                .setMessage(R.string.picked_depth_now_subject)
                .setPositiveButton(R.string.picked_depth_choose_subject,
                        (d, w) -> pick(REQUEST_SUBJECT, "image/png"))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void autoCutout(Uri photo) {
        clearPending();
        final AlertDialog working = new AlertDialog.Builder(getContext())
                .setMessage(R.string.picked_depth_auto_working)
                .setCancelable(false)
                .show();
        final Context app = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            final PickedDepthWallpaperStore.Cutout cutout =
                    PickedDepthWallpaperStore.autoCutout(app, photo);
            mHandler.post(() -> {
                working.dismiss();
                if (getActivity() == null || !isAdded()) {
                    cutout.recycle();
                    return;
                }
                if (cutout.code == PickedDepthWallpaperStore.OK) {
                    showPreview(photo, cutout);
                    return;
                }
                if (cutout.code == PickedDepthWallpaperStore.ERR_DECODE) {
                    Toast.makeText(getContext(), R.string.picked_depth_err_decode_photo,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                final AlertDialog.Builder error = new AlertDialog.Builder(getContext())
                        .setTitle(R.string.picked_depth_pick_title)
                        .setMessage(cutout.code == PickedDepthWallpaperStore.ERR_NO_SUBJECT
                                ? R.string.picked_depth_err_no_subject
                                : R.string.picked_depth_err_cutout);
                if (isBuiltIn(photo)) {
                    error.setPositiveButton(android.R.string.ok, null);
                } else {
                    error.setPositiveButton(R.string.picked_depth_own_cutout,
                            (d, w) -> askOwnCutout(photo))
                            .setNegativeButton(android.R.string.cancel, null);
                }
                showDialog(error);
            });
        });
    }

    private void showPreview(Uri photo, PickedDepthWallpaperStore.Cutout cutout) {
        mPending = cutout;
        final Context context = getContext();
        final int maxSide = context.getResources().getDisplayMetrics().heightPixels / 2;
        final float scale = Math.min(1f, maxSide / (float) cutout.layer.getHeight());
        // Its own bitmap: the cut-out is recycled after saving, maybe while the dialog fades.
        Bitmap preview = Bitmap.createScaledBitmap(cutout.layer,
                Math.max(1, Math.round(cutout.layer.getWidth() * scale)),
                Math.max(1, Math.round(cutout.layer.getHeight() * scale)), true);
        if (preview == cutout.layer) preview = cutout.layer.copy(Bitmap.Config.ARGB_8888, false);
        final ImageView image = new ImageView(context);
        image.setImageBitmap(preview);
        image.setAdjustViewBounds(true);
        image.setMaxHeight(maxSide);
        image.setBackgroundColor(0xff3c4043);
        final int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        final FrameLayout frame = new FrameLayout(context);
        frame.setPadding(pad, pad, pad, 0);
        frame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL));
        final AlertDialog.Builder dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.picked_depth_preview_title)
                .setMessage(R.string.picked_depth_preview_message)
                .setView(frame)
                .setPositiveButton(R.string.picked_depth_preview_set, (d, w) -> savePending())
                .setNegativeButton(android.R.string.cancel, (d, w) -> clearPending())
                .setOnCancelListener(d -> clearPending());
        if (!isBuiltIn(photo)) {
            dialog.setNeutralButton(R.string.picked_depth_own_cutout, (d, w) -> {
                clearPending();
                askOwnCutout(photo);
            });
        }
        showDialog(dialog);
    }

    private void savePending() {
        final PickedDepthWallpaperStore.Cutout cutout = mPending;
        mPending = null;
        if (cutout == null) return;
        final AlertDialog working = new AlertDialog.Builder(getContext())
                .setMessage(R.string.picked_depth_saving)
                .setCancelable(false)
                .show();
        final Context app = getContext().getApplicationContext();
        mExecutor.execute(() -> {
            final int result;
            try {
                result = PickedDepthWallpaperStore.save(app, cutout.wallpaper, cutout.layer);
            } finally {
                cutout.recycle();
            }
            mHandler.post(() -> {
                working.dismiss();
                if (getActivity() == null) return;
                Toast.makeText(getContext(), result == PickedDepthWallpaperStore.OK
                        ? R.string.picked_depth_done : R.string.picked_depth_err_save,
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        });
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
        if (requestCode != REQUEST_PHOTO && requestCode != REQUEST_SUBJECT
                && requestCode != REQUEST_PHOTO_AUTO) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_PHOTO_AUTO) {
            autoCutout(data.getData());
            return;
        }
        if (requestCode == REQUEST_PHOTO) {
            askOwnCutout(data.getData());
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
