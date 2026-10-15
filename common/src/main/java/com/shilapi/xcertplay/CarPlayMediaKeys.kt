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
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
    // Shared across owners: unregister the retired token before registering its replacement.
    private val mediaCenterWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-oem-media").apply { isDaemon = true }
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
        val adoptingStartupRegistration = controller == null && mediaCenter != null
        if (controller !== next) {
            releaseLocked(keepStartupRegistration = controller == null)
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
        registerPrefaceReceiver(context, next)
        if (adoptingStartupRegistration) {
            mediaCenterHistory.forEach(::reportDiagnostic)
            reportDiagnostic("OEM media center startup registration adopted")
        }
        initialize(context)
        next.playbackListener = { playing ->
            onIphonePlaying(next, playing)
        }
        next.nowPlayingListener = { _ -> onNowPlayingChanged(next) }
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
        controller = null
        releaseLocked()
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
                if (playing) regainFocusLocked()
                synchronizeMediaCenter(requestSource = playing)
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
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
        refreshTrackOwnership()
        synchronizeMediaCenter(requestSource = started)
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        val focusDisabled = AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)
        val granted = if (focusDisabled) {
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
            isActive = true
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
        if (!keepStartupRegistration) {
            mediaCenter?.close()
            mediaCenter = null
            mediaCenterHistory.clear()
        }
        mediaAudioOwner = null
        callback = null
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

    private fun reportDiagnostic(message: String) { runCatching { diagnostic(message) } }

    internal fun reportWheelVolume(message: String) = reportDiagnostic(message)

    @Synchronized
    fun report(): String = "Media keys controllerAttached=${controller != null} mediaSessionActive=${session?.isActive == true} " +
        "focusRequested=${focusRequest != null} focusHeld=$focusHeld mediaStreamActive=$mediaAudioActive " +
        "broadcastRegistered=${prefaceReceiver != null}; ${trackInterception?.report() ?: "Track interception not initialized"}; " +
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
        dispatchMedia(index, source)
    }

    /** Only the system-reported package, never PID/UID or metadata, belongs in the report. */
    private fun mediaControllerPackage(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { session?.currentControllerInfo?.packageName }
            .getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) } ?: "unavailable"
    } else "unavailable"

    private fun dispatchMedia(index: Int, source: String): Boolean {
        val origin = when {
            source == "preface-intercept" -> PrefaceMediaKeyEchoes.Source.INPUT
            source == "preface-wheel" -> PrefaceMediaKeyEchoes.Source.ANDROID_KEY
            source == "preface-media-center" -> PrefaceMediaKeyEchoes.Source.MEDIA_CENTER
            (index == CarPlayMediaButton.NEXT || index == CarPlayMediaButton.PREVIOUS) &&
                (source.startsWith("next caller=") || source.startsWith("previous caller=")) ->
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
    internal fun onWindowMediaKey(event: KeyEvent): Boolean? =
        synchronized(this) { prefaceReceiver }?.handle(event)

    private var callback: CarPlayMediaCallback? = null

    private fun registerPrefaceReceiver(context: Context, expected: CarPlayController) {
        if (prefaceReceiver != null) return
        trackInterception = PrefaceTrackKeyInterception(
            PrefaceInputInterceptionBackend(deliver = { task -> mainHandler.post { task() } }, diagnostic = ::reportDiagnostic),
            diagnostic = ::reportDiagnostic,
            siri = { requestPrefaceSiri() },
        ) { index ->
            // Recheck on delivery: a call or focus loss can precede the periodic ownership check.
            if (trackKeysEligible()) dispatchMedia(index, "preface-intercept") else refreshTrackOwnership()
        }
        refreshTrackOwnership()
        if (prefaceVoiceReceiver == null) {
            val voiceReceiver = PrefaceVoiceButtonReceiver(::requestPrefaceSiri)
            runCatching {
                ContextCompat.registerReceiver(context.applicationContext, voiceReceiver,
                    IntentFilter(PrefaceVoiceButtonReceiver.ACTION).apply {
                        addCategory(Intent.CATEGORY_DEFAULT)
                        priority = 1000
                    }, ContextCompat.RECEIVER_EXPORTED)
            }.onSuccess { prefaceVoiceReceiver = voiceReceiver }
                .onFailure { Log.w(TAG, "Preface voice broadcast receiver unavailable", it) }
        }
        val receiver = PrefaceMediaButtonReceiver(
            eligible = {
                val ownsKeys = synchronized(this) {
                    controller === expected && CarPlayBackgroundSession.active && (focusHeld || foreground())
                }
                ownsKeys && !AirPlayPersistence.loadCarBluetoothAudio(context) && !inCall(context)
            },
            send = { dispatchMedia(it, "preface-wheel") },
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
        val playing = controller?.mediaPlayingSnapshot() ?: mediaAudioActive
        mediaCenter?.update(nowPlaying.copy(playing = playing), mediaCenterKeysEligible(), requestSource)
    }

    private fun mediaCenterKeysEligible(): Boolean = trackKeysEligible() &&
        !com.shilapi.xcertplay.media.NavigationPlayback.snapshot().priorityVoiceActive &&
        (lastPrefaceSiriRequest?.let { SystemClock.uptimeMillis() - it < 1500L } != true)

    /** Binder callbacks must return acceptance without touching activity state off the main thread. */
    private fun onMediaCenterCommand(owner: PrefaceMediaCenter, index: Int): Boolean {
        val expected = synchronized(this) { controller } ?: return false
        val action = {
            synchronized(this) {
                controller === expected && mediaCenter === owner && mediaCenterKeysEligible() &&
                    dispatchMedia(index, "preface-media-center")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        if (!mainHandler.post(task)) return false
        return try { task.get(250L, TimeUnit.MILLISECONDS) } catch (_: Exception) {
            task.cancel(false)
            mainHandler.removeCallbacks(task)
            false
        }
    }

    private fun trackKeysEligible(): Boolean {
        val context = appContext ?: return false
        val ownsMedia = synchronized(this) {
            controller != null && CarPlayBackgroundSession.active &&
                (focusHeld || (focusRequest == null && foreground()))
        }
        return ownsMedia && !AirPlayPersistence.loadCarBluetoothAudio(context) && !inCall(context)
    }

    internal fun iphoneMediaPlaying(): Boolean? = synchronized(this) { controller }?.mediaPlayingSnapshot()

    internal fun volumeKeysEligible(context: Context): Boolean =
        NavigationWheelSettings.enabled(context) && trackKeysEligible() &&
            !WheelKeyService.volumeKeysReserved() &&
            !com.shilapi.xcertplay.media.NavigationPlayback.snapshot().priorityVoiceActive &&
            (lastPrefaceSiriRequest?.let { SystemClock.uptimeMillis() - it < 1500L } != true)

    private fun requestPrefaceSiri(): Boolean {
        if (!trackKeysEligible()) return false
        val now = SystemClock.uptimeMillis()
        val previous = lastPrefaceSiriRequest
        if (previous != null && now - previous in 0 until WheelSiriKey.REPEAT_GAP_MILLIS) return true
        val sent = synchronized(this) { controller }?.requestSiri() == true
        if (sent) lastPrefaceSiriRequest = now
        Log.i(TAG, "Preface voice key -> Siri sent=$sent")
        return sent
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
