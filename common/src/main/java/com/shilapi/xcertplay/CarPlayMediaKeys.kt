package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; some head units pick the session of the
 * audio-focus owner. DiPlay normally holds focus and an active session until the CarPlay session
 * ends, but focus can be disabled on head units that otherwise route physical volume buttons to
 * DiPlay. Keys go to the iPhone as CarPlay media HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val SIRI_REPEAT_GAP_MILLIS = 400L
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeMediaArtwork,
        publish = ::onArtworkDecoded,
        discard = { it.bitmap.recycle() },
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private val learnedHeld = mutableSetOf<Pair<String, Int>>()
    // A long-click broadcast can precede physical UP by seconds. Retain its acceptance
    // until each observed press is released, independently of the short echo window.
    private val siriHandledPresses = mutableSetOf<Pair<String, Int>>()
    private var mediaAudioOwner: Any? = null
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null
    private var prefaceReceiver: PrefaceMediaButtonReceiver? = null
    private var trackInterception: PrefaceTrackKeyInterception? = null
    private var mediaCenter: PrefaceMediaCenter? = null
    private var audioDiagnostics: CarAudioDiagnostics? = null
    private val mediaCenterHistory = ArrayDeque<String>()
    private const val MEDIA_CENTER_HISTORY_LIMIT = 64
    // Linkco initializes the SDK directly from its controller and registers inside onAPIReady.
    // Keep that synchronous ordering on the application thread, including a synchronous ready callback.
    private val mediaCenterWorker = Executor { task ->
        if (Looper.myLooper() === Looper.getMainLooper() && !Thread.holdsLock(this)) task.run()
        else mainHandler.post(task)
    }
    private val wheelMediaEchoes = PrefaceMediaKeyEchoes()
    private var prefaceVoiceReceiver: PrefaceVoiceButtonReceiver? = null
    private var lastPrefaceSiriRequest: Long? = null
    private var foreground: () -> Boolean = { false }
    private var diagnostic: (String) -> Unit = {}
    // Observe call/Bluetooth/window changes even while paused, without reacquiring audio focus.
    private val trackOwnershipCheck = object : Runnable {
        override fun run() {
            val interception = trackInterception ?: return
            // The OEM observer also delivers the press to its input service. It cannot consume
            // volume events, so writing another stream here would adjust both media and guidance.
            interception.update(trackKeysEligible())
            synchronizeMediaCenter()
            if (trackInterception === interception) mainHandler.postDelayed(this, 250L)
        }
    }

    /** Register the application's OEM source before a phone/controller exists; never claim playback. */
    @Synchronized
    fun initialize(context: Context, worker: Executor = mediaCenterWorker,
        backendFactory: (Context, (String) -> Unit) -> PrefaceMediaCenter.Backend = { application, report ->
            PrefaceMediaCenterBackend(application, diagnostic = report)
        }) {
        if (mediaCenter != null) return
        val application = context.applicationContext
        appContext = application
        lateinit var owned: PrefaceMediaCenter
        val report: (String) -> Unit = { message ->
            mainHandler.post {
                synchronized(this) {
                    if (mediaCenter === owned) {
                        mediaCenterHistory.addLast(message)
                        while (mediaCenterHistory.size > MEDIA_CENTER_HISTORY_LIMIT) mediaCenterHistory.removeFirst()
                        Log.i(TAG, message)
                        reportDiagnostic(message)
                    }
                }
            }
        }
        owned = PrefaceMediaCenter(worker, { backendFactory(application, report) },
            command = { index -> onMediaCenterCommand(owned, index) }, diagnostic = report)
        mediaCenter = owned
        owned.start()
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController, diagnostic: (String) -> Unit = {},
        mediaOwner: Any = next, foreground: () -> Boolean = { false }) {
        val changingController = controller !== next
        val adoptingStartupRegistration = controller == null && mediaCenter != null
        if (controller !== next) {
            controller?.bluetoothHandoffListener = null
            releaseLocked(keepStartupRegistration = true)
            learnedHeld.clear()
            siriHandledPresses.clear()
            artworkOwner = artworkQueue.newSession()
            mediaAudioOwner = mediaOwner
            lateinit var ownedCallback: CarPlayMediaCallback
            ownedCallback = CarPlayMediaCallback(
                eligible = { synchronized(this) { callback === ownedCallback && controller === next } },
                send = { index, source -> send(index, "$source caller=${mediaControllerPackage()}") },
                hardware = { event -> prefaceReceiver?.handle(event) },
            )
            callback = ownedCallback
        }
        appContext = context.applicationContext
        controller = next
        this.diagnostic = diagnostic
        this.foreground = foreground
        if (audioDiagnostics == null) {
            audioDiagnostics = CarAudioDiagnostics(context.applicationContext, ::reportDiagnostic).also { it.start() }
        }
        registerPrefaceVoiceReceiver(context, next)
        if (!next.usesCarBluetoothAudio) registerPrefaceReceiver(context, next)
        else reportDiagnostic("Media route=car-bluetooth; wheel keys and music remain vehicle-owned")
        if (adoptingStartupRegistration) {
            mediaCenterHistory.forEach(::reportDiagnostic)
            reportDiagnostic("OEM media center startup registration adopted")
        }
        initialize(context)
        if (changingController && !next.usesCarBluetoothAudio) mediaCenter?.retryInitialization(next) {
            synchronized(this) { controller === next && !next.usesCarBluetoothAudio }
        }
        next.playbackListener = { playing ->
            onIphonePlaying(next, playing)
        }
        next.nowPlayingListener = { _ -> onNowPlayingChanged(next) }
        val mediaContext = context.applicationContext
        next.bluetoothHandoffListener = { ready ->
            mainHandler.post {
                synchronized(this) {
                    if (controller !== next || next.usesCarBluetoothAudio) return@synchronized
                    if (!ready) {
                        val audio = appContext?.getSystemService(AudioManager::class.java)
                        if (audio != null) focusRequest?.abandon(audio)
                        focusRequest = null; focusOwner = null; focusHeld = false
                        session?.isActive = false
                    } else if (mediaAudioActive) {
                        if (session == null || !next.managesPlaybackAudioFocus) {
                            session?.release(); session = null
                            start(mediaContext)
                        }
                    }
                    refreshTrackOwnership()
                    synchronizeMediaCenter(requestSource = ready && mediaAudioActive)
                }
            }
        }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        val retained = next.nowPlayingSnapshot()
        onNowPlayingChanged(next)
        next.matchingArtworkSnapshot(retained.artworkTransferId)?.let { onArtworkChanged(next, it.id, it.bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        expected.bluetoothHandoffListener = null
        controller = null
        releaseLocked(keepStartupRegistration = true)
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean, expectedOwner: Any? = synchronized(this) { mediaAudioOwner }) {
        mainHandler.post {
            synchronized(this) {
                if (expectedOwner != null && mediaAudioOwner === expectedOwner) updateLocked(active)
            }
        }
    }

    @Synchronized
    fun updateForeground(expected: CarPlayController, diagnostic: ((String) -> Unit)? = null,
        foreground: () -> Boolean) {
        if (controller !== expected) return
        this.foreground = foreground
        if (diagnostic != null) this.diagnostic = diagnostic
        refreshTrackOwnership()
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                reportDiagnostic("Media iPhone playing=$playing focusHeld=$focusHeld mediaStreamActive=$mediaAudioActive")
                audioDiagnostics?.sample(if (playing) "iphone_play" else "iphone_pause")
                if (playing && !expected.usesCarBluetoothAudio) regainFocusLocked()
                synchronizeMediaCenter(requestSource = playing)
            }
            val retained = CarPlayBackgroundSession.snapshot()
            if (retained?.controller === expected) retained.sink.onIphoneMediaPlaying(playing)
        }
    }

    /** The PCM renderer is the focus owner; media keys observe the same grant without a second request. */
    fun onPlaybackFocusChanged(expected: CarPlayController, held: Boolean) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected || !expected.managesPlaybackAudioFocus || expected.usesCarBluetoothAudio) return@synchronized
                val gained = held && !focusHeld
                focusHeld = held
                session?.isActive = held
                refreshTrackOwnership()
                synchronizeMediaCenter(requestSource = gained && mediaAudioActive)
                reportDiagnostic("Media playback focus held=$held delegated=true")
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                // Resolve retained state at delivery: a queued callback cannot roll a replay back.
                val update = expected.nowPlayingSnapshot()
                val previousArtwork = artwork
                if (albumTrackChanged(nowPlaying, update)) {
                    val previousId = nowPlaying.artworkTransferId
                    if (previousId != null) {
                        artworkCache.remove(previousId)
                    }
                    if (previousId == update.artworkTransferId) {
                        artworkOwner = artworkQueue.newSession()
                    }
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                if (metadataChanged) ClusterMusicState.publish(update, update.artworkTransferId?.let { artworkCache[it] })
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on a DiLink 5.0 Tang that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
                publishPlaybackStateLocked()
                synchronizeMediaCenter()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, result: DecodedArtwork?) {
        val decoded = result?.bitmap
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            ClusterMusicState.publish(nowPlaying, artwork)
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        if (controller?.bluetoothHandoffReady != true) return
        val request = focusRequest ?: return
        if (focusHeld) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = request.request(audio) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) forwardGrantedFocusLocked()
        Log.i(TAG, "audio focus regained=$focusHeld")
        reportDiagnostic("Media focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        val started = active && !mediaAudioActive
        mediaAudioActive = active
        reportDiagnostic("Media stream active=$active focusHeld=$focusHeld")
        if (controller?.usesCarBluetoothAudio != true) {
            if (active && session == null) start(context) else if (active) regainFocusLocked()
        }
        publishPlaybackStateLocked()
        refreshTrackOwnership()
        synchronizeMediaCenter(requestSource = started)
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        if (expectedController.usesCarBluetoothAudio || !expectedController.bluetoothHandoffReady) return
        val focusDisabled = AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)
        val delegated = expectedController.managesPlaybackAudioFocus
        val granted = if (delegated) {
            focusOwner = null
            focusRequest = null
            focusHeld
        } else if (focusDisabled) {
            // Some OEMs route physical volume buttons to the focus owner. Keep an active
            // MediaSession without permanent focus so those buttons remain vehicle-owned.
            focusOwner = null
            focusRequest = null
            focusHeld = false
            false
        } else {
            val owner = Any().also { focusOwner = it }
            focusEventRevision = 0L
            val audio = context.getSystemService(AudioManager::class.java)
            val request = AudioFocusRequestCompat(
                AudioManager.AUDIOFOCUS_GAIN,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                { change -> onFocusChanged(expectedController, owner, change) },
                mainHandler,
            )
            focusRequest = request
            (audio?.let(request::request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {
                focusHeld = it
                if (it) forwardGrantedFocusLocked()
            }
        }
        session = MediaSession(context, "DiPlay CarPlay").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(callback, mainHandler)
            setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            isActive = if (delegated) granted else true
        }
        Log.i(TAG, "media keys active focusDisabled=$focusDisabled focusGranted=$granted")
        reportDiagnostic("Media keys active focusDisabled=$focusDisabled focusGranted=$granted")
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                // Only permanent loss moves media keys elsewhere; transient losses come back.
                if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
                else if (change == AudioManager.AUDIOFOCUS_GAIN) focusHeld = true
                if (change == AudioManager.AUDIOFOCUS_LOSS) session?.isActive = false
                else if (change == AudioManager.AUDIOFOCUS_GAIN) session?.isActive = true
                true
            }
        }
        if (!current) return
        refreshTrackOwnership()
        Log.i(TAG, "audio focus change=$change")
        reportDiagnostic("Media focus change=$change")
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked(keepStartupRegistration: Boolean = false) {
        audioDiagnostics?.close()
        audioDiagnostics = null
        if (keepStartupRegistration) {
            mediaCenter?.disconnect()
        } else {
            mediaCenter?.close()
            mediaCenter = null
            mediaCenterHistory.clear()
        }
        mediaAudioOwner = null
        callback = null
        learnedHeld.clear()
        siriHandledPresses.clear()
        wheelMediaEchoes.clear()
        mainHandler.removeCallbacks(trackOwnershipCheck)
        trackInterception?.close()
        trackInterception = null
        prefaceVoiceReceiver?.let { receiver -> runCatching { appContext?.unregisterReceiver(receiver) } }
        prefaceVoiceReceiver = null
        lastPrefaceSiriRequest = null
        prefaceReceiver?.let { receiver -> runCatching { appContext?.unregisterReceiver(receiver) } }
        prefaceReceiver = null
        foreground = { false }
        focusOwner = null
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.let(request::abandon) }
        focusRequest = null
        focusHeld = false
        diagnostic = {}
    }

    private fun reportDiagnostic(message: String) { WheelKeyLog.note(message); runCatching { diagnostic(message) } }


    @Synchronized
    fun report(): String = "Media keys controllerAttached=${controller != null} mediaSessionActive=${session?.isActive == true} " +
        "carBluetoothRoute=${controller?.usesCarBluetoothAudio == true} " +
        "focusRequested=${focusRequest != null} focusHeld=$focusHeld mediaStreamActive=$mediaAudioActive " +
        "broadcastRegistered=${prefaceReceiver != null} voiceBroadcastRegistered=${prefaceVoiceReceiver != null} " +
        "siriBlocked=${siriUnavailableReason() ?: "none"}; ${trackInterception?.report() ?: "Track interception not initialized"}; " +
        (mediaCenter?.report() ?: "OEM media center not initialized")

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    private fun send(index: Int, source: String) {
        // Transport callbacks already name an action; only raw KeyEvents use learned key codes.
        dispatchMedia(index, source)
    }

    /** Only the system-reported package, never PID/UID or metadata, belongs in the report. */
    private fun mediaControllerPackage(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { session?.currentControllerInfo?.packageName }
            .getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) } ?: "unavailable"
    } else "unavailable"

    private fun dispatchMedia(index: Int, source: String): Boolean {
        // Recheck before echo matching too: an old successful press must not acknowledge a new
        // command during a call, after focus loss, or after the controller disconnects.
        if (!mediaCommandsEligible()) return false
        val origin = when {
            source == "preface-intercept" -> PrefaceMediaKeyEchoes.Source.INPUT
            source == "preface-wheel" -> PrefaceMediaKeyEchoes.Source.ANDROID_KEY
            source == "preface-media-center" -> PrefaceMediaKeyEchoes.Source.MEDIA_CENTER
            source.startsWith("next caller=") || source.startsWith("previous caller=") ||
                source.startsWith("play caller=") || source.startsWith("pause caller=") ->
                PrefaceMediaKeyEchoes.Source.SESSION_CONTROL
            else -> null
        }
        if (origin != null) {
            var forwarded = false
            val result = wheelMediaEchoes.dispatch(index, origin) {
                forwarded = true
                sendMediaCommand(index, source)
            }
            if (!forwarded) reportDiagnostic("Media command source=$source index=$index suppressed=paired-wheel-echo")
            return result
        }
        return sendMediaCommand(index, source)
    }

    private fun sendMediaCommand(index: Int, source: String): Boolean {
        if (!mediaCommandsEligible()) return false
        val context = appContext ?: return false
        val defaultCode = when (index) {
            CarPlayMediaButton.NEXT -> 87
            CarPlayMediaButton.PREVIOUS -> 88
            CarPlayMediaButton.PLAY_PAUSE -> 85
            CarPlayMediaButton.PLAY -> 126
            CarPlayMediaButton.PAUSE -> 127
            else -> null
        }
        if (defaultCode != null && WheelKeyService.mediaKeyReserved(defaultCode)) return true
        if (index in CarPlayMediaButton.PLAY..CarPlayMediaButton.PAUSE && WheelKeyService.mediaKeyReserved(85)) return true
        val action = when (index) {
            CarPlayMediaButton.NEXT -> WheelMediaBindings.Action.NEXT
            CarPlayMediaButton.PREVIOUS -> WheelMediaBindings.Action.PREVIOUS
            else -> null
        }
        if (action != null) {
            val code = WheelMediaBindings.key(context, action) ?: return false
            if (WheelKeyService.mediaKeyReserved(code))
                return true // The active joystick owns this gesture, including parallel OEM callbacks.
        }
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            reportDiagnostic("Media command source=$source index=$index target=carVideo")
            return true
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
        reportDiagnostic("Media command source=$source index=$index sent=$sent focusHeld=$focusHeld")
        audioDiagnostics?.sample("media_command")
        return sent
    }

    /** Null retains ordinary Android key handling when no CarPlay receiver owns the window. */
    internal fun onWindowMediaKey(event: KeyEvent): Boolean? {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val receiver = synchronized(this) { prefaceReceiver }
        if (receiver != null) return receiver.handle(event)
        if (synchronized(this) { controller == null }) return null
        if (usesCarBluetoothAudio() && CarPlayMediaButton.belongsToCarBluetooth(event.keyCode)) return null
        return handleLearnedMediaKey(event.action == KeyEvent.ACTION_DOWN,
            event.keyCode, "preface-wheel", event.repeatCount == 0)
    }

    internal fun mediaBindingsChanged() {
        mainHandler.post {
            synchronized(this) {
                learnedHeld.clear()
                siriHandledPresses.clear()
                wheelMediaEchoes.clear()
                trackInterception?.close()
                refreshTrackOwnership()
            }
        }
    }

    /** Shares bindings across the input observer, Android window/session and optional key service. */
    internal fun handleLearnedMediaKey(down: Boolean, code: Int, source: String, first: Boolean = true): Boolean? {
        if (WheelMediaLearning.offer(down, code, first)) return true
        val context = appContext ?: return null
        if (!WheelMediaBindings.known(context, code)) return null
        val press = source to WheelMediaBindings.canonical(code)
        val released = if (!down) learnedHeld.remove(press) else false
        val siriAlreadyHandled = if (!down) siriHandledPresses.remove(press) else false
        val action = WheelMediaBindings.resolve(context, code) ?: return false
        if (!(if (action == WheelMediaBindings.Action.SIRI) siriKeyEligible() else mediaCommandsEligible()))
            return if (released) true else false
        if (WheelKeyService.mediaKeyReserved(code)) return true
        if (action == WheelMediaBindings.Action.SIRI) {
            if (down && first) learnedHeld.add(press)
            if (!down && released && !siriAlreadyHandled) requestSiriForPress(press.second)
            return true
        }
        if (!down || !first) return true
        if (source == "preface-intercept" && !learnedHeld.add(press)) return true
        val sent = dispatchMedia(if (action == WheelMediaBindings.Action.NEXT) CarPlayMediaButton.NEXT
            else CarPlayMediaButton.PREVIOUS, source)
        if (!sent) learnedHeld.remove(press)
        return sent
    }

    data class TakeoverStatus(val connected: Boolean, val bluetooth: Boolean,
        val input: PrefaceTrackKeyInterception.Status?, val media: PrefaceMediaCenter.Status?)
    @Synchronized internal fun takeoverStatus() = TakeoverStatus(controller != null && CarPlayBackgroundSession.active,
        usesCarBluetoothAudio(), trackInterception?.status(), mediaCenter?.status())

    private var callback: CarPlayMediaCallback? = null

    /** The E02 voice event belongs to the connected projection, independently of its music route. */
    private fun registerPrefaceVoiceReceiver(context: Context, expected: CarPlayController) {
        if (prefaceVoiceReceiver != null) return
        lateinit var voiceReceiver: PrefaceVoiceButtonReceiver
        voiceReceiver = PrefaceVoiceButtonReceiver {
            if (synchronized(this) { controller !== expected || prefaceVoiceReceiver !== voiceReceiver }) false
            else {
                val blocked = when {
                    WheelMediaLearning.offer(true, 298) -> "key-learning"
                    WheelMediaBindings.resolve(context, 298) != WheelMediaBindings.Action.SIRI -> "binding-changed"
                    else -> siriUnavailableReason()
                }
                if (blocked != null) {
                    reportDiagnostic("Siri voice broadcast ignored reason=$blocked")
                    false
                } else {
                    val accepted = requestSiriForPress(WheelMediaBindings.canonical(298))
                    reportDiagnostic("Siri voice broadcast accepted=$accepted carBluetoothRoute=${expected.usesCarBluetoothAudio}")
                    accepted
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, voiceReceiver,
                IntentFilter(PrefaceVoiceButtonReceiver.ACTION).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    priority = 1000
                }, ContextCompat.RECEIVER_EXPORTED)
        }.onSuccess {
            prefaceVoiceReceiver = voiceReceiver
            reportDiagnostic("Siri voice broadcast receiver registered carBluetoothRoute=${expected.usesCarBluetoothAudio}")
        }.onFailure {
            Log.w(TAG, "Preface voice broadcast receiver unavailable", it)
            reportDiagnostic("Siri voice broadcast receiver unavailable failureClass=${it.javaClass.simpleName}")
        }
    }

    private fun registerPrefaceReceiver(context: Context, expected: CarPlayController) {
        if (prefaceReceiver != null) return
        trackInterception = PrefaceTrackKeyInterception(
            PrefaceInputInterceptionBackend(deliver = { task -> mainHandler.post { task() } }, diagnostic = ::reportDiagnostic,
                additionalKeys = { WheelMediaBindings.requestedCodes(context) }),
            diagnostic = ::reportDiagnostic,
            releaseKeys = {
                learnedHeld.removeAll { it.first == "preface-intercept" }
                siriHandledPresses.removeAll { it.first == "preface-intercept" }
            },
            handleKey = { down, key -> handleLearnedMediaKey(down, key, "preface-intercept") != null },
            siri = { requestPrefaceSiri() },
        ) { index ->
            // Recheck on delivery: a call or focus loss can precede the periodic ownership check.
            if (trackKeysEligible()) dispatchMedia(index, "preface-intercept") else refreshTrackOwnership()
        }
        refreshTrackOwnership()
        val receiver = PrefaceMediaButtonReceiver(
            eligible = {
                // Siri keeps its own toggle/release policy; media execution is checked centrally.
                synchronized(this) { controller === expected } && (trackKeysEligible() || siriKeyEligible())
            },
            send = { dispatchMedia(it, "preface-wheel") },
            route = { event -> handleLearnedMediaKey(event.action == KeyEvent.ACTION_DOWN, event.keyCode,
                "preface-wheel", event.repeatCount == 0) },
        )
        runCatching {
            // The sender is the separate system input-service process.
            ContextCompat.registerReceiver(context.applicationContext, receiver,
                IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                ContextCompat.RECEIVER_EXPORTED)
        }.onSuccess {
            prefaceReceiver = receiver
            Log.i(TAG, "IHU519G media broadcast receiver registered")
        }.onFailure { Log.w(TAG, "IHU519G media broadcast receiver unavailable", it) }
    }

    private fun refreshTrackOwnership() {
        mainHandler.removeCallbacks(trackOwnershipCheck)
        mainHandler.post(trackOwnershipCheck)
    }

    private fun synchronizeMediaCenter(requestSource: Boolean = false) {
        // Linkco clears its shared playback record on audio-stream stop. An older iPhone
        // metadata playing=true must not leave the OEM record playing after that stop.
        val playing = mediaAudioActive && (controller?.mediaPlayingSnapshot() ?: true)
        mediaCenter?.update(nowPlaying.copy(playing = playing), mediaOwnerEligible(), requestSource,
            commandsAllowed = mediaCommandsEligible())
    }

    private fun mediaCommandsEligible(): Boolean = trackKeysEligible() &&
        !com.shilapi.xcertplay.media.NavigationPlayback.snapshot().priorityVoiceActive &&
        (lastPrefaceSiriRequest?.let { SystemClock.uptimeMillis() - it < 1500L } != true)

    /** Like the reference EventBus path, Binder acknowledges queued keys without waiting for main. */
    private fun onMediaCenterCommand(owner: PrefaceMediaCenter, index: Int): Boolean {
        val target = synchronized(this) {
            val expected = controller ?: return false
            if (mediaCenter !== owner || !mediaCommandsEligible()) return false
            expected to callback
        }
        val action = {
            synchronized(this) {
                controller === target.first && callback === target.second && mediaCenter === owner &&
                    mediaCommandsEligible() && dispatchMedia(index, "preface-media-center")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        return mainHandler.post { action() }
    }

    /** Actual source eligibility is retained across temporary call/Siri suppression. */
    private fun mediaOwnerEligible(): Boolean {
        val ownsMedia = synchronized(this) {
            controller != null && CarPlayBackgroundSession.active &&
                (focusHeld || (focusRequest == null && controller?.managesPlaybackAudioFocus != true && foreground()))
        }
        return ownsMedia && !usesCarBluetoothAudio()
            && controller?.bluetoothHandoffReady == true
    }

    private fun trackKeysEligible(): Boolean =
        appContext?.let { mediaOwnerEligible() && !inCall(it) } == true

    internal fun usesCarBluetoothAudio(): Boolean = synchronized(this) { controller?.usesCarBluetoothAudio == true }

    private fun requestSiriForPress(code: Int): Boolean {
        if (!siriKeyEligible()) return false
        if (siriHandledPresses.any { it.second == code }) return true
        if (!requestPrefaceSiri()) return false
        siriHandledPresses.addAll(learnedHeld.filter { it.second == code })
        return true
    }

    private fun requestPrefaceSiri(): Boolean {
        val context = appContext ?: return false
        if (!siriKeyEligible() || WheelMediaBindings.key(context, WheelMediaBindings.Action.SIRI) == null) return false
        val now = SystemClock.uptimeMillis()
        val previous = lastPrefaceSiriRequest
        if (previous != null && now - previous in 0 until SIRI_REPEAT_GAP_MILLIS) return true
        val sent = synchronized(this) { controller }?.requestSiri() == true
        if (sent) lastPrefaceSiriRequest = now
        Log.i(TAG, "Preface voice key -> Siri sent=$sent")
        reportDiagnostic("Siri request sent=$sent")
        return sent
    }

    private fun siriKeyEligible(): Boolean = siriUnavailableReason() == null

    private fun siriUnavailableReason(): String? {
        val context = appContext ?: return "context-unavailable"
        if (synchronized(this) { controller == null } || !CarPlayBackgroundSession.active) return "session-inactive"
        if (inCall(context)) return "call-active"
        // An explicit voice press in the visible projection must work before music gains focus.
        // In the background retain media ownership; native Bluetooth has its own music owner.
        if (!usesCarBluetoothAudio() && !foreground() && !synchronized(this) { focusHeld }) return "source-not-active"
        return null
    }

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawable(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private data class DecodedArtwork(val bitmap: Bitmap)

    private fun decodeMediaArtwork(bytes: ByteArray): DecodedArtwork? = decodeArtwork(bytes)?.let(::DecodedArtwork)

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * Fits [source] inside a transparent square of at most [MAX_ARTWORK_DIMENSION]. Car clusters draw
     * art in a square box and stretch it, so 16:9 video thumbnails looked squashed.
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Explicit play/pause keys and controller commands keep
 * their intent. Retired sessions must reject queued commands before touching the current receiver.
 */
internal class CarPlayMediaCallback(
    private val hardware: ((KeyEvent) -> Boolean?)? = null,
    private val eligible: () -> Boolean = { true },
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        if (!eligible()) return false
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        hardware?.invoke(event)?.let { return it }
        val index = CarPlayMediaButton.forKeyCode(event.keyCode)
            ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() { if (eligible()) send(CarPlayMediaButton.PLAY, "play") }
    override fun onPause() { if (eligible()) send(CarPlayMediaButton.PAUSE, "pause") }
    override fun onSkipToNext() { if (eligible()) send(CarPlayMediaButton.NEXT, "next") }
    override fun onSkipToPrevious() { if (eligible()) send(CarPlayMediaButton.PREVIOUS, "previous") }
}
