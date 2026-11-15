/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.miku.exsettings.fragments

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.miku.exsettings.fragments.edgeaurora.AuroraApp
import com.miku.exsettings.fragments.edgeaurora.AuroraAppPicker
import com.miku.exsettings.fragments.edgeaurora.AuroraApps

/**
 * [Miku-Picked UI] Per-app refresh rate. A global cap for everything, and per-app overrides
 * (Settings.System picked_app_refresh_rate "pkg=rate;pkg=rate"). SystemUI's
 * PickedRefreshRateController enforces it on the foreground app.
 */
class PickedRefreshRate : SettingsPreferenceFragment() {

    private val rates = intArrayOf(48, 60, 90, 120)
    private var apps = emptyList<AuroraApp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.picked_refresh_rate)
        findPreference<Preference>("picked_rr_add")!!.setOnPreferenceClickListener {
            if (apps.isEmpty()) {
                AuroraApps.load(requireContext()) {
                    apps = it
                    pickApp()
                }
            } else {
                pickApp()
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (apps.isEmpty()) {
            AuroraApps.load(requireContext()) {
                apps = it
                if (isAdded) refreshList()
            }
        }
        refreshList()
    }

    private fun label(pkg: String): String =
        apps.firstOrNull { it.info.packageName == pkg }?.label ?: pkg

    private fun pickApp() {
        AuroraAppPicker.show(requireContext(), R.string.picked_rr_add, apps) { selected ->
            val pkg = selected.firstOrNull() ?: return@show
            pickRate(pkg)
        }
    }

    private fun pickRate(pkg: String) {
        val current = config()[pkg] ?: 0
        val labels = rates.map { getString(R.string.picked_rr_hz, it) }.toMutableList()
        labels.add(getString(R.string.picked_rr_remove))
        val checked = rates.indexOf(current)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(label(pkg))
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                val map = config().toMutableMap()
                if (which < rates.size) map[pkg] = rates[which] else map.remove(pkg)
                save(map)
                d.dismiss()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshList() {
        val category = findPreference<PreferenceCategory>("picked_rr_apps") ?: return
        category.removeAll()
        val map = config()
        if (map.isEmpty()) {
            category.isVisible = false
            return
        }
        category.isVisible = true
        for ((pkg, rate) in map.entries.sortedBy { label(it.key).lowercase() }) {
            val pref = Preference(requireContext())
            pref.title = label(pkg)
            pref.summary = getString(R.string.picked_rr_hz, rate)
            pref.setOnPreferenceClickListener {
                pickRate(pkg)
                true
            }
            category.addPreference(pref)
        }
    }

    private fun config(): Map<String, Int> {
        val saved =
            Settings.System.getStringForUser(
                requireContext().contentResolver, KEY, UserHandle.myUserId())
        val map = LinkedHashMap<String, Int>()
        saved?.split(";")?.forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val rate = parts[1].trim().toIntOrNull()
                if (rate != null && rate > 0) map[parts[0].trim()] = rate
            }
        }
        return map
    }

    private fun save(map: Map<String, Int>) {
        Settings.System.putStringForUser(
            requireContext().contentResolver,
            KEY,
            map.entries.joinToString(";") { "${it.key}=${it.value}" },
            UserHandle.myUserId())
    }

    override fun getMetricsCategory() = MetricsEvent.MIKU

    companion object {
        private const val KEY = "picked_app_refresh_rate"
    }
}
