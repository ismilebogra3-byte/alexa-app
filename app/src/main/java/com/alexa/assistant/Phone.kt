package com.alexa.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent

/** App launching and hardware/global keys. */
object Phone {

    fun openApp(name: String): String {
        val pm = Core.app.packageManager
        val q = name.lowercase().trim()
        if (q.isEmpty()) return "ERROR: অ্যাপের নাম দিন"
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q.replace(" ", "")) }
        if (hit == null) return "ERROR: '" + name + "' নামে কোনো অ্যাপ পাওয়া যায়নি (ইংরেজি নাম দিন, যেমন YouTube)"
        val pkg = hit.activityInfo.packageName
        val label = hit.loadLabel(pm).toString()
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return "ERROR: এই অ্যাপ খোলা যায় না"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = AlexaA11y.instance ?: Core.app
        ctx.startActivity(launch)
        Thread.sleep(1500)
        val cur = AlexaA11y.instance?.currentPackage()
        return if (cur == pkg) "opened " + label
        else "launch sent for " + label + " but foreground app is '" + (cur ?: "?") + "' (may be blocked in background; user can allow 'Display over other apps')"
    }

    fun openUrl(url: String): String {
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return "ERROR: শুধু http(s) লিংক চলবে"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = AlexaA11y.instance ?: Core.app
        ctx.startActivity(i)
        Thread.sleep(1500)
        return "opened (foreground: " + (AlexaA11y.instance?.currentPackage() ?: "?") + ")"
    }

    private fun media(am: AudioManager, code: Int): String {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "ok"
    }

    fun press(key: String): String {
        val k = key.lowercase().trim()
        val am = Core.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (k) {
            "volume_up" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return "ok"
            }
            "volume_down" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "ok"
            }
            "mute" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                return "ok"
            }
            "play_pause" -> return media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> return media(am, KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> return media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        val s = AlexaA11y.instance ?: return "ERROR: Accessibility service চালু নেই"
        if (k == "enter") return s.imeEnter()
        val action = when (k) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return "ERROR: key হতে হবে: back, home, recents, notifications, quick_settings, lock, screenshot, enter, volume_up, volume_down, mute, play_pause, next, previous"
        }
        return if (s.performGlobalAction(action)) "ok" else "ERROR: কাজ হয়নি"
    }
}
