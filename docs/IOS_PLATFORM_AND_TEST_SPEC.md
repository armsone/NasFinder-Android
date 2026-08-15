# NasFinder iOS 플랫폼 확장·공유 저장소·테스트의 Android 이식 기술서

## 1. 목적, 범위, 근거 규칙

이 문서는 로컬 iOS NasFinder 저장소의 원본을 읽기 전용으로 조사하여, Android 앱의 Sharesheet 수신·송신, Storage Access Framework(SAF), `DocumentsProvider`, 위젯, 공유 저장소, 권한 및 테스트로 옮길 때 지켜야 할 구현 계약을 정의한다. 원본 저장소는 이 조사에서 수정하지 않았다.

조사 범위는 다음 전 파일이다.

- Share Extension: `NasFinderShare/Info.plist`, `NasFinderShare/NasFinderShare.entitlements`, `NasFinderShare/ShareViewController.swift`
- 공유 저장소: `NasFinderShared/SharedInbox.swift`와 앱 측 사용처 `NasFinder/App/NasFinderApp.swift`, `NasFinder/Features/Inbox/SharedInboxStore.swift`, `NasFinder/Features/Inbox/ReceivedFilesView.swift`, `NasFinder/Features/Browser/BrowserFavoritesStore.swift`
- File Provider: `NasFinderFileProvider/Info.plist`, `NasFinderFileProvider/NasFinderFileProvider.entitlements`, `NasFinderFileProvider/PrivacyInfo.xcprivacy`, `NasFinderFileProvider/Sendability.swift`, `NasFinderFileProvider/Item.swift`, `NasFinderFileProvider/Enumerator.swift`, `NasFinderFileProvider/Extension.swift`, `NasFinderFileProvider/Storage.swift`, `NasFinderFileProvider/RemoteBackends.swift`, `NasFinderFileProvider/ThumbnailCache.swift`
- Document Picker: `NasFinderDocumentPicker/Info.plist`, `NasFinderDocumentPicker/NasFinderDocumentPicker.entitlements`, `NasFinderDocumentPicker/DocumentPickerViewController.swift`
- Widget: `NasFinderWidget/Info.plist`, `NasFinderWidget/NasFinderLockWidget.swift`
- 앱/권한/프로젝트: `NasFinder/Info.plist`, `NasFinder/NasFinder.entitlements`, `NasFinder/PrivacyInfo.xcprivacy`, `NasFinder/Core/Services/ConnectionStore.swift`, `NasFinder/Features/Browser/FileProviderThumbnailCache.swift`, `NasFinder.xcodeproj/project.pbxproj`, `NasFinder.xcodeproj/xcshareddata/xcschemes/NasFinder.xcscheme`, `NasFinder.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`, `README.md`
- 테스트: `NasFinderTests`의 Swift 테스트 파일 29개 전부. iOS HEAD 기준 실제 테스트는 241개이고, 현재 읽기 전용 working tree에는 `BrowserFeaturePolicyTests.swift` 2개와 `SuperThumbnailVaultTests.swift` 4개가 추가되어 247개다(이름이 `test...`인 테스트 보조 메서드 7개는 제외).

조사 시점의 iOS HEAD는 `f5e7007`이다. 이 변경은 document type의 `LSHandlerRank`를 `Owner`에서 `Alternate`로 낮추고, 잘못 중첩됐던 URL scheme 선언을 `CFBundleURLTypes`로 분리한다. Android manifest는 deep link와 `content`/`file` VIEW filter가 이미 별도이며 intent priority나 기본 소유권을 주장하지 않으므로 추가 선언 없이 같은 비독점 문서 처리 성격을 유지한다.

Android의 현재 기준은 `app/build.gradle.kts`의 `minSdk 26`, `targetSdk/compileSdk 37`, Kotlin/JVM 17, Compose/Material 3, DataStore, Media3, OkHttp이며, `app/src/main/AndroidManifest.xml`에는 이미 `INTERNET`, 네트워크 상태, Wi-Fi 상태, Wake Lock과 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 수신 필터가 있다.

## 2. 플랫폼 대응표

| iOS 원본 | Android 대응 | 동일성 목표와 차이 |
|---|---|---|
| Share Extension | `ACTION_SEND`, `ACTION_SEND_MULTIPLE`을 받는 exported Activity 또는 전용 `ShareReceiverActivity` | 최대 50개, 부분 성공, 항목별 진행/오류, 취소, 받은 파일 화면 딥링크를 보존한다. |
| `UIActivityViewController`/`ShareLink` 송신 | `Intent.ACTION_SEND(_MULTIPLE)` + `FileProvider` content URI | `FLAG_GRANT_READ_URI_PERMISSION`, 정확한 MIME/ClipData, 임시 파일 정리를 필수화한다. |
| App Group `SharedInbox` | 앱 내부 저장소 + Room/원자적 manifest(또는 DB) + 같은 UID의 Provider/Activity | Android 구성요소는 기본적으로 같은 UID이므로 App Group entitlement는 불필요하지만, 프로세스 간 트랜잭션과 파일/메타데이터 일관성은 별도로 보장한다. |
| `NSFileProviderExtension` | `DocumentsProvider` + `DocumentsContract` | 시스템 파일 선택기의 “NasFinder” 루트로 Synology/SFTP를 노출한다. iOS의 sync anchor API를 억지로 모사하지 말고 query/open/create/rename/move/delete 계약으로 변환한다. |
| legacy Document Picker UI | 앱 내부 SAF 브라우저 + `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE` | Android 시스템 DocumentsUI에는 별도의 UI 확장이 없으므로, 자체 원격 브라우저와 시스템 SAF 호출을 분리한다. |
| WidgetKit accessoryCircular | App Widget(Glance 또는 RemoteViews), app shortcut; 필요 시 별도 Quick Settings Tile | 홈 화면 위젯은 제공 가능하다. 모든 Android 기기에서 iOS 잠금 화면 원형 위젯과 완전히 동일한 배치는 보장할 수 없다. |
| Keychain access group | Android Keystore로 보호한 credential store | 비밀번호/세션을 DataStore 평문에 저장하지 않는다. 연결 메타데이터와 비밀을 분리한다. |
| `nasfinder://inbox?id=` | manifest deep link 또는 명시적 PendingIntent destination | `inbox` 및 선택 record ID를 보존하고, 존재하지 않는 ID는 받은 파일 목록으로 안전하게 폴백한다. |

