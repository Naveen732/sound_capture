package com.example.sound

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.provider.MediaStore
import androidx.core.content.getSystemService
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.FileInputStream

@Parcelize
data class ScreenRecordConfig(
    val resultCode: Int,
    val data: Intent
) : Parcelable

class ScreenRecordService : Service() {

    // ---------------- VIDEO ----------------
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val mediaRecorder by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(applicationContext)
        else MediaRecorder()
    }

    private val videoFile by lazy {
        File(cacheDir, "screen_video.mp4")
    }

    // ---------------- AUDIO ----------------
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioEncoder: MediaCodec
    private lateinit var audioMuxer: MediaMuxer

    private var audioTrackIndex = -1
    private var muxerStarted = false

    private val audioCacheFile by lazy {
        File(cacheDir, "playback_audio.m4a")
    }

    // ---------------- COMMON ----------------
    private val serviceScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val projectionManager by lazy {
        getSystemService<MediaProjectionManager>()
    }

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }

    // =======================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            START_RECORDING -> {
                val notification =
                    NotificationHelper.createNotification(applicationContext)
                NotificationHelper.createNotificationChannel(applicationContext)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        1,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else startForeground(1, notification)

                _isRunning.value = true
                startRecording(intent)
            }

            STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    // =======================================================

    private fun startRecording(intent: Intent) {

        val config =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(
                    KEY_RECORDING_CONFIG,
                    ScreenRecordConfig::class.java
                )
            else intent.getParcelableExtra(KEY_RECORDING_CONFIG)

        if (config == null) return

        mediaProjection =
            projectionManager?.getMediaProjection(
                config.resultCode,
                config.data
            )

        mediaProjection?.registerCallback(projectionCallback, null)

        // VIDEO
        setupVideoRecorder()
        mediaRecorder.start()
        virtualDisplay = createVirtualDisplay()

        // AUDIO
        setupAudioPlaybackCapture()
        startAudioCapture()
    }

    // =======================================================
    // VIDEO SETUP
    // =======================================================

    private fun setupVideoRecorder() {
        val (w, h) = getWindowSize()
        with(mediaRecorder) {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(5_000_000)
            prepare()
        }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        val (w, h) = getWindowSize()
        return mediaProjection?.createVirtualDisplay(
            "Screen",
            w,
            h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        )
    }

    private fun getWindowSize(): Pair<Int, Int> {
        val metrics =
            WindowMetricsCalculator.getOrCreate()
                .computeMaximumWindowMetrics(this)
        return metrics.bounds.width() to metrics.bounds.height()
    }

    // =======================================================
    // AUDIO SETUP (AudioPlaybackCapture)
    // =======================================================

    private fun setupAudioPlaybackCapture() {

        val playbackConfig =
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val format =
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

        audioRecord =
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()

        setupAudioEncoder()
    }

    private fun setupAudioEncoder() {

        val format =
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                44100,
                2
            )

        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)

        audioEncoder =
            MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_AUDIO_AAC
            )

        audioEncoder.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        audioEncoder.start()

        audioMuxer =
            MediaMuxer(
                audioCacheFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
    }

    private fun startAudioCapture() {
        audioRecord.startRecording()

        serviceScope.launch {
            val buffer = ByteArray(4096)
            while (_isRunning.value) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) encodeAudio(buffer, read)
            }
        }
    }

    private fun encodeAudio(data: ByteArray, size: Int) {

        val inIndex = audioEncoder.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val inputBuffer = audioEncoder.getInputBuffer(inIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data, 0, size)

            audioEncoder.queueInputBuffer(
                inIndex,
                0,
                size,
                System.nanoTime() / 1000,
                0
            )
        }

        val info = MediaCodec.BufferInfo()
        var outIndex = audioEncoder.dequeueOutputBuffer(info, 0)

        while (outIndex >= 0) {
            val encoded = audioEncoder.getOutputBuffer(outIndex) ?: return

            if (info.size > 0) {
                if (!muxerStarted) {
                    audioTrackIndex =
                        audioMuxer.addTrack(audioEncoder.outputFormat)
                    audioMuxer.start()
                    muxerStarted = true
                }
                encoded.position(info.offset)
                encoded.limit(info.offset + info.size)
                audioMuxer.writeSampleData(
                    audioTrackIndex,
                    encoded,
                    info
                )
            }
            audioEncoder.releaseOutputBuffer(outIndex, false)
            outIndex = audioEncoder.dequeueOutputBuffer(info, 0)
        }
    }

    // =======================================================
    // STOP + SAVE
    // =======================================================

    private fun stopRecording() {

        _isRunning.value = false

        // AUDIO
        audioRecord.stop()
        audioRecord.release()
        audioEncoder.stop()
        audioEncoder.release()
        if (muxerStarted) audioMuxer.stop()
        audioMuxer.release()

        saveAudioToMusic()

        // VIDEO
        mediaRecorder.stop()
        mediaRecorder.reset()
        mediaProjection?.stop()
        virtualDisplay?.release()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveAudioToMusic() {
        serviceScope.launch {

            val values = ContentValues().apply {
                put(
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    "audio_${System.currentTimeMillis()}.m4a"
                )
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "Music/ScreenRecordings"
                )
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }

            val uri =
                contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return@launch

            contentResolver.openOutputStream(uri)?.use { out ->
                audioCacheFile.inputStream().use { it.copyTo(out) }
            }

            audioCacheFile.delete()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.coroutineContext.cancelChildren()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isServiceRunning = _isRunning.asStateFlow()

        const val START_RECORDING = "START_RECORDING"
        const val STOP_RECORDING = "STOP_RECORDING"
        const val KEY_RECORDING_CONFIG = "KEY_RECORDING_CONFIG"
    }
}
