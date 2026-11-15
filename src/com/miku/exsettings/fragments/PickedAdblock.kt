/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.miku.exsettings.fragments

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * [Miku-Picked UI] System-wide ad blocker, like AdAway's root mode. Downloads hosts lists, merges
 * them with the user's own block and allow lists into /data/misc/picked_adblock/hosts (written in
 * place, so the bind mount sees it at once), and persist.sys.picked.adblock makes init lay that
 * file over /system/etc/hosts (vendor/miku init.picked.rc). The DNS resolver reads it before any
 * DNS server, so it also works with Private DNS; apps with their own DNS-over-HTTPS bypass it.
 */
class PickedAdblock : SettingsPreferenceFragment() {

    private data class Source(val key: String, val title: Int, val url: String,
        val default: Boolean)

    private val sources = listOf(
        Source("stevenblack", R.string.picked_adblock_src_stevenblack,
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", true),
        Source("adaway", R.string.picked_adblock_src_adaway, "https://adaway.org/hosts.txt", true),
        Source("pgl", R.string.picked_adblock_src_pgl,
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0" +
                "&mimetype=plaintext", false),
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy {
        requireContext().getSharedPreferences("picked_adblock", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.picked_adblock)

        val enabled = findPreference<TwoStatePreference>("picked_adblock_enabled")!!
        enabled.isChecked = SystemProperties.getBoolean(PROP, false) && HOSTS.isFile
        enabled.setOnPreferenceChangeListener { _, v ->
            if (v as Boolean) {
                if (!HOSTS.isFile || HOSTS.length() == 0L) {
                    update { ok -> if (ok) SystemProperties.set(PROP, "1") else enabled.isChecked = false }
                } else {
                    SystemProperties.set(PROP, "1")
                }
            } else {
                SystemProperties.set(PROP, "0")
            }
            true
        }

        val category = findPreference<PreferenceCategory>("picked_adblock_sources")!!
        for (s in sources) {
            category.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = "picked_adblock_src_" + s.key
                setTitle(s.title)
                summary = URL(s.url).host
                isPersistent = false
                isChecked = prefs.getBoolean(s.key, s.default)
                setOnPreferenceChangeListener { _, v ->
                    prefs.edit().putBoolean(s.key, v as Boolean).apply()
                    true
                }
            })
        }
        findPreference<Preference>("picked_adblock_update")!!.setOnPreferenceClickListener {
            update(null)
            true
        }
        findPreference<Preference>("picked_adblock_blocklist")!!.setOnPreferenceClickListener {
            editList("blocklist", R.string.picked_adblock_blocklist_title)
            true
        }
        findPreference<Preference>("picked_adblock_allowlist")!!.setOnPreferenceClickListener {
            editList("allowlist", R.string.picked_adblock_allowlist_title)
            true
        }
        refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        val updated = prefs.getLong("updated", 0)
        findPreference<Preference>("picked_adblock_update")?.summary =
            if (updated == 0L) getString(R.string.picked_adblock_never)
            else getString(R.string.picked_adblock_status, prefs.getInt("count", 0),
                DateUtils.getRelativeTimeSpanString(updated))
    }

    private fun domains(text: String?): Set<String> =
        text.orEmpty().lines().map { it.substringBefore('#').trim().lowercase() }
            .filter { it.isNotEmpty() && !it.contains(' ') }.toSet()

    private fun editList(key: String, title: Int) {
        val input = EditText(requireContext()).apply {
            setText(prefs.getString(key, ""))
            hint = getString(R.string.picked_adblock_list_hint)
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.picked_adblock_save_apply) { _, _ ->
                prefs.edit().putString(key, input.text.toString()).apply()
                update(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Downloads the chosen lists and rewrites the hosts file. */
    private fun update(done: ((Boolean) -> Unit)?) {
        val chosen = sources.filter { prefs.getBoolean(it.key, it.default) }
        val block = domains(prefs.getString("blocklist", ""))
        val allow = domains(prefs.getString("allowlist", ""))
        val dialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.picked_adblock_updating)
            .setCancelable(false)
            .show()
        executor.execute {
            val result = try {
                build(chosen, block, allow)
            } catch (e: Exception) {
                -1
            }
            main.post {
                dialog.dismiss()
                if (!isAdded) return@post
                if (result >= 0) {
                    prefs.edit().putLong("updated", System.currentTimeMillis())
                        .putInt("count", result).apply()
                    Toast.makeText(context, getString(R.string.picked_adblock_done, result),
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.picked_adblock_failed, Toast.LENGTH_LONG)
                        .show()
                }
                refresh()
                done?.invoke(result >= 0)
            }
        }
    }

    /** Returns the number of blocked domains. Worker thread. */
    private fun build(chosen: List<Source>, block: Set<String>, allow: Set<String>): Int {
        val blocked = LinkedHashSet<String>()
        var anyOk = chosen.isEmpty()
        for (s in chosen) {
            val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode != 200) continue
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.substringBefore('#').trim()
                        if (line.isEmpty()) continue
                        val parts = line.split(WHITESPACE)
                        if (parts.size < 2) continue
                        if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
                        for (host in parts.drop(1)) {
                            val h = host.lowercase()
                            if (h != "localhost" && h != "0.0.0.0" && h.contains('.') &&
                                    !h.startsWith("local")) {
                                blocked += h
                            }
                        }
                    }
                }
                anyOk = true
            } finally {
                conn.disconnect()
            }
        }
        if (!anyOk) return -1
        blocked += block
        blocked -= allow
        // Write in place (same inode): /system/etc/hosts is a bind mount of this file.
        FileOutputStream(HOSTS, false).bufferedWriter().use { w ->
            w.write("# Miku-Picked UI ad blocker\n127.0.0.1 localhost\n::1 ip6-localhost\n")
            for (h in blocked) {
                w.write("0.0.0.0 ")
                w.write(h)
                w.write("\n")
            }
        }
        HOSTS.setReadable(true, false)
        return blocked.size
    }

    override fun getMetricsCategory() = MetricsEvent.MIKU

    companion object {
        private const val PROP = "persist.sys.picked.adblock"
        private val HOSTS = File("/data/misc/picked_adblock/hosts")
        private val WHITESPACE = Regex("\\s+")
    }
}
