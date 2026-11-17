# Разработка и выпуски

Установите Git, JDK 17, Python 3.10 или новее, Android SDK 36 и Build Tools 36.0.0. В Windows используйте `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

Увеличьте `versionCode` и обновите `versionName`. Настройте секреты `UNIEAR_KEYSTORE_BASE64` и `UNIEAR_KEYSTORE_PASSWORD`, затем отправьте соответствующий тег `vX.Y.Z`. Процесс проверит подпись и добавит APK и контрольную сумму SHA-256 в выпуск GitHub.

В каталоге `docs` выполните `npm ci`, затем `npm run dev` или `npm run build`. Статический сайт создаётся в `.vitepress/dist`.
