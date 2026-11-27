# Développement et versions

Installez Git, JDK 17, Python 3.10 ou ultérieur, Android SDK 36 et Build Tools 36.0.0. Sous Windows, utilisez `gradlew.bat`.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

Incrémentez `versionCode` et définissez `versionName`. Configurez les secrets `UNIEAR_KEYSTORE_BASE64` et `UNIEAR_KEYSTORE_PASSWORD`, puis poussez le tag `vX.Y.Z` correspondant. Le workflow vérifie la signature et joint l’APK et sa somme SHA-256 à la version GitHub.

Depuis `docs`, installez les dépendances avec `npm ci`, puis lancez `npm run dev` ou `npm run build`. Le site statique est généré dans `.vitepress/dist`.

## Publication par préfixe de commit

Poussez sur `main` un commit dont la première ligne commence par `release-v1.2.3:` pour une version stable, ou `beta-v1.2.3:` pour une préversion. Le workflow crée respectivement les tags `v1.2.3` et `beta-v1.2.3`. Le préfixe doit être suivi de la fin de ligne, d’un espace ou de deux-points.

La version numérique doit correspondre à `versionName` et `versionCode` doit augmenter. Après construction, signature et tests de démarrage, l’APK et sa somme SHA-256 sont joints à la version GitHub. La bêta est marquée Prerelease et ne devient jamais Latest. Seul le titre du dernier commit poussé est lu. Pour un squash ou une fusion, utilisez le préfixe dans le titre du commit final.

Les tags `vX.Y.Z` et `beta-vX.Y.Z` restent pris en charge. Chaque nouvelle bêta nécessite un nouveau numéro. Les exécutions répétées conservent les fichiers existants. Un tag visant un autre commit provoque une erreur. L’exécution manuelle valide et construit sans publier.
