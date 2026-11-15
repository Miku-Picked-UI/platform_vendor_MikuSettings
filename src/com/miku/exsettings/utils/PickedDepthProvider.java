/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.utils;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the depth wallpaper subject layer (see PickedDepthWallpaperStore) to SystemUI, read
 * only. Guarded by android.permission.STATUS_BAR_SERVICE in the manifest and direct boot aware,
 * so the lockscreen can load it before the first unlock (the file is in device protected
 * storage).
 */
public class PickedDepthProvider extends ContentProvider {

    private static final String SUBJECT = "subject.png";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !SUBJECT.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException(uri.toString());
        }
        final File file = new File(new File(getContext().createDeviceProtectedStorageContext()
                .getFilesDir(), "picked_depth"), SUBJECT);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