## 3. Sharesheet 수신 요구사항

### 3.1 활성화·입력 선택

iOS는 파일·이미지·Live Photo·동영상을 각각 최대 50개까지 선언하고 실제 처리도 provider 앞 50개로 제한한다(`NasFinderShare/Info.plist`, `NasFinderShare/ShareViewController.swift`). Android는 다음을 구현한다.

1. `ACTION_SEND`와 `ACTION_SEND_MULTIPLE`, MIME `*/*`를 받되 `Intent.EXTRA_STREAM`, `ClipData`, 단일 `intent.data`를 모두 정규화한다.
2. 중복 URI는 안정적으로 제거하고 입력 순서를 보존하며, 최대 50개만 처리한다. 50개 초과 사실은 사용자에게 명시한다.
3. URI scheme은 우선 `content://`, 필요 시 안전한 `file://` 호환 입력만 받고 네트워크/임의 scheme은 거부한다.
4. `ContentResolver.getType`, `OpenableColumns.DISPLAY_NAME/SIZE`, 확장자를 순서대로 사용한다. 표시 이름이 없으면 `공유 파일`과 MIME 기반 확장자를 사용하고, 경로 구분자를 제거한다.
5. URI가 문서/파일/이미지/동영상인지와 무관하게 열 수 있는 일반 content/data를 받아야 한다. Android에는 Live Photo 번들 표준이 없으므로 공급자가 복수 stream을 주면 별개 항목으로 보존하고, 단일 proprietary 묶음은 일반 파일로 저장한다.

### 3.2 복사·진행·원자성

iOS의 상태는 `waiting → importing → saved/failed`, 전체 progress, `0/N`, `N개 저장, M개 실패`를 사용한다(`ShareViewController.swift`). Android 전용 수신 Activity도 같은 정보 위계와 한국어 문구를 유지한다.

- 각 content URI는 `ContentResolver.openInputStream`/file descriptor로 앱 소유 임시 파일에 스트리밍 복사한다. 공급자 URI 자체를 장기 record로 저장하지 않는다.
- 메모리 전체 로드는 금지하고, 복사 중 byte progress가 가능하면 항목별/전체 진행률에 반영한다.
- iOS는 50개를 동시에 시작하지만 Android에서는 FD·메모리 고갈을 막도록 동시 복사 수를 제한한다(권장 3~4). 표시 순서와 최종 record 순서는 입력 순서를 유지한다.
- 먼저 UUID 기반 고유 저장 파일을 완성하고 fsync/close한 뒤 메타데이터 트랜잭션에 추가한다. 성공한 batch record를 한 트랜잭션으로 commit하여 UI가 반쪽 batch를 관찰하지 않게 한다.
- 일부 실패는 성공분을 보존하고 실패분을 표시한다. 전부 실패하면 완료/닫기만 제공한다.
- commit 실패 시 해당 batch가 만든 파일을 정리한다. 취소 시 실행 중 stream/job을 취소하고 아직 commit하지 않은 성공 파일을 정리한다.
- Activity 재생성/프로세스 종료를 견디도록 import job과 batch 상태를 저장한다. WorkManager는 즉시 UI 작업의 기본 수단이 아니라, 프로세스 생존이 필요한 큰 복사의 보완 수단으로만 사용한다.

### 3.3 완료·열기

iOS는 성공 후 `nasfinder://inbox?id=<마지막 UUID>` 자동 열기를 시도하고, 실패하면 수동 “NasFinder 열기”와 “완료”를 제공한다(`ShareViewController.swift`). Android에서는 같은 앱의 수신 Activity가 main task 안에서 받은 파일 화면으로 명시적으로 이동하는 것이 우선이다.

- 마지막 성공 record ID를 destination argument로 전달한다.
- 이미 main task가 있으면 singleTask의 `onNewIntent` 경로에서도 동일하게 처리한다.
- 자동 전환이 OS/호스트 동작상 부적합하면 “파일은 저장됐습니다…” 안내와 열기/완료 버튼을 제공한다.
- record가 즐겨찾기 archive 유형이면 iOS처럼 우선 import한 뒤 받은 파일 목록에서 제거하는 흐름을 별도 유지한다(`NasFinderApp.swift`, `BrowserFavoritesStore.swift`).

