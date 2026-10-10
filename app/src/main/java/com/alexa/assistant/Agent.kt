package com.alexa.assistant

import android.net.Uri
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Http {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) alexa/1.0")
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP " + code)
        return text
    }
}

/** LLM tool-calling loop (Groq / Gemini through their OpenAI-compatible APIs). */
object Agent {
    private val history = JSONArray()
    private val tools = JSONArray()

    private val dangerous = Regex(
        "\\brm\\s+-\\S*[rR]\\S*\\s+(/|~|\\\$HOME|\\*)\\S*(\\s|\$)|\\bmkfs|\\bdd\\s+.*of=/dev|\\bshutdown\\b|\\breboot\\b" +
            "|git\\s+push\\s+.*(--force|-f\\b)|(curl|wget)[^|]*\\|\\s*(sudo\\s+)?(ba)?sh|chmod\\s+-R\\s+777\\s+/" +
            "|termux-sms-send|termux-telephony-call|termux-sms-list|termux-contact-list|termux-call-log"
    )
    private val resultRe = Regex(
        "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun tool(name: String, desc: String, props: Map<String, String>, req: List<String>) {
        val p = JSONObject()
        for ((k, v) in props) p.put(k, JSONObject().put("type", v))
        val params = JSONObject().put("type", "object").put("properties", p)
        if (req.isNotEmpty()) params.put("required", JSONArray(req))
        val fn = JSONObject().put("name", name).put("description", desc).put("parameters", params)
        tools.put(JSONObject().put("type", "function").put("function", fn))
    }

    init {
        tool("open_app", "PHONE: open an app by its name as shown on the phone (English), e.g. YouTube, Chrome, Settings, WhatsApp.", mapOf("name" to "string"), listOf("name"))
        tool("open_url", "PHONE: open an http(s) link in the matching app. YouTube links open the YouTube app.", mapOf("url" to "string"), listOf("url"))
        tool("screen", "PHONE: read the current screen: app name and numbered visible elements with [tap]/[input] and coordinates.", emptyMap(), emptyList())
        tool("tap_text", "PHONE: tap the element whose text or description contains this text.", mapOf("text" to "string"), listOf("text"))
        tool("tap", "PHONE: tap at pixel coordinates x,y (use only if tap_text cannot work).", mapOf("x" to "integer", "y" to "integer"), listOf("x", "y"))
        tool("type_text", "PHONE: set the text of the focused input field (Bangla and English work).", mapOf("text" to "string"), listOf("text"))
        tool("scroll", "PHONE: scroll the screen. direction is 'down' or 'up'.", mapOf("direction" to "string"), emptyList())
        tool("press", "PHONE: press back, home, recents, notifications, quick_settings, lock, screenshot, enter, volume_up, volume_down, mute, play_pause, next, previous.", mapOf("key" to "string"), listOf("key"))
        tool("wait", "Wait N seconds (max 10) for a screen to load.", mapOf("seconds" to "integer"), emptyList())
        tool("termux", "LINUX: run a bash command inside Termux (home folder). timeout_seconds up to 1800 for long installs.", mapOf("command" to "string", "timeout_seconds" to "integer"), listOf("command"))
        tool("web_search", "Search the web.", mapOf("query" to "string"), listOf("query"))
        tool("fetch_url", "Fetch a web page or raw file as text.", mapOf("url" to "string"), listOf("url"))
        tool("remember", "Save a durable note about the user or how to do things.", mapOf("note" to "string"), listOf("note"))
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val a11y = if (Prefs.a11yEnabled && AlexaA11y.instance != null) "ON" else "OFF"
        return """You are Alexa, a voice assistant that runs as an Android app on the user's phone. You control the phone, run Linux commands through Termux, and can write code.
Your FINAL reply is spoken aloud: 1-2 short sentences, no markdown, in the language the user spoke (Bangla if Bangla).
Work until the task is really done and verified. On any error read it, fix the cause and retry; after 3 failures change approach. Ask the user only when truly blocked.
Now: """ + now + """. Phone control (Accessibility) is currently """ + a11y + """.
PHONE: open apps with open_app (English app name) or open_url. YouTube search link: https://www.youtube.com/results?search_query=WORDS . Call screen before tapping, prefer tap_text over tap, and call screen again to verify the result. After typing in a search box use press enter. Never type passwords, OTP or payment data. If phone control is OFF and you need it, tell the user to turn it on in Alexa settings.
LINUX: use the termux tool. Install a Linux distro: pkg install -y proot-distro && proot-distro install ubuntu (use timeout_seconds 900). Run inside it: proot-distro login ubuntu -- bash -c '...'. Termux:API commands (termux-wifi-connectioninfo, termux-torch, termux-battery-status) work only if the Termux:API app is installed. Speed test: pip install speedtest-cli, or time a curl download. Security check = ONLY the user's own phone, router and network: open ports (nmap), Wi-Fi encryption, outdated packages; explain simply. Never scan devices or networks the user does not own.
Never send SMS, make calls, post, pay or delete on your own; the system asks the user for those.
Use remember for durable facts (preferences, how to run things). Earlier notes:
""" + Prefs.notes.ifBlank { "(none)" }
    }

