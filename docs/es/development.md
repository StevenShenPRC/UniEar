# Desarrollo y versiones

Instala Git, JDK 17, Python 3.10 o posterior, Android SDK 36 y Build Tools 36.0.0. En Windows usa `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

Incrementa `versionCode` y actualiza `versionName`. Configura los secretos `UNIEAR_KEYSTORE_BASE64` y `UNIEAR_KEYSTORE_PASSWORD` y sube la etiqueta `vX.Y.Z` correspondiente. El flujo verifica la firma y adjunta el APK y su suma SHA-256 a la versión de GitHub.

En `docs`, instala con `npm ci` y ejecuta `npm run dev` o `npm run build`. El sitio estático se genera en `.vitepress/dist`.

## Publicar mediante el título del commit

Envía a `main` un commit cuya primera línea empiece por `release-v1.2.3:` para una versión estable o `beta-v1.2.3:` para una versión preliminar. Los tags serán `v1.2.3` y `beta-v1.2.3`, respectivamente. Tras el prefijo debe ir el final de línea, un espacio o dos puntos.

La versión numérica debe coincidir con `versionName` e incrementarse `versionCode`. Tras compilar, firmar y probar el inicio, se adjuntan el APK y su suma SHA-256 a GitHub Release. Beta se marca como Prerelease y nunca como Latest. Solo se lee el título del último commit enviado. En fusiones o squash, pon el prefijo en el título del commit resultante.

Se siguen admitiendo tags `vX.Y.Z` y `beta-vX.Y.Z`. Usa un número nuevo para cada beta. Las repeticiones conservan los archivos publicados. Un tag dirigido a otro commit causa un error. La ejecución manual valida y compila sin publicar.
