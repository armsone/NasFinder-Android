# Parity & Refresh Verification Report: 2.15.1

## Problem Analysis & Root Cause
In `LocalPhotoLibraryScreen.kt`, `MediaSourcePageContent` is rendered inside `HorizontalPager` with `beyondViewportPageCount = 1`.
When browsing other source tabs (such as photos or calendar), the adjacent "phonehard" page can remain cached in memory offscreen.
In `MediaSourcePageContent`:
```kotlin
val listing by produceState<List<com.armsone.nasfinder.data.PhoneHardItem>?>(null, page.path) {
    value = null
    value = withContext(Dispatchers.IO) { model.loadPhoneHardFolder(page.path) }
}
```
Because the produceState keys were solely `page.path`, when a user copied files into PhoneHard (or created a new directory) from photos/calendar and then switched to the PhoneHard tab, `page.path` remained identical (e.g. `null` for root), causing the offscreen cached state to retain old directory contents without triggering a reload.

## Solution & Minimal Fix
1. **Source Revision State**: Introduced `sourceRevision` remembered across recompositions (`rememberSaveable`).
2. **Settled Activation Reload**: Added `LaunchedEffect(pagerState.settledPage) { sourceRevision++ }` so whenever the pager settles on a page (such as switching tabs into PhoneHard), `sourceRevision` updates.
3. **Copy Completion Reload**: Added `sourceRevision++` and `libraryRevision++` to the `onCopyToPhoneHard` completion callback (`SelectPhoneHardDestinationDialog`), ensuring copy operations immediately invalidate and refresh the source listings.
4. **Lifecycle Foreground Reload**: Connected `Lifecycle.Event.ON_RESUME` to bump `sourceRevision++` as well as `libraryRevision++`.
5. **Key Propagation**: Passed `revision = sourceRevision` into `MediaSourcePageContent` and added `revision` to the `produceState(null, page.path, revision)` keys for PhoneHard, and `LaunchedEffect(..., revision)` for Favorites thumbnails.
6. **No Regressions**: No continuous polling or global canvas redraws; selection basket, selection ordering, and per-source folder navigation paths remain intact; existing error handling, folder thumbnails, and fallback mechanics are completely preserved.

## Version Bump
- `versionName`: `2.15.0` -> `2.15.1`
- `versionCode`: `416671` -> `416672`
- `BUILD_NUMBER`: `202610172012` (KST timestamp)

## Build/Install Record (Claude, 20:18)
- Active build check: Android Studio idle Gradle daemons only; no active build.
- `./gradlew assembleDebug --console=plain` -> exit=0, BUILD SUCCESSFUL (47s), log: /tmp/nasfinder-android-refresh-20261017/build.log. No source repair needed. Tests/lint not run.
- Active services before stop: none (`dumpsys activity services` nothing). `am force-stop` exit=0.
- `adb -s 192.168.0.253:39513 install -r` exit=0 (Success), data preserved.
- Launch `.EnamelLauncherAlias` exit=0 (Starting intent only; UI not verified).
- Installed: versionName=2.15.1, versionCode=416672.
- SHA256 APK = installed base.apk = 51c2e99c141106a2ca3a1232493108aa2e506bec3c9f3d44c5ff2eedc73b02ab (match).
