# Développement et versions

Installez Git, JDK 17, Python 3.10 ou ultérieur, Android SDK 36 et Build Tools 36.0.0. Sous Windows, utilisez `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

Incrémentez `versionCode` et définissez `versionName`. Configurez les secrets `UNIEAR_KEYSTORE_BASE64` et `UNIEAR_KEYSTORE_PASSWORD`, puis poussez le tag `vX.Y.Z` correspondant. Le workflow vérifie la signature et joint l’APK et sa somme SHA-256 à la version GitHub.

Depuis `docs`, installez les dépendances avec `npm ci`, puis lancez `npm run dev` ou `npm run build`. Le site statique est généré dans `.vitepress/dist`.
