/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Display;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Boot animation picker storage, import and preview.
 *
 * persist.sys.picked.bootanim selects the animation bootanimation plays (see
 * frameworks/base/cmds/bootanimation): "" = Miku default, "custom" = the user's import in
 * /data/misc/picked_bootanim/bootanimation.zip, anything else = a bundled
 * /product/media/picked_bootanim/&lt;style&gt;.zip.
 *
 * Imports may be a bootanimation.zip (re-packed with STORED entries, which bootanimation
 * needs), a GIF or a video; GIFs and videos are converted to frames sized to the screen.
 */
final class PickedBootAnimationStore {

    private static final String TAG = "PickedBootAnimation";

    static final String PROP_STYLE = "persist.sys.picked.bootanim";
    static final String PROP_CLOCK = "persist.sys.bootanim.clock.enabled";
    static final String STYLE_DEFAULT = "";
    static final String STYLE_CUSTOM = "custom";

    static final File CUSTOM_FILE = new File("/data/misc/picked_bootanim/bootanimation.zip");
    static final File BUNDLED_DIR = new File("/product/media/picked_bootanim");
    static final File DEFAULT_FILE = new File("/product/media/bootanimation.zip");

    private static final long MAX_IMPORT_BYTES = 150L * 1024 * 1024;
    private static final int MAX_FPS = 30;
    private static final long MAX_CLIP_MS = 10_000;
    private static final int JPEG_QUALITY = 85;
    // Clock (when enabled) 260 px above the bottom edge, horizontally centred.
    private static final String CLOCK_POS = " c 260";

    private PickedBootAnimationStore() {}

    static String getStyle() {
        return SystemProperties.get(PROP_STYLE, STYLE_DEFAULT);
    }

    static void setStyle(String style) {
        SystemProperties.set(PROP_STYLE, style);
    }

    static boolean hasCustom() {
        return CUSTOM_FILE.isFile();
    }

    static void removeCustom() {
        CUSTOM_FILE.delete();
        if (STYLE_CUSTOM.equals(getStyle())) setStyle(STYLE_DEFAULT);
    }