### 3.4 Sharesheet 송신

iOS 앱은 원격 파일을 임시 다운로드한 뒤 공유하고 받은 파일은 바로 재공유한다(`FileBrowserView.swift`, `RemotePreviewView.swift`, `ReceivedFilesView.swift`). Android 요구사항은 다음과 같다.

- 앱 내부 파일을 `androidx.core.content.FileProvider`의 content URI로 노출하고 `file://` URI를 절대 내보내지 않는다.
- 단일/복수 파일에 맞춰 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`, `ClipData`, `EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`을 함께 설정한다.
- 모든 항목이 같은 구체 MIME이면 이를 쓰고, 혼합이면 가장 좁은 공통 MIME 또는 `*/*`를 쓴다.
- 공유 준비 중 취소, 다운로드 오류, partial failure, selection order를 보존한다. 공유 sheet가 사라진 뒤 작업 전용 임시 디렉터리를 정리하고 stale 디렉터리 정리 정책을 둔다.

## 4. 공유 받은 파일 저장소

iOS record는 UUID, 원본명, UUID 기반 저장명, UTI, byte count, importedAt을 가진다. 파일은 App Group의 `SharedInbox`, manifest는 `manifest.json`이며 ISO-8601, pretty/sorted JSON, 원자 쓰기와 first-unlock file protection을 사용한다(`NasFinderShared/SharedInbox.swift`). Android record의 최소 필드는 다음과 같다.

```text
id: UUID
originalFilename: String
storedFilename: String (UUID + 안전한 최대 20자 영숫자 확장자)
mimeType: String?
byteCount: Long
importedAt: Instant
```

구현 계약:

- 저장 위치는 credential-protected 앱 내부 files 영역의 `SharedInbox/`로 한다. 첫 잠금 해제 전 접근이 꼭 필요하지 않으면 device-protected storage를 사용하지 않는다.
- Room 사용을 권장한다. JSON manifest를 유지한다면 temp+atomic rename, file lock, cross-process lock을 모두 제공한다. 단순 in-process Mutex만으로는 충분하지 않다.
- 원본명은 마지막 path component만 취하고 빈 값, `.`, `/`를 거부/대체한다. 저장명에는 `/`, `\`, `..` 또는 상위 경로 탈출이 없어야 하며 canonical parent를 검사한다.
- symlink와 특수 파일은 거부한다. Android content URI는 실제 경로로 변환하지 않고 stream으로 복사한다.
- directory 공유는 iOS가 내부적으로 지원하지만 일반 share URI에는 트리 의미가 없다. SAF tree에서 받은 폴더는 명시적 재귀 import 기능으로만 지원하고 symlink/순환/항목 수/총 용량 상한을 둔다.
- 파일 삭제는 DB/manifest와 payload의 불일치를 복구 가능하게 처리한다. orphan sweep과 missing-payload reconciliation을 앱 시작/주기 maintenance에 둔다.
- 목록은 `importedAt` 내림차순, 동률이면 locale-aware 자연 이름 오름차순이다. reload, 삭제, 앱 foreground 재진입 시 갱신한다(`SharedInboxStore.swift`).
- 받은 파일 화면은 빈 상태, 개수, 미리보기, 파일명, 크기, 날짜, 개별 공유, 삭제, 선택, 전체 선택/해제, NAS 전송을 제공한다. 폴더는 미리보기/재공유는 가능하더라도 NAS batch upload 선택에서는 제외한다(`ReceivedFilesView.swift`).
- 이미지·영상·PDF는 썸네일, 이미지·영상은 연속 미디어 미리보기에 포함하고, 일반 파일은 유형 아이콘을 쓴다.

원본에 없는 보존 정책(총 용량, 개수, 만료)은 Android가 임의 삭제로 추가하지 않는다. 필요하면 설정과 명시적 사용자 동의를 갖춘 별도 제품 결정으로 둔다.

## 5. SAF와 DocumentsProvider

### 5.1 시스템 파일 위치

`NasFinderFileProvider`는 연결 UUID를 domain ID로 쓰고, 앱 시작 시 빠진 domain을 복구하며 연결 수정 시 remove/add, 삭제 시 domain/credential/session을 함께 제거한다(`ConnectionStore.swift`). Android `DocumentsProvider`는 설치 앱당 하나의 provider authority 아래에서 여러 root row를 반환한다.

- 권장 authority: `com.armsone.nasfinder.documents`.
- manifest provider는 `android.content.action.DOCUMENTS_PROVIDER`, `exported=true`, `grantUriPermissions=true`, `android.permission.MANAGE_DOCUMENTS` 보호를 적용한다. 앱이 `MANAGE_DOCUMENTS` 권한을 요청하는 것이 아니라 시스템 DocumentsUI가 provider를 호출하도록 선언하는 패턴이다.
- `queryRoots()`는 지원 연결마다 stable root ID=`connection UUID`, title=`connection.name`, summary=`host`, MIME=`*/*`, available bytes(모르면 미지정)를 반환한다.
- 현재 iOS File Provider가 실제 지원하는 연결은 Synology와 SFTP뿐이다. SMB, WebDAV, FTP, Dropbox, OneDrive, Google Drive는 앱 모델에 존재하지만 provider 생성 시 unsupported다(`Storage.swift`). Android의 첫 동등 구현도 Synology/SFTP만 root로 노출하며, 다른 backend가 완성되기 전 “지원” flag를 광고하지 않는다.
- 연결 메타데이터는 DataStore/DB, 비밀번호와 Synology session은 Keystore 보호 저장소에 분리한다. provider 프로세스에서도 안전하게 읽을 수 있어야 한다.

### 5.2 문서 식별자·조회

iOS remote item ID는 `remote-path:` + URL-safe base64(path)이고 version은 path/type/size/mtime SHA-256이다(`Storage.swift`). Android에서는 연결 간 충돌을 막아 `v1:<connection UUID>:<URL-safe base64 canonical remote path>` 형태의 stable document ID를 사용한다.

- `queryDocument()`와 `queryChildDocuments()`는 이름, MIME, size, lastModified, flags를 채운다.
- root 경계를 canonical component 단위로 확인하고 `.`/`..` 및 경로 prefix 착시를 거부한다.
- dot-prefix 파일/폴더는 iOS처럼 숨긴다.
- 정렬은 폴더 우선이 필요한 자체 UI와 달리, DocumentsProvider 결과는 안정적 자연 이름 순서를 기본으로 한다.
- cancellation signal을 모든 network list/download/upload에 연결한다. 취소를 일반 오류 dialog로 노출하지 않는다.
- iOS sync anchor는 extension process 메모리에 최대 12개만 남아 프로세스 재시작 후 `syncAnchorExpired`가 난다(`Storage.swift`). Android는 이를 저장할 필요가 없다. `notifyChange`와 DocumentsUI의 재query를 사용하고, stale cache TTL/etag는 backend별로 정의한다.

### 5.3 열기·다운로드·쓰기

iOS는 `fetchContents`에서 manager temp directory에 materialize하고, placeholder/eviction/writeback은 사실상 구현하지 않는다(`Extension.swift`). Android는 `openDocument()`에 다음을 적용한다.

- 읽기: seek가 필요 없거나 작은 파일은 pipe/proxy FD로 스트리밍할 수 있다. seek/대형 미디어/호환 앱을 위해 안전한 local cache materialization 경로도 제공한다.
- partial/local cache 파일은 성공 전 최종 이름으로 공개하지 않는다. 취소/오류 시 삭제한다.
- SFTP만 create folder, upload, rename, move/reparent, content overwrite, recursive delete를 광고한다. Synology provider는 iOS와 동일하게 읽기 전용이다. 앱 내부 Synology 서비스가 mutation을 지원한다는 이유로 DocumentsProvider에 쓰기 flag를 광고하면 안 된다.
- SFTP root/폴더 flags: create, rename, move, delete, child create. 파일 flags: write, rename, move, delete, thumbnail. Synology는 read/thumbnail만 광고한다.
- `createDocument`, `createFolder`, `renameDocument`, `moveDocument`, `deleteDocument` 성공 후 원격 list로 결과를 검증하고 관련 document URI/root에 `notifyChange`한다.
- filename은 trim 후 빈 값, `.`, `..`, `/`, NUL을 거부한다. 동일 이름 정책은 앱의 fail/skip/replace/keep-both 계약을 재사용한다.
- recursive delete는 각 단계 cancellation을 확인한다. root 자체 mutation은 금지한다.
- SFTP 비밀번호 인증과 SHA-256 host-key pinning을 유지한다. 저장된 key가 없거나 변경되면 연결을 거부하고 앱에서 검증하도록 안내한다(`RemoteBackends.swift`).
- Synology는 File Station API session을 재사용하고 인증 오류 106/107/119에서 한 번 session 폐기·재로그인 후 재시도한다. HTTPS/HTTP 선택, host/port, 5초 list/login timeout, 45초 일반 요청 계약을 명시적으로 이식한다.

### 5.4 썸네일

iOS provider는 이미지 24개 확장자와 영상 26개 확장자에 대해 small/medium/large를 구분하며, `(connection ID, path, mtime, size, requested tier)` SHA-256 key로 App Group cache를 쓴다(`ThumbnailCache.swift`).

- Android `openDocumentThumbnail()`은 requested size에 맞춰 small(≤128), medium(≤512), large를 선택한다.
- Synology server thumbnail을 먼저 시도하고 없으면 `.NasFinder-Vault/v1-<SHA256>.jpg`를 시도한다. 단일 응답 상한은 4 MiB다.
- cached image는 실제 decode 가능한지 확인하고 깨졌으면 miss로 처리한다. requested tier가 없을 때 원본과 같은 fallback 순서(small→medium→large, medium→small→large, large→medium→small)를 적용한다.
- 앱이 새 thumbnail을 만들거나 legacy cache를 이관하면 provider URI에 `notifyChange`한다. iOS의 migration은 완료 flag가 있어도 새 파일을 계속 동기화한다(`FileProviderThumbnailCache.swift`).
- 캐시는 최적화일 뿐이며 실패하면 일반 파일 아이콘으로 폴백한다. 원격 원본을 thumbnail cache에 넣지 않는다.

### 5.5 앱 내부 SAF 사용

iOS legacy Document Picker는 iOS 14부터 deprecated된 호환 UI이며 Import/Open만 지원한다(`DocumentPickerViewController.swift`). 연결 목록 → 폴더 탐색 → 파일 다운로드, list/small thumbnails/large poster, pull-to-refresh, 취소 불가 download sheet, 오류 retry를 제공한다.

Android에서는 다음 두 경로를 구분한다.

- 외부 파일을 NasFinder로 가져오기/저장 위치 선택: `ACTION_OPEN_DOCUMENT(_TREE)`, `ACTION_CREATE_DOCUMENT`; 반환 URI 권한은 필요할 때만 `takePersistableUriPermission`으로 보존한다.
- NasFinder 원격을 다른 앱에서 선택: 위 `DocumentsProvider`가 담당한다.
- 앱 자체 원격 브라우저 UI는 iOS의 세 보기 모드(자세히/작은 썸네일/포스터), 폴더 우선, dotfile 숨김, 최대 동시 thumbnail 3, refresh의 visible timeout 5초, 항목명/크기, 다운로드 취소를 보존한다.

## 6. 위젯과 딥링크

iOS 위젯은 데이터가 없는 static timeline(`.never`), accessoryCircular 한 종류, NAS 파동/비콘/2-bay chassis glyph, “NasFinder 바로 열기”, `nasfinder://open`만 제공한다(`NasFinderLockWidget.swift`). Android 구현 요구사항:

