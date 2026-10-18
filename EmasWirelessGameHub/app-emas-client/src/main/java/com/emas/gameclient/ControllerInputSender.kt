package com.emas.gameclient

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControllerInputSender(
    private val serverIp: String,
    private val serverPort: Int = ProtocolConstants.INPUT_PORT
) {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private val buffer = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
    private var buttonBitmask = 0

    init {
        Thread {
            try {
                socket = DatagramSocket()
                targetAddress = InetAddress.getByName(serverIp)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
            (event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false
        }

        val mask = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0x0001
            KeyEvent.KEYCODE_BUTTON_B -> 0x0002
            KeyEvent.KEYCODE_BUTTON_X -> 0x0004
            KeyEvent.KEYCODE_BUTTON_Y -> 0x0008
            KeyEvent.KEYCODE_BUTTON_L1 -> 0x0010
            KeyEvent.KEYCODE_BUTTON_R1 -> 0x0020
            KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0040
            KeyEvent.KEYCODE_BUTTON_START -> 0x0080
            KeyEvent.KEYCODE_DPAD_UP -> 0x0100
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x0200
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x0400
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0800
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x1000
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x2000
            else -> 0
        }

        if (mask != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                buttonBitmask = buttonBitmask or mask
            } else if (event.action == KeyEvent.ACTION_UP) {
                buttonBitmask = buttonBitmask and mask.inv()
            }
            sendPacket(0f, 0f, 0f, 0f)
            return true
        }
        return false
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false
        }

        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z)
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ)

        sendPacket(lx, ly, rx, ry)
        return true
    }

    private fun sendPacket(lx: Float, ly: Float, rx: Float, ry: Float) {
        val dest = targetAddress ?: return
        val sock = socket ?: return

        Thread {
            try {
                synchronized(buffer) {
                    buffer.clear()
                    buffer.putInt(ProtocolConstants.PACKET_MAGIC)
                    buffer.putInt(buttonBitmask)
                    buffer.putShort((lx * 32767).toInt().toShort())
                    buffer.putShort((ly * 32767).toInt().toShort())
                    buffer.putShort((rx * 32767).toInt().toShort())
                    buffer.putShort((ry * 32767).toInt().toShort())

                    val data = buffer.array()
                    val packet = DatagramPacket(data, buffer.position(), dest, serverPort)
                    sock.send(packet)
                }
            } catch (e: Exception) {
                // Ignore dropped UDP frames
            }
        }.start()
    }

    fun close() {
        socket?.close()
        socket = null
    }
}
