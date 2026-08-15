# iPhone 디자인 패리티 기술서

## 2026-08-15 실제 iPhone 화면 26장 재대조

사용자가 제공한 실제 iPhone 캡처 26장을 코드보다 우선하는 시각 기준으로 재대조했다. 원본 HEIC는 읽기 전용으로 유지하고 Quick Look 렌더만 사용했다.

- Dashboard: `내 파일`과 `네트워크`를 각각 하나의 둥근 그룹과 내부 구분선으로 구성하고, 빈 즐겨찾기 shelf는 숨긴다. 저장공간은 실제 Android 볼륨 수치, 설정은 하단 capsule로 표시한다.
- Browser: 검색은 상단 검색 버튼으로 필요할 때만 연다. 파일 작업/보기/정렬은 iPhone과 같은 전체 폭 bottom sheet이며 `자세히/작은 썸네일/포스터`, 이름·날짜·크기·종류, 오름/내림, 숫자·한글·외국어 우선, 폴더 먼저를 같은 segmented 구조로 제공한다.
- Poster/Cover Flow: 세로 포스터는 2열 정사각형 artwork와 외부 이름·메타데이터를 사용한다. 가로 포스터는 실제 여러 항목을 겹친 Cover Flow, 회전·반사·밝은/어두운 바닥으로 전환한다.
- Settings: 테마는 3+2 compact preview, 앱 아이콘은 Blue NAS → Purple NAS → Vibe Coder → Cyber Vault 순서와 실제 원본 raster를 사용한다.
- Thumbnail: Synology 서버가 특정 MOV codec의 썸네일을 만들지 못하면 iPhone과 동일하게 bounded Range 기반 영상 프레임 생성으로 fallback한다. visible poster의 Range 작업은 제한된 동시성으로 처리해 하나의 프레임도 끝나기 전에 공유 예산이 소진되는 현상을 막는다.

> 기준 소스: 로컬 iOS NasFinder 저장소 (읽기 전용 재조사, 2026-08-15, 커밋 `f5e7007` 및 당시 미커밋 UI 포함)

### `f5e7007` 이후 working-tree UI delta 감사

| 경로/상태 | iPhone working tree | Android 대응 | 검증 상태 |
|---|---|---|---|
| 파일 브라우저 · 일반 보기 · portrait/landscape · 모든 테마 | 시스템 back을 숨기고 32pt accent 원형 `chevron.left`; 탭은 이전 폴더, 0.5초 길게 누르면 첫 화면 | 32dp 원형 glyph/48dp 터치 영역, 탭은 기존 이전 폴더·전송 취소를 보존하고 long press는 Dashboard로 이동. 선택 모드에서는 long press를 노출하지 않음 | 소스 정적 대조 |
| 파일 브라우저 · Cover Flow | 별도 Cover Flow chrome을 계속 사용하며 일반 back button은 숨김 | 기존 landscape multi-item Cover Flow의 독립 chrome 유지 | 변경 없음 확인 |
| Super Thumbnail · setup | 폴더 제목 행뿐 아니라 하단 `하위 폴더 포함…` 안내까지 한 버튼 영역이며 폴더 선택 접근성 hint 제공 | 두 요소를 단일 clickable 영역으로 결합하고 `NAS에서 처리할 폴더 선택` action label 제공 | 소스 정적 대조 |
| Super Thumbnail · 조건 미충족 | 10회 숨은 시작, 5회부터 countdown/selection haptic, 10회 success haptic; 선택·조건·작업·이탈 때 reset | 기존 Android 구현이 같은 visible 상태와 reset 지점을 유지 | 변경 없음 확인 |
| Super Thumbnail · 최근 작업 | 보고서 진입 영역과 별도 `…` destructive 삭제 menu | 기존 40dp menu target과 개별 삭제 동작 유지 | 변경 없음 확인 |
| Super Thumbnail · 진행/보고/오류/modal | forced awake는 setup/progress view가 아니라 실제 작업 activity에만 귀속. 협업 Vault와 thermal pause 문구가 추가됨 | `AUTOMATIC`은 실제 WorkManager active 상태에서만 화면을 유지. Android snapshot에 협업 phase·thermal pause reason 필드가 없어 거짓 상태를 추가하지 않음 | backend 상태 계약 차이는 남은 gap |

loading/empty/error/destructive confirmation의 SwiftUI 계층에는 이 delta에서 화면 구조 변경이 없었다. Android도 기존 실제 Work snapshot, session report, Vault 삭제 결과만 표시하며 추정 수치를 만들지 않는다. 빌드와 실기기 screenshot 재캡처는 루트 최종 검증에서 수행한다.
>
> 목적: Android/Jetpack Compose 구현이 iPhone 앱의 화면 구조, 시각 위계, 상태, 제스처, 접근성과 보조 UI를 빠뜨리지 않고 재현하도록 하는 단일 기준 문서.

## 1. 패리티 원칙

- 기능명만 옮기지 말고 **정보의 우선순위, 여백, 노출 강도, 상태 전환**까지 동일하게 맞춘다.
- iOS 포인트(`pt`)는 Compose의 `dp`, iOS 고정 폰트 크기는 `sp`로 1:1 시작한다. 시스템 의미 글꼴(`headline`, `caption` 등)은 Android Typography 토큰에 매핑하되 사용자 글꼴 크기를 따른다.
- SF Symbols는 같은 의미와 실루엣의 Material Symbols Rounded를 우선한다. 정확한 대응이 없으면 앱 전용 VectorDrawable을 만든다. 아이콘 의미나 접근성 라벨을 바꾸지 않는다.
- iOS `NavigationStack`은 Navigation Compose, `sheet`는 `ModalBottomSheet` 또는 전체 화면 dialog destination, `fullScreenCover`는 전체 화면 destination, `alert`/`confirmationDialog`는 각각 `AlertDialog`/`ModalBottomSheet`로 대응한다.
- `List(.insetGrouped)`는 둥근 그룹 카드처럼 재해석하지 말고 Material3의 edge-to-edge 배경 위에 section 간격과 divider를 억제한 그룹형 목록으로 만든다. `List(.plain)`은 row 중심의 평면 목록이다.
- iOS `.bar`, material(`thin/regular/ultraThin`)은 Android에서 반투명 surface + 가능할 때 blur로 대응한다. blur 미지원 시 불투명 surface로 대비를 보장한다.
- 탭 영역은 최소 `44dp`, Android 권장 접근성 목표는 `48dp`로 넓히되 보이는 크기는 iPhone과 같게 유지한다.
- 동적 글꼴/접근성 크기에서 잘림보다 재배치를 우선한다. iPhone의 accessibility size 분기(열 수 축소, 행 높이 증가)를 그대로 둔다.

