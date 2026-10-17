# Media Source View Modes (v2.15.2 / build 202610172050)

## Overview
This parity note documents the media-source view modes introduced for the video creation / media selector flow in NasFinder Android.

## Key Changes
1. **View Mode Selection (`MediaSourceViewMode`)**:
   - `DETAILS` ("자세히"): Renders metadata rows (`SourceRow`) with filename, folder/source indication, byte size, and formatted date.
   - `THUMBNAIL` ("썸네일"): Default layout mode (`GridCells.Adaptive(104.dp)`).
   - `POSTER` ("포스터"): Larger poster grid (`GridCells.Adaptive(158.dp)`) with enhanced typography, higher resolution thumbnails, and date information.
   - Compact mode control exposed in both PhoneHard and Favorites folder/browse routes via `MediaSourceViewModeMenu`.
   - Default is `THUMBNAIL`, managed independently from general browser preferences and preserved across folder navigation in the media-selector flow.

2. **Navigation & Basket Preservation**:
   - Back actions (`BackHandler` and TopAppBar navigation icon) unified via `handleBack`.
   - Navigating back from nested subdirectories pops up one directory level.
   - When at the root of a tab, backing returns to the primary "photos" tab before exiting the media screen.
   - In BrowserScreen pickMode (`mediaPick != null`), back handling routes through `cancelNetworkMediaPick()`, returning safely to the media creation screen without dismissing the entire screen to Dashboard.
   - Basket (`selected`), selection order, and staging order remain untouched during tab switching, folder navigation, or cancellation.
   - Embedded Google Photos source remains untouched and fully functional.

3. **Version & Build**:
   - Updated `versionName` to `2.15.2`.
   - Updated `versionCode` to `416673`.
   - Updated `BUILD_NUMBER` to `202610172050`.

## Android 빌드/설치 기록 (2026-10-17 21:10–21:12 KST)

- Apple corrective 마커 확인 후 `./gradlew assembleDebug --console=plain` 실행, 로그: `/tmp/nasfinder-media-modes-android-build.log`.
- 1차 빌드 exit=1: `LocalPhotoLibraryScreen.kt`의 `DropdownMenu`/`DropdownMenuItem`/`Surface` import 누락 → 해당 import 3줄만 추가. 2차 빌드 exit=0 (BUILD SUCCESSFUL).
- 소스 수정: `NasFinderViewModel.loadPhoneHardFolderResult`(미디어 소스 탐색 전용, CancellationException 전파, 그 외 실패는 Result.failure) 추가. 기존 `loadPhoneHardFolder`는 그대로 유지. `LocalPhotoLibraryScreen.kt` PhoneHard 페이지는 실패 시 빈 폴더 대신 오류 메시지 + "다시 시도" + "상위 폴더로" 표시, 바구니/경로 유지.
- 네트워크 선택(`BrowserScreen` pickMode)은 `BrowserMenu`의 레이아웃 선택(자세히/작은 썸네일/포스터)이 pickMode에서도 노출되어 추가 수정 없음.
- APK: versionName 2.15.2 / versionCode 416673, SHA256 `d0c26faa68e34b51a4eb9b59ed1292e8de921b1aef2f94a928e07869014e7499` (기기 설치본과 일치).
- 설치: 192.168.0.253:39513, 활성 서비스·전송 없음 확인 후 force-stop, `adb install -r`(데이터 보존) Success, `.EnamelLauncherAlias` 실행, pid 27924.
- 미수행: 테스트/lint/Git/UI 입력. 실제 보기 모드·혼합 소스 복사·내보내기·뒤로/취소 확인은 TM 담당.

## 후속 소스 수정 (SOURCE ONLY, 미빌드/미설치)
- LocalPhotoLibraryScreen.kt: HorizontalPager 각 페이지를 `Box(Modifier.fillMaxSize().focusProperties { canFocus = pagerState.currentPage == pageIndex })`로 감쌈. 이웃 페이지는 계속 composition(beyondViewportPageCount=1 유지, Google 세션/바구니/경로 보존)되지만 키보드·D-pad 포커스는 활성 페이지에만 허용. 신규 의존성 없음(compose ui 1.7.6 기존 API).
- NasFinderApp.kt: BrowserScreen pickMode 전용 `pickLayout`(rememberSaveable, 기본 SMALL_GRID)로 렌더링(`effectiveLayout`). BrowserMenu에 선택 파라미터 `pickLayout/onPickLayout` 추가, pickMode에서만 자세히/썸네일/포스터 3개 표시하고 일반 browser preferences는 변경하지 않음. 일반 브라우저 라벨(작은 썸네일)은 그대로. pickMode에서는 CoverFlow 제외.
- 미실행: 빌드/테스트/설치. 구문·선언 확인은 소스 읽기로만 수행.

