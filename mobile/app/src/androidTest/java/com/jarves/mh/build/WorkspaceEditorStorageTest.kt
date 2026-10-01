package com.jarves.mh.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forge.build.WorkspaceTextFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceEditorStorageTest {
    @Test fun savesCompleteDocumentPreservesModeAndRefusesConcurrentReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "workspaces/editor-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(root, "script.sh").apply { writeText("\ufeff#!/bin/sh\r\necho 中文\r\n"); setExecutable(true, true) }
        val opened = WorkspaceTextFile.open(root, "script.sh")
        val saved = opened.save(root, opened.text + "echo saved\r\n")
        assertEquals("\ufeff#!/bin/sh\r\necho 中文\r\necho saved\r\n", file.readText())
        assertTrue(file.canExecute())
        file.writeText("external Agent update")
        try { saved.save(root, "unsaved user draft"); fail("Must retain the newer external edit") }
        catch (expected: IOException) { assertTrue(expected.message!!.contains("changed")) }
        assertEquals("external Agent update", file.readText())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".forge-edit-") })
    }
}
