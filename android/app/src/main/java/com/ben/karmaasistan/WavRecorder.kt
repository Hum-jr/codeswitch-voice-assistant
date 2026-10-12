package com.ben.karmaasistan

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records 16 kHz, mono, 16-bit PCM audio (Whisper's native input format)
 * and saves it as a WAV file.
 */
class WavRecorder(private val sampleRate: Int = 16_000) {

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()
    @Volatile private var bytesRecorded = 0L

    val isRecording: Boolean get() = running

    /** Loudness of the latest buffer, 0..1 (for the waveform UI). */
    @Volatile var level: Float = 0f
        private set

    /** Seconds recorded so far. */
    val elapsedSeconds: Double get() = bytesRecorded / (2.0 * sampleRate)

    /** Caller must make sure RECORD_AUDIO permission is granted. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate) // at least 0.5 s of audio
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // less phone-side processing, better for ASR
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord could not be initialized")
        }
        synchronized(pcm) { pcm.reset() }
        bytesRecorded = 0L
        level = 0f
        audioRecord = rec
        running = true
        rec.startRecording()
        worker = thread(name = "wav-recorder") {
            val buf = ByteArray(2048) // ~64 ms per read → smooth waveform updates
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(pcm) { pcm.write(buf, 0, n) }
                    bytesRecorded += n
                    level = rmsLevel(buf, n)
                }
            }
        }
    }

    /** Stops recording, writes [out] as WAV and returns the duration in seconds. */
    fun stop(out: File): Double {
        val bytes = stopInternal() ?: return 0.0
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            fos.write(wavHeader(bytes.size))
            fos.write(bytes)
        }
        return bytes.size / (2.0 * sampleRate)
    }

    /** Stops recording and discards the audio. */
    fun cancel() {
        stopInternal()
    }

    private fun stopInternal(): ByteArray? {
        if (!running) return null
        running = false
        worker?.join(1_000)
        audioRecord?.run {
            stop()
            release()
        }
        audioRecord = null
        worker = null
        level = 0f
        return synchronized(pcm) { pcm.toByteArray() }
    }

    /** RMS of 16-bit little-endian samples, mapped to 0..1 for display. */
    private fun rmsLevel(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val s = sample.toShort() / 32768.0
            sum += s * s
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = kotlin.math.sqrt(sum / count)
        return (kotlin.math.sqrt(rms) * 2.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun wavHeader(dataLen: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                 // fmt chunk size
            putShort(1.toShort())      // PCM
            putShort(1.toShort())      // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)     // byte rate
            putShort(2.toShort())      // block align
            putShort(16.toShort())     // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen)
        }.array()
}