## 2. 전역 디자인 시스템

### 2.1 테마 선택과 색상

테마는 `자동`, `낮`, `밤`, `Vibe Coder`, `Windy Meadow` 5종이다. 자동은 시스템, 낮/Meadow는 light, 밤/Vibe는 dark를 강제한다. 앱 전체 tint는 아래 accent를 사용한다.

| 토큰 | iPhone 값 | Compose 대응 |
|---|---|---|
| Accent 기본 | light `#007AFF`, dark `#0A84FF` | `colorScheme.primary` |
| Accent Vibe | RGB 0.18/0.91/0.72 = `#2EE8B8` 근사 | Vibe primary |
| Accent Meadow | RGB 0.05/0.55/0.76 = `#0D8CC2` 근사 | Meadow primary |
| NAS blue | light `#007AFF`, dark `#0A84FF` | Synology 계열 강조 |
| SFTP green | light `#33AD4F`, dark `#30D159` | SFTP 강조 |
| Browser orange | light `#FF9400`, dark `#FF9E0A` | Browser 강조 |
| Folder blue 기본 | light `#1478B3`, dark `#5CBAF0` | 폴더 아이콘 |
| Folder Vibe / Meadow | `#3BD6B0` / `#0A7AAE` | 테마별 폴더 |
| Content background 기본 | light `#F4FBFE`, dark `#09131C` 근사 | 화면 바탕 |
| Content background Vibe / Meadow | `#030B0B` / `#EDF5D1` 근사 | 화면 바탕 |
| Thumbnail surface 기본 | light white 88%, dark `#131C24` | 썸네일 카드 |
| Thumbnail surface Vibe / Meadow | `#091B18` / `#FBF9E8` | 썸네일 카드 |
| Thumbnail border 기본 | light `#BFE3F2`, dark `#2E4757` | 1dp outline |
| Thumbnail border Vibe / Meadow | `#1A5245` / `#9EBA66` | 1dp outline |
| Vibe primary/secondary text | `#E6FAF2` / `#99BFB3` | onBackground/onSurfaceVariant |

서비스 고유색과 원형 문자 배지는 반드시 유지한다.

| 서비스 | 색 | 배지 |
|---|---:|---:|
| Synology | `#0067E6` | N |
| SFTP | `#218739` | S |
| SMB | `#0F6CBD` | M |
| WebDAV | `#6554C0` | W |
| FTP | `#E87500` | F |
| Dropbox | `#0061FF` | D |
| OneDrive | `#0078D4` | O |
| Google Drive | `#34A853` | G |
| 폰하드 | `#5856D6` | H |

Night는 서비스색을 white 쪽으로 8%, Vibe는 `#BDFFE6` 쪽으로 6%, Meadow는 black 쪽으로 4% 혼합한다. 배지 글자는 WCAG 대비가 더 높은 black/white를 계산해 선택한다.

근거: `NasFinder/App/SkyBreezeTheme.swift`, `Resources/Assets.xcassets/AccentColor.colorset/Contents.json`.

### 2.2 배경 장식

- 기본 낮: `#96D9FF → #DBF2FF → white`, top-leading에서 bottom-trailing. 큰 white cloud(화면 폭 42%, 최대 190, 우상단 y 34, opacity .38), 작은 cloud(24%, 최대 110, 좌측 y 122, .24), wind(20%, 최대 86, accent .10, y 210).
- 기본 밤/자동 dark: `#091F30 → #0E1721`, cloud opacity `.06/.04`.
- Vibe: `#010908 → #051713 → #030B0B`, 12열 monospace code rain. 글자 6~11sp, 열 간격 최소 8, 첫 글자 `#BDFFE6` + glow radius 4, 나머지는 accent 20%.
- Meadow: `#3BBDF2 → #B8E6E0 → #E3F0B3` 근사, 하단 초록 ellipse(폭 135%, 높이 46%, opacity .24)와 우상단 white wind(.24).
- Reduce transparency가 켜지면 모든 장식을 제거하고 gradient만 남긴다.

Compose: 공용 `SkyBreezeBackground(theme, reduceTransparency)`를 만들고 모든 지정 화면의 root 뒤에 배치한다.

### 2.3 타이포그래피·모양·모션

- 화면 제목은 기본 inline top app bar. 대시보드만 텍스트 제목 없이 44×44 앱 아이콘 + `NasFinder` subheadline semibold 로고를 leading에 둔다.
- section header: footnote medium, secondary, 아이콘과 텍스트 간격 3.
- 보조 설명: caption2/secondary. 파일 크기·날짜·카운트·진행률은 monospaced digits.
- 공통 썸네일 radius: 목록 9, 작은 grid 11, 큰 포스터 15. border 1.
- 설정 테마 카드 radius 12, Super Thumbnail 패널 16~18, 대시보드 보조 카드(현재 비활성 코드) 18~22.
- 공통 상태 전환은 ease-in-out 180~200ms. Cover Flow settle 280ms, 즐겨찾기 재정렬 120~160ms.
- selected check는 `checkmark.circle.fill`/accent, unselected는 outline circle/secondary + material circle 바탕.

## 3. 전체 내비게이션 지도

