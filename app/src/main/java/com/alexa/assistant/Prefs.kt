package com.alexa.assistant

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("alexa", Context.MODE_PRIVATE)
    }

    fun str(k: String, d: String = ""): String = sp?.getString(k, d) ?: d
    fun bool(k: String, d: Boolean = false): Boolean = sp?.getBoolean(k, d) ?: d
    fun put(k: String, v: String) { sp?.edit()?.putString(k, v)?.apply() }
    fun put(k: String, v: Boolean) { sp?.edit()?.putBoolean(k, v)?.apply() }

    val provider: String get() = str("provider", "groq")
    val groqKey: String get() = str("groq_key").trim()
    val geminiKey: String get() = str("gemini_key").trim()
    val modelGroq: String get() = str("model_groq", "openai/gpt-oss-120b").ifBlank { "openai/gpt-oss-120b" }
    val modelGemini: String get() = str("model_gemini", "gemini-2.5-flash").ifBlank { "gemini-2.5-flash" }
    val sttModel: String get() = str("stt_model", "whisper-large-v3-turbo").ifBlank { "whisper-large-v3-turbo" }
    val greeting: String get() = str("greeting", "জি বস, কী করতে পারি?").ifBlank { "জি বস, কী করতে পারি?" }
    val voiceReply: Boolean get() = bool("voice_reply", true)
    val a11yEnabled: Boolean get() = bool("a11y_enabled", false)
    val autoMode: Boolean get() = bool("auto_mode", true)
    val notes: String get() = str("notes", "")

    fun addNote(line: String) {
        put("notes", (notes + line + "\n").takeLast(6000))
    }
}
