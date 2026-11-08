/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * [Evolution X] Fingerprint icon for under-display fingerprint sensors.
 *
 * The icons come from the UdfpsIcons resource package (org.evolution.udfps.icons, array
 * "udfps_icons"); the selected index is stored in Settings.System.UDFPS_ICON and drawn by
 * SystemUI's UdfpsIconDrawable. Index 0 is the default AOSP icon.
 * Java version of Evolution X's UdfpsIconPicker (Evolver).
 */
public class PickedUdfpsIcons extends SettingsPreferenceFragment {

    static final String ICONS_PACKAGE = "org.evolution.udfps.icons";

    private Context mIconsContext;
    private final List<Integer> mIconIds = new ArrayList<>();

    /** Whether the icon package is installed (used to show the entry). */
    public static boolean isAvailable(Context context) {
        try {
            context.getPackageManager().getPackageInfo(ICONS_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActivity().setTitle(R.string.picked_udfps_icon_title);
        try {
            mIconsContext = getActivity().createPackageContext(ICONS_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);
            Resources res = mIconsContext.getResources();
            int arrayId = res.getIdentifier("udfps_icons", "array", ICONS_PACKAGE);
            if (arrayId != 0) {
                for (String name : res.getStringArray(arrayId)) {
                    mIconIds.add(res.getIdentifier(name, "drawable", ICONS_PACKAGE));
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            mIconsContext = null;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        // Creates the preference list that SettingsBasePreferenceFragment.onViewCreated()
        // sets a divider on; without it the page crashes (same as FontsPicker/IconShapes).
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.item_view, container, false);
        RecyclerView rv = view.findViewById(R.id.recycler_view);
        int span = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? 4 : 3;
        rv.setLayoutManager(new GridLayoutManager(getActivity(), span));
        rv.setAdapter(new IconAdapter());
        return view;
    }

    private int getSelected() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.UDFPS_ICON, 0, UserHandle.USER_CURRENT);
    }

    private class IconAdapter extends RecyclerView.Adapter<IconAdapter.Holder> {
        private int mSelected = getSelected();

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_option, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Drawable icon = null;
            int id = mIconIds.get(position);
            if (mIconsContext != null && id != 0) {
                try {
                    icon = mIconsContext.getDrawable(id);
                } catch (Resources.NotFoundException e) {
                    icon = null;
                }
            }
            holder.image.setImageDrawable(icon);
            holder.image.setPadding(20, 20, 20, 20);
            holder.label.setVisibility(View.GONE);
            holder.itemView.setActivated(position == mSelected);
            holder.itemView.setOnClickListener(v -> {
                int old = mSelected;
                mSelected = holder.getBindingAdapterPosition();
                notifyItemChanged(old);
                notifyItemChanged(mSelected);
                Settings.System.putIntForUser(getContext().getContentResolver(),
                        Settings.System.UDFPS_ICON, mSelected, UserHandle.USER_CURRENT);
            });
        }

        @Override
        public int getItemCount() {
            return mIconIds.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView label;

            Holder(View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.option_thumbnail);
                label = itemView.findViewById(R.id.option_label);
            }
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.MIKU;
    }
}
