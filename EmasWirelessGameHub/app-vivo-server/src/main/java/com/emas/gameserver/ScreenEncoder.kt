package com.emas.gameserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val outputStream: OutputStream,
    private val width: Int = ProtocolConstants.DEFAULT_WIDTH,
    private val height: Int = ProtocolConstants.DEFAULT_HEIGHT,
    private val fps: Int = ProtocolConstants.DEFAULT_FPS,
    private val bitrate: Int = ProtocolConstants.DEFAULT_BITRATE,
    private val dpi: Int = 320
) {
    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
        private const val TIMEOUT_US = 10000L
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ProtocolConstants.I_FRAME_INTERVAL)
            // Low-latency tuning for gaming
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "EmasGameVirtualDisplay",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        workerThread = Thread({ drainEncoder() }, "ScreenEncoderThread").apply { start() }
        Log.i(TAG, "ScreenEncoder started at ${width}x${height} @ ${fps}FPS ($bitrate bps)")
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val lenBuffer = ByteArray(4)

        try {
            while (isRunning.get()) {
                val encoder = codec ?: break
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                if (outputBufferIndex >= 0) {
                    val encodedData: ByteBuffer? = encoder.getOutputBuffer(outputBufferIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        val dataSize = bufferInfo.size
                        // Write 4-byte big-endian frame length prefix
                        lenBuffer[0] = (dataSize shr 24).toByte()
                        lenBuffer[1] = (dataSize shr 16).toByte()
                        lenBuffer[2] = (dataSize shr 8).toByte()
                        lenBuffer[3] = (dataSize and 0xFF).toByte()

                        synchronized(outputStream) {
                            outputStream.write(lenBuffer)
                            val chunk = ByteArray(dataSize)
                            encodedData.get(chunk)
                            outputStream.write(chunk)
                            outputStream.flush()
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder stream error: ${e.message}")
        } finally {
            stop()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping ScreenEncoder...")

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            codec?.stop()
            codec?.release()
            codec = null

            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder resources: ${e.message}")
        }
    }
}