- 최소 1×1 홈 화면 launcher widget을 제공하고 동일한 단색 glyph와 “NasFinder 열기” 접근성 설명을 사용한다.
- widget tap은 immutable/update-current `PendingIntent`로 MainActivity의 open destination을 연다. Android 12+ exported/pending-intent flag를 정확히 지정한다.
- 상태 데이터, 주기 refresh, 네트워크 작업을 추가하지 않는다. iOS처럼 정적이다.
- 런처가 lock-screen widget을 지원하면 같은 asset을 허용하되, stock Android 전 기기에서 accessoryCircular lock-screen 배치를 보장한다고 문서화하지 않는다. 발견성이 필요하면 app shortcut 또는 별도 Quick Settings tile을 제품 결정으로 검토하되 위젯과 혼동하지 않는다.

## 7. manifest, 권한, 보안, 프로젝트 요구사항

### 7.1 iOS 기준

원본은 iOS 17+, Swift 6, 앱/확장 모두 version `1.0`/build `202608121535`, Team `T7B4EPLHPK`이다. 앱 bundle ID는 `com.armsone.nasfinder`, 확장은 `.fileprovider`, `.share`, `.widget`, `.documentpicker`다. 앱은 네 확장을 모두 embed/depend하고, File Provider/Document Picker는 Citadel, 앱은 Citadel/VLCKit/SMBClient를 연결한다(`project.pbxproj`). 앱과 Share는 `NasFinderShared`를 같이 컴파일하고, Document Picker는 File Provider 소스를 같이 컴파일한다.

