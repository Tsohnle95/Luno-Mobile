package com.luno.mobile.data.update

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test

class ApkInstallerTest {
    private val installed = ApkIdentity("com.luno.mobile", "1.0.3", 4L, setOf("release-certificate"))
    private val update = installed.copy(versionName = "1.0.4", versionCode = 5L)

    @Test
    fun matchingNewerRelease_isAccepted() {
        validateUpdateIdentity(installed, update, "v1.0.4")
    }

    @Test
    fun differentPackage_isRejected() {
        val failure = assertThrows(IOException::class.java) {
            validateUpdateIdentity(installed, update.copy(packageName = "other.app"), "1.0.4")
        }
        assertThat(failure).hasMessageThat().contains("different app")
    }

    @Test
    fun differentOrMissingSigningCertificate_isRejected() {
        listOf(setOf("debug-certificate"), emptySet()).forEach { signers ->
            val failure = assertThrows(IOException::class.java) {
                validateUpdateIdentity(installed, update.copy(signers = signers), "1.0.4")
            }
            assertThat(failure).hasMessageThat().contains("signing key")
        }
    }

    @Test
    fun sameVersionCodeAndDowngrade_areRejected() {
        listOf(3L, 4L).forEach { versionCode ->
            assertThrows(IOException::class.java) {
                validateUpdateIdentity(installed, update.copy(versionCode = versionCode), "1.0.4")
            }
        }
    }

    @Test
    fun tagAndApkMustAgree() {
        assertThrows(IOException::class.java) { validateUpdateIdentity(installed, update, "1.0.5") }
    }

    @Test
    fun assetMustBelongToLunoPublicRelease() {
        ApkInstaller.requireReleaseAssetUrl("https://github.com/Tsohnle95/Luno-Mobile/releases/download/v1.0.4/Luno-Mobile-v1.0.4.apk".toHttpUrl())
        listOf(
            "http://github.com/Tsohnle95/Luno-Mobile/releases/download/v1.0.4/luno.apk",
            "https://github.com/another/repository/releases/download/v1.0.4/luno.apk",
            "https://github.com.evil.test/Tsohnle95/Luno-Mobile/releases/download/v1.0.4/luno.apk",
            "https://api.github.com/repos/Tsohnle95/Luno-Mobile/releases/assets/1"
        ).forEach { url ->
            assertThrows(IOException::class.java) { ApkInstaller.requireReleaseAssetUrl(url.toHttpUrl()) }
        }
    }
}