```text
대시보드(ConnectionListView)
├─ 즐겨찾기 shelf → 파일 브라우저/미리보기
├─ 받은 파일 → 받은 파일 미리보기 / NAS로 보내기
├─ 썸네일 캐시
├─ Super Thumbnail → 폴더 선택 → 진행 전체화면 → 보고서
├─ 저장된 연결 → 파일 브라우저 → 폴더 / 미리보기 / 파일 작업
├─ Browser → 즐겨찾기 편집 / 다운로드 저장 위치
├─ 폰하드
├─ 연결 추가/수정
└─ 설정 → 테마 / 앱 아이콘 / 화면 꺼짐 / 파일 앱 안내 / OSS

외부 UI
├─ Share Extension → 받은 파일
├─ Document Picker/File Provider 호환 UI
└─ Lock Screen circular widget
```

## 4. 화면별 사양

### 4.1 대시보드 / 연결 목록

현재 실제 화면은 `NavigationStack + insetGrouped List`이며 section 순서는 다음과 같다.

1. **내 파일**: 가로 즐겨찾기 shelf, `받은 파일`(개수·크기), `썸네일 캐시`(캐시 크기), `Super Thumbnail`(캐시 크기).
2. **네트워크**: 저장 연결 rows → Browser → 폰하드 → 조용한 `+ 네트워크 추가`. footer는 기본 위치/자동 열기 상태를 한 줄 caption2 tertiary로 표시한다.
3. **저장공간**: 전체/사용 가능 문자열과 progress.
4. **설정**: gear row 한 개.

상단 로고는 현재 아이콘 preview asset을 44×44, radius 10, 0.5 outline로 표시하고 탭하면 마지막으로 보던 원격 폴더를 연다. 기본 연결이 있으면 첫 진입 때 자동으로 연다.

연결 row: 아이콘 32, 텍스트 subheadline medium, 서비스 badge capsule, endpoint caption2, 우측 `…` 메뉴 visible target 44×54. 메뉴는 기본 위치 설정/해제, 수정, 위/아래 이동, 삭제. 삭제 dialog는 로그인 정보와 파일 앱 위치만 제거되고 서버 파일은 유지됨을 명시한다. Browser row는 orange globe + `WWW` capsule, 폰하드는 purple service icon + H badge. Android의 back stack을 초기화하는 “첫 화면으로 이동” 동작도 보존한다.

즐겨찾기 shelf: item side 52, tile width 56, 간격 9, 가로 indicator 없음. 길게 누른 뒤 이동하면 드래그 재정렬, 가장자리 34dp에서 160ms 단위 자동 스크롤. 이동 없이 long press가 끝나면 폭 224 제거 popover(취소/빨간 제거). 빈 상태는 caption 안내를 vertical 8로 표시한다.

주의: `quickLocationsSection`, `networkLocationsSection`, `StorageOverviewCard`, `FilesAppIntegrationBanner` 등 카드형 대시보드 구현은 파일에 남아 있으나 현재 `dashboardSections`에서 호출되지 않는다. Android는 이를 현재 UI로 구현하지 않는다.

근거: `Features/Connections/ConnectionListView.swift`, `Features/Favorites/FavoriteViews.swift`.

### 4.2 연결 추가/수정

전체 화면 sheet 내부 inline `연결 추가/연결 수정`. top-left 취소, top-right 연결/저장(진행 중 spinner). Form 순서:

- 연결 방식: 3열 grid, 간격 8, 각 카드 최소 높이 48, radius 11. 아이콘 body medium + caption. 선택은 서비스색 14% fill/55% stroke, 미선택은 secondary grouped background/서비스색 14% stroke.
- 종류: Synology, SFTP, SMB, WebDAV, FTP, Dropbox, OneDrive, Google Drive. WebDAV는 provider preset picker. OAuth 종류는 계정 section과 `…로 계속`만 노출.
- 서버: 표시 이름, host(URL keyboard), inline 빨간 주소 오류, port trailing number, 프로토콜별 안내, Synology port 22 orange 경고, HTTPS toggle, 시작 위치.
- 로그인: 사용자 이름, secure password.
- 연결 확인: `연결만 확인`, spinner, 성공 green check. SFTP는 SSH host key fingerprint 신뢰 alert를 반드시 거친다.

Android는 저장 전 검증·경고·disabled 상태와 TLS/port 자동 동기화를 동일하게 표현한다. 자격증명 보안 footnote는 축약하지 않는다.

근거: `Features/Connections/AddConnectionView.swift`, `Core/Models/Connection.swift`.

### 4.3 파일 브라우저

root는 content background. 상단 구성은 inline 중앙 제목 버튼(폴더명 + 작은 네트워크 사용량 표시, 탭하면 대시보드), 우측 32 원형 accent `…`, 그 아래 경로 bar, 3dp thumbnail progress line이다.

경로 bar: secondary system background, horizontal 12/vertical 8, caption. 상위 폴더 버튼 36×28 radius 7 accent 12%, drive icon, 연결명 semibold, `>` breadcrumb(중간은 monospace), 우측 `N개` 또는 `검색/전체개` monospaced. 검색은 navigation drawer의 `현재 폴더 검색`에 해당하는 검색 field.

보기 4종:

- 자세히: plain list. 썸네일 58(접근성 50), radius 9, 이름 body 최대 2줄(접근성 3), 종류·크기·수정 시각 caption.
- 작은 썸네일: adaptive width 78~104(접근성 118~155), column spacing 8, grid spacing 12, outer padding 10. 썸네일 104 요청, 이름 6sp regular, metadata caption2.
- 포스터: adaptive 158~340(접근성 min 270), spacing 16/20, padding 16. 썸네일 280 요청, 이름 8.5sp semibold, 날짜+시간 포함.
- 오버플로우(Cover Flow): landscape에서 포스터로부터 자동 진입, portrait로 복귀. 직접 저장된 Cover Flow 상태는 다음 진입 때 포스터로 안전 복구한다.

빈/오류/loading/search-empty 상태는 각각 `빈 폴더`, Wi-Fi 오류+다시 시도, spinner, 검색 결과 없음이다. pull-to-refresh는 목록뿐 아니라 썸네일 실패/traffic budget도 초기화한다.

탭: 폴더 열기, 파일 전체화면 미리보기, 선택 모드에서는 선택 toggle. long press `450ms`, 이동 허용 20dp로 item 작업 popover. item popover 폭 340: `선택/복사/이동/수정/삭제`, 둘째 줄 `별/받기/공유/썸네일 생성(또는 중지)`, 하단 이름·미디어·특징·날짜·크기 정보 panel. 기능 capability에 따라 disabled/숨김 상태를 그대로 둔다.

