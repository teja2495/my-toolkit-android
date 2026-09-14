# Toolkit for Android

A personal Android utility app with a few features I built for myself. This is also the companion app for [Toolkit for macOS](https://github.com/teja2495/my-mac-app), providing the Android side of its Mac–Android integration features.

## Features

### Mac Integration

Pair your phone with Toolkit on your Mac over a local Wi-Fi network. Once connected, you can:

- Browse files on your Android phone from the Mac, and browse the Mac's Desktop and Downloads folders from your phone.
- Send files from your phone with Android's Share menu, or drop files into Toolkit on the Mac to save them to Android Downloads.
- Send your phone's clipboard text to the Mac with a Quick Settings tile. The Mac app can also sync its clipboard text to the phone.

Pairing requires you to confirm the same code on both devices. The bridge uses encrypted connections and only starts on Wi-Fi networks you mark as trusted in the Android app.

### Rewritely

Create text shortcuts with custom prompts, then use them in selected Android apps to rewrite text. Rewritely uses an Accessibility service to detect shortcuts and update the active text field. You can use an OpenAI API key for in-place rewriting or configure a shortcut to open the prompt in ChatGPT.

## Getting started

1. Build and install the Android app, and run [Toolkit for macOS](https://github.com/teja2495/my-mac-app) on your Mac.
2. Connect both devices to the same Wi-Fi network. In the Android app, open **Mac Integration**, trust the current network, and tap **Start Bridge**.
3. In the Mac app, open **Phone Integration**, find your phone, and click **Pair**. Check that the pairing code matches on both devices, then approve it on each device.

File browsing from the Mac requires file access on Android. The Android app offers that permission in **Mac Integration**. For Rewritely, enable its Accessibility service, add a shortcut, and choose the apps where it should work. An OpenAI API key is needed for in-place rewrites.

## Building

Open the project in Android Studio, or build a debug APK with:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The project targets Android 7.0 (API 24) and later.
