# NIGHT Voice — Minecraft 1.21.1 (Fabric)

Proximity voice chat with 3D audio, whisper/normal/shout, wall muffling, cave reverb, radio item and voice recording.

## البناء
- GitHub: ارفع المجلد كله على ريبو، وGitHub Actions (`.github/workflows/build.yml`) يبني الـ jar ويرفعه في Artifacts.
- محلياً: `gradle wrapper --gradle-version 8.12` ثم `./gradlew build` ← الناتج في `build/libs/`.
- المود لازم يكون على السيرفر **وعلى كل لاعب** (Fabric API مطلوب).
- افتح منفذ **UDP 24464** على السيرفر (`config/nightvoice.json` → `udpPort`).

## الإعدادات (config/nightvoice.json)
- `whisperRange` 4 | `normalRange` 16 | `shoutRange` 32 | `radioEnabled`
- جهة العميل: الجهاز، الصوت، `occlusion`, `reverb`, `hrtf`, `voiceActivation`, `bitrate` (24000–32000)

## الأزرار (قابلة للتغيير من Controls)
V = اضغط للتحدث | M = كتم المايك (Shift+M = كتم السماعة) | B = همس/عادي/صراخ | G = لاسلكي | Record / Settings بدون زر افتراضي
الإعدادات: Options ← زر **Voice Chat** (أعلى اليمين).

## اللاسلكي
العنصر `nightvoice:radio` (`/give @p nightvoice:radio`). كليك يمين = القناة التالية، Shift+كليك يمين = السابقة.
امسكه بيدك واضغط G: يسمعك كل من يمسك لاسلكي على نفس القناة بأي مسافة. السيرفر هو اللي يتحقق من وجود اللاسلكي بيدك.

## البنية
- `server/VoiceServer` : UDP relay، يعيد توجيه الحزم للاعبين القريبين فقط (لقطة مواقع تُحدَّث كل tick×2، بدون لمس كائنات اللعبة من thread الشبكة).
- التوثيق: السيرفر يرسل للاعب secret عشوائي مع `InitVoicePayload` عند الدخول؛ العميل يثبت نفسه بـ UDP AUTH.
- `client/MicCapture` : TargetDataLine 48kHz/16-bit/mono → Opus (Concentus) إطارات 20ms.
- `client/AudioEngine` : OpenAL مستقل (context خاص + thread-local)، كل متحدث = مصدر Mono ثلاثي الأبعاد.
- `client/Occlusion` : كتم الجدران (Low-pass حسب نوع/سماكة البلوكات) + الصدى (قياس انغلاق المكان بـ 14 raycast).
- `client/VoiceRecorder` : يسجل ملفات `.nvr` (إطارات Opus + وقت + موقع) في `.minecraft/nightvoice/recordings/`.

## ملاحظات تصميمية
- الاستقبال Mono (مو Stereo): OpenAL لا يطبّق 3D إلا على المصادر Mono، وهو يطلع Stereo/Binaural (HRTF) بنفسه للسماعات.
- الخبو: عكسي مع المسافة بعد مسافة مرجعية (ربع المدى) ثم يتلاشى لصفر عند آخر المدى.

## غير مكتمل / المرحلة التالية
- ربط تشغيل `.nvr` مع إعادة التشغيل داخل Flashback (يحتاج Mixin على Flashback؛ حالياً التسجيل فقط).
- أيقونة المايك فوق اسم اللاعب في العالم / Tab list (يحتاج Mixin أو Render event).
- Rate-limit للحزم على السيرفر، ومجموعات/غرف خاصة.
- لم يُجرَّب البناء في بيئتي (لا يوجد وصول لـ Maven)، فتوقع تعديلات بسيطة في أول بناء.
