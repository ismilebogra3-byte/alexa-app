package com.alexa.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Dark UI (white text), all built in code so the project needs no extra libraries. */
class MainActivity : Activity() {
    private val bgColor = Color.parseColor("#0A0A0F")
    private val cardColor = Color.parseColor("#16161E")
    private val accColor = Color.parseColor("#7C5CFF")
    private val badColor = Color.parseColor("#EF4444")
    private val mutColor = Color.parseColor("#A0A0B4")
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var logBox: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var subView: TextView
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var ccBtn: Button
    private lateinit var settingsView: ScrollView
    private var subOn = true

    private val hideSub = Runnable { subView.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            Core.ui.postDelayed(this, 1500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 12): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun button(label: String, color: Int): Button {
        val b = Button(this)
        b.text = label
        b.setAllCaps(false)
        b.setTextColor(Color.WHITE)
        b.background = rounded(color)
        b.setPadding(dp(14), dp(6), dp(14), dp(6))
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        subOn = Prefs.bool("subtitles", true)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)

        root.addView(header())

        val content = FrameLayout(this)
        logScroll = ScrollView(this)
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.setPadding(dp(14), dp(10), dp(14), dp(10))
        logScroll.addView(logBox, ViewGroup.LayoutParams(match, wrap))
        content.addView(logScroll, FrameLayout.LayoutParams(match, match))

        settingsView = buildSettings()
        settingsView.visibility = View.GONE
        content.addView(settingsView, FrameLayout.LayoutParams(match, match))

        subView = TextView(this)
        subView.setTextColor(Color.WHITE)
        subView.textSize = 18f
        subView.background = rounded(Color.parseColor("#D9000000"), 10)
        subView.setPadding(dp(14), dp(8), dp(14), dp(8))
        subView.gravity = Gravity.CENTER
        subView.visibility = View.GONE
        val slp = FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        slp.bottomMargin = dp(10)
        slp.leftMargin = dp(16)
        slp.rightMargin = dp(16)
        content.addView(subView, slp)

        root.addView(content, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(bottomBar())
        setContentView(root)

        addLine("Alexa তৈরি। Settings (⚙) থেকে key দিন, Accessibility চালু করুন, তারপর Start চাপুন বা নিচে লিখুন।", mutColor, false)
        Core.logSink = { kind, text -> onLog(kind, text) }
        Core.statusSink = { refreshStatus() }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        Core.ui.post(ticker)
    }

    override fun onPause() {
        Core.ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        Core.logSink = null
        Core.statusSink = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun header(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val title = TextView(this)
        title.text = "Alexa"
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        statusView = TextView(this)
        statusView.setTextColor(mutColor)
        statusView.textSize = 12f
        statusView.setPadding(dp(10), 0, dp(10), 0)
        startBtn = button("Start", accColor)
        startBtn.setOnClickListener { onStartStop() }
        val gear = button("⚙", cardColor)
        gear.setOnClickListener {
            settingsView.visibility = if (settingsView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        row.addView(title)
        row.addView(statusView, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(startBtn)
        row.addView(gear)
        return row
    }

    private fun bottomBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(8), dp(10), dp(8))

        // subtitle button: bottom-left
        ccBtn = button("CC", cardColor)
        styleCc()
        ccBtn.setOnClickListener {
            subOn = !subOn
            Prefs.put("subtitles", subOn)
            styleCc()
            if (!subOn) subView.visibility = View.GONE
        }

        val input = EditText(this)
        input.hint = "Alexa, YouTube খোলো…"
        input.setHintTextColor(mutColor)
        input.setTextColor(Color.WHITE)
        input.background = rounded(cardColor, 22)
        input.setPadding(dp(16), dp(10), dp(16), dp(10))
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEND

        val send = button("➤", accColor)
        fun doSend() {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                input.setText("")
                if (!Core.runTask(t)) toast("Alexa এখনো কাজ করছে — Stop চাপুন")
            }
        }
        send.setOnClickListener { doSend() }
        input.setOnEditorActionListener { _, _, _ ->
            doSend()
            true
        }

        row.addView(ccBtn)
        val lp = LinearLayout.LayoutParams(0, wrap, 1f)
        lp.leftMargin = dp(8)
        lp.rightMargin = dp(8)
        row.addView(input, lp)
        row.addView(send)
        return row
    }

    private fun styleCc() {
        ccBtn.alpha = if (subOn) 1f else 0.4f
        ccBtn.paint.isStrikeThruText = !subOn
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(mutColor)
        t.textSize = 13f
        t.setPadding(0, dp(14), 0, dp(6))
        return t
    }

    private fun field(parent: LinearLayout, title: String, key: String, def: String, pw: Boolean) {
        parent.addView(label(title))
        val et = EditText(this)
        et.setText(Prefs.str(key, def))
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(mutColor)
        et.background = rounded(cardColor, 10)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.setSingleLine(true)
        if (pw) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { Prefs.put(key, s?.toString()?.trim() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        parent.addView(et, LinearLayout.LayoutParams(match, wrap))
    }

    private fun toggle(parent: LinearLayout, title: String, key: String, def: Boolean, onChange: ((Boolean) -> Unit)?) {
        val sw = Switch(this)
        sw.text = title
        sw.setTextColor(Color.WHITE)
        sw.isChecked = Prefs.bool(key, def)
        sw.setPadding(0, dp(10), 0, dp(10))
        sw.setOnCheckedChangeListener { _, v ->
            Prefs.put(key, v)
            if (onChange != null) onChange(v)
        }
        parent.addView(sw, LinearLayout.LayoutParams(match, wrap))
    }

    private fun action(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = button(text, cardColor)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.topMargin = dp(8)
        parent.addView(b, lp)
    }

    private fun buildSettings(): ScrollView {
        val sv = ScrollView(this)
        sv.setBackgroundColor(bgColor)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(30))

        col.addView(label("Model provider (কাজ বোঝার ব্রেইন)"))
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        val rb1 = RadioButton(this)
        rb1.text = "Groq"
        rb1.id = View.generateViewId()
        rb1.setTextColor(Color.WHITE)
        val rb2 = RadioButton(this)
        rb2.text = "Gemini Flash"
        rb2.id = View.generateViewId()
        rb2.setTextColor(Color.WHITE)
        rg.addView(rb1)
        rg.addView(rb2)
        if (Prefs.provider == "gemini") rb2.isChecked = true else rb1.isChecked = true
        rg.setOnCheckedChangeListener { _, id -> Prefs.put("provider", if (id == rb2.id) "gemini" else "groq") }
        col.addView(rg)

        field(col, "Groq API key (শোনার জন্যও লাগে — ফ্রি, console.groq.com)", "groq_key", "", true)
        field(col, "Gemini API key (ঐচ্ছিক)", "gemini_key", "", true)
        field(col, "Groq model", "model_groq", "openai/gpt-oss-120b", false)
        field(col, "Gemini model", "model_gemini", "gemini-2.5-flash", false)
        field(col, "জাগলে যা বলবে", "greeting", "জি বস, কী করতে পারি?", false)

        col.addView(label(" "))
        toggle(col, "Accessibility (ফোন কন্ট্রোল) অন", "a11y_enabled", false) { on ->
            if (on && AlexaA11y.instance == null) {
                toast("এবার Accessibility লিস্টে 'Alexa' খুঁজে চালু করুন")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        toggle(col, "কথা বলে উত্তর দেবে", "voice_reply", true, null)
        toggle(col, "Auto mode (Termux কমান্ডে বারবার জিজ্ঞেস করবে না)", "auto_mode", true, null)

        action(col, "Accessibility সেটিংস খুলুন") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        action(col, "'Display over other apps' অনুমতি (ব্যাকগ্রাউন্ডে অ্যাপ খুলতে)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        action(col, "ব্যাটারি অপ্টিমাইজেশন বন্ধ করুন") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        action(col, "Mic test (৬ সেকেন্ড বলুন)") {
            toast("৬ সেকেন্ড কিছু বলুন…")
            Thread { Core.log("info", "Mic test: " + Mic.test(6)) }.start()
        }

        col.addView(label("Termux (Linux কাজের জন্য)"))
        val help = TextView(this)
        help.setTextColor(Color.WHITE)
        help.textSize = 13f
        help.setTextIsSelectable(true)
        help.text = "Termux-এ একবার চালান:\nmkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\nতারপর Termux পুরোপুরি বন্ধ করে আবার খুলুন, এবং নিচের বাটনে অনুমতি দিন।"
        col.addView(help)
        action(col, "Termux-এ কমান্ড চালানোর অনুমতি দিন") {
            requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 2)
        }
        action(col, "Termux টেস্ট") {
            toast("Termux-এ পাঠানো হচ্ছে…")
            Thread { Core.log("info", "Termux: " + Termux.run("echo ok; uname -m", 30)) }.start()
        }

        sv.addView(col, ViewGroup.LayoutParams(match, wrap))
        return sv
    }

    // ---------------------------------------------------------------- behaviour

    private fun onStartStop() {
        if (Core.listening || Core.busy.get()) {
            Core.stop = true
            Speech.stop()
            stopService(Intent(this, ListenService::class.java))
            refreshStatus()
            return
        }
        if (Prefs.groqKey.isBlank()) {
            toast("শোনার জন্য Settings-এ Groq key দিন (ফ্রি, Whisper-এর জন্য)")
            settingsView.visibility = View.VISIBLE
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS")
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        startListening()
    }

    private fun startListening() {
        startForegroundService(Intent(this, ListenService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
            else toast("মাইক্রোফোনের অনুমতি ছাড়া শোনা যাবে না")
        }
    }

    private fun refreshStatus() {
        val on = Core.listening || Core.busy.get()
        val state = if (Core.busy.get()) "কাজ করছে…" else if (Core.listening) "শুনছি — \"Alexa\" বলুন" else "বন্ধ"
        val a11y = if (AlexaA11y.instance != null) "Accessibility ✅" else "Accessibility ❌"
        statusView.text = state + "\n" + a11y
        startBtn.text = if (on) "Stop" else "Start"
        startBtn.background = rounded(if (on) badColor else accColor)
    }

    private fun showSub(t: String) {
        if (!subOn || t.isBlank()) return
        subView.text = t.takeLast(180)
        subView.visibility = View.VISIBLE
        Core.ui.removeCallbacks(hideSub)
        Core.ui.postDelayed(hideSub, 5000) // subtitle clears itself after 5 seconds
    }

    private fun addLine(text: String, color: Int, user: Boolean) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = 15f
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        if (user) tv.background = rounded(accColor, 14)
        val lp = LinearLayout.LayoutParams(wrap, wrap)
        lp.topMargin = dp(6)
        if (user) lp.gravity = Gravity.END
        logBox.addView(tv, lp)
        if (logBox.childCount > 150) logBox.removeViewAt(0)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun onLog(kind: String, text: String) {
        when (kind) {
            "heard" -> showSub("🎤 " + text)
            "user" -> {
                addLine(text, Color.WHITE, true)
                showSub("🧑 " + text)
            }
            "say" -> {
                addLine(text, Color.WHITE, false)
                showSub(text)
            }
            "tool" -> addLine("→ " + text, mutColor, false)
            "error" -> {
                addLine("⚠ " + text, Color.parseColor("#FF8A8A"), false)
                showSub("⚠ " + text)
            }
            else -> {
                addLine(text, mutColor, false)
                showSub(text)
            }
        }
    }
}
