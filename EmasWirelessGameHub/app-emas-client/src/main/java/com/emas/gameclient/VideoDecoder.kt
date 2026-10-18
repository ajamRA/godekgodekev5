package com.emas.gameclient

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoDecoder(
    private val surface: Surface,
    private val inputStream: InputStream,
    private val width: Int = ProtocolConstants.DEFAULT_WIDTH,
    private val height: Int = ProtocolConstants.DEFAULT_HEIGHT
) {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10000L
    }

    private var decoder: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        // Enable low latency playback mode on Android 10
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

        decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
            configure(format, surface, null, 0)
            start()
        }

        workerThread = Thread({ decodeLoop() }, "VideoDecoderThread").apply { start() }
        Log.i(TAG, "Hardware VideoDecoder initialized on Proton e.MAS 5 MT8666 GPU")
    }

    private fun decodeLoop() {
        val lenBuffer = ByteArray(4)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (isRunning.get()) {
                val dec = decoder ?: break

                // 1. Read 4-byte frame length prefix
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = inputStream.read(lenBuffer, bytesRead, 4 - bytesRead)
                    if (read < 0) throw Exception("Stream closed by vivo server")
                    bytesRead += read
                }

                val frameSize = (lenBuffer[0].toInt() and 0xFF shl 24) or
                                (lenBuffer[1].toInt() and 0xFF shl 16) or
                                (lenBuffer[2].toInt() and 0xFF shl 8) or
                                (lenBuffer[3].toInt() and 0xFF)

                if (frameSize <= 0 || frameSize > 2_000_000) continue

                // 2. Read full frame payload
                val frameData = ByteArray(frameSize)
                var payloadRead = 0
                while (payloadRead < frameSize) {
                    val read = inputStream.read(frameData, payloadRead, frameSize - payloadRead)
                    if (read < 0) throw Exception("Incomplete payload received")
                    payloadRead += read
                }

                // 3. Feed frame into MediaCodec input buffer
                val inputIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer: ByteBuffer? = dec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(frameData, 0, frameSize)
                    dec.queueInputBuffer(inputIndex, 0, frameSize, System.nanoTime() / 1000, 0)
                }

                // 4. Render decoded frame to SurfaceView
                var outputIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                while (outputIndex >= 0) {
                    dec.releaseOutputBuffer(outputIndex, true) // true renders frame to Surface immediately
                    outputIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ralat Penyahkod Video: ${e.message}")
        } finally {
            stop()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping VideoDecoder...")
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder: ${e.message}")
        }
    }
}
