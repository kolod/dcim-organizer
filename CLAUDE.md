# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Build and install debug APK:
```
./gradlew installDebug
```

Run unit tests:
```
./gradlew test
```

Run instrumented tests (requires connected device/emulator):
```
./gradlew connectedAndroidTest
```

Run a single unit test class:
```
./gradlew test --tests "com.kolod.dcimorganizer.PhotoOrganizerTest"
```

## Architecture

Single-activity app with two Kotlin source files:

- **`MainActivity.kt`** — Jetpack Compose UI. `OrganizeScreen` composable manages loading/status state, requests `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` at runtime via `RequestMultiplePermissions`, and launches `PhotoOrganizer.organize()` on `Dispatchers.IO` via coroutine. When the organizer reports URIs it can't write to, the activity calls `MediaStore.createWriteRequest()` and launches the resulting `IntentSender` via `StartIntentSenderForResult` to ask the user to grant write access in a single batch dialog, then re-runs `organize()`.
- **`PhotoOrganizer.kt`** — Core logic. `organize()` uses `MediaStore.Files` ContentResolver queries plus `contentResolver.update()` to move files by setting `RELATIVE_PATH` to `DCIM/<year>/<month>/`. Returns an `OrganizeResult(movedCount, pendingPermissionUris)`; URIs the app isn't allowed to modify (typically files owned by other apps) are collected rather than failing the whole pass.

## Key Constraints

- `minSdk = 33` — the app is MediaStore-only; there is no `File` / `renameTo` fallback.
- Permissions are intentionally minimal: only `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (declared in the manifest, requested at runtime). The app does NOT request `MANAGE_EXTERNAL_STORAGE`. Write access for files the app doesn't own is obtained per-batch via `MediaStore.createWriteRequest()`.
- Files are never overwritten: if `contentResolver.update()` would collide with an existing file at the target `RELATIVE_PATH`, the underlying call fails and the source is skipped.