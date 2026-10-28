# Text Launcher

A tiny Android home screen that shows **app names only**. No icons are loaded,
no dependencies, a few KB of code.

- Alphabetical list of every app, plain text
- Type to filter (prefix matches first); **Enter** launches the top result
- **Long-press** a name: App info, Uninstall, Hide / Unhide
- **Long-press the clock** to show or conceal hidden apps
- **Home** resets the search; **Back** only clears the search box
- Updates itself when you install or remove apps

Requires Android 8.0 (API 26) or newer.

## Get the APK from GitHub

1. Create a new GitHub repo and push this folder to it (`main` branch).
2. Open the **Actions** tab. The *Build APK* workflow runs on every push.
3. When it finishes, open the run and download the **TextLauncher-apk** artifact (a zip containing `TextLauncher.apk`).

For a permanent download link, push a tag instead:

```
git tag v1.0
git push origin v1.0
```

The APK is then attached to a GitHub Release.

## Install

Copy the APK to your phone, open it, and allow "install unknown apps" when asked.
Then press Home and choose **Text Launcher** as your default home app.

## Optional: stable signing key

Without one, CI signs with a throwaway debug key, so each build has a different
signature and you must uninstall before updating. To fix that, make a keystore once:

```
keytool -genkeypair -v -keystore release.jks -alias textlauncher \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks
```

Add these repo secrets (Settings, Secrets and variables, Actions):
`KEYSTORE_BASE64` (the base64 output), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Keep `release.jks` private and backed up.

## Build locally

```
./gradlew assembleRelease
```

Needs JDK 17 and the Android SDK (platform 34).
