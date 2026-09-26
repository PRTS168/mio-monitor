package com.a41probe.monitor.data.remote

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * 局域网帧协议：4 字节 big-endian 长度（payload 字节数）+ UTF-8 JSON payload。
 * 被监控端（Server）与监控端（Client）共用同一读写规则。
 */
object Wire {
    /** 单帧上限 2MB（全指标快照实测 < 8KB，留足余量；超限视为坏帧）。 */
    const val MAX_FRAME = 2 * 1024 * 1024

    fun writeFrame(out: DataOutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    /** 读一帧；对端正常关闭（EOF）返回 null；长度非法抛 [IOException]。 */
    fun readFrame(input: DataInputStream): String? {
        val len = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len <= 0 || len > MAX_FRAME) throw IOException("bad frame length: $len")
        val buf = ByteArray(len)
        // P2-1: 长度头已读、payload 中途断流也返回 null，与"EOF 返回 null"契约一致
        try {
            input.readFully(buf)
        } catch (e: EOFException) {
            return null
        }
        return String(buf, Charsets.UTF_8)
    }
}
