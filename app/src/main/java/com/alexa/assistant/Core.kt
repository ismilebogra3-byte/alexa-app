package com.alexa.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Shared state + task runner (one task at a time). */
object Core {
    const val CH_LISTEN = "alexa_listen"
    const val CH_ALERT = "alexa_alert"

    lateinit var app: Context
    val ui = Handler(Looper.getMainLooper())
    val busy = AtomicBoolean(false)

    @Volatile var stop = false
    @Volatile var muteUntil = 0L
    @Volatile var listening = false
    @Volatile var logSink: ((String, String) -> Unit)? = null
    @Volatile var statusSink: (() -> Unit)? = null

    private var inited = false
    private val exec = Executors.newSingleThreadExecutor()

    fun init(c: Context) {
        if (inited) return
        inited = true
        app = c.applicationContext
        Prefs.init(app)
        Speech.init(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Alexa listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alexa alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun log(kind: String, text: String) {
        ui.post { logSink?.invoke(kind, text) }
    }

    fun status() {
        ui.post { statusSink?.invoke() }
    }

    fun runTask(text: String): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        stop = false
        status()
        exec.execute {
            try {
                log("user", text)
                val reply = Agent.run(text)
                if (reply.isNotBlank()) {
                    log("say", reply)
                    if (Prefs.voiceReply) Speech.speakAndWait(reply)
                }
            } catch (e: Exception) {
                log("error", e.message ?: e.toString())
            } finally {
                busy.set(false)
                status()
            }
        }
        return true
    }
}

/** Text-to-speech with "wait until finished" so the mic can stay muted meanwhile. */
object Speech {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private var warnedBn = false

    fun init(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finish(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finish(utteranceId) }
                })
                ready = true
            }
        }
    }

    private fun finish(id: String?) {
        if (id != null) latches.remove(id)?.countDown()
    }

    fun speakAndWait(text: String) {
        val t = tts ?: return
        var waited = 0
        while (!ready && waited < 20) { Thread.sleep(100); waited++ }
        if (!ready) return
        val bn = text.any { it in '\u0980'..'\u09FF' }
        val loc = if (bn) Locale("bn", "BD") else Locale.US
        val r = t.setLanguage(loc)
        if (bn && !warnedBn && (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)) {
            warnedBn = true
            Core.log("info", "বাংলা TTS ভয়েস নেই — Google Text-to-speech-এ বাংলা ইনস্টল করুন")
        }
        val id = UUID.randomUUID().toString()
        val l = CountDownLatch(1)
        latches[id] = l
        Core.muteUntil = Long.MAX_VALUE
        t.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, id)
        l.await(30, TimeUnit.SECONDS)
        latches.remove(id)
        Core.muteUntil = System.currentTimeMillis() + 900
    }

    fun stop() {
        tts?.stop()
        for (l in latches.values) l.countDown()
    }
}

/** Allow/Deny for sensitive actions, answered from a notification (works in background). */
object Confirm {
    private val waits = ConcurrentHashMap<Int, CountDownLatch>()
    private val answers = ConcurrentHashMap<Int, Boolean>()
    private val ids = AtomicInteger(5000)

    fun ask(text: String): Boolean {
        val ctx = Core.app
        val id = ids.incrementAndGet()
        val latch = CountDownLatch(1)
        waits[id] = latch

        fun pi(allow: Boolean): PendingIntent {
            val i = Intent(ctx, ConfirmReceiver::class.java)
            i.putExtra("id", id)
            i.putExtra("ok", allow)
            val code = id * 2 + (if (allow) 1 else 0)
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val icon = Icon.createWithResource(ctx, R.drawable.ic_launcher)
        val n = Notification.Builder(ctx, Core.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Alexa: অনুমতি দরকার")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(Notification.Action.Builder(icon, "Allow", pi(true)).build())
            .addAction(Notification.Action.Builder(icon, "Deny", pi(false)).build())
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(id, n)
        Core.log("info", "অনুমতি দরকার: " + text)
        Thread { Speech.speakAndWait("অনুমতি দরকার, নোটিফিকেশন দেখুন") }.start()

        var waitedHalfSecs = 0
        while (latch.count > 0 && waitedHalfSecs < 240 && !Core.stop) {
            latch.await(500, TimeUnit.MILLISECONDS)
            waitedHalfSecs++
        }
        nm.cancel(id)
        waits.remove(id)
        return answers.remove(id) == true
    }

    fun answer(id: Int, ok: Boolean) {
        answers[id] = ok
        waits[id]?.countDown()
    }
}

class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Confirm.answer(intent.getIntExtra("id", -1), intent.getBooleanExtra("ok", false))
    }
}
