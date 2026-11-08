/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.widget.LayoutPreference;

/**
 * [Evolution X] Custom lockscreen clock styles.
 *
 * Swipe through previews of the clock styles and apply one. The style index is stored in
 * Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE and must match the order of CLOCK_LAYOUTS in
 * SystemUI's com.android.systemui.clocks.ClockStyle. The lockscreen layout only picks up a
 * change of style after SystemUI restarts, so applying a new style restarts SystemUI.
 * Preview layouts and clock names come from Evolution X's Evolver (ClockUtils).
 */
public class PickedClockStyles extends SettingsPreferenceFragment {

    private static final String TAG = "PickedClockStyles";
    private static final String KEY_PREVIEW = "picked_clock_preview";

    /** Index = clock style, in the order of SystemUI's ClockStyle.CLOCK_LAYOUTS. */
    private static final int[] PREVIEW_LAYOUTS = {
        R.layout.keyguard_clock_default,
        R.layout.keyguard_clock_oos,
        R.layout.keyguard_clock_oos2,
        R.layout.keyguard_clock_center,
        R.layout.keyguard_clock_simple,
        R.layout.keyguard_clock_miui,
        R.layout.keyguard_clock_ide,
        R.layout.keyguard_clock_moto,
        R.layout.keyguard_clock_stylish,
        R.layout.keyguard_clock_stylish2,
        R.layout.keyguard_clock_stylish3,
        R.layout.keyguard_clock_stylish4,
        R.layout.keyguard_clock_stylish5,
        R.layout.keyguard_clock_stylish6,
        R.layout.keyguard_clock_stylish7,
        R.layout.keyguard_clock_stylish8,
        R.layout.keyguard_clock_stylish9,
        R.layout.keyguard_clock_stylish10,
        R.layout.keyguard_clock_word,
        R.layout.keyguard_clock_life,
        R.layout.keyguard_clock_a9,
        R.layout.keyguard_clock_nos1,
        R.layout.keyguard_clock_nos2,
        R.layout.keyguard_clock_num,
        R.layout.keyguard_clock_accent,
        R.layout.keyguard_clock_analog,
        R.layout.keyguard_clock_block,
        R.layout.keyguard_clock_bubble,
        R.layout.keyguard_clock_label,
        R.layout.keyguard_clock_ios,
        R.layout.keyguard_clock_taden,
        R.layout.keyguard_clock_mont,
        R.layout.keyguard_clock_encode,
        R.layout.keyguard_clock_nos3,
        R.layout.keyguard_anci_clock_outline,
        R.layout.keyguard_anci_clock_ovalium,
        R.layout.keyguard_anci_clock_rectangle,
        R.layout.keyguard_anci_clock_wallet,
        R.layout.keyguard_anci_clockdate_clavicula,
        R.layout.keyguard_anci_clockdate_kln,
        R.layout.keyguard_anci_clockdate_miring,
        R.layout.keyguard_anci_clockdate_scapula,
        R.layout.keyguard_anci_clockdate_sternum,
        R.layout.keyguard_sparkcircle,
        R.layout.keyguard_sparklist,
        R.layout.keyguard_clock_miui2,
        R.layout.keyguard_clock_ios2,
        R.layout.keyguard_clock_ios3,
        R.layout.keyguard_clock_ios4,
        R.layout.keyguard_clock_ios5,
        R.layout.keyguard_clock_ios6,
        R.layout.keyguard_clock_ios7,
        R.layout.keyguard_clock_ios8,
        R.layout.keyguard_clock_ios9,
        R.layout.keyguard_clock_ios10,
        R.layout.keyguard_clock_ios11,
        R.layout.keyguard_clock_ios12,
        R.layout.keyguard_clock_ios13,
        R.layout.keyguard_clock_ios14,
        R.layout.keyguard_clock_ios15,
        R.layout.keyguard_clock_ios16,
        R.layout.keyguard_clock_ios17,
        R.layout.keyguard_clock_ios18,
        R.layout.keyguard_clock_ios19,
        R.layout.keyguard_clock_big1,
        R.layout.keyguard_clock_big2,
        R.layout.keyguard_clock_big3,
        R.layout.keyguard_clock_big4,
        R.layout.keyguard_clock_sweet,
        R.layout.keyguard_clock_pixel,
        R.layout.keyguard_clock_samurai,
        R.layout.keyguard_clock_gateway,
        R.layout.keyguard_clock_tall,
        R.layout.keyguard_clock_gobold,
        R.layout.keyguard_clock_gobold2,
        R.layout.keyguard_clock_delirium,
        R.layout.keyguard_clock_deliriumdual,
        R.layout.keyguard_clock_skewrom,
        R.layout.keyguard_clock_skewrom2,
        R.layout.keyguard_clock_taller,
        R.layout.keyguard_clock_taller2,
        R.layout.keyguard_clock_taller3,
        R.layout.keyguard_clock_modak,
        R.layout.keyguard_clock_galada,
        R.layout.keyguard_clock_cos1,
        R.layout.keyguard_clock_cos2,
    };

    private ViewPager mPager;
    private TextView mName;
    private int mPosition;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.picked_clock_styles);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LayoutPreference preview = findPreference(KEY_PREVIEW);
        if (preview == null) return;

        mPager = preview.findViewById(R.id.picked_clock_pager);
        mName = preview.findViewById(R.id.picked_clock_name);
        mPager.setAdapter(new ClockPagerAdapter());

        mPosition = getCurrentStyle();
        if (mPosition < 0 || mPosition >= PREVIEW_LAYOUTS.length) mPosition = 0;
        mPager.setCurrentItem(mPosition, false);
        updateName();

        mPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mPosition = position;
                mPager.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                updateName();
            }
        });

        preview.findViewById(R.id.picked_clock_apply).setOnClickListener(v -> apply());
    }

    private int getCurrentStyle() {
        return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, 0, UserHandle.USER_CURRENT);
    }

    private void updateName() {
        String[] names = getResources().getStringArray(R.array.picked_clock_style_names);
        if (mName != null && mPosition >= 0 && mPosition < names.length) {
            mName.setText(names[mPosition]);
        }
    }

    private void apply() {
        if (mPosition == getCurrentStyle()) return;
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.picked_clock_styles_title)
                .setMessage(R.string.picked_clock_apply_message)
                .setPositiveButton(R.string.apply, (d, w) -> {
                    Context ctx = getContext();
                    Settings.Secure.putIntForUser(ctx.getContentResolver(),
                            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, mPosition,
                            UserHandle.USER_CURRENT);
                    restartSystemUI(ctx);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void restartSystemUI(Context context) {
        try {
            context.getSystemService(ActivityManager.class)
                    .forceStopPackage("com.android.systemui");
        } catch (Exception e) {
            Log.e(TAG, "Could not restart SystemUI", e);
        }
    }

    private class ClockPagerAdapter extends PagerAdapter {
        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View layout;
            try {
                layout = LayoutInflater.from(getActivity())
                        .inflate(PREVIEW_LAYOUTS[position], container, false);
            } catch (Exception e) {
                // Never let one broken preview close the page.
                Log.e(TAG, "Preview " + position + " failed", e);
                layout = new View(getActivity());
            }
            container.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position,
                @NonNull Object object) {
            container.removeView((View) object);
        }

        @Override
        public int getCount() {
            return PREVIEW_LAYOUTS.length;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
