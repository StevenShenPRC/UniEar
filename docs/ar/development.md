# التطوير والإصدارات

ثبّت Git وJDK 17 وPython 3.10 أو أحدث، بالإضافة إلى Android SDK 36 وBuild Tools 36.0.0. على Windows استخدم `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

زِد `versionCode` وحدّث `versionName`. اضبط السرّين `UNIEAR_KEYSTORE_BASE64` و`UNIEAR_KEYSTORE_PASSWORD` ثم ادفع الوسم المطابق `vX.Y.Z`. يتحقق سير العمل من التوقيع ويرفق ملف APK وقيمة SHA-256 بإصدار GitHub.

في مجلد `docs` نفّذ `npm ci` ثم `npm run dev` أو `npm run build`. يُنشأ الموقع الثابت في `.vitepress/dist`.
