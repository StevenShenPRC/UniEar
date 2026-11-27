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
3. Use a release commit prefix or push a matching tag as described below.

## Build these docs

```sh
cd docs
npm ci
npm run dev
npm run build
```

The static site is in `.vitepress/dist`. Set `DOCS_BASE` before building if you host it at a subpath. Keep English pages in this directory and their Chinese counterparts in `zh/` under the same filename.

## Add a language

Copy `core/src/main/resources/uniear/core/messages.properties` to `messages_<language>.properties` beside it. Translate the values and preserve the keys and numbered placeholders. Register the language in `Translations.supportedLanguages`. For Android, add `values-<language>/strings.xml` and declare the language in `res/xml/locales_config.xml`. Mirror the documentation pages under `docs/<language>/`, then register the locale in `docs/.vitepress/config.mts`. Set the text direction to `rtl` when appropriate. Unsupported application locales and missing catalog entries use English.

## Commit-triggered releases

Push the release commit to `main`. The **first line of the HEAD commit message** must start with one of these prefixes.

| Commit subject | GitHub tag | Release type |
| --- | --- | --- |
| `release-v1.2.3: Describe the update` | `v1.2.3` | Stable |
| `beta-v1.2.3: Describe the beta` | `beta-v1.2.3` | Prerelease |

Use three numeric version components. The prefix must be followed by the end of the line, whitespace or a colon. Set Android `versionName` to the same numeric version and increment `versionCode` before committing. The workflow builds and verifies the signed APK, runs its startup checks, then attaches the APK and SHA-256 checksum to the GitHub Release. Beta releases are marked **Prerelease** and never become **Latest**.

Only the final commit of a push is inspected. For a squash merge, put the prefix in the resulting squash commit title. If using a merge commit, put it in the merge commit title. A prefix in a commit body or an earlier commit does not trigger publication.

Existing `vX.Y.Z` and `beta-vX.Y.Z` tags also trigger the corresponding release. Use a new version for each beta build. Re-running a published version preserves its assets, and a tag pointing to a different commit fails validation. Manual runs validate and build matching release subjects or tags without publishing.
