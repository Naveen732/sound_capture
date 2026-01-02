package com.example.sound

import android.content.Context
import android.media.*
import android.util.Log
import java.io.File

class AacEncoder(context: Context) {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val file = File(context.getExternalFilesDir(null), "playback_audio.m4a")
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var trackIndex = -1
    private var muxerStarted = false

    fun start() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            44100,
            2
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        Log.d("AAC", "Output file: ${file.absolutePath}")
    }

    fun encode(data: ByteArray, length: Int) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            inputBuffer.put(data, 0, length)
            codec.queueInputBuffer(
                inputIndex,
                0,
                length,
                System.nanoTime() / 1000,
                0
            )
        }

        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    Log.d("AAC", "Muxer started")
                }

                else -> {
                    if (outputIndex >= 0 && muxerStarted && bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outputIndex)!!
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                    }
                    if (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            codec.stop()
            codec.release()
        } catch (_: Exception) {}

        if (muxerStarted) {
            try {
                muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }

        Log.d("AAC", "Encoder stopped")
    }
}
