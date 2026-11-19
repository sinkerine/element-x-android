/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.migration.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.migration.api.AppMigrationRunner
import io.element.android.features.migration.impl.migrations.AppMigration
import io.element.android.features.migration.test.FakeAppMigrationRunner
import io.element.android.libraries.architecture.AsyncData
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MigrationPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - no pending migration`() = runTest {
        val runLambda = lambdaRecorder<Unit> { }
        val presenter = createPresenter(
            FakeAppMigrationRunner(
                hasPendingMigrationsResult = { false },
                runPendingMigrationsResult = { runLambda() },
            )
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Uninitialized)
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Success(Unit))
        }
        runLambda.assertions().isCalledOnce()
    }

    @Test
    fun `present - pending migrations`() = runTest {
        val migrationsDone = CompletableDeferred<Unit>()
        val presenter = createPresenter(
            FakeAppMigrationRunner(
                hasPendingMigrationsResult = { true },
                runPendingMigrationsResult = { migrationsDone.await() },
            )
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Uninitialized)
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Loading(Unit))
            migrationsDone.complete(Unit)
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Success(Unit))
        }
    }

    @Test
    fun `present - failed migration can be retried without advancing version`() = runTest {
        var attempts = 0
        val firstAttempt = CompletableDeferred<Unit>()
        val retryAttempt = CompletableDeferred<Unit>()
        val migration = object : AppMigration {
            override val order = 12
            override suspend fun migrate(isFreshInstall: Boolean) {
                if (++attempts == 1) firstAttempt.await() else retryAttempt.await()
            }
        }
        val store = InMemoryMigrationStore(11)
        val presenter = createPresenter(DefaultAppMigrationRunner(store, setOf(migration), testCoroutineDispatchers()))
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Uninitialized)
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Loading(Unit))
            firstAttempt.completeExceptionally(IllegalStateException("temporary failure"))
            val failedState = awaitItem()
            assertThat(failedState.migrationAction).isInstanceOf(AsyncData.Failure::class.java)
            assertThat(store.applicationMigrationVersion().first()).isEqualTo(11)
            failedState.onRetry()
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Loading(Unit))
            retryAttempt.complete(Unit)
            assertThat(awaitItem().migrationAction).isEqualTo(AsyncData.Success(Unit))
            assertThat(attempts).isEqualTo(2)
            assertThat(store.applicationMigrationVersion().first()).isEqualTo(12)
        }
    }

    private fun createPresenter(appMigrationRunner: AppMigrationRunner) = MigrationPresenter(
        appMigrationRunner = appMigrationRunner,
    )
}
