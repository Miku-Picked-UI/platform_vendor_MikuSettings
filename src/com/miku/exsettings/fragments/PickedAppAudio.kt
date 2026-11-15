/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.miku.exsettings.fragments

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.TwoStatePreference
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.miku.exsettings.fragments.edgeaurora.AuroraApp
import com.miku.exsettings.fragments.edgeaurora.AuroraAppPicker
import com.miku.exsettings.fragments.edgeaurora.AuroraApps

/**
 * [Miku-Picked UI] Per-app audio: per-app volume and separate app sound, applied by
 * system_server's PickedAppAudio (services/core/.../audio). Settings.System keys:
 * picked_app_volume "pkg=percent;...", picked_separate_sound_enabled,
 * picked_separate_sound_apps "pkg;pkg", picked_separate_sound_device (0 speaker, 1 connected).
 */
class PickedAppAudio : SettingsPreferenceFragment() {

    private var apps = emptyList<AuroraApp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.picked_app_audio)
        findPreference<Preference>("picked_av_add")!!.setOnPreferenceClickListener {
            withApps {
                AuroraAppPicker.show(requireContext(), R.string.picked_av_add, apps) { sel ->
                    sel.firstOrNull()?.let { pickVolume(it) }
                }
            }
            true
        }
        val enabled = findPreference<TwoStatePreference>("picked_sas_enabled")!!
        enabled.isChecked = get(KEY_SEP_ENABLED) == "1"
        enabled.setOnPreferenceChangeListener { _, v ->
            put(KEY_SEP_ENABLED, if (v as Boolean) "1" else "0")
            true
        }
        val device = findPreference<ListPreference>("picked_sas_device")!!
        device.value = get(KEY_SEP_DEVICE) ?: "1"
        device.setOnPreferenceChangeListener { _, v ->
            put(KEY_SEP_DEVICE, v as String)
            true
        }
        findPreference<Preference>("picked_sas_apps")!!.setOnPreferenceClickListener {
            withApps {
                AuroraAppPicker.show(requireContext(), R.string.picked_sas_apps_title, apps,
                    separateApps()) { sel ->
                    put(KEY_SEP_APPS, sel.sorted().joinToString(";"))
                    refresh()
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        withApps { if (isAdded) refresh() }
        refresh()
    }

    private fun withApps(block: () -> Unit) {
        if (apps.isNotEmpty()) {
            block()
            return
        }
        AuroraApps.load(requireContext()) {
            apps = it
            block()
        }
    }

    private fun get(key: String): String? =
        Settings.System.getStringForUser(requireContext().contentResolver, key,
            UserHandle.myUserId())

    private fun put(key: String, value: String) {
        Settings.System.putStringForUser(requireContext().contentResolver, key, value,
            UserHandle.myUserId())
    }

    private fun label(pkg: String) = apps.firstOrNull { it.info.packageName == pkg }?.label ?: pkg

    private fun volumes(): MutableMap<String, Int> {
        val map = LinkedHashMap<String, Int>()
        get(KEY_VOLUME)?.split(";")?.forEach { e ->
            val kv = e.split("=")
            if (kv.size == 2) kv[1].trim().toIntOrNull()?.let { map[kv[0].trim()] = it }
        }
        return map
    }

    private fun saveVolumes(map: Map<String, Int>) =
        put(KEY_VOLUME, map.entries.joinToString(";") { "${it.key}=${it.value}" })

    private fun separateApps(): Set<String> =
        get(KEY_SEP_APPS)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    private fun pickVolume(pkg: String) {
        val ctx = requireContext()
        val pad = (24 * resources.displayMetrics.density).toInt()
        val value = TextView(ctx)
        val bar = SeekBar(ctx).apply {
            max = 100
            progress = volumes()[pkg] ?: 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = getString(R.string.picked_av_percent, p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        value.text = getString(R.string.picked_av_percent, bar.progress)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(value)
            addView(bar)
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(label(pkg))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val map = volumes()
                if (bar.progress == 100) map.remove(pkg) else map[pkg] = bar.progress
                saveVolumes(map)
                refresh()
            }
            .setNeutralButton(R.string.picked_av_reset) { _, _ ->
                saveVolumes(volumes().apply { remove(pkg) })
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val category = findPreference<PreferenceCategory>("picked_av_apps") ?: return
        category.removeAll()
        val map = volumes()
        category.isVisible = map.isNotEmpty()
        for ((pkg, pct) in map.entries.sortedBy { label(it.key).lowercase() }) {
            val pref = Preference(requireContext())
            pref.title = label(pkg)
            pref.summary = getString(R.string.picked_av_percent, pct)
            apps.firstOrNull { it.info.packageName == pkg }
                ?.let { pref.icon = it.info.loadIcon(requireContext().packageManager) }
            pref.setOnPreferenceClickListener {
                pickVolume(pkg)
                true
            }
            category.addPreference(pref)
        }
        val sep = separateApps()
        findPreference<Preference>("picked_sas_apps")?.summary =
            if (sep.isEmpty()) getString(R.string.picked_sas_apps_none)
            else sep.map { label(it) }.sortedBy { it.lowercase() }.joinToString(", ")
    }

    override fun getMetricsCategory() = MetricsEvent.MIKU

    companion object {
        private const val KEY_VOLUME = "picked_app_volume"
        private const val KEY_SEP_ENABLED = "picked_separate_sound_enabled"
        private const val KEY_SEP_APPS = "picked_separate_sound_apps"
        private const val KEY_SEP_DEVICE = "picked_separate_sound_device"
    }
}