앱/Share/File Provider/Document Picker는 App Group `group.com.armsone.nasfinder`를 쓰며 앱/File Provider/Document Picker는 shared Keychain access group도 쓴다. 위젯에는 App Group entitlement가 없다. 앱은 `nasfinder`, Dropbox, Microsoft, Google callback scheme, document-open-in-place, local networking, web content arbitrary loads를 선언한다(`Info.plist`, entitlements). privacy manifest는 file timestamp, disk space, UserDefaults reason을 선언한다.

### 7.2 Android 선언

필수/조건부 선언은 다음과 같다.

- 유지: `INTERNET`, `ACCESS_NETWORK_STATE`; Wi-Fi 조건 판단에 실제 사용하면 `ACCESS_WIFI_STATE`; 장시간 foreground 작업에서만 `WAKE_LOCK`.
- Sharesheet Activity: exported intent filter. 민감한 main navigation을 전부 exported로 열지 말고 share/deep-link 입력을 엄격히 검증한다.
- DocumentsProvider: 전용 authority, `exported=true`, `grantUriPermissions=true`, `MANAGE_DOCUMENTS` read/write permission, documents-provider action.
- 송신 FileProvider: 별도 authority `.fileprovider`, `exported=false`, `grantUriPermissions=true`, 최소 path XML. inbox 전체나 credential/cache root를 광범위하게 노출하지 않는다.
- App Widget receiver/provider와 immutable PendingIntent.
- 저장소 권한(`READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`)은 요청하지 않는다. SAF/content URI와 앱 내부 저장소로 충분하다.
- Wi-Fi 정보나 주변 기기 API를 실제 호출하지 않으면 nearby/location runtime permission을 추가하지 않는다. foreground service/notification을 도입할 때만 해당 service type과 notification permission을 별도 설계한다.
- HTTP Synology를 지원하므로 cleartext는 무차별 허용하지 말고 현재 `network_security_config.xml`에서 필요한 local/user-configured host 정책과 경고를 설계한다. credential, session ID, OAuth token을 로그/backup/plain DataStore에 남기지 않는다. 현재 앱은 `allowBackup=false`를 유지한다.

## 8. 원본의 확인된 미구현·제약

Android가 “완전한 iOS 동등성”으로 오인하면 안 되는 원본 한계다.

