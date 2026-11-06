/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.migration.impl.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.sessionstorage.api.SessionStore
import java.io.File

@ContributesIntoSet(AppScope::class)
class AppMigration11(
    private val sessionStore: SessionStore,
    @CacheDirectory private val cacheDirectory: File,
) : AppMigration {
    override val order: Int = 11

    override suspend fun migrate(isFreshInstall: Boolean) {
        if (isFreshInstall) return

        for (session in sessionStore.getAllSessions()) {
            val sessionDirectory = File(session.sessionPath)
            val resolvedCacheDirectory = session.cachePath.takeIf(String::isNotEmpty)?.let(::File)
                ?: File(cacheDirectory, sessionDirectory.name)
            val directories = setOf(sessionDirectory, resolvedCacheDirectory)
            for (directory in directories) {
                for (name in EVENT_CACHE_FILES) {
                    val file = File(directory, name)
                    if (file.exists()) {
                        check(file.delete() || !file.exists()) { "Unable to delete event cache file: ${file.absolutePath}" }
                    }
                }
            }
        }
    }

    private companion object {
        val EVENT_CACHE_FILES = listOf(
            "matrix-sdk-event-cache.sqlite3",
            "matrix-sdk-event-cache.sqlite3-shm",
            "matrix-sdk-event-cache.sqlite3-wal",
        )
    }
}
