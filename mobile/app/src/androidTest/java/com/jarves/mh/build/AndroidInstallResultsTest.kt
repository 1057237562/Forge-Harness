package com.jarves.mh.build

import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.runtime.AndroidInstallResults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidInstallResultsTest {
    @Test fun olderAttemptCannotOverwriteReinstallationOfSameBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidInstallResults.register(context, 1900000004, "same-build", "dev.forge.sample")
        AndroidInstallResults.register(context, 1900000005, "same-build", "dev.forge.sample")
        assertNull(AndroidInstallResults.record(context, 1900000004, PackageInstaller.STATUS_FAILURE_ABORTED, "old cancellation"))
        assertEquals(1900000005, AndroidInstallResults.record(context, 1900000005, PackageInstaller.STATUS_SUCCESS, "installed")!!.sessionId)
    }
    @Test fun bindsCallbacksToOwnedSessionAndConsumesTerminalResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = 1900000001
        AndroidInstallResults.register(context, session, "build-one", "dev.forge.sample")
        assertNull(AndroidInstallResults.record(context, session + 1, PackageInstaller.STATUS_SUCCESS, "unknown"))
        assertNull(AndroidInstallResults.record(context, session, PackageInstaller.STATUS_SUCCESS, "wrong", "dev.other"))
        val pending = AndroidInstallResults.record(context, session, PackageInstaller.STATUS_PENDING_USER_ACTION, "pending")!!
        assertEquals("build-one", pending.buildId)
        val cancelled = AndroidInstallResults.record(context, session, PackageInstaller.STATUS_FAILURE_ABORTED, "cancelled")!!
        assertEquals("dev.forge.sample", cancelled.packageName)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, cancelled.status)
        assertNull(AndroidInstallResults.record(context, session, PackageInstaller.STATUS_SUCCESS, "late"))
        assertEquals(cancelled, AndroidInstallResults.updates.value)
    }

    @Test fun independentInstallSessionsRetainTheirBuildIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidInstallResults.register(context, 1900000002, "older-build", "dev.forge.sample")
        AndroidInstallResults.register(context, 1900000003, "newer-build", "dev.forge.sample")
        assertEquals("newer-build", AndroidInstallResults.record(context, 1900000003, PackageInstaller.STATUS_SUCCESS, "installed")!!.buildId)
        assertEquals("older-build", AndroidInstallResults.record(context, 1900000002, PackageInstaller.STATUS_FAILURE_ABORTED, "cancelled")!!.buildId)
    }
}