1. File Provider는 Synology/SFTP만 지원한다. SMB/WebDAV/FTP/Dropbox/OneDrive/Google Drive는 명시적으로 unsupported다(`Storage.swift`).
2. File Provider 쓰기는 SFTP만 지원한다. Synology provider는 read-only이며 root/item capability도 읽기만 광고한다.
3. File Provider placeholder는 항상 file-not-found, local eviction은 없음, host edit writeback은 없음이다(`Extension.swift`).
4. sync anchor history는 프로세스 메모리뿐이라 extension 재시작 후 change delta 대신 anchor-expired가 발생한다.
5. working set enumeration은 사실상 root와 동일하며, 전역 최근 작업 집합이 아니다.
6. legacy Document Picker는 deprecated API 기반이고 Import/Open만 지원하며 unsupported 연결은 목록에서 제외한다. 업로드/수정 UI가 없다.
7. Share Extension은 최대 50개 작업을 모두 동시에 시작하므로 resource pressure 제한이 없다. 자동 app open은 OS 버전에 따라 실패할 수 있어 비공개 responder-chain fallback을 사용한다.
8. SharedInbox에는 용량/개수/retention/orphan reconciliation 정책이 없다. delete는 manifest를 먼저 지우므로 파일 삭제 실패 시 orphan이 남을 수 있다.
9. Widget은 정적 원형 launcher뿐이며 데이터/연결 상태/refresh가 없다.
10. 실제 NAS/SFTP network E2E는 출시 전 필요하다고 README가 명시한다. SFTP key auth와 Synology OTP login도 미구현이다.
11. 테스트는 ShareViewController, SharedInbox 원자성/보안, File Provider CRUD/enumeration/backend, DocumentPicker model/UI, Widget deep link를 직접 단위 테스트하지 않는다. 관련 확장 테스트는 bundle pairing 1개와 thumbnail cache migration 1개뿐이다.
12. scheme에서 unit tests는 non-parallel이다. `NASFINDER_VLC_INTEGRATION_TESTS=1`은 기본 비활성이고, AVI/ASF 외부 VideoLAN sample 테스트는 이 환경 변수가 없으면 skip된다(`NasFinder.xcscheme`, `RemotePreviewStateTests.swift`).

### 8.1 조사 시점 iOS working tree의 최신 계약

원본 저장소의 plist, entitlement, Share/File Provider/Document Picker/Widget metadata에는 HEAD `f5e7007` 이후 커밋되지 않은 변경이 없다. 현재 working tree 변경은 `FileBrowserView.swift`, `SuperThumbnailVault.swift`, `ThumbnailPreheater.swift`, `SuperThumbnailView.swift`, 두 테스트 파일과 `docs/google-photos-rollout.md`다. Android 이식에서는 이를 원본의 확정 릴리스 계약과 구분하되 최신 동작 후보로 추적한다.

- 표준 Wi-Fi/외부 전원 조건을 만족하지 않고 소규모 작업 판정도 없는 경우, 활성 시작 버튼 영역을 10번 누르면 제한 실행을 시작한다. 1~4회에는 문구를 노출하지 않고 5~9회에는 각각 5~1회의 남은 횟수를 표시하며, 10회에는 성공 haptic 후 실행한다.
- 이 숨김 제스처는 폴더가 선택되고 assessment·준비·실행·취소가 모두 비활성일 때만 유효하다. 선택 변경, 화면 이탈, 가용 조건 해제, 실제 실행 시 tap count를 초기화한다. 접근성 트리에는 투명 overlay 자체를 숨긴다.
- 열 상태는 nominal이면 그대로 진행하고 fair이면 항목 경계마다 500ms pacing한다. serious/critical 및 알 수 없는 상태에서는 새 고비용 항목을 시작하지 않는다. Android API 29+는 `PowerManager`의 NONE/LIGHT→nominal, MODERATE→fair, SEVERE 이상→pause/retry로 매핑한다. 열 API가 없는 API 26~28은 보수적으로 500ms pacing한다.
- 여러 기기/프로세스가 같은 Vault를 처리할 때 worker heartbeat 90초와 item lease 180초를 사용하며 같은 항목은 한 worker만 선점한다. 만료 lease는 다른 worker가 회수하고 cooperative 완료 결과는 항목별로 즉시 Vault에 게시한다.
- 최근 슈퍼 썸네일 작업 행은 본문 탭으로 보고서를 열고, 별도 overflow 메뉴에서 해당 기록만 삭제한다. 메뉴의 접근성 이름은 작업명 뒤에 `최근 작업 메뉴`를 붙인다. 삭제 대상이 last/previous selection이면 대응 persisted key도 함께 비운다.
- `docs/google-photos-rollout.md`는 `nasfinder.com` 공개와 OAuth 검증 이후를 위한 준비 문서이며 현재 구현·권한·manifest 범위를 변경하지 않는다. Android에도 사용자 승인이나 실제 OAuth 설정 없이 이를 선행 구현하지 않는다.

SuperThumbnail의 FolderPicker, retry queue, NAS Vault, 진행·보고서, WorkManager 프로세스 재시작과 접근성의 Android 회귀 기준은 `docs/SUPER_THUMBNAIL_PARITY_CHECKLIST.md`에 별도로 유지한다. Android의 현재 persistent WorkManager 진행 상태와 iOS의 item별 queue/Vault report 영속 상태를 같은 기능으로 오인하지 않는다.

