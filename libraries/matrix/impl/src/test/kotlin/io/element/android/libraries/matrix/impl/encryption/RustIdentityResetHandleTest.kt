/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.encryption

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_USER_ID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.AuthData
import org.matrix.rustcomponents.sdk.IdentityResetHandle
import org.matrix.rustcomponents.sdk.NoHandle

class RustIdentityResetHandleTest {
    private class Handle : IdentityResetHandle(NoHandle) {
        var auth: AuthData? = null
        override suspend fun reset(auth: AuthData) {
            this.auth = auth
        }
    }

    @Test
    fun `password reset includes challenge session`() = runTest {
        val handle = Handle()
        RustPasswordIdentityResetHandle(A_USER_ID, handle, "test-session").resetPassword("test-password").getOrThrow()
        val details = (handle.auth as AuthData.Password).passwordDetails
        assertThat(details.identifier).isEqualTo(A_USER_ID.value)
        assertThat(details.password).isEqualTo("test-password")
        assertThat(details.session).isEqualTo("test-session")
    }

    @Test
    fun `oauth reset includes challenge session`() = runTest {
        val handle = Handle()
        RustIdentityOAuthResetHandle(handle, "https://example.org/approve", "test-session").resetOAuth().getOrThrow()
        assertThat((handle.auth as AuthData.OAuth).session).isEqualTo("test-session")
    }
}
