package com.jarves.mh.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.EditorDraftStore
import dev.forge.build.WorkspaceTextFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Run prepare, force-stop the app, then run recover in a new instrumentation process. */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class EditorDraftRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.cacheDir, "draft-recovery-test").apply { mkdirs() }
    private fun store() = EditorDraftStore(File(context.filesDir, "draft-recovery-test-store"))
    @Test fun prepareDraft() {
        File(root, "source.txt").writeText("saved source")
        val doc = WorkspaceTextFile.open(root, "source.txt")
        store().update("test-project", "source.txt", doc.sha256, "unsaved 中文 draft", 1)
        assertEquals("unsaved 中文 draft", store().load("test-project", "source.txt").text)
    }
    @Test fun recoverDraftAfterProcessDeath() {
        val draft = store().load("test-project", "source.txt")
        assertNotNull("Run prepareDraft before force-stopping the app", draft)
        assertEquals("unsaved 中文 draft", draft.text)
        assertEquals("saved source", File(root, "source.txt").readText())
        File(root, "source.txt").writeText("external update")
        val current = WorkspaceTextFile.open(root, "source.txt").withExpectedHash(draft.baseHash)
        try { current.save(root, draft.text); fail("Must preserve the external update") }
        catch (expected: IOException) { assertTrue(expected.message!!.contains("changed")) }
        assertEquals("external update", File(root, "source.txt").readText())
        store().update("test-project", "source.txt", null, null, 2)
        assertNull(store().load("test-project", "source.txt"))
    }
}
