package com.jarves.mh.build

import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.runtime.AndroidInstallResults
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Run each method in a separate instrumentation process, in the indicated order. */
@RunWith(AndroidJUnit4::class)
class AndroidInstallRecoveryTest {
    @Before fun requiresSeparateProcesses() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("installRecovery") == "true")
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun prepareSession() {
        AndroidInstallResults.register(context, 1900000010, "recovery-build", "dev.forge.recovery")
        assertNull(AndroidInstallResults.lastResult(context))
    }
    @Test fun receiveAfterRestart() {
        assertNull(AndroidInstallResults.updates.value)
        val result = AndroidInstallResults.record(context, 1900000010, PackageInstaller.STATUS_FAILURE_ABORTED, "Installation was cancelled.")!!
        assertEquals("recovery-build", result.buildId)
    }
    @Test fun readAfterSecondRestart() {
        assertNull(AndroidInstallResults.updates.value)
        val result = AndroidInstallResults.lastResult(context)!!
        assertEquals("recovery-build", result.buildId)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, result.status)
        assertNull(AndroidInstallResults.record(context, 1900000010, PackageInstaller.STATUS_SUCCESS, "late"))
        AndroidInstallResults.register(context, 1900000011, "recovery-build", "dev.forge.recovery")
        assertNull(AndroidInstallResults.lastResult(context))
        AndroidInstallResults.record(context, 1900000011, PackageInstaller.STATUS_FAILURE_ABORTED, "Test finished")
    }
}
