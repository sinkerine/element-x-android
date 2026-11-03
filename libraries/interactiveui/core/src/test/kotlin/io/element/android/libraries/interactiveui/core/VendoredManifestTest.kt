/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
class VendoredManifestTest {
    @Test fun `all vendored bytes match recorded SHA256 manifest`() {
        val module = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it, "vendored.sha256").isFile }
        val lines = File(module, "vendored.sha256").readLines()
        assertEquals(130, lines.size)
        lines.forEach { line ->
            val (expected, path) = line.split("  ", limit = 2)
            val bytes = File(module, path).readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(path, expected, actual)
        }
        val vendored = listOf("src/main/resources/interactive-ui", "src/test/resources/interactive-ui/fixtures")
            .flatMap { File(module, it).walkTopDown().filter(File::isFile).toList() }
            .map { it.relativeTo(module).path }
            .toSet()
        assertEquals(vendored, lines.map { it.substringAfter("  ") }.toSet())
    }
}