Browser `…` popover: 340×460, padding H12/V10. 파일 작업 4열(선택/붙여넣기/새 폴더/새로고침), 보기 3열(자세히/작은 썸네일/포스터), 정렬 기준·순서·이름 우선·폴더 먼저. action tile 높이 52, radius 10, 아이콘 19, label 10sp. option 최소 높이 44.

선택 bottom bar: `.bar`, top divider, H16/top10/bottom8. 선택 개수 + 전체 선택/해제, horizontal actions(복사/이동/삭제/공유/받기), action 최소 폭 58. 작업/공유/다운로드 banner는 bottom overlay에 material panel radius 16으로 겹친다.

`RemoteFileInfoView`라는 별도 정보 sheet 구현도 소스에 있다(46 아이콘, 이름, 종류/크기/수정일, 연결/방식/서버/monospace 전체 경로, 완료 버튼). 현재 파일 브라우저 활성 호출 경로에서는 사용되지 않고 compact item panel이 정보를 대신하므로 Android 기본 화면에는 중복 노출하지 않는다. 이후 iPhone에서 다시 연결되면 같은 sheet를 활성화한다.

근거: `Features/Browser/FileBrowserView.swift`, `FileBrowserViewModel.swift`, `FileBrowserInteractionCoordinator.swift`, `RemoteThumbnailView.swift`.

### 4.4 Cover Flow 상세

- 배경 white 또는 `#050506`, 전환 200ms. background 선택은 more panel에 존재한다.
- 화면당 측면 7개, preload는 양쪽 8개. card step은 화면 폭 5%, 42~66.
- base width는 폭 32%를 230 이상으로, 높이 72%/310 이하로 제한. 중앙 target은 base×1.26 또는 폭 38% 이상, 최대 460이며 상단 chrome 아래에 맞춘다.
- 측면 scale 0.80, Y rotation 최대 ±42°, 중앙 radius 18, 측면 13, thumbnail request 360×260.
- 하단 baseline은 높이-22. dark reflection 높이 15%(최대 중앙 44/측면 32), light 10%(최대 20). floor glow 높이 dark 72/light 82.
- drag 최소 1, 관성 최대 3장, settle 280ms + selection haptic. tap open, long press 450ms actions.
- overlay: 좌측 back 44 circle, 가운데 폴더명(max 폭 44% 또는 340, min height 44), 우측 more 44 circle. dark/light chrome 대비와 border/shadow를 반전한다.

근거: `Features/Browser/FileBrowserCoverFlowView.swift`, `FileBrowserView.swift`.

### 4.5 원격 미리보기 / 미디어 플레이어

검정 전체화면, system navigation bar 숨김. 이미지·비디오는 순차 media만 묶고 기타 파일은 로컬 다운로드 후 platform document preview(QuickLook 대응: Android `ACTION_VIEW` 또는 내장 renderer)로 연다.

상단: safe inset+8, 좌우 inset+8, spacing 10. 닫기 44 circle, 중앙 파일명 headline 1줄(세로에서는 그 아래 44 circle의 `현재/전체`), 우측 share 44 material circle; 아직 로컬 URL이 없으면 42 disabled dark circle.

하단: safe bottom+10. 이전/다음/재생 44 circles, video progress(높이 44 black 28% capsule, mono 11sp, compact 시간 폭 34/일반 42), 반복 모드. landscape는 한 줄이며 position+mode가 우측, portrait는 compact progress와 mode. controls는 2.5초 후 숨고 200ms fade; 숨은 비디오는 하단 2dp mini progress. 사진 slideshow 재생 중 숨은 controls opacity .10으로 edge navigation을 유지한다.

사진: pinch/pan zoom, single tap controls/play toggle, navigation swipe, downward dismiss. slideshow 좌우 버튼 46 black 28%, bottom+72, 진행선 2dp, interval 1/2/3/5/10/15/30초.

비디오: single tap(숨김이면 reveal, 보이면 play/pause), double tap reset zoom, pinch zoom+pan. horizontal drag seek, upward vertical drag volume, downward vertical drag dismiss(360 기준 opacity 최대 45% 감소). seek HUD radius 14 black 72%, volume HUD material capsule. 재생 모드는 전체 반복/임의/한 항목 반복. 부분 range stream과 전체 파일 fallback 상태 문구를 구분한다.

loading: progress와 받은 bytes/percent, white 72% caption mono; error: white unavailable view + 다시 시도. playback 중 screen awake activity를 잡고 background에서 pause한다.

근거: `Features/Preview/RemotePreviewView.swift`, `ZoomableMediaImageView.swift`, `SharedFullscreenMediaPlayer.swift`, `CompatibilityVideoPlayer.swift`.

### 4.6 받은 파일

inline 제목, plain list, Sky background. 빈 상태: tray + “다른 앱의 공유 메뉴에서…” 안내. row H spacing 12, vertical 3; thumbnail 38 계열(실제 leading preview는 38), 이름 최대 2줄, size+import date caption, 우측 share target 38. 탭 preview. trailing full swipe delete. context menu: NAS로 보내기(일반 파일만), 공유, 삭제.

우측 `선택/완료`; 선택 시 leading circle, bottom bar에서 전체 선택/해제와 `NAS로 보내기`. 업로드 불가능 항목은 disabled. NAS 보내기 sheet는 연결 목록 → 폴더만 탐색 → 고정 bottom `이 폴더에 업로드`; 작업 중 back/dismiss 금지, 충돌은 keep-both.

근거: `Features/Inbox/ReceivedFilesView.swift`, `InboxUploadDestinationView.swift`, `SharedInboxStore.swift`.

### 4.7 즐겨찾기 전체 목록

현재 대시보드에서는 shelf가 직접 쓰이지만 별도 `FavoriteListView`도 유지한다. 빈 상태 star 안내. grid는 compact 3열, regular 6열, accessibility 2열, spacing 12/18, padding 16. cell side 72/radius 11, folder는 3×3 mosaic(간격 1), 우상단 연결 badge, 이름 caption2 최대 2줄. context menu 제거 → confirmation dialog.

