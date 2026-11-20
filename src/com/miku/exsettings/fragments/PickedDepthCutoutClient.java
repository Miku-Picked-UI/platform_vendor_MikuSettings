/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking client of vendor/miku/picked/depth_cutout CutoutService, which finds the subject of a
 * photo on device (U²-Net). Must not run on the main thread: the service connection is
 * delivered there.
 */
final class PickedDepthCutoutClient {

    private static final String TAG = "PickedDepthWallpaper";

    static final int SIZE = 320;

    private static final ComponentName SERVICE = new ComponentName(
            "com.mikupicked.depthcutout", "com.mikupicked.depthcutout.CutoutService");
    // Keep in sync with CutoutService.
    private static final int MSG_CUTOUT = 1;
    private static final int RESULT_OK = 0;
    private static final String KEY_RGB = "rgb";
    private static final String KEY_MASK = "mask";

    private static final long CONNECT_TIMEOUT_S = 10;
    private static final long RUN_TIMEOUT_S = 60;

    private PickedDepthCutoutClient() {}

    /** Whether the cut-out app is installed. */
    static boolean isAvailable(Context context) {
        return context.getPackageManager().resolveService(new Intent().setComponent(SERVICE), 0)
                != null;
    }

    /**
     * @param rgb SIZE*SIZE packed RGB bytes
     * @return SIZE*SIZE mask bytes (255 = subject), or null on failure
     */
    static byte[] run(Context context, byte[] rgb) {
        final AtomicReference<IBinder> binder = new AtomicReference<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder.set(service);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder.set(null);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                connected.countDown();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                connected.countDown();
            }
        };
        final HandlerThread thread = new HandlerThread(TAG);
        boolean bindCalled = false;
        try {
            bindCalled = true;
            if (!context.bindService(new Intent().setComponent(SERVICE), conn,
                    Context.BIND_AUTO_CREATE)) {
                Log.w(TAG, "Cannot bind the cut-out service");
                return null;
            }
            if (!connected.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS) || binder.get() == null) {
                Log.w(TAG, "Cut-out service did not connect");
                return null;
            }
            thread.start();
            final AtomicReference<byte[]> mask = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Messenger replyTo = new Messenger(new Handler(thread.getLooper(), reply -> {
                if (reply.what == MSG_CUTOUT) {
                    if (reply.arg1 == RESULT_OK) {
                        final byte[] m = reply.getData().getByteArray(KEY_MASK);
                        if (m != null && m.length == SIZE * SIZE) mask.set(m);
                    }
                    done.countDown();
                }
                return true;
            }));
            final Message msg = Message.obtain(null, MSG_CUTOUT);
            final Bundle data = new Bundle();
            data.putByteArray(KEY_RGB, rgb);
            msg.setData(data);
            msg.replyTo = replyTo;
            new Messenger(binder.get()).send(msg);
            if (!done.await(RUN_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "Cut-out timed out");
                return null;
            }
            if (mask.get() == null) Log.w(TAG, "Cut-out service failed");
            return mask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Cut-out failed", e);
            return null;
        } finally {
            // Also after a failed bindService, which still registers the connection.
            if (bindCalled) {
                try {
                    context.unbindService(conn);
                } catch (IllegalArgumentException e) {
                    // not registered
                }
            }
            thread.quitSafely();
        }
    }
}
