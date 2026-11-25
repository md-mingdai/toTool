package com.mingdai.toTool.fakecall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import java.io.File
import java.util.Locale

/**
 * 一通模拟来电。系统电话应用负责响铃和接听界面，这里只负责来电信息、
 * 接听后的声音以及挂断时的清理。
 */
class FakeCallConnection(
    private val context: Context,
    private val callerName: String,
    private val callerNumber: String,
    hideNumber: Boolean,
    private val audioPath: String,
    private val ringTimeoutSeconds: Int,
) : Connection() {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var answered = false
    private val emergencyNumber = PhoneNumberUtils.isEmergencyNumber(callerNumber)
    private val timeoutRunnable = Runnable {
        if (!answered) disconnect(DisconnectCause.MISSED)
    }

    init {
        if (hideNumber || emergencyNumber) {
            setAddress(Uri.EMPTY, TelecomManager.PRESENTATION_RESTRICTED)
            setCallerDisplayName("", TelecomManager.PRESENTATION_RESTRICTED)
        } else {
            setAddress(
                Uri.fromParts("tel", callerNumber, null),
                TelecomManager.PRESENTATION_ALLOWED,
            )
            setCallerDisplayName(
                callerName.ifBlank { callerNumber },
                TelecomManager.PRESENTATION_ALLOWED,
            )
        }
        setConnectionCapabilities(CAPABILITY_MUTE)
        setAudioModeIsVoip(true)
        setInitializing()
        setInitialized()
        setRinging()
        scheduleTimeout()
    }

    override fun onAnswer() {
        if (answered) return
        answered = true
        handler.removeCallbacks(timeoutRunnable)
        setActive()
        requestAudioFocus()
        startAudio()
    }

    override fun onReject() = disconnect(DisconnectCause.REJECTED)

    override fun onReject(rejectReason: Int) = disconnect(DisconnectCause.REJECTED)

    override fun onDisconnect() = disconnect(DisconnectCause.LOCAL)

    override fun onAbort() = disconnect(DisconnectCause.CANCELED)

    private fun scheduleTimeout() {
        val seconds = ringTimeoutSeconds.coerceAtLeast(0)
        if (seconds <= 0) return
        handler.postDelayed(timeoutRunnable, seconds * 1_000L)
    }

    private fun startAudio() {
        // 定时闹钟里保存的是计划时的路径；优先使用当前选择，避免之后换音频时播到旧文件。
        val path = sequenceOf(
            FakeCallAudioStore.audioPath(context).orEmpty(),
            audioPath,
        ).firstOrNull { it.isNotBlank() && File(it).isFile }
        if (path != null && playFile(File(path))) return
        runCatching { speakDefault() }
    }

    private fun playFile(file: File): Boolean {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(voiceAudioAttributes())
            player.setDataSource(file.absolutePath)
            player.isLooping = true
            player.setOnErrorListener { _, _, _ ->
                if (mediaPlayer === player) {
                    stopPlayer()
                    runCatching { speakDefault() }
                }
                true
            }
            mediaPlayer = player
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer === prepared) runCatching { prepared.start() }
            }
            player.prepareAsync()
            true
        } catch (e: Exception) {
            if (mediaPlayer === player) mediaPlayer = null
            runCatching { player.release() }
            false
        }
    }

    private fun speakDefault() {
        val message = FakeCallAudioStore.defaultMessage(context)
        val engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val current = tts ?: return@TextToSpeech
            runCatching {
                val locale = Locale.getDefault()
                if (current.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    current.language = locale
                }
                current.setAudioAttributes(voiceAudioAttributes())
                current.speak(message, TextToSpeech.QUEUE_FLUSH, null, "toTool_fake_call")
            }
        }
        tts = engine
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(voiceAudioAttributes())
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }
    }

    private fun stopPlayer() {
        mediaPlayer?.run {
            runCatching { if (isPlaying) stop() }
            runCatching { release() }
        }
        mediaPlayer = null
    }

    private fun disconnect(cause: Int) {
        handler.removeCallbacks(timeoutRunnable)
        stopPlayer()
        tts?.run {
            runCatching { stop() }
            runCatching { shutdown() }
        }
        tts = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        setDisconnected(DisconnectCause(cause))
        destroy()
    }

    private fun voiceAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
}
