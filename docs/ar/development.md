# التطوير والإصدارات

ثبّت Git وJDK 17 وPython 3.10 أو أحدث، بالإضافة إلى Android SDK 36 وBuild Tools 36.0.0. على Windows استخدم `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

زِد `versionCode` وحدّث `versionName`. اضبط السرّين `UNIEAR_KEYSTORE_BASE64` و`UNIEAR_KEYSTORE_PASSWORD` ثم ادفع الوسم المطابق `vX.Y.Z`. يتحقق سير العمل من التوقيع ويرفق ملف APK وقيمة SHA-256 بإصدار GitHub.

في مجلد `docs` نفّذ `npm ci` ثم `npm run dev` أو `npm run build`. يُنشأ الموقع الثابت في `.vitepress/dist`.

## النشر باستخدام بادئة عنوان الإيداع

ادفع إلى `main` إيداعًا يبدأ سطر عنوانه الأول بـ `release-v1.2.3:` للإصدار المستقر، أو `beta-v1.2.3:` للإصدار التجريبي. يُنشأ الوسمان `v1.2.3` و`beta-v1.2.3` على الترتيب. يجب أن تتبع البادئة نهاية السطر أو مسافة أو نقطتان.

يجب أن تطابق الأرقام قيمة `versionName`، مع زيادة `versionCode`. بعد البناء والتوقيع وفحص بدء التشغيل، يُرفق ملف APK وملف SHA-256 بإصدار GitHub. تُعلّم النسخة التجريبية بأنها Prerelease ولا تصبح Latest. يُقرأ عنوان آخر إيداع في عملية الدفع فقط. عند الدمج أو squash، ضع البادئة في عنوان الإيداع النهائي.

يبقى دفع الوسمين `vX.Y.Z` و`beta-vX.Y.Z` مدعومًا. استخدم رقمًا جديدًا لكل نسخة تجريبية جديدة. تحفظ إعادة التشغيل المرفقات المنشورة، ويؤدي وجود وسم يشير إلى إيداع آخر إلى خطأ. يفحص التشغيل اليدوي ويبني دون نشر.
