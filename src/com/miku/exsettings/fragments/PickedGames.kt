/*
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miku.exsettings.fragments

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.UserHandle
import android.provider.Settings
import com.android.settings.R
import com.miku.exsettings.fragments.edgeaurora.AuroraApp
import com.miku.exsettings.fragments.edgeaurora.AuroraAppPicker
import com.miku.exsettings.fragments.edgeaurora.AuroraApps

/**
 * The game list behind GameManagerService's game mode, and with it the power HAL's GAME profile
 * (sustained clocks). frameworks/base reads the same two Settings.Secure keys: apps added as games,
 * and games (by their manifest category) taken off the list. With nothing saved yet, the
 * framework's config_pickedGamePackages is the added set.
 */
object PickedGames {
    private const val KEY_ADDED = "picked_game_packages"
    private const val KEY_EXCLUDED = "picked_game_packages_excluded"

    private fun split(list: String?): Set<String> =
        list.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun added(context: Context): Set<String> {
        val saved =
            Settings.Secure.getStringForUser(
                context.contentResolver,
                KEY_ADDED,
                UserHandle.myUserId(),
            )
        if (saved != null) return split(saved)
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_pickedGamePackages", "array", "android")
        return if (id != 0) res.getStringArray(id).toSet() else emptySet()
    }

    private fun excluded(context: Context): Set<String> =
        split(
            Settings.Secure.getStringForUser(
                context.contentResolver,
                KEY_EXCLUDED,
                UserHandle.myUserId(),
            )
        )

    private fun isDeclaredGame(app: AuroraApp) = app.info.category == ApplicationInfo.CATEGORY_GAME

    private fun currentGames(context: Context, apps: List<AuroraApp>): Set<String> {
        val added = added(context)
        val excluded = excluded(context)
        return apps
            .filter {
                val pkg = it.info.packageName
                pkg !in excluded && (pkg in added || isDeclaredGame(it))
            }
            .map { it.info.packageName }
            .toSet()
    }

    /** Number of installed apps on the list, delivered on the main thread. */
    @JvmStatic
    fun count(context: Context, result: (Int) -> Unit) {
        AuroraApps.load(context) { apps -> result(currentGames(context, apps).size) }
    }

    /** Shows the multi-select app list; [onSaved] runs on the main thread after saving. */
    @JvmStatic
    fun show(context: Context, onSaved: Runnable) {
        AuroraApps.load(context) { apps ->
            AuroraAppPicker.show(context, R.string.picked_games_title, apps, currentGames(context, apps)) {
                selected ->
                val declared = apps.filter { isDeclaredGame(it) }.map { it.info.packageName }.toSet()
                // Apps on the list that aren't games by their manifest are "added"; manifest games
                // left off the list are "excluded". Added entries for apps that aren't installed
                // right now are kept, so uninstalling and reinstalling a game keeps it listed.
                val installed = apps.map { it.info.packageName }.toSet()
                val keptAdded = added(context).filter { it !in installed }
                val newAdded = (selected - declared) + keptAdded
                val newExcluded = declared - selected
                val cr = context.contentResolver
                Settings.Secure.putStringForUser(
                    cr,
                    KEY_ADDED,
                    newAdded.sorted().joinToString(","),
                    UserHandle.myUserId(),
                )
                Settings.Secure.putStringForUser(
                    cr,
                    KEY_EXCLUDED,
                    newExcluded.sorted().joinToString(","),
                    UserHandle.myUserId(),
                )
                onSaved.run()
            }
        }
    }
}