근거: `Features/Favorites/FavoriteViews.swift`.

### 4.8 내장 Browser

navigation bar를 숨긴 full content. top address bar `.bar`, H12/V9, spacing 7, 하단 load progress 2.

- back/close 32 circle: tap은 뒤로(없으면 닫기), long press 500ms는 닫기.
- 주소 capsule 높이 32, H10, text 13 medium, focused clear icon.
- go/stop primary accent circle 32: long press는 clipboard URL 붙여넣고 이동.
- reload 32, bookmark 32: tap 즐겨찾기 panel toggle, long press 500ms 현재 주소 add/remove.
- favorite panel H12/top8, radius 10, material, shadow radius14 y6, 목록 최대 높이 360. favicon을 tap하면 삭제, long press 550ms homepage 지정. homepage에는 house 표시.
- 편집 sheet: inline `즐겨찾기`, 항상 edit mode인 plain list. favicon 28/radius6, title subheadline semibold + URL caption2, drag reorder, 각 행 trash. 상단 `전체삭제`, 파일 export, 닫기. 빈 상태 bookmark 안내. Android는 같은 JSON 즐겨찾기 archive의 import/export 진입점까지 유지한다.
- 다운로드 capsule overlay와 취소. 완료 후 `받은 파일에 저장/네트워크 위치 선택/취소` dialog. 외부 scheme은 system app으로 전달.

기본 URL은 첫 즐겨찾기 또는 Google. WKWebView session/뒤로가기 gesture와 popup same-window 처리에 해당하는 Android WebView 설정을 보존한다.

근거: `Features/Browser/WebBrowserView.swift`, `BrowserFavoritesStore.swift`.

### 4.9 폰하드

inset grouped list, purple service tint. sections:

1. 주소 picker(실행 중 disabled), 선택 비밀번호, `접속/끊기` prominent, red 오류.
2. 실행 중 access URL: 34 service icon + 13 H badge, monospaced selectable URL.
3. 파일: 상위 폴더, 업로드 진행 rows, 오류/빈 상태, 보기 방식 menu.

보기: list(thumb 52) / 작은 3열 / 큰 2열, grid column spacing 10, row spacing 14, thumb radius 11. directory tap 이동, file tap은 없음, context menu 받기/삭제. top-right 주소 새로고침은 server 실행 중 disabled. 삭제 alert를 유지한다.

근거: `Features/WebHard/WebHardServerView.swift`, `WebHardFileStore.swift`, `WebHardHTTPServer.swift`.

### 4.10 Super Thumbnail

대시보드 row에는 30×30 mark와 cache 크기. mark는 indigo→blue→cyan gradient, white photo plate + yellow wand, compact radius 8; 큰 mark 82 radius 20.

메인: content background, 중앙 조용한 `Super Thumbnail` toolbar. Scroll column H18/top12/bottom24, spacing16:

- setup panel radius18: 폴더 선택 row(min 72), 안내, hero(min72), `NAS에도 보관` toggle, 보관 시점 menu.
- action panel radius18: 전원/네트워크/대상 요구 상태와 start/resume action.
- history panel(있을 때): 이전 폴더/보고서/재개.
- storage panel radius16: lifetime network/cache, NAS vault 삭제, reset.

최신 동작 보강:

- 표준 시작 조건은 선택한 폴더 + 데이터 요금 없는 Wi‑Fi + 외부 전원이다. 조건이 충족되지 않아 비활성화된 시작 버튼을 연속 10회 누르면 제한 실행을 시작한다. 1~4회는 아무 문구도 보이지 않고, 5회부터 `제한 없이 시작하려면 5번 더 누르세요.`에서 1회까지 countdown과 선택 햅틱을 제공하며 10회째 성공 햅틱을 낸다.
- 숨은 탭 수는 폴더/연결 선택 변경, 표준 조건 충족, 작업 시작, 화면 이탈 때 즉시 0으로 되돌린다. 숨은 overlay는 TalkBack 접근성 트리에서 제외한다.
- 최근 작업 행은 전체 보고서/재선택 영역과 우측 별도 `…` 메뉴로 나눈다. 보이는 메뉴 영역은 iPhone 28×40에 대응하되 Android touch target은 40×40 이상을 보장한다. 접근성 라벨은 `{이름} 최근 작업 메뉴`, destructive action은 `최근 작업에서 삭제`다.
- Android는 별도 `Screen.SuperThumbnailFolderPicker`에서 NAS를 먼저 고른 뒤 기존 `RemoteFileService.list`로 실제 하위 폴더만 단계적으로 탐색한다. 하단 54dp `이 폴더 선택`으로 확정한 connection/path/title을 WorkManager root와 SharedPreferences 최근 선택(최대 10개)에 함께 저장한다. 숨김 폴더는 picker에서 제외하며 root 밖 경로는 UI에서 생성하지 않는다.
- `Screen.SuperThumbnailProgress`는 실제 WorkManager snapshot만 사용한다. Android Worker에는 전체 예정 수, 현재 파일명, ETA, 단계별 5/20/40초 수치, 개별 실패 정보가 없으므로 이를 추정하지 않는다. 대신 확인/생성/실패/세션 예상 네트워크/안전 예산 도달과 WAITING/RUNNING/terminal 상태를 표시한다. 실행 중 system Back을 막고 54dp `작업 중단`, terminal 상태에는 `완료`를 제공한다.
- 최근 작업을 누르면 `Screen.SuperThumbnailReport`로 이동한다. 선택 location이 실제 마지막 Work location과 일치할 때만 상태·확인·생성·실패·예상 네트워크를 보고하고, 일치하지 않으면 `저장된 보고서 없음`을 표시한다. 임의 수치나 다른 폴더 작업 결과를 재사용하지 않는다.
- Android의 `SuperThumbnailDataController`/`SuperThumbnailSessionStore` 계약으로 NAS Vault 저장 시점, 재개, 선택 root의 Vault 삭제와 session report를 연결한다. 성공·cache·실패·pending·Vault 수치는 저장된 실제 session 결과만 표시하며, backend가 제공하지 않는 누적 lifetime 통계는 만들지 않는다.
- Android도 Settings 내부의 단순 실행 카드를 제거하고 `Screen.SuperThumbnail` 전용 화면으로 분리한다. 대시보드와 Settings의 요약 row가 이 화면으로 진입하며 setup(18), action(18), history(18), storage(16) 패널 순서와 H18/top12/bottom24 간격을 유지한다.

