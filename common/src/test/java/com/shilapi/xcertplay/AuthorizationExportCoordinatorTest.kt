package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AuthorizationExportCoordinatorTest {
    private var permitted = true
    private var component = AuthorizationComponentInstaller.State.READY
    private var denied = 0
    private var requests = 0
    private var opens = 0
    private var notices = 0
    private var changes = 0
    private val jobs = mutableListOf<Runnable>()
    private val callbacks = mutableListOf<() -> Unit>()
    private var saved = AuthorizationComponentExport.Saved("Download/component.apk", File("Download"))
    private val export = AuthorizationExportCoordinator(
        { component }, { denied++ }, { !permitted }, { requests++ }, jobs::add,
        { saved }, callbacks::add, { opens++; true }, { notices++ }, { changes++ },
    )

    @Test fun duplicateClicksScheduleOnlyOneExport() {
        export.export(); export.export()
        assertEquals(1, jobs.size)
        jobs.single().run(); callbacks.single()()
        assertFalse(export.pending)
        assertFalse(export.failed)
        assertEquals(saved.path, export.savedPath)
        assertEquals(1, opens)
    }

    @Test fun closingOwnerIgnoresLateCompletion() {
        export.export(); jobs.single().run()
        export.close()
        val before = changes
        callbacks.single()()
        assertEquals(before, changes)
        assertEquals(0, opens)
        assertNull(export.savedPath)
    }

    @Test fun existingFileReportsSavedWithoutOpeningFolder() {
        saved = saved.copy(alreadySaved = true)
        export.export(); jobs.single().run(); callbacks.single()()
        assertTrue(export.alreadySaved)
        assertEquals(1, notices)
        assertEquals(0, opens)
    }

    @Test fun permissionDenialDoesNotStartWorkerAndAllowsExplicitRetry() {
        permitted = false
        export.export()
        assertEquals(1, requests)
        export.permissionResult(false)
        assertTrue(export.storageDenied)
        assertTrue(jobs.isEmpty())
        permitted = true
        export.export()
        assertFalse(export.storageDenied)
        assertEquals(1, jobs.size)
    }

    @Test fun permissionReturnRechecksComponentIdentity() {
        permitted = false
        export.export()
        permitted = true
        component = AuthorizationComponentInstaller.State.EXISTING
        export.permissionResult(true)
        assertEquals(1, denied)
        assertTrue(jobs.isEmpty())
    }
}