## 9. Android 테스트 전략과 합격 기준

### 9.1 테스트 계층

- JVM unit: path/filename/MIME/record/ID/version/cache key/정렬/충돌/권한 정책, backend request encoding, cancellation state machine.
- Robolectric 또는 component test: share Intent/ClipData parsing, deep link, FileProvider URI, widget PendingIntent, manifest exported/provider declarations.
- Instrumentation: ContentResolver stream import, Room+payload transaction/recovery, SAF persistable grant, DocumentsProvider query/open/create/rename/move/delete, process recreation.
- Contract/fake server: Synology auth/session/retry/list/download/thumbnail와 SFTP host-key/cancel/chunk/upload/recursive delete.
- 실제 기기 E2E: 다른 앱→Sharesheet→받은 파일, NasFinder→다른 앱 공유, DocumentsUI root에서 Synology/SFTP browse/open, SFTP mutation, 위젯 launch. 실제 NAS/SFTP fixture와 네트워크 단절/재연결을 포함한다.

### 9.2 이 플랫폼 범위에 새로 필수인 테스트

1. Share 단일/복수/ClipData-only/data-only, 중복 URI, 0개, 50개, 51개, MIME/이름 누락, 공급자 read 실패, partial success, cancel race, rotation/process recreation, commit failure cleanup.
2. SharedInbox traversal/symlink/invalid extension, atomic batch visibility, concurrent process import/delete, duplicate UUID/filename, missing payload/orphan recovery, 정렬, file protection/backup 제외.
3. DocumentsProvider roots supported-kind filter, stable ID round trip, root containment, dotfile hide, MIME/flags, cancellation, read cache partial cleanup, SFTP create/write/rename/move/delete, Synology read-only, notifyChange.
4. Thumbnail tier/fallback/key invalidation/4 MiB cap/corrupt cache/server→vault fallback/migration after earlier run.
5. SAF open/create/tree grant lifecycle와 security exception 처리.
6. Widget/deep link PendingIntent, record ID present/missing, accessibility label.
7. manifest 보안: exported component 최소화, DocumentsProvider/FileProvider authority와 permission, no broad storage permission, FileProvider path 범위.

### 9.3 iOS 테스트 전수 인벤토리

아래는 `NasFinderTests`의 HEAD 기준 실제 테스트 241개와 조사 시점 working tree의 6개 추가분을 파일별로 빠짐없이 집계한 것이다. 괄호 안은 개수와 Android가 보존할 회귀 계약이다.

