# Desarrollo y versiones

Instala Git, JDK 17, Python 3.10 o posterior, Android SDK 36 y Build Tools 36.0.0. En Windows usa `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

Incrementa `versionCode` y actualiza `versionName`. Configura los secretos `UNIEAR_KEYSTORE_BASE64` y `UNIEAR_KEYSTORE_PASSWORD` y sube la etiqueta `vX.Y.Z` correspondiente. El flujo verifica la firma y adjunta el APK y su suma SHA-256 a la versión de GitHub.

En `docs`, instala con `npm ci` y ejecuta `npm run dev` o `npm run build`. El sitio estático se genera en `.vitepress/dist`.
