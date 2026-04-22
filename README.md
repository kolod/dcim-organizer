# DCIM Organizer

An Android utility that reorganizes the photos and videos in your `DCIM/` folder into a `DCIM/<year>/<month>/` layout, using each file's shoot date. One big dated pile becomes a browsable archive — no desktop, no cables, no cloud.

> **Status:** pre-release. The first public release is still in planning; there's no published build yet. The instructions below are for building and running from source.

## What it does

- Scans every image and video under `DCIM/` on external storage via MediaStore.
- For each file, picks a target folder of `DCIM/<year>/<MM>/` based on `DATE_TAKEN` (falling back to `DATE_MODIFIED` if the shoot date is missing).
- Moves the file by updating its `RELATIVE_PATH` through `ContentResolver.update()`. Files already in the correct folder are left alone.
- For files the app doesn't own (e.g. photos taken by the stock Camera app), it collects the URIs and asks the user to grant write access in a single system dialog via `MediaStore.createWriteRequest()`, then re-runs to move them.
- Never overwrites: if the target path already contains a file with the same name, the source is skipped.

## Permissions

The app is intentionally conservative about permissions:

- `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` — declared in the manifest and requested at runtime.
- No `MANAGE_EXTERNAL_STORAGE`. Write access for files owned by other apps is obtained per-batch through the standard `MediaStore.createWriteRequest()` flow, which shows the OS-supplied grant dialog.

If the user denies the read permissions, the app reports that reads are required and stops. If the user denies the write-access dialog, whatever moves already succeeded are kept and the rest are reported as skipped.

## Requirements

- Android 13 (API 33) or newer.
- `compileSdk` 36 / `targetSdk` 37 as configured in `app/build.gradle.kts`.

## Usage

1. Build and install the app from source onto a device running Android 13+ (see **Build** below).
2. Launch it and tap the big **Organize** button.
3. Grant read access to photos and videos when prompted.
4. If any files need write access, approve them in the system dialog that appears.
5. The status line reports how many files were moved, or "Already organized" if nothing needed to change.

## Build

```
./gradlew installDebug          # build and install the debug APK on a connected device
./gradlew assembleDebug         # just build the APK
./gradlew test                  # run unit tests
./gradlew connectedAndroidTest  # run instrumented tests (needs a device/emulator)
```

Run a single unit test class:

```
./gradlew test --tests "com.kolod.dcimorganizer.PhotoOrganizerTest"
```

## Project layout

- `app/src/main/kotlin/com/kolod/dcimorganizer/MainActivity.kt` — Jetpack Compose UI. Hosts the `OrganizeScreen` composable, the runtime-permission launcher, and the `StartIntentSenderForResult` launcher that drives the MediaStore write-request dialog.
- `app/src/main/kotlin/com/kolod/dcimorganizer/PhotoOrganizer.kt` — Core logic. Single `organize()` method that queries MediaStore, computes target `RELATIVE_PATH`s, issues the update, and returns an `OrganizeResult(movedCount, pendingPermissionUris)`.
- `app/src/main/kotlin/com/kolod/dcimorganizer/ui/theme/` — Compose theme (Material 3).
- `app/src/main/AndroidManifest.xml` — declares only the two `READ_MEDIA_*` permissions.

## How the two-pass flow works

1. The user taps **Organize**. If `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` are not already granted, the runtime permission dialog appears first.
2. `PhotoOrganizer.organize()` runs on `Dispatchers.IO`. It moves every file it has write access to, and collects URIs that threw `SecurityException` into `pendingPermissionUris`.
3. If `pendingPermissionUris` is non-empty, the activity calls `MediaStore.createWriteRequest(resolver, uris)` and launches the returned `IntentSender`. The system shows a single dialog listing the affected files.
4. If the user approves, `organize()` runs again — this time the previously-failing files succeed and are moved. Any still-failing files are reported as skipped.
5. If the user denies, the running total is kept and the status line notes that some files were skipped.

## Notes & limitations

- Month folders are zero-padded (`01`..`12`).
- The app relies on the MediaStore index. If a file is present on disk but hasn't been indexed, it won't be seen.
- The app processes every volume reachable via `MediaStore.VOLUME_EXTERNAL`, not just the primary one.
- There's no undo. Once files are moved, you'd need to move them back yourself.
