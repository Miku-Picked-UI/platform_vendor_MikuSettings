/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.preferences;

import android.content.Context;
import android.util.AttributeSet;

import java.util.Locale;

/**
 * A Settings.Secure seek bar that stores an int but shows it divided by {@link #setDivisor}, for
 * fractional tunables (e.g. 255 is shown as "2.55 Hz").
 */
public class CustomSecureDecimalSeekBarPreference extends CustomSecureSeekBarPreference {

    private int mDivisor = 1;
    private int mDecimals = 0;

    public CustomSecureDecimalSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setDivisor(int divisor) {
        mDivisor = Math.max(1, divisor);
        mDecimals = 0;
        for (int d = mDivisor; d >= 10; d /= 10) mDecimals++;
        updateValueViews();
    }

    @Override
    protected String getTextValue(int v) {
        if (mDivisor == 1) return super.getTextValue(v);
        return (mShowSign && v > 0 ? "+" : "")
                + String.format(Locale.getDefault(), "%." + mDecimals + "f", v / (float) mDivisor)
                + mUnits;
    }
}
