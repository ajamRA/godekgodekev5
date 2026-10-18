package com.emas.gameserver

object ProtocolConstants {
    const val CONTROL_PORT = 8888
    const val VIDEO_PORT = 8889
    const val INPUT_PORT = 8890

    // Video Streaming Configuration
    const val DEFAULT_WIDTH = 1920
    const val DEFAULT_HEIGHT = 1080
    const val DEFAULT_FPS = 60
    const val DEFAULT_BITRATE = 15_000_000 // 15 Mbps for ultra-sharp 1080p60
    const val I_FRAME_INTERVAL = 1 // 1 second keyframe interval

    // Protocol Command Headers
    const val CMD_HANDSHAKE_REQ = "EMAS_HELLO"
    const val CMD_HANDSHAKE_ACK = "EMAS_READY"
    const val CMD_STOP = "EMAS_STOP"

    // Controller Packet Magic
    const val PACKET_MAGIC = 0x454D4153 // "EMAS"
}