폴더 picker는 NAS 선택 → folder browser → bottom `이 폴더 선택`. 진행은 dismiss 불가 full-screen NavigationStack: mark+상태, folder, pause reason, 28sp mono `완료/전체`, 6dp progress, filename, wait/ETA, 완료·건너뜀·실패, NAS vault 상태, disclosure details/preview/failures/report. bottom material H24/V12의 54-high radius14 `작업 중단` 또는 prominent `완료`. 처리 중 화면 꺼짐 방지.

근거: `Features/Connections/SuperThumbnailView.swift`, `SuperThumbnailQueueStore.swift`, `SuperThumbnailVault.swift`.

### 4.11 썸네일 캐시

inset grouped list: 현재 cache 사용량/파일 수, 자동 정리 limit picker(30일·최대 5,000개 안내 포함), destructive 지금 비우기. 삭제 confirmation은 원본/받은 파일이 유지됨을 명시한다.

근거: `Features/Connections/ThumbnailCacheSettingsView.swift`.

### 4.12 설정 / 아이콘

설정 순서: 테마 → 앱 아이콘 → 화면 꺼짐 방지 → Apple 파일 앱 연동 → 오픈 소스.

- 테마: 3열×2행, spacing 8, 높이 216, row inset H12/V10. card H104, padding9, radius12, selected outline 1.5/else .5. 아이콘+check, title, 4개의 5dp 서비스색 dots. 선택 200ms. 명시적으로 앱 아이콘을 고르기 전까지만 Vibe 선택 시 Vibe Coder 아이콘을 기본값으로 사용하고, 이후에는 테마와 아이콘 선택을 독립 유지한다.
- 아이콘: 2열, spacing16. compact preview 72/radius16, standalone 96/radius21, shadow r4/y2. Blue NAS, Purple NAS, Vibe Coder, Cyber Vault. 선택 check 또는 pending spinner.
- 화면 동작 segmented: 오토/항상 켜짐/끔과 설명. Android는 keep-screen-on window flag를 같은 정책으로 적용한다.
- 파일 앱 연동: Android에서는 iOS 고유 설명을 그대로 노출하지 말고 대응 기능(SAF/DocumentsProvider)의 사용법으로 플랫폼 번역하되 section 위계와 22 circle 번호 1~4를 유지한다.
- OSS: VLCKit iOS 문구는 Android 실제 playback dependency 라이선스로 교체해야 하는 유일한 플랫폼별 내용이다.

근거: `Features/Settings/AppSettingsView.swift`, `AppIconSettingsView.swift`, `App/ScreenAwakeController.swift`.

### 4.13 Share Extension 대응

iOS extension UI: system background, safe area H20, top22/bottom18, vertical spacing14. title2 centered `NasFinder에 저장`, 2-line subheadline summary, progress, secondary background status text(radius12, inset top/bottom12 left/right10, min height160), 46-high horizontal equal buttons(취소/완료/filled NasFinder 열기). 최대 50개, 각 항목 `•/↓/✓/!` 상태.

Android는 Sharesheet `ACTION_SEND/ACTION_SEND_MULTIPLE` 수신 Activity에서 같은 UI와 상태를 구현하고 완료 후 Inbox 또는 앱 열기를 제공한다.

근거: `NasFinderShare/ShareViewController.swift`.

### 4.14 Document Picker / Android DocumentsProvider UI

iOS 호환 picker는 별도 teal theme이다. background light `#F4FBFE`, dark `#051211`; surface light white94%, dark `#0D1D1C`; border light `#B8DBEB` 66%, dark `#2E5952` 72%; accent system teal.

연결 목록은 Synology/SFTP만, group `연결`; row spacing14, icon 32, name body medium, host caption. 파일 보기 list/small/large: list thumb56 radius9, small 3열/104/radius11, large 2열/180/radius15, outer padding16. loading material panel radius16. download는 높이 210 sheet, progress large, 이름/크기, 취소. Android DocumentsProvider 자체 system picker에서는 임의 chrome을 강제할 수 없으므로 thumbnails, 정렬, root names, 상태를 provider 결과로 패리티시킨다.

근거: `NasFinderDocumentPicker/DocumentPickerViewController.swift`, `NasFinderFileProvider/*`.

### 4.15 잠금 화면 위젯

iOS는 accessory circular 한 종류. NAS glyph는 100×100 좌표계의 3개 discovery wave, vertical beacon, 2-bay chassis를 primary monochrome으로 그리며 2 padding. 탭 `nasfinder://open`, 라벨 `NasFinder 열기`.

Android는 원형 lock-screen widget 지원이 기기별이므로 최소 동일 monochrome glyph의 1×1 launcher/app widget 또는 shortcut을 제공하고 deep link를 같은 첫 화면에 연결한다.

근거: `NasFinderWidget/NasFinderLockWidget.swift`.

## 5. 이미지 자산 목록

| 논리 이름 | 실제 파일 | 크기 |
|---|---|---:|
| Blue NAS app icon | `Resources/Assets.xcassets/AppIcon.appiconset/NasFinder-AppIcon-White-Purple-1024.png` | 1024² |
| Purple NAS app icon | `Resources/Assets.xcassets/AppIconAlternate.appiconset/NasFinder-AppIcon-Alternate-1024.png` | 1024² |
| Vibe Coder icon | `Resources/Assets.xcassets/AppIconVibeCoder.appiconset/AppIconVibeCoder-1024.png` | 1024² |
| Cyber Vault icon | `Resources/Assets.xcassets/AppIconCyberVault.appiconset/AppIconCyberVault-1024.png` | 1024² |
| Blue/Purple previews | 각 `128/256/384` | 1x/2x/3x |
| Vibe/Cyber previews | 각 `96/192/288` | 1x/2x/3x |