    fun run(task: String): String {
        trimHistory()
        history.put(JSONObject().put("role", "user").put("content", task))
        var finalText = ""
        var done = false
        for (step in 0 until 40) {
            if (Core.stop) {
                Core.log("info", "থামানো হয়েছে")
                return ""
            }
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (i in 0 until history.length()) msgs.put(history.get(i))
            val msg = chat(msgs).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val content = if (msg.isNull("content")) "" else msg.optString("content", "")
            val calls = msg.optJSONArray("tool_calls")
            val assistant = JSONObject().put("role", "assistant")
            if (calls != null && calls.length() > 0) {
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    if (c.optString("id").isBlank()) c.put("id", "call_" + System.nanoTime() + "_" + i)
                }
                assistant.put("content", if (content.isEmpty()) JSONObject.NULL else content)
                assistant.put("tool_calls", calls)
                history.put(assistant)
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val f = c.getJSONObject("function")
                    val name = f.getString("name")
                    val args = try {
                        JSONObject(f.optString("arguments", "{}").ifBlank { "{}" })
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    Core.log("tool", name + " " + args.toString().take(90))
                    val out = if (Core.stop) "Stopped by user." else execTool(name, args)
                    history.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out.take(4000)))
                }
            } else {
                assistant.put("content", content)
                history.put(assistant)
                finalText = content.trim()
                done = true
                break
            }
        }
        if (!done && finalText.isEmpty()) finalText = "ধাপের সীমা শেষ — আবার বললে চালিয়ে যাব।"
        Prefs.addNote(SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date()) + " " + task.take(80) + " -> " + finalText.take(120))
        return finalText
    }

    private fun trimHistory() {
        val n = history.length()
        for (i in 0 until n - 8) {
            val m = history.getJSONObject(i)
            if (m.optString("role") == "tool") {
                val c = m.optString("content")
                if (c.length > 300) m.put("content", c.take(200) + "\n...[trimmed]")
            }
        }
        while (history.length() > 40) history.remove(0)
        while (history.length() > 0 && history.getJSONObject(0).optString("role") != "user") history.remove(0)
    }

    private fun chat(messages: JSONArray): JSONObject {
        val gem = Prefs.provider == "gemini"
        val base = if (gem) "https://generativelanguage.googleapis.com/v1beta/openai" else "https://api.groq.com/openai/v1"
        val key = if (gem) Prefs.geminiKey else Prefs.groqKey
        if (key.isBlank()) throw RuntimeException("API key নেই — Settings-এ " + (if (gem) "Gemini" else "Groq") + " key দিন")
        val body = JSONObject()
            .put("model", if (gem) Prefs.modelGemini else Prefs.modelGroq)
            .put("messages", messages)
            .put("tools", tools)
            .put("max_tokens", 2048)
        val bytes = body.toString().toByteArray()
        for (attempt in 0..5) {
            val c = URL(base + "/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + key)
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) alexa/1.0")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429 && attempt < 5) {
                val wait = minOf(c.getHeaderField("retry-after")?.toDoubleOrNull() ?: (3.0 * (1 shl attempt)), 30.0)
                Core.log("info", "rate limit — " + wait.toInt() + " সেকেন্ড অপেক্ষা")
                var left = (wait * 2).toInt()
                while (left > 0 && !Core.stop) {
                    Thread.sleep(500)
                    left--
                }
                if (Core.stop) throw RuntimeException("stopped")
                continue
            }
            if (code !in 200..299) throw RuntimeException("HTTP " + code + ": " + text.take(300))
            return JSONObject(text)
        }
        throw RuntimeException("rate limit: ৬ বার চেষ্টা করেও হয়নি")
    }

    private fun gate(block: () -> String): String =
        if (!Prefs.a11yEnabled) "ERROR: Accessibility (ফোন কন্ট্রোল) বন্ধ আছে। ব্যবহারকারীকে Alexa Settings-এ অন করতে বলুন।" else block()

    private fun withA11y(block: (AlexaA11y) -> String): String = gate {
        val s = AlexaA11y.instance
        if (s == null) "ERROR: Accessibility service চালু নেই। Android Settings > Accessibility > Alexa চালু করতে বলুন।" else block(s)
    }

    private fun execTool(name: String, a: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> gate { Phone.openApp(a.optString("name")) }
                "open_url" -> gate { Phone.openUrl(a.optString("url")) }
                "screen" -> withA11y { it.screen() }
                "tap_text" -> withA11y { it.tapText(a.optString("text")) }
                "tap" -> withA11y { it.tapXY(a.optInt("x"), a.optInt("y")) }
                "type_text" -> withA11y { it.typeText(a.optString("text")) }
                "scroll" -> withA11y { it.scroll(a.optString("direction", "down") != "up") }
                "press" -> gate { Phone.press(a.optString("key")) }
                "wait" -> {
                    Thread.sleep(a.optInt("seconds", 2).coerceIn(1, 10) * 1000L)
                    "waited"
                }
                "termux" -> {
                    val cmd = a.optString("command")
                    val to = a.optInt("timeout_seconds", 120).coerceIn(5, 1800)
                    if ((dangerous.containsMatchIn(cmd) || !Prefs.autoMode) && !Confirm.ask("Termux-এ কমান্ড চালাব?\n\$ " + cmd)) {
                        "User denied this command."
                    } else {
                        Termux.run(cmd, to)
                    }
                }
                "web_search" -> webSearch(a.optString("query"))
                "fetch_url" -> fetchUrl(a.optString("url"))
                "remember" -> {
                    Prefs.addNote(a.optString("note"))
                    "saved"
                }
                else -> "ERROR: unknown tool " + name
            }
        } catch (e: Exception) {
            "ERROR: " + (e.message ?: e.toString())
        }
    }

    private fun strip(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun webSearch(q: String): String {
        val html = Http.get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"))
        val out = StringBuilder()
        var n = 0
        for (m in resultRe.findAll(html)) {
            var url = m.groupValues[1]
            val u = Uri.parse(if (url.startsWith("//")) "https:" + url else url)
            val real = u.getQueryParameter("uddg")
            if (real != null) url = real
            out.append(strip(m.groupValues[2])).append("\n").append(url).append("\n").append(strip(m.groupValues[3])).append("\n\n")
            n++
            if (n >= 6) break
        }
        return if (n == 0) "no results" else out.toString()
    }

    private fun fetchUrl(url: String): String {
        var t = Http.get(url)
        if (t.length > 400_000) t = t.take(400_000)
        if (Regex("<(html|body|head)", RegexOption.IGNORE_CASE).containsMatchIn(t.take(2000))) {
            t = t.replace(Regex("(?is)<(script|style|noscript|svg).*?</\\1>"), " ")
            t = strip(t).replace(Regex("\\s+"), " ")
        }
        return t.take(6000)
    }
}
