package com.luno.mobile.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class UpdateTransactionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("update-test", Context.MODE_PRIVATE)
    private val release = GitHubRelease("v1.0.5", "1.0.5", "Luno", "Notes", "https://github.com/release", "https://github.com/apk", 1024, "sha256:abc")
    private val transaction = UpdateTransaction(release, 5L, UpdatePhase.DOWNLOADING, workId = "work-id")

    @Before
    fun setUp() { preferences.edit().clear().commit() }

    @Test
    fun everyPhaseAndNativeConfirmation_surviveNewStoreInstance() {
        val intent = Intent("android.content.pm.action.CONFIRM_INSTALL")
            .setPackage("com.android.packageinstaller")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
        UpdatePhase.entries.forEach { phase ->
            val pending = transaction.copy(phase = phase, sessionId = 42,
                confirmationUri = intent.toUri(Intent.URI_INTENT_SCHEME), confirmationLaunched = true,
                autoInstall = false, message = "Retry installation")
            UpdateTransactionStore(preferences).save(pending)
            val restored = UpdateTransactionStore(preferences).load()
            assertThat(restored).isEqualTo(pending)
            val confirmation = Intent.parseUri(restored!!.confirmationUri, Intent.URI_INTENT_SCHEME)
            assertThat(confirmation.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)).isEqualTo(42)
            assertThat(confirmation.`package`).isEqualTo("com.android.packageinstaller")
        }
    }

    @Test
    fun openingInstallerOrRestartingOldVersion_neverCreatesSuccessReceipt() {
        val store = UpdateTransactionStore(preferences)
        store.reconcile("1.0.4", 5L, false)
        store.save(transaction.copy(phase = UpdatePhase.INSTALLING, sessionId = 42))
        assertThat(UpdateTransactionStore(preferences).reconcile("1.0.4", 5L, true)).isNull()
        assertThat(store.load()?.sessionId).isEqualTo(42)
    }

    @Test
    fun installedTarget_clearsTransactionAndKeepsReceiptUntilAcknowledged() {
        val store = UpdateTransactionStore(preferences)
        store.reconcile("1.0.4", 5L, false)
        store.save(transaction)
        assertThat(UpdateTransactionStore(preferences).reconcile("1.0.5", 6L, true)).isEqualTo("1.0.5")
        assertThat(store.load()).isNull()
        assertThat(UpdateTransactionStore(preferences).reconcile("1.0.5", 6L, true)).isEqualTo("1.0.5")
        store.acknowledge()
        assertThat(UpdateTransactionStore(preferences).reconcile("1.0.5", 6L, true)).isNull()
    }

    @Test
    fun updateFromOldUpdater_getsOneReceiptButFreshInstallDoesNot() {
        val store = UpdateTransactionStore(preferences)
        assertThat(store.reconcile("1.0.5", 6L, false)).isNull()
        preferences.edit().clear().commit()
        assertThat(store.reconcile("1.0.5", 6L, true)).isEqualTo("1.0.5")
        store.acknowledge()
        assertThat(store.reconcile("1.0.5", 6L, true)).isNull()
    }

    @Test
    fun targetRequiresNewInstalledCodeAndAtLeastTheRequestedVersion() {
        assertThat(isCompletedUpdate(transaction, "1.0.5", 5L)).isFalse()
        assertThat(isCompletedUpdate(transaction, "1.0.4", 6L)).isFalse()
        assertThat(isCompletedUpdate(transaction, "1.0.5", 6L)).isTrue()
        assertThat(isCompletedUpdate(transaction, "1.0.6", 7L)).isTrue()
    }

    @Test
    fun staleSessionAndResultsAfterCancellation_areIgnored() {
        val installing = transaction.copy(phase = UpdatePhase.INSTALLING, sessionId = 42)
        assertThat(acceptInstallResult(installing, 41)).isFalse()
        assertThat(acceptInstallResult(installing, 42)).isTrue()
        assertThat(acceptInstallResult(installing.copy(phase = UpdatePhase.FAILED), 42)).isFalse()
        val store = UpdateTransactionStore(preferences)
        store.save(installing)
        store.save(null)
        assertThat(UpdateTransactionStore(preferences).load()).isNull()
    }
}
