package com.jarves.mh.build

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarves.mh.runtime.AndroidAppInstaller
import com.jarves.mh.runtime.AndroidAppInstallReceiver
import com.jarves.mh.runtime.AndroidInstallResults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidInstallReceiverTest {
    @Test fun missingApprovalIntentBecomesFailureAndCannotLaterSucceed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = 1900000020
        AndroidInstallResults.register(context, session, "missing-prompt", "dev.forge.sample")
        val callback = Intent(AndroidAppInstaller.ACTION_INSTALL_RESULT)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        instrumentation.runOnMainSync { AndroidAppInstallReceiver().onReceive(context, callback) }
        val result = AndroidInstallResults.lastResult(context)!!
        assertEquals(PackageInstaller.STATUS_FAILURE, result.status)
        assertTrue(result.message.contains("Could not open installation prompt"))
        callback.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
        instrumentation.runOnMainSync { AndroidAppInstallReceiver().onReceive(context, callback) }
        assertEquals(result, AndroidInstallResults.lastResult(context))
    }

    @Test fun unknownSessionCannotLaunchSuppliedApprovalIntent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = AndroidInstallResults.lastResult(context)
        val callback = Intent(AndroidAppInstaller.ACTION_INSTALL_RESULT)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 1900000021)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("dev.forge.NONEXISTENT_ACTIVITY"))
        instrumentation.runOnMainSync { AndroidAppInstallReceiver().onReceive(context, callback) }
        assertEquals(before, AndroidInstallResults.lastResult(context))
    }
}
