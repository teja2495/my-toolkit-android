# My Toolkit Android - AI Agent Guide

Follow these instructions before making code changes in this repository.

## Hard Rules

- Never run `git add` or `git commit` unless the user explicitly asks for it.
- Follow the existing code architecture, folder structure, and UI design patterns already present in this repo.
- Keep changes focused and incremental. Do not refactor unrelated features while implementing a request.
- This app is for the user's personal needs. Features in this repo may be completely unrelated to each other, so do not force unrelated features into a shared abstraction unless the codebase already supports that pattern.

## Product Context

- This is a personal Android utility app with multiple independent features.
- Some features may act as companion functionality for the macOS app in `/Users/teja2495/Projects/my-toolkit-mac`.
- When a feature clearly has a macOS counterpart, inspect the matching code in `my-toolkit-mac` and preserve the intended behavior unless the user asks for Android-specific changes.
- When a feature is Android-only, treat this repo as the source of truth and follow the existing local implementation style.

## Implementation Guidance

- Prefer extending the existing feature/package structure over introducing new top-level architecture.
- Keep UI code aligned with the current Compose and Material 3 patterns already used in the app.
- Keep reusable logic close to the feature unless it is already shared across multiple parts of the app.
- If a new request touches only one feature, avoid introducing cross-feature coupling.

## Required Verification

Run this from the repo root after every implementation task:

```bash
./gradlew assembleDebug && adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk && adb shell am force-stop com.tk.myapp && adb shell am start -W -n com.tk.myapp/com.tk.myapp.MainActivity
```

If it fails because of code or build issues, fix them before finishing.