Android에 가져올 때 원본 1024 이미지를 launcher adaptive icon foreground/background 규칙에 맞춰 파생하고, 설정 preview는 원본 모서리를 다시 자르지 않도록 실제 iOS preview의 visual inset을 비교한다. 이 문서는 소스 자산을 수정하지 않는다.

## 6. 빠뜨리기 쉬운 패리티 체크리스트

- [ ] 앱 실행 시 지정한 기본 연결 자동 열기, 로고 탭 시 마지막 폴더 재개.
- [ ] 대시보드의 현재 활성 List와 파일에 남은 비활성 카드형 UI를 혼동하지 않음.
- [ ] 서비스별 색/문자 badge 및 테마별 색 blend.
- [ ] 숨김 파일 제외, 폴더 먼저/자연어 이름 정렬, 이름 숫자/문자 우선 옵션.
- [ ] breadcrumb 상위 폴더 36×28와 item count/search count.
- [ ] long press 450ms item panel, 탭과 exclusive 처리.
- [ ] capability에 따른 복사/이동/rename/delete/upload disabled/숨김.
- [ ] 선택 mode bottom bar, 폴더 공유 불가 오류, keep-both upload.
- [ ] thumbnail 3dp 진행선과 limit message, refresh 시 cache/traffic reset.
- [ ] Cover Flow 회전 자동 진입/복귀, 흰/검 배경, reflection/haptic.
- [ ] 미디어 controls 2.5초 자동 숨김, 2dp mini progress, 사진 slideshow .10 dim controls.
- [ ] 비디오 좌우 seek/위 volume/아래 dismiss/pinch zoom/double reset.
- [ ] 받은 파일 swipe/context/share/NAS upload, 선택 불가 file 상태.
- [ ] Browser의 long-press shortcuts와 다운로드 저장 위치 dialog.
- [ ] 폰하드 실행 중 주소/password/refresh disabled 및 background 진입 시 server stop.
- [ ] Super Thumbnail 작업 중 dismiss 차단·화면 켜짐·중단 상태·vault pending/failure.
- [ ] 명시 아이콘 선택 전 Vibe 기본 아이콘 적용, 선택 후 테마/아이콘 독립 유지.
- [ ] reduce transparency, dynamic type, accessibility labels/hints/custom actions.
- [ ] 오류/빈/loading/진행/취소/완료/재시도 상태를 모든 화면에 구현.
- [ ] external share, DocumentsProvider, deep link/widget 같은 앱 외부 진입점.

## 7. Compose 구현 매핑 요약

| iPhone | Compose/Android |
|---|---|
| `NavigationStack`, destination | `NavHost`, typed route |
| inline navigation title/toolbar | `CenterAlignedTopAppBar` |
| `List(.insetGrouped)` | `LazyColumn` + section semantics/insets |
| `List(.plain)` | divider-minimal `LazyColumn` |
| `LazyVGrid(.adaptive)` | `LazyVerticalGrid(GridCells.Adaptive)` |
| `sheet` | `ModalBottomSheet` 또는 dialog destination |
| `fullScreenCover` | full-screen `NavHost` destination |
| `popover` | anchored `Popup`/`DropdownMenu` (폭 340 강제) |
| `confirmationDialog` | `ModalBottomSheet`/`AlertDialog` |
| `ContentUnavailableView` | 공용 empty/error composable |
| `.searchable` | expandable `SearchBar` |
| `.refreshable` | Material pull-to-refresh |
| `.bar` / material | translucent surface + blur fallback |
| `ShareLink`/activity controller | Android Sharesheet intent |
| QuickLook | MIME `ACTION_VIEW` 또는 내장 preview |
| File Provider | `DocumentsProvider` |
| Keychain | Android Keystore-backed encrypted storage |
| `isIdleTimerDisabled` | `FLAG_KEEP_SCREEN_ON` |
| Dynamic Type / VoiceOver | `sp`, fontScale reflow, TalkBack semantics |

## 8. 근거 파일 전수 범위

UI/디자인 기준으로 다음을 직접 조사했다.

- App: `NasFinder/App/NasFinderApp.swift`, `SkyBreezeTheme.swift`, `ScreenAwakeController.swift`
- Connections: `ConnectionListView.swift`, `AddConnectionView.swift`, `ThumbnailCacheSettingsView.swift`, `SuperThumbnailView.swift`
- Browser: `FileBrowserContainerView.swift`, `FileBrowserView.swift`, `FileBrowserCoverFlowView.swift`, `FileBrowserViewModel.swift`, `FileBrowserInteractionCoordinator.swift`, `RemoteFileInfoView.swift`, `RemoteThumbnailView.swift`, `WebBrowserView.swift`, `BrowserFavoritesStore.swift`
- Preview: `RemotePreviewView.swift`, `ZoomableMediaImageView.swift`, `SharedFullscreenMediaPlayer.swift`, `CompatibilityVideoPlayer.swift`
- Inbox/Favorites/Settings/WebHard: 각 `Features` 하위 모든 View 파일
- 외부 extension: `NasFinderShare/ShareViewController.swift`, `NasFinderDocumentPicker/DocumentPickerViewController.swift`, `NasFinderWidget/NasFinderLockWidget.swift`, `NasFinderFileProvider/*`
- Assets: `NasFinder/Resources/Assets.xcassets` 전체 Contents 및 PNG dimension

백엔드 서비스·모델 파일은 화면에서 capability, 상태, 서비스 종류, 정렬/표시 의미를 결정하는 범위에서 대조했다. Android 구현 중 iPhone 디자인을 바꾸거나 해석이 갈리는 경우 위 근거 파일의 현재 활성 `body`/호출 경로를 최종 기준으로 삼는다.

## 9. 2026-08-15 Android 전수 재대조와 반영

### 디자인 토큰

`SkyBreezeTheme.swift`의 실제 RGB와 opacity를 다시 계산해 Android color scheme에 직접 매핑했다.

