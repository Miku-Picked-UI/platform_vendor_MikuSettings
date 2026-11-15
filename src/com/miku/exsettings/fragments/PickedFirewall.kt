/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.miku.exsettings.fragments

import android.os.Bundle
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
 * [Miku-Picked UI] Per-app network firewall. Two Settings.Global package lists read by
 * system_server's PickedFirewall (NetworkPolicyManagerService): picked_firewall_mobile (no mobile
 * data) and picked_firewall_all (no network at all).
 */
class PickedFirewall : SettingsPreferenceFragment() {

    private var apps = emptyList<AuroraApp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.picked_firewall)
        findPreference<Preference>("picked_fw_add")!!.setOnPreferenceClickListener {
            withApps { pickApp() }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        withApps { if (isAdded) refreshList() }
        refreshList()
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

    private fun label(pkg: String): String =
        apps.firstOrNull { it.info.packageName == pkg }?.label ?: pkg

    private fun read(key: String): MutableSet<String> =
        Settings.Global.getString(requireContext().contentResolver, key)
            ?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
            ?: mutableSetOf()

    private fun write(key: String, set: Set<String>) {
        Settings.Global.putString(requireContext().contentResolver, key,
            set.sorted().joinToString(";"))
    }

    /** 0 = allowed, 1 = no mobile data, 2 = no internet. */
    private fun mode(pkg: String): Int = when {
        pkg in read(KEY_ALL) -> 2
        pkg in read(KEY_MOBILE) -> 1
        else -> 0
    }

    private fun setMode(pkg: String, mode: Int) {
        val mobile = read(KEY_MOBILE).apply { remove(pkg) }
        val all = read(KEY_ALL).apply { remove(pkg) }
        when (mode) {
            1 -> mobile.add(pkg)
            2 -> all.add(pkg)
        }
        write(KEY_MOBILE, mobile)
        write(KEY_ALL, all)
    }

    private fun pickApp() {
        AuroraAppPicker.show(requireContext(), R.string.picked_fw_add, apps) { selected ->
            val pkg = selected.firstOrNull() ?: return@show
            pickMode(pkg)
        }
    }

    private fun modeLabels() = arrayOf(
        getString(R.string.picked_fw_allowed),
        getString(R.string.picked_fw_no_mobile),
        getString(R.string.picked_fw_no_internet))

    private fun pickMode(pkg: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(label(pkg))
            .setSingleChoiceItems(modeLabels(), mode(pkg)) { d, which ->
                setMode(pkg, which)
                d.dismiss()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshList() {
        val category = findPreference<PreferenceCategory>("picked_fw_apps") ?: return
        category.removeAll()
        val labels = modeLabels()
        val blocked = (read(KEY_MOBILE) + read(KEY_ALL)).sortedBy { label(it).lowercase() }
        category.isVisible = blocked.isNotEmpty()
        for (pkg in blocked) {
            val pref = Preference(requireContext())
            pref.title = label(pkg)
            pref.summary = labels[mode(pkg)]
            apps.firstOrNull { it.info.packageName == pkg }
                ?.let { pref.icon = it.info.loadIcon(requireContext().packageManager) }
            pref.setOnPreferenceClickListener {
                pickMode(pkg)
                true
            }
            category.addPreference(pref)
        }
    }

    override fun getMetricsCategory() = MetricsEvent.MIKU

    companion object {
        private const val KEY_MOBILE = "picked_firewall_mobile"
        private const val KEY_ALL = "picked_firewall_all"
    }
}
