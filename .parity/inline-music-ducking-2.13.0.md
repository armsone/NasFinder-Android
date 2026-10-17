# NasFinder Android 2.13.0 (416665) — Inline Music Thumbnail Playback & Continuous Audio Ducking

## 1. Detail-View Music Thumbnail Inline Playback (Remote & PhoneHard)
- **State & Playback Engine (`NasFinderViewModel.kt`)**:
  - Added `InlineAudioPlaybackState` (`key`, `title`, `isPreparing`, `downloadFraction`, `isPlaying`, `progress`, `durationMillis`, `positionMillis`, `errorMessage`) to `AppState`.
  - Added single-active ExoPlayer controller (`toggleInlineRemoteAudio`, `toggleInlineInboxAudio`, `stopInlineAudio`, `inlineRemoteAudioKey`, `inlineInboxAudioKey`).
  - Remote audio files use range streaming (`RemoteMediaRoute.RANGE_MEDIA3`) when supported by the active connection, or stage via `downloadCache` with live `downloadFraction` progress before starting playback.
  - PhoneHard (`InboxDisplayItem`) audio files play directly from local storage.
  - Only one audio track plays at a time; switching tracks, changing folders/layouts, entering selection mode, opening full-screen preview/trailer/music-video, backgrounding, or disposing stops and releases the active player.
- **Detail List UI (`NasFinderApp.kt`)**:
  - Added `InlineAudioThumbnailOverlay` on the leading thumbnail box in both Remote `BrowserScreen` (`BrowserLayout.LIST` -> `FileRow`) and PhoneHard `InboxScreen` (`InboxLayout.DETAILS` -> `InboxLeadingPreview`).
  - Shows a play button overlay when idle, circular buffering/download progress while preparing, and a pause/play toggle inside a circular elapsed-progress ring (`CircularProgressIndicator(progress = { progress })`) with `elapsed / duration` metadata while active.
  - Tapping the thumbnail toggles inline playback without triggering row navigation or opening the full-screen preview.

## 2. Continuous Original-Loudness Background Music Ducking
- **Continuous Loudness Envelope (`TrailerAudioAnalysis.kt`)**:
  - Added `AudioAnalysisService.extractLoudnessEnvelope` (50ms bins, RMS + peak blend in dBFS with adaptive room-tone floor and 3-tap temporal smoothing) returning normalized `0.0f..1.0f` loudness per bin.
- **Rendered Timeline Mapping & Gain Smoothing (`AutoDuckingAudioProcessor.kt`, `TrailerExporter.kt`)**:
  - Added `AudioDuckingTimeline.buildWithLoudnessEnvelopes` mapping each rendered scene's `[scene.start, scene.start + scene.length]` slice (and `0.0f` for photo/silent clips) onto the output timeline while preserving original clip gain (`1.0f`).
  - Updated `AutoDuckingAudioProcessor` to sample continuous target music gain (`targetMusicGainAt`, interpolating between `fullGain = 0.85f` and `duckedGain = 0.18f`) with exponential attack (~80ms) and release (~280ms) smoothing per audio frame, or constant gain (`0.85f`) when `autoDucking` is disabled.
  - Preserved `autoDucking` (default `true`) across `TrailerSession`, `TrailerDraftStore`, `VideoTrailerScreen`, settings, cancel, regenerate, and draft resume.

## 3. 필수 보정 및 최종 빌드/설치 증거 [KST Claude Sonnet]
- `TrailerAudioAnalysis.extractLoudnessEnvelope`: 클립 분포 기반 적응형 floor(p20+6dB, [-45,-30]) 및 ceiling -12 제거. 절대 full-scale 고정 매핑(-55 dBFS 이하 → 0f, -25 dBFS 이상 → 1f, 파일별 정규화 없음)으로 교체. RMS/peak 추출, 스무딩, 취소, 무트랙/오류 fallback, 타이밍, 게인 파라미터는 불변.
- 컴파일 오류 수정: `NasFinderViewModel.kt:5192` `streamSpec.createMediaSource(application)` → `createMediaSource()` (시그니처는 `RemoteRangeMediaSource.kt:203` 인자 없음).
- `./gradlew assembleReleaseQa --console=plain`: 실제 exit 0, BUILD SUCCESSFUL.
- APK: `app/build/outputs/apk/releaseQa/app-releaseQa.apk`
- 기기: 192.168.0.253:39513 (SM_F968N, mDNS 기존 페어링 경로로 adb connect). 앱 force-stop → `install -r` Success(데이터 유지) → 실행(pid 3297).
- 설치 확인: versionName 2.13.0, versionCode 416665.
- 미검증: 실제 오디오 청취 확인은 사용자 확인 대상. 테스트 러너 미실행.
