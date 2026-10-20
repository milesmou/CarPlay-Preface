package com.shilapi.xcertplay

/** One export request, including permission and worker completion. Called on the UI thread. */
internal class AuthorizationExportCoordinator(
    private val componentState: () -> AuthorizationComponentInstaller.State,
    private val unavailable: (AuthorizationComponentInstaller.State) -> Unit,
    private val needsPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val worker: (Runnable) -> Unit,
    private val prepare: () -> AuthorizationComponentExport.Saved,
    private val onUi: (() -> Unit) -> Unit,
    private val openFolder: (AuthorizationComponentExport.Saved) -> Boolean,
    private val alreadySavedNotice: (String) -> Unit,
    private val changed: () -> Unit,
) : AutoCloseable {
    var pending = false; private set
    var failed = false; private set
    var savedPath: String? = null; private set
    var alreadySaved = false; private set
    var folderUnavailable = false
    var storageDenied = false; private set
    private var closed = false
    private var generation = 0

    fun permissionResult(granted: Boolean) {
        if (closed) return
        pending = false
        if (granted) export() else { storageDenied = true; changed() }
    }

    fun export() {
        if (closed || pending) return
        val component = componentState()
        if (!AuthorizationComponentInstaller.canExport(component)) { unavailable(component); return }
        storageDenied = false
        pending = true
        if (needsPermission()) {
            runCatching(requestPermission).onFailure { pending = false; storageDenied = true; changed() }
            return
        }
        failed = false
        folderUnavailable = false
        val request = ++generation
        changed()
        try {
            worker(Runnable {
                val result = runCatching(prepare)
                onUi {
                    if (!closed && request == generation) {
                        pending = false
                        failed = result.fold({ saved ->
                            savedPath = saved.path
                            alreadySaved = saved.alreadySaved
                            folderUnavailable = if (saved.alreadySaved) {
                                alreadySavedNotice(saved.path)
                                false
                            } else !openFolder(saved)
                            false
                        }, { true })
                        changed()
                    }
                }
            })
        } catch (_: RuntimeException) {
            pending = false
            failed = true
            changed()
        }
    }

    override fun close() { closed = true; generation++; pending = false }
}
