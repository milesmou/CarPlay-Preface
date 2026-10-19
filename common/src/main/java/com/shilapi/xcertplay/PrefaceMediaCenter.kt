package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/** A registered OEM source is separate from Android focus and from volume selection. */
internal class PrefaceMediaCenter(
    private val worker: Executor,
    private val factory: () -> Backend,
    command: (Int) -> Boolean,
    private val diagnostic: (String) -> Unit,
) : AutoCloseable {
    interface Backend {
        fun init(snapshot: () -> Snapshot, command: (Int) -> Boolean, ready: (Boolean) -> Unit)
        fun register(): Any?
        fun requestPlay(token: Any): Boolean
        fun publish(token: Any, info: CarPlayNowPlaying): Boolean
        fun disconnect() {}
        fun unregister(token: Any): Boolean
        fun report(): String = ""
    }

    data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val eligible: Boolean = false,
        val request: Long = 0L, val eligibilityEpoch: Long = 0L, val commandsAllowed: Boolean = eligible,
        val recoveryEpoch: Long = 0L)

    @Volatile private var snapshot = Snapshot()
    @Volatile private var dispatch: ((Int) -> Boolean)? = command
    @Volatile private var closed = false
    @Volatile private var registered = false
    @Volatile private var ownsSource = false
    @Volatile private var stage = "initializing"
    @Volatile private var backend: Backend? = null
    @Volatile private var initializationGeneration = 0L
    private var token: Any? = null
    private var handledRequest = 0L
    private var apiRecovery: Snapshot? = null
    private val initializationRetryOwners = mutableListOf<WeakReference<Any>>()
    private var published: CarPlayNowPlaying? = null

    fun start() = worker.execute {
        if (closed) return@execute
        initializeBackend()
    }

    /** An explicit new phone controller may retry a failed startup, never polling or metadata. */
    fun retryInitialization(owner: Any, isCurrent: () -> Boolean = { true }) = worker.execute {
        if (closed || !isCurrent() || registered || !(stage == "api-not-ready" ||
                stage.startsWith("init-failed:") || stage.startsWith("init-unavailable:"))) return@execute
        initializationRetryOwners.removeAll { it.get() == null }
        if (initializationRetryOwners.any { it.get() === owner }) return@execute
        initializationRetryOwners.add(WeakReference(owner))
        // The SDK singleton may retain callbacks from the previous backend. Invalidate them
        // before unregistering its token and replacing the application-owned backend.
        initializationGeneration++
        ownsSource = false
        cleanup()
        backend = null
        initializeBackend()
    }

    private fun initializeBackend() {
        val generation = ++initializationGeneration
        status("initializing")
        attempt("init") {
            val created = factory()
            backend = created
            created.init({ snapshot }, { index ->
                !closed && initializationGeneration == generation && backend === created &&
                    registered && ownsSource && snapshot.eligible && snapshot.commandsAllowed &&
                    dispatch?.invoke(index) == true
            }) { ready -> worker.execute {
                if (initializationGeneration == generation && backend === created) onReady(ready)
            } }
            if (stage == "initializing") status("waiting-api")
        }
    }

    /** Only a real start/resume raises [requestSource]; metadata and polling never claim focus. */
    @Synchronized
    fun update(info: CarPlayNowPlaying, eligible: Boolean, requestSource: Boolean = false,
        commandsAllowed: Boolean = eligible) {
        if (closed) return
        val previous = snapshot
        val allowed = eligible && commandsAllowed
        val request = previous.request + if (requestSource && allowed && info.playing) 1L else 0L
        // A later SDK-ready callback may recover uninterrupted playback, but must not undo a
        // pause, source/focus change or priority command suppression that occurred in between.
        val recoveryCancelled = (previous.eligible && !eligible) ||
            (previous.commandsAllowed && !allowed) || (previous.info.playing && !info.playing)
        val next = Snapshot(info, eligible, request,
            previous.eligibilityEpoch + if (eligible != previous.eligible) 1L else 0L, allowed,
            previous.recoveryEpoch + if (recoveryCancelled) 1L else 0L)
        if (next == previous) return
        snapshot = next
        // Cancel stale work immediately, before the IPC queue catches up.
        if (!eligible) ownsSource = false
        worker.execute { synchronizeSource() }
    }

    private fun onReady(ready: Boolean) {
        if (closed) return
        if (!ready) {
            val desired = snapshot
            if (desired.eligible && desired.commandsAllowed && desired.info.playing &&
                (ownsSource || desired.request > handledRequest)) apiRecovery = desired
            registered = false
            ownsSource = false
            handledRequest = desired.request
            cleanup()
            status("api-not-ready")
            return
        }
        if (token == null) attempt("register") {
            token = backend?.register()
            registered = token != null
            status(if (registered) "registered" else "register-null")
        }
        synchronizeSource()
    }

    private fun synchronizeSource() {
        if (closed) return
        val desired = snapshot
        if (!desired.eligible) {
            ownsSource = false
            handledRequest = desired.request
            return
        }
        val current = token ?: return
        val api = backend ?: return
        val recovering = apiRecovery?.let {
            it.request == desired.request && it.recoveryEpoch == desired.recoveryEpoch &&
                it.eligibilityEpoch == desired.eligibilityEpoch
        } == true
        apiRecovery = null
        if (desired.request > handledRequest || recovering) {
            handledRequest = desired.request
            if (desired.commandsAllowed && desired.info.playing && !ownsSource) attempt("request-play") {
                val accepted = api.requestPlay(current)
                ownsSource = accepted && !closed && snapshot.eligible &&
                    snapshot.eligibilityEpoch == desired.eligibilityEpoch &&
                    (!recovering || (snapshot.commandsAllowed && snapshot.info.playing &&
                        snapshot.recoveryEpoch == desired.recoveryEpoch))
                status(if (accepted) "request-play-accepted" else "request-play-denied")
                published = null
            }
        }
        if (!ownsSource || closed || !snapshot.eligible) return
        val info = snapshot.info
        if (published == info) return
        attempt("publish") {
            if (api.publish(current, info)) published = info else status("publish-rejected")
        }
    }

    /** The reference keeps its process registration but marks its external player disconnected. */
    @Synchronized
    fun disconnect() {
        if (closed) return
        val previous = snapshot
        val next = Snapshot(request = previous.request, eligibilityEpoch = previous.eligibilityEpoch + 1L,
            recoveryEpoch = previous.recoveryEpoch + 1L)
        snapshot = next
        ownsSource = false
        worker.execute {
            // A replacement session may already have updated the same process-owned registration.
            if (!closed && snapshot.eligibilityEpoch == next.eligibilityEpoch && !snapshot.eligible) {
                handledRequest = next.request
                published = null
                attempt("disconnect") { backend?.disconnect() }
            }
        }
    }

    override fun close() {
        closed = true
        registered = false
        ownsSource = false
        // The SDK may retain its ready callback after unregister; release renderer/activity owners.
        dispatch = null
        worker.execute { cleanup(); status("closed") }
    }

    private fun cleanup() {
        val old = token ?: return
        token = null
        published = null
        attempt("unregister") {
            status(if (backend?.unregister(old) == true) "unregistered" else "unregister-rejected")
        }
    }

    private inline fun attempt(phase: String, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            status("$phase-failed:${rootCause(error).javaClass.simpleName}")
            runCatching { diagnostic("OEM media diagnostic phase=$phase ${MediaReadinessDiagnostics.failure(error)}") }
        } catch (error: LinkageError) {
            status("$phase-unavailable:${error.javaClass.simpleName}")
        }
    }

    private fun status(value: String) {
        stage = value
        runCatching { diagnostic("OEM media center $value") }
    }

    data class Status(val registered: Boolean, val ownsSource: Boolean)
    fun status() = Status(registered, ownsSource)
    fun report(): String = "OEM media center default=true stage=$stage registered=$registered sourceOwned=$ownsSource " +
        "commandsAllowed=${snapshot.commandsAllowed} " +
        (backend?.report() ?: "metadataCapabilities=not-initialized")

    private fun rootCause(error: Throwable): Throwable {
        var result = error
        while (result.cause != null && result.cause !== result) result = result.cause!!
        return result
    }
}
