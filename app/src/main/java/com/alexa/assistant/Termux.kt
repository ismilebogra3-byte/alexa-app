package com.alexa.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs shell commands inside Termux (Linux side) via Termux's RUN_COMMAND intent.
 * One-time setup in Termux:  mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
 * then restart Termux and grant "Run commands in Termux environment" to Alexa.
 */
object Termux {
    private val waiting = ConcurrentHashMap<Int, ArrayBlockingQueue<String>>()
    private val counter = AtomicInteger(1000)

    fun run(command: String, timeoutSec: Int): String {
        val ctx = Core.app
        val id = counter.incrementAndGet()
        val q = ArrayBlockingQueue<String>(1)
        waiting[id] = q

        val result = Intent(ctx, TermuxResultReceiver::class.java)
        result.putExtra("exec_id", id)
        val pi = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val i = Intent("com.termux.RUN_COMMAND")
        i.setClassName("com.termux", "com.termux.app.RunCommandService")
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        try {
            ctx.startForegroundService(i)
        } catch (e: Exception) {
            waiting.remove(id)
            return "ERROR: Termux-এ কমান্ড পাঠানো যায়নি (" + (e.message ?: "") + "). Termux ইনস্টল আছে? allow-external-apps চালু? Alexa-কে RUN_COMMAND অনুমতি দেওয়া আছে?"
        }
        var left = timeoutSec
        while (left > 0) {
            val r = q.poll(1, TimeUnit.SECONDS)
            if (r != null) return r
            if (Core.stop) {
                waiting.remove(id)
                return "Stopped by user."
            }
            left--
        }
        waiting.remove(id)
        return "ERROR: " + timeoutSec + " সেকেন্ডে শেষ হয়নি (Termux-এ হয়তো এখনো চলছে)"
    }

    fun deliver(id: Int, text: String) {
        waiting.remove(id)?.offer(text)
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("exec_id", -1)
        val b = intent.getBundleExtra("result")
        val sb = StringBuilder()
        if (b == null) {
            sb.append("ERROR: Termux থেকে কোনো ফল আসেনি")
        } else {
            val err = b.getInt("err", -1)
            if (err != -1) sb.append("ERROR: ").append(b.getString("errmsg") ?: "unknown").append("\n")
            sb.append("exit code ").append(b.getInt("exitCode", -1)).append("\n")
            sb.append(b.getString("stdout") ?: "")
            val se = b.getString("stderr") ?: ""
            if (se.isNotBlank()) sb.append("\n[stderr]\n").append(se)
        }
        val text = sb.toString()
        Termux.deliver(id, if (text.length > 5000) "[...earlier output omitted]\n" + text.takeLast(5000) else text)
    }
}
