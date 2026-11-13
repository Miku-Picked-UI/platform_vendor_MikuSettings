/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.utils;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import vendor.lineage.health.IWirelessFastCharge;
import vendor.lineage.health.WirelessFastChargeMode;
import vendor.lineage.powershare.IPowerShare;

/**
 * [Miku-Picked UI] Fast wireless charging and Wireless PowerShare (reverse wireless charging)
 * through LineageOS' health and PowerShare HALs, which this tree ships without lineage-sdk.
 * Every method is a quick binder call and copes with the HAL being absent.
 */
public final class PickedCharging {
    private static final String TAG = "PickedCharging";
    private static final String WIRELESS_FAST_CHARGE =
            "vendor.lineage.health.IWirelessFastCharge/default";
    private static final String POWERSHARE = "vendor.lineage.powershare.IPowerShare/default";

    private PickedCharging() {}

    private static IBinder service(String name) {
        if (!ServiceManager.isDeclared(name)) return null;
        try {
            return ServiceManager.waitForDeclaredService(name);
        } catch (RuntimeException e) {
            Log.e(TAG, name + " unavailable", e);
            return null;
        }
    }

    private static IWirelessFastCharge wirelessFastCharge() {
        final IBinder b = service(WIRELESS_FAST_CHARGE);
        return b == null ? null : IWirelessFastCharge.Stub.asInterface(b);
    }

    private static IPowerShare powerShare() {
        final IBinder b = service(POWERSHARE);
        return b == null ? null : IPowerShare.Stub.asInterface(b);
    }

    /** Whether the device can switch fast wireless charging. */
    public static boolean isWirelessFastChargeSupported() {
        final IWirelessFastCharge hal = wirelessFastCharge();
        if (hal == null) return false;
        try {
            return (hal.getSupportedWirelessFastChargeModes()
                    & WirelessFastChargeMode.WIRELESS_FAST_CHARGE) != 0;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "getSupportedWirelessFastChargeModes", e);
            return false;
        }
    }

    public static boolean isWirelessFastChargeEnabled() {
        final IWirelessFastCharge hal = wirelessFastCharge();
        if (hal == null) return false;
        try {
            return hal.getWirelessFastChargeMode() == WirelessFastChargeMode.WIRELESS_FAST_CHARGE;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "getWirelessFastChargeMode", e);
            return false;
        }
    }

    /** Returns whether the HAL took the change. */
    public static boolean setWirelessFastChargeEnabled(boolean enabled) {
        final IWirelessFastCharge hal = wirelessFastCharge();
        if (hal == null) return false;
        try {
            final int mode = enabled
                    ? WirelessFastChargeMode.WIRELESS_FAST_CHARGE : WirelessFastChargeMode.NONE;
            return hal.setWirelessFastChargeMode(mode) == mode;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "setWirelessFastChargeMode", e);
            return false;
        }
    }

    public static boolean isPowerShareSupported() {
        return powerShare() != null;
    }

    public static boolean isPowerShareEnabled() {
        final IPowerShare hal = powerShare();
        if (hal == null) return false;
        try {
            return hal.isEnabled();
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "isEnabled", e);
            return false;
        }
    }

    public static boolean setPowerShareEnabled(boolean enabled) {
        final IPowerShare hal = powerShare();
        if (hal == null) return false;
        try {
            hal.setEnabled(enabled);
            return hal.isEnabled() == enabled;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "setEnabled", e);
            return false;
        }
    }

    /** Battery level (%) below which PowerShare stops, or -1 if unknown. */
    public static int getPowerShareMinBattery() {
        final IPowerShare hal = powerShare();
        if (hal == null) return -1;
        try {
            return hal.getMinBattery();
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "getMinBattery", e);
            return -1;
        }
    }

    public static void setPowerShareMinBattery(int percent) {
        final IPowerShare hal = powerShare();
        if (hal == null) return;
        try {
            hal.setMinBattery(percent);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "setMinBattery", e);
        }
    }
}
