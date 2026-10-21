package com.ben.karmaasistan

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavIO {
    /** Reads a 16-bit PCM mono WAV (as written by WavRecorder) into floats in [-1, 1]. */
    fun readMono16(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${file.name} is not a WAV file" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                val start = pos + 8
                return FloatArray(n) { i -> bb.getShort(start + i * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }
}
