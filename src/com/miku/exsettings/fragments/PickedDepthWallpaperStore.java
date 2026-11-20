/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Depth wallpaper processing (SystemUI picked/depthwallpaper/DepthWallpaperSection draws it).
 *
 * Automatic ({@link #autoCutout}): the user picks just a photo. Its subject is found on device by
 * vendor/miku/picked/depth_cutout (U²-Net salient object segmentation, see
 * PickedDepthCutoutClient). The model sees the whole photo letterboxed into 320x320 (keeping the
 * aspect ratio and all of the context matters a lot more than resolution), the part of the mask
 * under the screen crop is scaled up and its edges are snapped to the photo with a guided filter.
 *
 * Own cut-out ({@link #apply}): the user picks a photo and a cut-out of its subject (a transparent PNG, e.g. from the
 * gallery's "copy subject"). Cut-outs are usually cropped to the subject, so it is found in the
 * photo by template matching (coarse search on a small copy, then refined at full size). The
 * photo and a same-sized subject layer are then centre-cropped to the screen's aspect ratio and
 * scaled to the screen size: the photo becomes the lockscreen wallpaper, the layer is stored in
 * Settings' device protected storage, where PickedDepthProvider serves it to SystemUI.
 */
final class PickedDepthWallpaperStore {

    private static final String TAG = "PickedDepthWallpaper";

    static final String KEY_ENABLED = "picked_depth_wallpaper_enabled";
    static final String KEY_VERSION = "picked_depth_wallpaper_version";

    static final int OK = 0;
    static final int ERR_DECODE = 1;
    static final int ERR_NOT_FOUND = 2;
    static final int ERR_SAVE = 3;
    static final int ERR_CUTOUT = 4;
    static final int ERR_NO_SUBJECT = 5;

    private static final int MAX_DECODE_SIDE = 3000;
    private static final int COARSE_SIDE = 320;
    private static final int COARSE_SAMPLES = 900;
    private static final int FINE_SAMPLES = 2500;
    // Mean absolute luma difference over the subject above which the cut-out is "not found".
    private static final int MAX_MEAN_DIFF = 40;

    // Automatic cut-out.
    private static final int LETTERBOX_COLOR = 0xff7c7468; // about the ImageNet mean
    private static final int GUIDE_RADIUS = 8; // at half the screen size
    private static final float GUIDE_EPS = 1e-3f;
    // Alpha curve after the guided filter: below LOW is background, above LOW + RANGE subject.
    private static final float ALPHA_LOW = 0.25f;
    private static final float ALPHA_RANGE = 0.5f;
    // At least this share of the screen must be subject.
    private static final float MIN_SUBJECT_SHARE = 0.003f;

    /** Screen-sized wallpaper and subject layer from {@link #autoCutout}, or an error code. */
    static final class Cutout {
        final int code;
        final Bitmap wallpaper;
        final Bitmap layer;

        Cutout(int code, Bitmap wallpaper, Bitmap layer) {
            this.code = code;
            this.wallpaper = wallpaper;
            this.layer = layer;
        }

        void recycle() {
            if (wallpaper != null) wallpaper.recycle();
            if (layer != null) layer.recycle();
        }
    }

    private PickedDepthWallpaperStore() {}

    static File subjectFile(Context context) {
        return new File(new File(context.createDeviceProtectedStorageContext().getFilesDir(),
                "picked_depth"), "subject.png");
    }

    static boolean hasSubject(Context context) {
        return subjectFile(context).isFile();
    }

    static boolean isEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), KEY_ENABLED, 0) == 1;
    }

    static void setEnabled(Context context, boolean enabled) {
        Settings.Secure.putInt(context.getContentResolver(), KEY_ENABLED, enabled ? 1 : 0);
    }

    /** Runs on a background thread. */
    static int apply(Context context, Uri photoUri, Uri subjectUri) {
        final Bitmap photo;
        final Bitmap subject;
        final int[] photoSize = new int[2];
        try {
            photo = decode(context, photoUri, 0, photoSize);
            // Decode the cut-out at the same scale as the photo.
            final int sample = Math.max(1, (int) Math.ceil(
                    Math.max(photoSize[0], photoSize[1]) / (double) MAX_DECODE_SIDE));
            subject = decode(context, subjectUri, sample, null);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Decode failed", e);
            return ERR_DECODE;
        }
        if (photo == null || subject == null || !subject.hasAlpha()) return ERR_DECODE;
        if (subject.getWidth() > photo.getWidth() || subject.getHeight() > photo.getHeight()) {
            return ERR_NOT_FOUND;
        }

        final Point offset = locate(photo, subject);
        if (offset == null) return ERR_NOT_FOUND;

        final Bitmap layer = Bitmap.createBitmap(photo.getWidth(), photo.getHeight(),
                Bitmap.Config.ARGB_8888);
        new Canvas(layer).drawBitmap(subject, offset.x, offset.y, null);
        subject.recycle();

        final Point screen = screenSize(context);
        final Rect crop = centerCrop(photo.getWidth(), photo.getHeight(), screen.x, screen.y);
        final Bitmap wallpaper = scaleCrop(photo, crop, screen);
        final Bitmap subjectLayer = scaleCrop(layer, crop, screen);
        photo.recycle();
        layer.recycle();

        try {
            return save(context, wallpaper, subjectLayer);
        } finally {
            wallpaper.recycle();
            subjectLayer.recycle();
        }
    }

    /**
     * Finds the subject of the photo automatically. Runs on a background thread. On success the
     * caller owns the bitmaps: pass them to {@link #save}, then recycle them.
     */
    static Cutout autoCutout(Context context, Uri photoUri) {
        final Bitmap photo;
        try {
            photo = decode(context, photoUri, 0, null);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Decode failed", e);
            return new Cutout(ERR_DECODE, null, null);
        }
        if (photo == null) return new Cutout(ERR_DECODE, null, null);
        final int w = photo.getWidth(), h = photo.getHeight();
        final Point screen = screenSize(context);
        final Rect crop = centerCrop(w, h, screen.x, screen.y);
        final Bitmap wallpaper = scaleCrop(photo, crop, screen);

        // Model input: the whole photo letterboxed, as packed RGB.
        final int size = PickedDepthCutoutClient.SIZE;
        final float sc = size / (float) Math.max(w, h);
        final int nw = Math.max(1, Math.round(w * sc)), nh = Math.max(1, Math.round(h * sc));
        final int ox = (size - nw) / 2, oy = (size - nh) / 2;
        final Bitmap input = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas ic = new Canvas(input);
        ic.drawColor(LETTERBOX_COLOR);
        ic.drawBitmap(photo, null, new Rect(ox, oy, ox + nw, oy + nh),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        photo.recycle();
        final int[] px = new int[size * size];
        input.getPixels(px, 0, size, 0, 0, size, size);
        input.recycle();
        final byte[] rgb = new byte[size * size * 3];
        for (int i = 0; i < px.length; i++) {
            rgb[i * 3] = (byte) (px[i] >> 16);
            rgb[i * 3 + 1] = (byte) (px[i] >> 8);
            rgb[i * 3 + 2] = (byte) px[i];
        }

        final byte[] mask = PickedDepthCutoutClient.run(context, rgb);
        if (mask == null) {
            wallpaper.recycle();
            return new Cutout(ERR_CUTOUT, null, null);
        }

        // The mask under the screen crop, at half the screen size.
        final int gw = Math.max(1, screen.x / 2), gh = Math.max(1, screen.y / 2);
        for (int i = 0; i < px.length; i++) {
            final int m = mask[i] & 0xff;
            px[i] = 0xff000000 | (m << 16) | (m << 8) | m;
        }
        final Bitmap maskFull = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        final Bitmap maskCrop = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        final Matrix matrix = new Matrix();
        matrix.setRectToRect(new RectF(ox + crop.left * sc, oy + crop.top * sc,
                        ox + crop.right * sc, oy + crop.bottom * sc),
                new RectF(0, 0, gw, gh), Matrix.ScaleToFit.FILL);
        new Canvas(maskCrop).drawBitmap(maskFull, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        maskFull.recycle();

        final int n = gw * gh;
        final int[] gpx = new int[n];
        maskCrop.getPixels(gpx, 0, gw, 0, 0, gw, gh);
        maskCrop.recycle();
        final float[] p = new float[n];
        for (int i = 0; i < n; i++) p[i] = (gpx[i] & 0xff) / 255f;
        final Bitmap guideBmp = Bitmap.createScaledBitmap(wallpaper, gw, gh, true);
        guideBmp.getPixels(gpx, 0, gw, 0, 0, gw, gh);
        if (guideBmp != wallpaper) guideBmp.recycle();
        final float[] guide = new float[n];
        for (int i = 0; i < n; i++) {
            final int c = gpx[i];
            guide[i] = ((((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29)
                    >> 8) / 255f;
        }
        final float[] q = guidedFilter(guide, p, gw, gh, GUIDE_RADIUS, GUIDE_EPS);

        int subject = 0;
        for (int i = 0; i < n; i++) {
            final float a = Math.max(0f, Math.min(1f, (q[i] - ALPHA_LOW) / ALPHA_RANGE));
            if (a >= 0.5f) subject++;
            gpx[i] = Math.round(a * 255f) << 24; // black with the mask as alpha
        }
        if (subject < n * MIN_SUBJECT_SHARE) {
            wallpaper.recycle();
            return new Cutout(ERR_NO_SUBJECT, null, null);
        }

        // Subject layer: the wallpaper with the mask, scaled up, as alpha.
        final Bitmap alphaSmall = Bitmap.createBitmap(gpx, gw, gh, Bitmap.Config.ARGB_8888);
        final Bitmap alpha = Bitmap.createScaledBitmap(alphaSmall, screen.x, screen.y, true);
        if (alpha != alphaSmall) alphaSmall.recycle();
        final Bitmap layer = wallpaper.copy(Bitmap.Config.ARGB_8888, true);
        final Paint dstIn = new Paint(Paint.FILTER_BITMAP_FLAG);
        dstIn.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        new Canvas(layer).drawBitmap(alpha, 0, 0, dstIn);
        alpha.recycle();
        return new Cutout(OK, wallpaper, layer);
    }

    /**
     * Stores the screen-sized subject layer and sets the screen-sized wallpaper as the lockscreen
     * wallpaper. Runs on a background thread; does not recycle the bitmaps.
     */
    static int save(Context context, Bitmap wallpaper, Bitmap subjectLayer) {
        final File file = subjectFile(context);
        final File tmp = new File(file.getParentFile(), "subject.png.tmp");
        try {
            file.getParentFile().mkdirs();
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                subjectLayer.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
            WallpaperManager.getInstance(context).setBitmap(wallpaper, null, true,
                    WallpaperManager.FLAG_LOCK);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Saving failed", e);
            tmp.delete();
            return ERR_SAVE;
        }
        Settings.Secure.putString(context.getContentResolver(), KEY_VERSION,
                Long.toString(System.currentTimeMillis()));
        setEnabled(context, true);
        return OK;
    }

    // ---------------------------------------------------------------------------------------
    // Guided filter (He, Sun and Tang), grey guide
    // ---------------------------------------------------------------------------------------

    /** Edge-preserving smoothing of {@code p} that follows the edges of {@code guide}. */
    private static float[] guidedFilter(float[] guide, float[] p, int w, int h, int r,
            float eps) {
        final int n = w * h;
        final double[] integral = new double[(w + 1) * (h + 1)];
        final float[] tmp = new float[n];
        final float[] meanI = boxMean(guide, w, h, r, integral);
        final float[] meanP = boxMean(p, w, h, r, integral);
        for (int i = 0; i < n; i++) tmp[i] = guide[i] * p[i];
        final float[] corrIp = boxMean(tmp, w, h, r, integral);
        for (int i = 0; i < n; i++) tmp[i] = guide[i] * guide[i];
        final float[] corrII = boxMean(tmp, w, h, r, integral);
        // a into corrIp, b into meanP
        for (int i = 0; i < n; i++) {
            final float varI = corrII[i] - meanI[i] * meanI[i];
            final float covIp = corrIp[i] - meanI[i] * meanP[i];
            final float a = covIp / (varI + eps);
            corrIp[i] = a;
            meanP[i] = meanP[i] - a * meanI[i];
        }
        final float[] meanA = boxMean(corrIp, w, h, r, integral);
        final float[] meanB = boxMean(meanP, w, h, r, integral);
        for (int i = 0; i < n; i++) meanA[i] = meanA[i] * guide[i] + meanB[i];
        return meanA;
    }

    /** Mean over the (2r+1)^2 window around each pixel, clipped at the edges. */
    private static float[] boxMean(float[] a, int w, int h, int r, double[] integral) {
        final int stride = w + 1;
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += a[y * w + x];
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row;
            }
        }
        final float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            final int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                final int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                final double sum = integral[y1 * stride + x1] - integral[y0 * stride + x1]
                        - integral[y1 * stride + x0] + integral[y0 * stride + x0];
                out[y * w + x] = (float) (sum / ((y1 - y0) * (x1 - x0)));
            }
        }
        return out;
    }

    /**
     * Decodes {@code uri} as a software ARGB bitmap, honouring EXIF orientation.
     * {@code sample} 0 = pick one so the longest side is at most MAX_DECODE_SIDE.
     */
    private static Bitmap decode(Context context, Uri uri, int sample, int[] outSize)
            throws IOException {
        final ImageDecoder.Source src = ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            final int w = info.getSize().getWidth();
            final int h = info.getSize().getHeight();
            if (outSize != null) {
                outSize[0] = w;
                outSize[1] = h;
            }
            final int sm = sample > 0 ? sample
                    : Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) MAX_DECODE_SIDE));
            decoder.setTargetSampleSize(sm);
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);
        });
    }

    private static Point screenSize(Context context) {
        final Point p = new Point();
        context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                .getRealSize(p);
        return new Point(Math.min(p.x, p.y), Math.max(p.x, p.y));
    }

    private static Rect centerCrop(int w, int h, int sw, int sh) {
        final float target = sw / (float) sh;
        if (w / (float) h > target) {
            final int cw = Math.round(h * target);
            return new Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h);
        }
        final int ch = Math.round(w / target);
        return new Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch);
    }

    private static Bitmap scaleCrop(Bitmap src, Rect crop, Point size) {
        final Bitmap out = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, crop, new Rect(0, 0, size.x, size.y),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Template matching
    // ---------------------------------------------------------------------------------------

    private static Point locate(Bitmap photo, Bitmap subject) {
        if (subject.getWidth() == photo.getWidth() && subject.getHeight() == photo.getHeight()) {
            return new Point(0, 0); // already a full-size layer
        }
        // Coarse: both scaled so the photo's longest side is COARSE_SIDE.
        final float scale = Math.min(1f,
                COARSE_SIDE / (float) Math.max(photo.getWidth(), photo.getHeight()));
        final Bitmap cp = scaled(photo, scale);
        final Bitmap cs = scaled(subject, scale);
        final int[] coarse = search(luma(cp), cp.getWidth(), cp.getHeight(), cs,
                0, cp.getWidth() - cs.getWidth(), 0, cp.getHeight() - cs.getHeight(),
                COARSE_SAMPLES);
        if (cp != photo) cp.recycle();
        if (cs != subject) cs.recycle();
        if (coarse == null) return null;

        // Fine: full size around the coarse hit.
        final int r = (int) Math.ceil(1 / scale) + 2;
        final int cx = Math.round(coarse[0] / scale);
        final int cy = Math.round(coarse[1] / scale);
        final int maxX = photo.getWidth() - subject.getWidth();
        final int maxY = photo.getHeight() - subject.getHeight();
        final int[] fine = search(luma(photo), photo.getWidth(), photo.getHeight(), subject,
                Math.max(0, cx - r), Math.min(maxX, cx + r),
                Math.max(0, cy - r), Math.min(maxY, cy + r), FINE_SAMPLES);
        if (fine == null || fine[2] > MAX_MEAN_DIFF) {
            Log.w(TAG, "Cut-out not found in the photo"
                    + (fine != null ? " (mean diff " + fine[2] + ")" : ""));
            return null;
        }
        return new Point(fine[0], fine[1]);
    }

    private static Bitmap scaled(Bitmap b, float scale) {
        if (scale >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                Math.max(1, Math.round(b.getHeight() * scale)), true);
    }

    private static int[] luma(Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight();
        final int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            final int c = px[i];
            px[i] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
        }
        return px;
    }

    /**
     * Exhaustive search of offsets [x0..x1] x [y0..y1] minimising the sum of absolute luma
     * differences over up to {@code maxSamples} opaque subject pixels.
     *
     * @return {x, y, meanDiff} or null if the subject has no opaque pixels
     */
    private static int[] search(int[] photo, int pw, int ph, Bitmap subject,
            int x0, int x1, int y0, int y1, int maxSamples) {
        final int sw = subject.getWidth(), sh = subject.getHeight();
        final int[] spx = new int[sw * sh];
        subject.getPixels(spx, 0, sw, 0, 0, sw, sh);
        int opaque = 0;
        for (int c : spx) if ((c >>> 24) > 200) opaque++;
        if (opaque == 0 || x1 < x0 || y1 < y0) return null;
        final int stride = Math.max(1, opaque / maxSamples);
        final int n = Math.min(maxSamples, (opaque + stride - 1) / stride);
        final int[] sx = new int[n], sy = new int[n], sl = new int[n];
        int k = 0, seen = 0;
        for (int i = 0; i < spx.length && k < n; i++) {
            final int c = spx[i];
            if ((c >>> 24) <= 200) continue;
            if (seen++ % stride != 0) continue;
            sx[k] = i % sw;
            sy[k] = i / sw;
            sl[k] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
            k++;
        }
        long best = Long.MAX_VALUE;
        int bx = x0, by = y0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                long sum = 0;
                for (int i = 0; i < k && sum < best; i++) {
                    sum += Math.abs(photo[(y + sy[i]) * pw + x + sx[i]] - sl[i]);
                }
                if (sum < best) {
                    best = sum;
                    bx = x;
                    by = y;
                }
            }
        }
        return new int[] {bx, by, (int) (best / k)};
    }
}