    /** Bundled style names (zip basenames), sorted. */
    static List<String> bundledStyles() {
        final List<String> out = new ArrayList<>();
        final File[] files = BUNDLED_DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                final String n = f.getName();
                if (n.endsWith(".zip") && !n.contains("/")) out.add(n.substring(0, n.length() - 4));
            }
        }
        Collections.sort(out);
        return out;
    }

    static File fileForStyle(String style) {
        if (STYLE_DEFAULT.equals(style)) return DEFAULT_FILE;
        if (STYLE_CUSTOM.equals(style)) return CUSTOM_FILE;
        return new File(BUNDLED_DIR, style + ".zip");
    }

    // ---------------------------------------------------------------------------------------
    // Import
    // ---------------------------------------------------------------------------------------

    /** Imports {@code uri} as the custom animation. Runs on a background thread. */
    static boolean importFrom(Context context, Uri uri) {
        final ContentResolver cr = context.getContentResolver();
        final String type = cr.getType(uri);
        final File dir = CUSTOM_FILE.getParentFile();
        final File tmp = new File(dir, "import.tmp");
        final File out = new File(dir, "bootanimation.zip.new");
        try {
            final boolean ok;
            if (type != null && type.startsWith("video/")) {
                ok = convertVideo(context, uri, out);
            } else {
                // Copy first: zips need random access and GIFs are decoded from a stream.
                copy(cr, uri, tmp);
                if (isZip(tmp)) {
                    ok = repackZip(tmp, out);
                } else if (isGif(tmp)) {
                    ok = convertGif(context, tmp, out);
                } else {
                    Log.w(TAG, "Unsupported import " + type);
                    ok = false;
                }
            }
            if (!ok || !out.renameTo(CUSTOM_FILE)) {
                out.delete();
                return false;
            }
            CUSTOM_FILE.setReadable(true, false);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Import failed", e);
            out.delete();
            return false;
        } finally {
            tmp.delete();
        }
    }

    private static void copy(ContentResolver cr, Uri uri, File dst) throws IOException {
        try (InputStream in = cr.openInputStream(uri);
             FileOutputStream os = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            final byte[] buf = new byte[256 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_IMPORT_BYTES) throw new IOException("File too large");
                os.write(buf, 0, n);
            }
        }
    }

    private static byte[] head(File f, int n) throws IOException {
        final byte[] b = new byte[n];
        try (InputStream in = new java.io.FileInputStream(f)) {
            return in.read(b) == n ? b : new byte[0];
        }
    }

    private static boolean isZip(File f) throws IOException {
        final byte[] b = head(f, 4);
        return b.length == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    private static boolean isGif(File f) throws IOException {
        final byte[] b = head(f, 3);
        return b.length == 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
    }

    /** Copies a bootanimation.zip, storing every entry uncompressed. */
    private static boolean repackZip(File src, File dst) throws IOException {
        try (ZipFile zip = new ZipFile(src)) {
            if (zip.getEntry("desc.txt") == null) {
                Log.w(TAG, "Not a boot animation: no desc.txt");
                return false;
            }
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dst))) {
                final Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry e = entries.nextElement();
                    final String name = e.getName();
                    if (name.startsWith("/") || name.contains("..")) return false;
                    if (e.isDirectory()) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        putStored(zos, name, readAll(in));
                    }
                }
            }
        }
        return true;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void putStored(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        final ZipEntry e = new ZipEntry(name);
        final CRC32 crc = new CRC32();
        crc.update(data);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /** Screen size in portrait (bootanimation draws frames 1:1, centred). */
    private static int[] screenSize(Context context) {
        // DisplayManager works from the application context (WindowManager needs a visual one).
        final Point p = new Point();
        context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                .getRealSize(p);
        return new int[] {Math.min(p.x, p.y), Math.max(p.x, p.y)};
    }

    /** Fits {@code w x h} into the screen, keeping the aspect ratio. Even sizes only. */
    private static int[] fit(Context context, int w, int h) {
        final int[] screen = screenSize(context);
        float scale = Math.min(screen[0] / (float) w, screen[1] / (float) h);
        return new int[] {Math.max(2, Math.round(w * scale) & ~1),
                Math.max(2, Math.round(h * scale) & ~1)};
    }

    private static final class FrameWriter implements AutoCloseable {
        private final ZipOutputStream mZos;
        private final int mWidth, mHeight;
        private final Bitmap mFrame;
        private final Canvas mCanvas;
        private int mCount;

        FrameWriter(File dst, int width, int height, int fps) throws IOException {
            mZos = new ZipOutputStream(new FileOutputStream(dst));
            mWidth = width;
            mHeight = height;
            mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mCanvas = new Canvas(mFrame);
            putStored(mZos, "desc.txt", (width + " " + height + " " + fps + "\n"
                    + "p 0 0 part0 #000000" + CLOCK_POS + "\n").getBytes(StandardCharsets.UTF_8));
        }

        Canvas begin() {
            mCanvas.drawColor(Color.BLACK);
            return mCanvas;
        }

        void add(Bitmap src) throws IOException {
            begin().drawBitmap(src, null, new Rect(0, 0, mWidth, mHeight), null);
            commit();
        }

        void commit() throws IOException {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            mFrame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            putStored(mZos, String.format("part0/%05d.jpg", mCount++), bos.toByteArray());
        }

        int count() {
            return mCount;
        }

        @Override
        public void close() throws IOException {
            mFrame.recycle();
            mZos.close();
        }
    }

    private static boolean convertVideo(Context context, Uri uri, File dst) throws IOException {
        final MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, uri);
            final long durationMs = parseLong(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            int w = (int) parseLong(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int h = (int) parseLong(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            final int rot = (int) parseLong(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rot == 90 || rot == 270) {
                final int t = w;
                w = h;
                h = t;
            }
            if (durationMs <= 0 || w <= 0 || h <= 0) return false;
            final long frameCount = parseLong(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT), 0);
            final int srcFps = frameCount > 0
                    ? Math.round(frameCount * 1000f / durationMs) : MAX_FPS;
            final int fps = Math.max(1, Math.min(MAX_FPS, srcFps));
            final long clipMs = Math.min(durationMs, MAX_CLIP_MS);
            final int[] size = fit(context, w, h);
            try (FrameWriter fw = new FrameWriter(dst, size[0], size[1], fps)) {
                for (long t = 0; t < clipMs; t += 1000 / fps) {
                    final Bitmap b = mmr.getScaledFrameAtTime(t * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST, size[0], size[1]);
                    if (b == null) continue;
                    fw.add(b);
                    b.recycle();
                }
                return fw.count() > 0;
            }
        } finally {
            mmr.release();
        }
    }

    @SuppressWarnings("deprecation") // Movie is still the only frame-addressable GIF decoder.
    private static boolean convertGif(Context context, File gif, File dst) throws IOException {
        final Movie movie;
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(gif))) {
            movie = Movie.decodeStream(in);
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) return false;
        final int durationMs = movie.duration() > 0 ? movie.duration() : 1000;
        final int fps = 25;
        final int[] size = fit(context, movie.width(), movie.height());
        final float sx = size[0] / (float) movie.width();
        final float sy = size[1] / (float) movie.height();
        try (FrameWriter fw = new FrameWriter(dst, size[0], size[1], fps)) {
            for (int t = 0; t < Math.min(durationMs, MAX_CLIP_MS); t += 1000 / fps) {
                movie.setTime(t);
                final Canvas c = fw.begin();
                c.save();
                c.scale(sx, sy);
                movie.draw(c, 0, 0);
                c.restore();
                fw.commit();
            }
            return fw.count() > 0;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return s != null ? Long.parseLong(s.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------------------------

    /** Preview frames of every part (looping parts once), downscaled to {@code maxSide}. */
    static AnimationDrawable loadPreview(Context context, File file, int maxSide) {
        final int maxFrames = 180;
        try (ZipFile zip = new ZipFile(file)) {
            final ZipEntry descEntry = zip.getEntry("desc.txt");
            if (descEntry == null) return null;
            final String desc;
            try (InputStream in = zip.getInputStream(descEntry)) {
                desc = new String(readAll(in), StandardCharsets.UTF_8);
            }
            final String[] lines = desc.split("\n");
            final String[] top = lines[0].trim().split("\\s+");
            final int width = Integer.parseInt(top[0]);
            final int height = Integer.parseInt(top[1]);
            final int fps = Math.max(1, Integer.parseInt(top[2]));
            int sample = 1;
            while (Math.max(width, height) / (sample * 2) >= maxSide) sample *= 2;

            final List<String> parts = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                final String[] f = lines[i].trim().split("\\s+");
                if (f.length >= 4 && f[0].length() == 1 && "cpf".indexOf(f[0].charAt(0)) >= 0) {
                    parts.add(f[3] + "/");
                }
            }
            final List<String> names = new ArrayList<>();
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry e = entries.nextElement();
                if (!e.isDirectory()) names.add(e.getName());
            }
            Collections.sort(names);

            final AnimationDrawable anim = new AnimationDrawable();
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            int added = 0;
            for (String part : parts) {
                for (String name : names) {
                    if (added >= maxFrames) break;
                    if (!name.startsWith(part) || name.indexOf('/', part.length()) >= 0) continue;
                    final String base = name.substring(part.length());
                    if (base.equals("trim.txt") || base.equals("audio.wav")) continue;
                    try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                        final Bitmap b = BitmapFactory.decodeStream(in, null, opts);
                        if (b == null) continue;
                        anim.addFrame(new BitmapDrawable(context.getResources(), b), 1000 / fps);
                        added++;
                    }
                }
            }
            anim.setOneShot(false);
            return added > 0 ? anim : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Preview failed for " + file, e);
            return null;
        }
    }
}
