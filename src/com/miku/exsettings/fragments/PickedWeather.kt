/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */
package com.miku.exsettings.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * [Miku-Picked UI] Weather for the custom lockscreen clocks (SystemUI WeatherRepository, from
 * Open-Meteo). The city is found with Open-Meteo's geocoding search; only its coordinates are
 * stored and sent, no device location is used.
 */
class PickedWeather : SettingsPreferenceFragment() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.picked_weather)
        val enabled = findPreference<TwoStatePreference>("picked_weather_enabled_pref")!!
        enabled.isChecked = get(KEY_ENABLED) == "1"
        enabled.setOnPreferenceChangeListener { _, v ->
            put(KEY_ENABLED, if (v as Boolean) "1" else "0")
            if (v && get(KEY_LAT) == null) searchCity()
            true
        }
        val unit = findPreference<ListPreference>("picked_weather_unit_pref")!!
        unit.value = get(KEY_UNIT) ?: defaultUnit()
        unit.setOnPreferenceChangeListener { _, v ->
            put(KEY_UNIT, v as String)
            true
        }
        findPreference<Preference>("picked_weather_city_pref")!!.setOnPreferenceClickListener {
            searchCity()
            true
        }
        refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /** Fahrenheit for the US and a few others, Celsius everywhere else. */
    private fun defaultUnit() =
        if (Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) "1"
        else "0"

    private fun get(key: String): String? =
        Settings.System.getStringForUser(requireContext().contentResolver, key,
            UserHandle.myUserId())

    private fun put(key: String, value: String?) {
        Settings.System.putStringForUser(requireContext().contentResolver, key, value,
            UserHandle.myUserId())
    }

    private fun refresh() {
        findPreference<Preference>("picked_weather_city_pref")?.summary =
            get(KEY_CITY) ?: getString(R.string.picked_weather_city_none)
    }

    private fun searchCity() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.picked_weather_city_hint)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.picked_weather_city_title)
            .setView(frame)
            .setPositiveButton(R.string.picked_weather_search) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) lookup(q)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun lookup(query: String) {
        val lang = Locale.getDefault().language
        executor.execute {
            val places = try {
                val url = URL("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=$lang&name=" + URLEncoder.encode(query, "UTF-8"))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val results = json.optJSONArray("results")
                    (0 until (results?.length() ?: 0)).map { i ->
                        val o = results!!.getJSONObject(i)
                        Place(o.getString("name"),
                            listOfNotNull(o.optString("admin1").ifEmpty { null },
                                o.optString("country").ifEmpty { null }).joinToString(", "),
                            o.getDouble("latitude"), o.getDouble("longitude"))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            main.post { if (isAdded) showResults(places) }
        }
    }

    private fun showResults(places: List<Place>?) {
        if (places == null) {
            Toast.makeText(context, R.string.picked_weather_search_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (places.isEmpty()) {
            Toast.makeText(context, R.string.picked_weather_no_results, Toast.LENGTH_LONG).show()
            return
        }
        val labels = places.map { if (it.detail.isEmpty()) it.name else "${it.name} (${it.detail})" }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.picked_weather_city_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val p = places[which]
                put(KEY_LAT, String.format(Locale.US, "%.4f", p.lat))
                put(KEY_LON, String.format(Locale.US, "%.4f", p.lon))
                put(KEY_CITY, p.name)
                put(KEY_ENABLED, "1")
                findPreference<TwoStatePreference>("picked_weather_enabled_pref")?.isChecked = true
                refresh()
            }
            .show()
    }

    override fun getMetricsCategory() = MetricsEvent.MIKU

    companion object {
        private const val KEY_ENABLED = "picked_weather_enabled"
        private const val KEY_LAT = "picked_weather_lat"
        private const val KEY_LON = "picked_weather_lon"
        private const val KEY_CITY = "picked_weather_city"
        private const val KEY_UNIT = "picked_weather_unit"
    }
}
