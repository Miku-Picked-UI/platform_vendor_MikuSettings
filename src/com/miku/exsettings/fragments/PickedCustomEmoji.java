/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The "custom" emoji style: an emoji font the user picks from their files.
 *
 * The font and its customization XML go to Settings' device protected storage
 * (/data/user_de/0/com.android.settings/files/picked_emoji/, readable at boot before the
 * first unlock). With persist.sys.ax_emoji_style=custom, FontManagerService copies the font at
 * boot to /data/fonts/picked/, which every app can read (apps open system fonts by path), and
 * adds it in front of the stock emoji font, so emoji missing from it still show as Noto.
 */
final class PickedCustomEmoji {

    private static final String TAG = "PickedCustomEmoji";

    static final String STYLE = "custom";

    private static final String DIR = "picked_emoji";
    private static final String FONT_FILE = "PickedCustomEmoji.ttf";
    private static final String XML_FILE = "fonts_customization_emoji_custom.xml";
    private static final long MAX_SIZE = 64L * 1024 * 1024;

    private PickedCustomEmoji() {}

    private static File dir(Context context) {
        // Must match SystemFonts.CUSTOM_EMOJI_XML.
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), DIR);
    }

    /** Whether a custom emoji font has been installed. */
    static boolean isInstalled(Context context) {
        final File d = dir(context);
        return new File(d, FONT_FILE).isFile() && new File(d, XML_FILE).isFile();
    }

    /**
     * Copies the font at {@code uri} into place and writes its customization XML.
     *
     * @return true on success; false if the file is not a TrueType/OpenType font or could not
     *         be copied (the previous custom font, if any, is kept then)
     */
    static boolean install(Context context, Uri uri) {
        final File d = dir(context);
        if (!d.isDirectory() && !d.mkdirs()) {
            Log.e(TAG, "Cannot create " + d);
            return false;
        }
        final File tmp = new File(d, FONT_FILE + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return false;
            final byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SIZE) {
                    Log.e(TAG, "Font too large");
                    tmp.delete();
                    return false;
                }
                out.write(buf, 0, n);
            }
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Copy failed", e);
            tmp.delete();
            return false;
        }

        if (!isFont(tmp)) {
            Log.e(TAG, "Not a TrueType/OpenType font");
            tmp.delete();
            return false;
        }
        final File font = new File(d, FONT_FILE);
        if (!tmp.renameTo(font)) {
            tmp.delete();
            return false;
        }
        // system_server (same uid) reads these files; keep them private to the system uid.
        font.setReadable(true, true);

        final String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<fonts-modification version=\"1\">\n"
                + "    <family customizationType=\"new-locale-family\" operation=\"prepend\""
                + " lang=\"und-Zsye\">\n"
                + "        <font weight=\"400\" style=\"normal\">" + FONT_FILE + "</font>\n"
                + "    </family>\n"
                + "</fonts-modification>\n";
        try (OutputStream out = new FileOutputStream(new File(d, XML_FILE))) {
            out.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Writing the XML failed", e);
            return false;
        }
        return true;
    }

    /** Checks the sfnt header: TrueType (0x00010000 or "true") or OpenType CFF ("OTTO"). */
    private static boolean isFont(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            final byte[] h = new byte[4];
            if (in.read(h) != 4) return false;
            final boolean trueType = h[0] == 0 && h[1] == 1 && h[2] == 0 && h[3] == 0;
            final String tag = new String(h, StandardCharsets.US_ASCII);
            return trueType || "true".equals(tag) || "OTTO".equals(tag);
        } catch (IOException e) {
            return false;
        }
    }
}
