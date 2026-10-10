# Alexa (Android APK) — বিল্ড গাইড

এই প্রজেক্ট থেকে GitHub Actions ফ্রিতে APK বানিয়ে দেয়। কম্পিউটার বা Android Studio লাগবে না।

## ১. APK বানানো (Termux থেকে, ফোনেই)
1. github.com-এ নতুন repository খুলুন (যেমন `alexa-app`), একটা Personal Access Token বানান (repo অনুমতি সহ)।
2. Termux-এ:
```
pkg install -y git unzip
unzip alexa-app.zip && cd AlexaApp
git init -b main && git add -A && git commit -m "alexa"
git remote add origin https://github.com/ইউজারনেম/alexa-app.git
git push -u origin main        # পাসওয়ার্ডের জায়গায় token
```
3. GitHub-এ repo → **Actions** → "Build APK" (push করলে নিজেই চলে; না চললে Run workflow)। ৪-৮ মিনিট লাগে।
4. শেষ হলে সেই run খুলে নিচে **Artifacts → Alexa-debug-apk** ডাউনলোড করুন (zip, ভেতরে `app-debug.apk`)।
5. APK ইনস্টল করুন (Unknown sources অনুমতি দিন; Play Protect সতর্কতা এলে "Install anyway")।

বিল্ড এরর এলে Actions-এর লাল ধাপের লগ কপি করে আমাকে পাঠান — ঠিক করে দেব। (কোডটি এখানে কম্পাইল করে দেখা হয়নি।)

## ২. প্রথমবার সেটআপ (অ্যাপে ⚙ Settings)
- **Groq API key** দিন (console.groq.com → API Keys, ফ্রি) — শোনা (Whisper) ও কাজ বোঝা দুটোতেই লাগে। চাইলে Gemini key-ও দিতে পারেন।
- **Accessibility**: অ্যাপে সুইচ অন করুন → Android Settings > Accessibility > **Alexa** চালু করুন।
  Android 13+ হলে সুইচ ধূসর থাকলে: Settings > Apps > Alexa > ⋮ (উপরে ডানে) > **Allow restricted settings**, তারপর আবার চেষ্টা।
- **Display over other apps** অনুমতি দিন (ব্যাকগ্রাউন্ড থেকে অ্যাপ খোলার জন্য), ব্যাটারি অপ্টিমাইজেশন বন্ধ করুন।
- **Mic test** চাপুন; চাইলে Home চেপে অন্য অ্যাপে গিয়ে বলুন। লগে ফল আসবে।
- **Termux**: Termux-এ `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties`, Termux পুরো বন্ধ করে আবার খুলুন, অ্যাপে "Termux-এ কমান্ড চালানোর অনুমতি" ও "Termux টেস্ট" চাপুন। (Termux-কে F-Droid থেকে নিন।)
- বাংলায় উত্তর শুনতে ফোনে Google Text-to-speech-এর বাংলা ভয়েস ইনস্টল রাখুন।

## ৩. ব্যবহার
- **Start** → ব্যাকগ্রাউন্ডে শোনা শুরু (নোটিফিকেশনে "শুনছি")। **Stop** → সব বন্ধ।
- "Hey Alexa" / "Hello Alexa" / "Alexa" → "জি বস, কী করতে পারি?" → আপনার কাজ। অথবা একবারে: "Alexa, YouTube খোলো"।
- "Alexa stop" → চলমান কাজ থামে। নিচে লিখেও কমান্ড দেওয়া যায় (Start ছাড়াই)।
- সংবেদনশীল কাজের (Send/Pay/Delete/Install/Call...) আগে নোটিফিকেশনে **Allow / Deny** আসে।
- বাঁদিকে নিচে **CC** = সাবটাইটেল অন/অফ; সাবটাইটেল ৫ সেকেন্ড পরে নিজে মুছে যায়।

## গোপনীয়তা ও সীমা
- শোনার সময় প্রতিটি কথার অংশ (শুধু "Alexa" নয়) Groq Whisper-এ আপলোড হয়। ঘণ্টায় সর্বোচ্চ ১৫০টি। গোপন কথাবার্তার সময় Stop করে রাখুন।
- API key ফোনের অ্যাপ-স্টোরেজে থাকে।
- ফ্রি মডেলে জটিল কাজে ভুল হবে। ডিবাগ APK, Play Store-এর জন্য নয়।