- `AppIconChoiceTests.swift` (4): 기본/대체/unknown fallback/cyber icon asset name.
- `AppThemePreferenceTests.swift` (7): unknown theme fallback, stable unique values, VibeCoder/service palette, badge letter/color readability, day base colors, color-scheme contrast.
- `BrowserFeaturePolicyTests.swift` (HEAD 12, working tree 14): Cover Flow stored background, drag preload/snap/momentum cap/direction, root-safe path/parent, thumbnail power/command eligibility, super-thumbnail media scope, favorite canonical dedupe, 30-minute session retention. working tree에는 thermal nominal/fair/serious/critical 정책과 숨김 시작 카운트다운 테스트가 추가됐다.
- `BrowserURLPolicyTests.swift` (2): HTTPS scheme 보정·non-web 거부, download filename path separator 제거.
- `ConnectionTests.swift` (37): preferred/last location persistence, Synology/SFTP root normalize, File Provider support advertising, OAuth provider/callback/identity/credential/S3 credential, thumbnail type candidates, SFTP/IPv6/Synology address parsing, TLS/default ports/kind change/input verification, IPTIME/network presets/driver selection, unsafe address rejection, SFTP/Synology diagnostic stage·timeout·TLS·cancel·sanitized message.
- `DocumentProviderConfigurationTests.swift` (1): embedded File Provider `com.apple.fileprovider-nonui` + App Group와 Document Picker `com.apple.fileprovider-ui` Import/Open pairing.
- `DownloadCacheTests.swift` (1): trusted cached payload가 stale listing size에 의존하지 않음.
- `FavoriteStoreTests.swift` (8): mosaic first nine, skin-tone threshold, reorder gesture, persistence/removal, cloud+legacy identity, folder/order, exact-ID remove/move.
- `FileBrowserInteractionCoordinatorTests.swift` (4): 폴더 제외+selection order download, panel dismiss 후 action once, dismiss cancellation, folder/file activation routing.
- `FileBrowserItemSorterTests.swift` (7): natural name and folder-first, missing date/size last, kind sorting, diacritic search, number/Korean/foreign priority와 descending stability.
- `FileOperationCoordinatorTests.swift` (10): keep-both batch upload, preferred source name, per-file failure continuation, cancellation partial success/failure/cleanup, refresh serialization, reload-after-current-load. 이 파일의 `testURL`은 private helper라 테스트 수에서 제외했다.
- `FileProviderThumbnailCacheTests.swift` (1): earlier migration 뒤 새 thumbnail도 다시 동기화.
- `PageNetworkTrafficTrackerTests.swift` (6): zero/unit formatting, accumulate/reset, progress delta dedupe, uncached positive delta, cached no recount.
- `RemoteFileOperationTests.swift` (8): absolute/relative root containment, unsafe name, keep-both extension/suffix/case, folder replace rejection, read-only mutation rejection, progress operation ID.
- `RemoteFileVisibilityPolicyTests.swift` (2): dot-prefix 숨김, ordinary/embedded dot 표시.
- `RemotePreviewStateTests.swift` (51): player label/auto-hide, volume-vs-dismiss gesture lock, AVPlayer/VLC format route, exact-base subtitle, SFTP/Synology MKV thumbnail route, silent serialized compatibility thumbnail, super-thumbnail report/cache/scope/vault resume/retry, bounded traffic/range read/seek/stall/cancel/watchdog, optional real AVI/ASF play-seek-rotate-thumbnail, tap/autoplay/loading/error/streaming state, MOV progress, bounded thumbnail/timeouts/quality black-white threshold, progress clamp/EOF/stall/retry/watchdog, mixed media wrap/playback modes/slideshow/manual navigation, immediate super-thumbnail cancel. 파일 하단 mock service의 `testConnection` 6개는 protocol stub이므로 제외했다.
- `RemoteThumbnailCacheKeyTests.swift` (3): stored sizes coverage, rendered-size 공유 key, modification invalidation.
- `RemoteThumbnailDiskCacheTests.swift` (4): main-thread notification, scoped statistics/removal, allowed automatic limits, folder refresh와 late-store rejection.
- `RemoteThumbnailImageDecoderTests.swift` (2): pixel-bound downsample, invalid bytes rejection.
- `RemoteVideoStreamingTaskRegistryTests.swift` (9): bounded chunks, contained range cache, larger cache preservation, folder/cellular traffic leases/reset/cap, byte budget, cancel before/after start.
- `SFTPAdaptiveThumbnailPlanTests.swift` (4): head/tail first stage, non-overlap/item budget, 50-item 18 MiB goal, remaining budget.
- `SFTPFileMutationSupportTests.swift` (9): relative root containment, root mutation ban, component boundary, symlink not directory, keep-both extension, fail/skip/replace conflict와 directory replace rejection.
- `SFTPTaskCancellationBridgeTests.swift` (3): live/listed size fallback, transport close+cancel normalization, close-unblocks-success도 cancellation 유지.
- `SFTPVideoThumbnailRangePlanTests.swift` (5): small whole-file/non-overlap, medium two ranges, large hard cap, small ≤640 KiB, empty/unrepresentable rejection.
- `ScreenAwakeControllerTests.swift` (4): automatic default, active-work-only sleep prevention, independent activity release, persisted selection.
- `SuperThumbnailVaultTests.swift` (HEAD 6, working tree 10): 기존 whole-folder-before-upload, finish-only later upload, resume immediate cached upload, cross-connection restore, cancelled upload retry, NAS vault-only removal에 동시 lease 단일 선점, 만료 worker/lease 회수, worker 간 재할당, cooperative item별 즉시 게시가 추가됐다.
- `SynologyFileServiceTests.swift` (20): session reuse/expiry/concurrent relogin, thumbnail cancel/timeout, download progress/payload/range/stale size, HTTPS/HTTP root list, create+rename parameter versions, delete polling/cancel, copy/move v3 conflict, disk multipart upload/relogin/keep-both/typed conflict, connection verification stages.
- `URLSessionProgressDownloaderTests.swift` (5): valid partial range, full-response rejection, ordered coalesced progress drain, cancellation normalization, late temp file discard.
- `WebHardFileStoreTests.swift` (6): traversal, intermediate upload+keep existing, symlink hide, ZIP header/name, HTTP upload/list/download, optional password-free access.

HEAD 합계 검산: `4+7+12+2+37+1+1+8+4+7+10+1+6+8+2+51+3+4+2+9+4+9+3+5+4+6+20+5+6 = 241`. 조사 시점 working tree는 Browser 2개와 Vault 4개 증가로 247개다.

## 10. 구현 순서와 완료 정의

1. SharedInbox record/transaction/security와 Share Intent 수신을 먼저 완성한다.
2. 송신 FileProvider와 받은 파일 UI/딥링크를 연결한다.
3. credential store와 연결 repository를 provider-safe하게 만든 뒤 read-only DocumentsProvider(query/open/thumbnail)를 구현한다.
4. SFTP mutation을 create/rename/move/write/delete 순으로 추가하고 flag는 구현·테스트가 끝난 기능만 광고한다.
5. SAF import/export와 정적 위젯을 추가한다.
6. 위 신규 필수 테스트와 이식 가능한 iOS 회귀 계약을 통과한 뒤 실제 NAS/SFTP 기기 E2E를 수행한다.

완료는 UI가 비슷해 보이는 상태가 아니라 다음이 모두 확인된 상태다: 최대 50개 공유의 원자적/부분 성공 처리, 받은 파일 보존·재공유·삭제·NAS 전송, 시스템 DocumentsUI에서 Synology/SFTP 조회와 다운로드, SFTP 쓰기 작업, 취소·재시작·경로 탈출 방어, credential 비노출, thumbnail fallback/cache, 정적 widget launch, manifest 보안, 관련 자동 테스트와 실제 네트워크 E2E 결과.
