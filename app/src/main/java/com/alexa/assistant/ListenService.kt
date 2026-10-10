package com.alexa.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service (type: microphone) = the "always listening" part.
 * Local energy VAD finds a phrase -> Groq Whisper -> wake word ("Hey/Hello Alexa") -> greeting -> command -> agent.
 */
class ListenService : Service() {

    companion object {
        val WAKE = Regex(
            "(?:hey\\s+|hi\\s+|hello\\s+|ok(?:ay)?\\s+|হেই\\s+|হ্যালো\\s+|হেলো\\s+|ওকে\\s+)?" +
                "(?:alexa|alexia|alexsa|elexa|aleksa|আলেক্সা|এলেক্সা|অ্যালেক্সা|আলেকসা|এলেকসা|অ্যালেকসা)[\\s,.!?:।\\-]*(.*)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val STOP_RE = Regex("^\\s*(stop|cancel|থামো|থামুন|বন্ধ করো)\\s*[.!?।]*\\s*\$", RegexOption.IGNORE_CASE)
    }

    @Volatile private var running = false
    @Volatile private var rec: AudioRecord? = null
    private var wl: PowerManager.WakeLock? = null
    private var floor = -1.0
    private val frame = ShortArray(480) // 30 ms @ 16 kHz

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopPi = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Core.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Alexa")
            .setContentText("শুনছি — \"Alexa\" বলুন")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Stop", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            Core.listening = true
            Core.stop = false
            Core.status()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alexa:listen")
            wl?.acquire()
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { rec?.stop() } catch (e: Exception) {}
        try { wl?.release() } catch (e: Exception) {}
        Core.listening = false
        Core.status()
        super.onDestroy()
    }

    private fun readFrame(r: AudioRecord): Boolean {
        var got = 0
        while (got < frame.size) {
            val n = r.read(frame, got, frame.size - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    /** One spoken phrase as PCM. null = timeout/stopped, empty = too short (ignore). */
    private fun nextSegment(r: AudioRecord, timeoutMs: Long): ShortArray? {
        val pre = ArrayDeque<ShortArray>()
        val voiced = ArrayList<ShortArray>()
        var started = false
        var loud = 0
        var quiet = 0
        val t0 = System.currentTimeMillis()
        while (running) {
            if (!readFrame(r)) return null
            if (System.currentTimeMillis() < Core.muteUntil) { // we are talking / just talked
                pre.clear()
                voiced.clear()
                started = false
                loud = 0
                quiet = 0
                continue
            }
            val level = Audio.rms(frame)
            if (floor < 0) floor = level
            val speech = level > maxOf(350.0, floor * 3 + 120)
            val copy = frame.copyOf()
            if (!started) {
                if (!speech) floor = 0.95 * floor + 0.05 * level
                pre.addLast(copy)
                if (pre.size > 10) pre.removeFirst()
                loud = if (speech) loud + 1 else 0
                if (loud >= 3) {
                    started = true
                    voiced.addAll(pre)
                    quiet = 0
                } else if (timeoutMs > 0 && System.currentTimeMillis() - t0 > timeoutMs) {
                    return null
                }
            } else {
                voiced.add(copy)
                quiet = if (speech) 0 else quiet + 1
                if (quiet >= 24 || voiced.size > 8 * 33) break // ~0.7 s silence or 8 s cap
            }
        }
        if (!running) return null
        if (voiced.size < 17) return ShortArray(0)
        val out = ShortArray(voiced.size * 480)
        var o = 0
        for (f in voiced) {
            System.arraycopy(f, 0, out, o, 480)
            o += 480
        }
        return out
    }

    private fun loop() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r: AudioRecord? = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8000))
        } catch (e: Exception) {
            null
        }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Core.log("error", "মাইক খোলা যায়নি (অনুমতি দেওয়া আছে?)")
            stopSelf()
            return
        }
        rec = r
        try {
            r.startRecording()
            Core.log("info", "শুনছি — \"Alexa\" বলুন")
            while (running) {
                val seg = nextSegment(r, 0L)
                if (seg == null) {
                    if (!running) break else continue
                }
                if (seg.isEmpty()) continue
                val text = Stt.transcribe(seg)
                if (text.isBlank()) continue
                Core.log("heard", text)
                val m = WAKE.find(text) ?: continue
                var cmd = m.groupValues[1].trim().trim(',', '.', '!', '?', ':', '।', '-', ' ')
                if (STOP_RE.containsMatchIn(cmd)) {
                    Core.stop = true
                    Speech.stop()
                    continue
                }
                if (cmd.isEmpty()) { // only "Hey Alexa" -> greet, then wait for the command
                    Speech.speakAndWait(Prefs.greeting)
                    val t1 = System.currentTimeMillis()
                    var seg2: ShortArray? = null
                    while (running && System.currentTimeMillis() - t1 < 10_000L) {
                        seg2 = nextSegment(r, 10_000L - (System.currentTimeMillis() - t1))
                        if (seg2 == null || seg2.isNotEmpty()) break
                    }
                    val s2 = seg2
                    cmd = if (s2 != null && s2.isNotEmpty()) Stt.transcribe(s2).trim() else ""
                    if (cmd.isBlank()) continue
                    if (STOP_RE.containsMatchIn(cmd)) {
                        Core.stop = true
                        Speech.stop()
                        continue
                    }
                }
                if (Core.busy.get()) {
                    Speech.speakAndWait("আমি এখনো একটা কাজ করছি।")
                    continue
                }
                Speech.speakAndWait("ঠিক আছে।")
                Core.runTask(cmd)
            }
        } catch (e: Exception) {
            Core.log("error", "mic: " + (e.message ?: e.toString()))
        } finally {
            try { r.stop() } catch (e: Exception) {}
            r.release()
            rec = null
            stopSelf()
        }
    }
}