| 영역 | iPhone 실제 값 | Android 반영 |
|---|---:|---:|
| 기본 content light | `#F4FBFE` | `background #F4FBFE` |
| 기본 content dark | `#09131C` 근사 | `background #09131C` |
| 기본 thumbnail light | white 88% | white surface + 카드 alpha `.88` |
| 기본 thumbnail dark | `#131C24` | `surface #131C24` |
| 기본 border light/dark | `#BFE3F2` / `#2E4757` | `outlineVariant` |
| Vibe background/surface/border | `#030B0B` / `#091B18` / `#1A5245` | 동일 |
| Vibe text primary/secondary | `#E6FAF2` / `#99BFB3` | `onSurface` / `onSurfaceVariant` |
| Meadow background/surface/border | `#EDF5D1` / `#FBF9E8` / `#9EBA66` | 동일 |

서비스색은 Synology `#0067E6`, SFTP `#218739`, SMB `#0F6CBD`, WebDAV `#6554C0`, FTP `#E87500`, Dropbox `#0061FF`, OneDrive `#0078D4`, Google Drive `#34A853`, PhoneHard `#5856D6`으로 바로잡았다. Night는 white 8%, Vibe는 `#BDFFE6` 6%, Meadow는 black 4% 혼합한다. 채워진 원형 배지 글자는 iPhone과 같은 sRGB 상대 휘도/대비 계산으로 black 또는 white를 선택한다.

폴더 tint도 고정 light blue를 제거하고 iPhone 값을 그대로 분기했다. Day/System light `#1478B3`, Night/System dark `#5CBAF0`, Vibe `#3BD6B0`, Meadow `#0A7AAD`다.

gradient 뒤 장식도 코드 자산으로 복원했다. Day/System light는 cloud `.38/.24`와 accent wind `.10`, Night는 cloud `.06/.04`, Vibe는 12열 8sp code rain, Meadow는 하단 meadow `.16`와 white wind `.24`다.

Typography는 SwiftUI semantic size를 Compose `sp`로 고정 매핑했다: body `17/22`, subheadline `15/20`, footnote `13/18`, caption `12/16`, caption2 `11/14`, title3 `22/28`(font size/line height). Android `fontScale`은 그대로 곱해지고, 각 화면의 iPhone weight(regular/medium/semibold)는 개별 `fontWeight`로 유지한다. 파일명·연결명·경로처럼 폭이 제한된 값은 1~2줄과 ellipsis를 명시하고 설명 문장은 줄 수를 고정하지 않는다.

### 화면별 수정

- 대시보드: 44dp 현재 launcher icon + subheadline semibold 로고, favorite 폭 56/이미지 52, 그룹 카드 16 radius/실제 border, 연결 row 32 서비스 배지·54 최소 높이·44×54 overflow로 iPhone 위계를 복원했다. 긴 endpoint와 이름은 각각 1줄 ellipsis다.
- 원격 브라우저: 상세 목록 artwork는 일반 58dp/접근성 50dp, radius 9이며 이름은 일반 2줄/접근성 3줄, 종류·크기·수정 시각을 별도 metadata로 표시한다. 목록과 grid의 직접 star/share/overflow를 제거하고 450ms long-press panel에 선택·즐겨찾기·공유·파일 작업을 모았다. small grid는 adaptive 78dp(접근성 118), H8/V12/padding10이고 poster는 158dp(접근성 270), H16/V20/padding16이다. 두 grid 모두 정사각 thumbnail surface 다음에 2줄 제목과 실제 metadata가 오는 iPhone 구조다. top bar의 새 폴더·업로드·새로고침은 하나의 `…` 메뉴에 합쳐 작은 화면/큰 글꼴에서도 폴더명과 핵심 동작이 숨지 않게 했다.
- Web Browser: 하단 Material 주소창을 제거하고 iPhone과 같은 상단 `.bar` 구조(H12/V9, 간격 7, 32 circle, 주소 capsule, 2dp progress)로 이동했다. back은 history가 없으면 close이고 long press는 즉시 close, bookmark long press는 현재 주소 add/remove다. font scale 1.3 이상에서는 control을 40dp로 늘려 글자와 touch target 잘림을 피한다.
- PhoneHard: 비밀번호와 open/close는 폭 360dp 미만 또는 font scale 1.3 이상에서 세로로 재배치한다. 접속 URL은 선택 가능하고 1줄 ellipsis, grid는 접근성 글꼴에서 small 2열/large 1열로 축소하며 제목은 2줄이다.
- 설정 테마: 일반 글꼴 3열, 접근성 글꼴 2열, 카드 최소 104/radius12, 선택 outline 1.5, title/description 2줄과 서비스색 dot 4개를 구현했다.
- 앱 아이콘: 사용자 지정 순서 `기본 Blue → Cyber Vault → Vibe Coder → Purple`의 실제 raster preview를 2열/72/radius16으로 노출한다. pending spinner, 선택 check, 실패 rollback과 launcher 반영 지연 안내를 포함한다. 한 번 명시적으로 고른 아이콘은 이후 테마 변경과 독립적으로 유지된다.
- Super Thumbnail: FolderPicker/Progress/Report/Vault UI는 이 문서 4.10의 최신 보강 사항대로 실제 RemoteFileService, Work snapshot, SessionReport, DataController 결과에만 연결했다.
- 대시보드 저장공간은 동작 없는 가짜 row를 제거하고 Android app data volume의 실제 total/usable bytes, 사용률과 progress만 표시한다. 받은 파일 요약도 실제 개수와 byte 합계를 함께 표시한다.

### 아직 플랫폼별로 다른 표현

- Android system picker/DocumentsProvider의 chrome은 앱이 임의로 바꿀 수 없어 결과 metadata와 thumbnail만 맞춘다.
- Web Browser 즐겨찾기 전체 편집은 Android ModalBottomSheet를 유지한다. 상단 inline popover의 위치만 다르고 데이터·행 위계·동작은 유지된다.
- Android fontScale은 iOS Dynamic Type과 단계 값이 달라 `1.3` 이상을 accessibility 재배치 기준으로 사용한다. 고정 이미지 크기보다 텍스트와 행동 노출을 우선한다.
