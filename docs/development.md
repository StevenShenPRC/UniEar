# Development and releases

Install Git, JDK 17 and Python 3.10 or later. Android builds also need SDK Platform 36 and Build Tools 36.0.0.

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

On Windows use `gradlew.bat`. For desktop-only Gradle configuration add `-PdesktopOnly`. Protocol code is taken from the HyperEars commit pinned in `upstream/hyperears.rev`.

## Publish an Android release

1. Set `versionName` and increment `versionCode` in `android/build.gradle.kts`.
2. Ensure `UNIEAR_KEYSTORE_BASE64` and `UNIEAR_KEYSTORE_PASSWORD` hold the existing PKCS#12 key with alias `uniear` and its password.
3. Push a tag matching `versionName`, for example `v0.3.2`, on the intended commit. The Release workflow tests, builds, signs and verifies the APK before creating a GitHub Release with the APK, SHA-256 file and generated notes.

## Build these docs

```sh
cd docs
npm ci
npm run dev
npm run build
```

The static site is in `.vitepress/dist`. Set `DOCS_BASE` before building if you host it at a subpath. Keep English pages in this directory and their Chinese counterparts in `zh/` under the same filename.
