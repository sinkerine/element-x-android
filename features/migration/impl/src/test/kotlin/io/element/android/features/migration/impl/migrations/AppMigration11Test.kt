/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.migration.impl.migrations

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppMigration11Test {
    @Test
    fun `migration removes only event caches for existing sessions`() = runTest {
        val root = Files.createTempDirectory("migration11").toFile()
        try {
            val firstSession = File(root, "session-one").apply { mkdirs() }
            val firstCache = File(root, "cache-one").apply { mkdirs() }
            val secondSession = File(root, "session-two").apply { mkdirs() }
            val legacyCache = File(root, "cache/session-two").apply { mkdirs() }
            val sessions = listOf(
                aSessionData(sessionId = "@one:server", sessionPath = firstSession.path, cachePath = firstCache.path),
                aSessionData(sessionId = "@two:server", sessionPath = secondSession.path, cachePath = ""),
            )
            val eventCacheNames = listOf(
                "matrix-sdk-event-cache.sqlite3",
                "matrix-sdk-event-cache.sqlite3-shm",
                "matrix-sdk-event-cache.sqlite3-wal",
            )
            for (directory in listOf(firstSession, firstCache, secondSession, legacyCache)) {
                File(directory, "unrelated-cache.db").writeText("keep")
            }
            for (directory in listOf(firstCache, legacyCache)) {
                for (name in eventCacheNames) File(directory, name).writeText("remove")
            }
            val migration = AppMigration11(
                sessionStore = InMemorySessionStore(sessions),
                cacheDirectory = File(root, "cache"),
            )

            migration.migrate(isFreshInstall = false)

            for (directory in listOf(firstCache, legacyCache)) {
                for (name in eventCacheNames) assertThat(File(directory, name).exists()).isFalse()
            }
            for (directory in listOf(firstSession, firstCache, secondSession, legacyCache)) {
                assertThat(File(directory, "unrelated-cache.db").readText()).isEqualTo("keep")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `migration skips fresh installs and tolerates missing cache files`() = runTest {
        val root = Files.createTempDirectory("migration11").toFile()
        try {
            val sessionDirectory = File(root, "session").apply { mkdirs() }
            val cacheDirectory = File(root, "cache").apply { mkdirs() }
            val database = File(cacheDirectory, "matrix-sdk-event-cache.sqlite3").apply { writeText("keep") }
            val migration = AppMigration11(
                sessionStore = InMemorySessionStore(
                    listOf(aSessionData(sessionPath = sessionDirectory.path, cachePath = cacheDirectory.path))
                ),
                cacheDirectory = root,
            )

            migration.migrate(isFreshInstall = true)
            assertThat(database.exists()).isTrue()
            migration.migrate(isFreshInstall = false)
            assertThat(database.exists()).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `migration failure is propagated for retry`() = runTest {
        val root = Files.createTempDirectory("migration11").toFile()
        try {
            val sessionDirectory = File(root, "session").apply { mkdirs() }
            val cacheDirectory = File(root, "cache").apply { mkdirs() }
            val blockedDatabase = File(cacheDirectory, "matrix-sdk-event-cache.sqlite3").apply {
                mkdir()
                File(this, "child").writeText("prevent deletion")
            }
            val migration = AppMigration11(
                sessionStore = InMemorySessionStore(
                    listOf(aSessionData(sessionPath = sessionDirectory.path, cachePath = cacheDirectory.path))
                ),
                cacheDirectory = root,
            )

            val result = runCatchingExceptions { migration.migrate(isFreshInstall = false) }
            assertThat(result.isFailure).isTrue()
            assertThat(blockedDatabase.exists()).isTrue()
        } finally {
            root.deleteRecursively()
        }
    }
}