## Android 최종 빌드/설치 기록 (2026-10-17 21:33 KST)
- 명령: `./gradlew assembleDebug --console=plain` → exit 0, BUILD SUCCESSFUL in 37s (로그: /tmp/nasfinder-media-modes-android-final-build.log). 컴파일 경고만 있음(NasFinderViewModel.kt:4458 불필요한 safe call), 소스 수정 없음.
- APK 메타데이터: 2.15.2 / code 416673, SHA256 2cf2094ef4e5d6f2179a501bed32243fe75bb75d89a0f371463b479ca9bddb4a
- 설치: 192.168.0.253:39513, 활성 export 없음 확인 후 force-stop, `adb install -r` Success(데이터 보존), .EnamelLauncherAlias 실행(pid 19785).
- 기기 dumpsys versionName=2.15.2 / versionCode=416673, 설치 APK sha256 로컬과 일치.
- 테스트/lint/Git/UI 입력 없음. UI 런타임 검증은 TM 담당.

## 포커스 경계 수정 (SOURCE ONLY, 2026-10-17 21:42 KST, 미빌드/미설치)
- 실제 원인: 2.15.2 최종 후보에서 Photos → Favorites 탭 → Tab 시 화면 밖 PhoneHard 'test' 폴더로 포커스 이동·활성 페이지 변경. 페이지 Box의 `focusProperties { canFocus = ... }`는 Box 자체에 focusTarget이 없어 가장 가까운 하위 focus target(예: LazyVerticalGrid/LazyColumn의 내부 focus group)에만 적용됨(1.7.6 `fetchFocusProperties`는 상위 FocusTarget에서 멈춤). group의 canFocus=false는 자식 항목의 포커스 가능 여부를 막지 않으므로 1D(Tab)/2D 탐색이 이웃 페이지 항목을 그대로 선택함 → canFocus 상속 가정이 잘못됨.
- 수정: 각 페이지를 `Modifier.fillMaxSize().focusProperties { if (pagerState.currentPage != pageIndex) enter = { FocusRequester.Cancel } }.focusGroup()`로 명시적 focus group 경계화. compose-ui 1.7.6 AAR 바이트코드 확인: 하위 항목 requestFocus 시 `performCustomRequestFocus`가 Inactive 상위 group의 `enter`를 호출 → Cancel이면 requestFocus 실패(null) → `moveFocus` onFound=false → 1D 탐색은 다음 후보(활성 페이지)로 계속 진행. `FocusProperties.enter`는 `@ExperimentalComposeUiApi`이므로 `LocalPhotoLibraryScreen`에 OptIn 추가. `focusGroup`은 foundation 1.7.6 `FocusableKt` 기존 API. 신규 의존성 없음.
- 보존: 모든 페이지 composition(beyondViewportPageCount=1, Google 세션/경로/바구니), 선택 처리, NasFinderApp.kt의 pickLayout, 복사 표시 이름 수정 미변경.
- 확인: 괄호 균형 스크립트만 수행(kotlinc/ktlint 없음). 빌드/설치는 `android-final-runtime-idle` 마커 부재로 미수행.

## 포커스 수정 최종 빌드/설치 (2026-10-17 21:49 KST, Asia/Seoul)
- 명령: `./gradlew assembleDebug --console=plain` → exit 0 (BUILD SUCCESSFUL, 19s). 로그 /tmp/nasfinder-media-modes-focus-final-build.log. 컴파일 오류 없음, 소스/버전 변경 없음.
- APK: app/build/outputs/apk/debug/app-debug.apk, versionName 2.15.2 / versionCode 416673, SHA256 ac02d97b839d4f5084d337a6279c51aef8057a998425902cbc6e13e5abf8f15e.
- 설치: 192.168.0.253:39513, 활성 export 없음 확인 후 앱 force-stop, `adb install -r`(데이터 보존) Success, .EnamelLauncherAlias 실행. 기기 설치본 base.apk SHA256이 로컬과 일치, dumpsys 2.15.2/416673.
- 사용자 복사 폴더·데이터 삭제 없음. UI 동작 검증은 수행하지 않음(TM 담당).
