package com.alexa.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Audio {
    fun rms(f: ShortArray): Double {
        var s = 0.0
        for (x in f) s += x.toDouble() * x.toDouble()
        return Math.sqrt(s / f.size)
    }
}

/** Speech -> text with Groq Whisper. Every uploaded phrase counts against PER_HOUR. */
object Stt {
    const val PER_HOUR = 150
    private val uploads = ArrayList<Long>()
    private var lastErr = 0L

    private fun err(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastErr > 60_000L) {
            lastErr = now
            Core.log("error", msg)
        }
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(16000)
        bb.putInt(32000)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    fun transcribe(pcm: ShortArray): String {
        if (Prefs.groqKey.isBlank()) {
            err("শোনার জন্য Groq key লাগবে (Settings)")
            return ""
        }
        val now = System.currentTimeMillis()
        synchronized(uploads) {
            uploads.removeAll { now - it > 3_600_000L }
            if (uploads.size >= PER_HOUR) {
                err("STT-র ঘণ্টাপ্রতি সীমা ছুঁয়েছে — একটু পরে আবার শুনবে")
                return ""
            }
            uploads.add(now)
        }
        val b = "----alexa" + System.nanoTime()
        val bos = ByteArrayOutputStream()
        fun field(k: String, v: String) {
            bos.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + k + "\"\r\n\r\n" + v + "\r\n").toByteArray())
        }
        field("model", Prefs.sttModel)
        field("response_format", "json")
        field("temperature", "0")
        field("prompt", "Alexa, hey Alexa, hello Alexa, আলেক্সা, YouTube, Termux")
        bos.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray())
        bos.write(wav(pcm))
        bos.write(("\r\n--" + b + "--\r\n").toByteArray())
        val bytes = bos.toByteArray()
        try {
            val c = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer " + Prefs.groqKey)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429) {
                Thread.sleep(3000)
                return ""
            }
            if (code !in 200..299) {
                err("STT HTTP " + code + ": " + text.take(150))
                return ""
            }
            return JSONObject(text).optString("text", "").trim()
        } catch (e: Exception) {
            err("STT: " + (e.message ?: e.toString()))
            return ""
        }
    }
}

/** Quick check that the microphone really delivers sound (also with the screen off / other app on top). */
object Mic {
    fun test(seconds: Int): String {
        if (Core.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "❌ Microphone অনুমতি নেই"
        if (Core.listening) return "আগে Stop চাপুন (মাইক একসাথে দুই জায়গায় চলে না)"
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r: AudioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8000))
        } catch (e: Exception) {
            return "❌ " + (e.message ?: e.toString())
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) return "❌ মাইক খোলা যায়নি"
        val frame = ShortArray(480)
        var peak = 0.0
        var sum = 0.0
        var n = 0
        var loud = 0
        try {
            r.startRecording()
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) {
                var got = 0
                while (got < frame.size) {
                    val k = r.read(frame, got, frame.size - got)
                    if (k <= 0) break
                    got += k
                }
                if (got < frame.size) break
                val v = Audio.rms(frame)
                if (v > peak) peak = v
                sum += v
                n++
                if (v > 400) loud++
            }
        } finally {
            try { r.stop() } catch (e: Exception) {}
            r.release()
        }
        if (n == 0) return "❌ কোনো অডিও আসেনি"
        val avg = sum / n
        val verdict = if (peak > 600) "✅ কথা ধরা পড়ছে" else "❌ প্রায় নিঃশব্দ — অনুমতি বা ব্যাকগ্রাউন্ড ব্লক হতে পারে"
        return verdict + " (peak " + peak.toInt() + ", avg " + avg.toInt() + ", জোরালো ফ্রেম " + loud + ")"
    }
}
