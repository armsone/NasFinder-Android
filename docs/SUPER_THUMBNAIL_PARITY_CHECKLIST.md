# SuperThumbnail iOS→Android 회귀 체크리스트

## 범위와 현재 상태

이 문서는 iOS 원본의 `SuperThumbnailView.swift`, `SuperThumbnailQueueStore.swift`, `SuperThumbnailVault.swift`, `ThumbnailPreheater.swift`, `RemotePreviewStateTests.swift`, `SuperThumbnailVaultTests.swift`를 읽기 전용으로 감사한 Android 이식 기준이다. iOS 원본은 수정하지 않았다.

Android는 WorkManager unique work, 연결별 작업 식별자, 표준/제한 실행 constraints, root-bound BFS, 항목·깊이·예상 byte 예산, 진행 Data와 취소를 구현한다. signature 기반 queue/report/resume와 Vault 상태도 앱 전용 JSON에 영속하며, cache miss에서는 signature 기반 Vault JPEG를 검증해 로컬 cache로 복원한 뒤 원격 원본 재생성을 생략한다.

## 폴더 선택 계약

- 선택 가능한 연결과 실제 backend 지원 여부가 일치해야 한다. credential 또는 서비스 생성이 불가능한 연결은 선택 후 늦게 실패시키지 않는다.
- 연결 root부터 폴더만 표시하고 파일은 선택 목록에서 제외한다. 현재 폴더 자체를 선택할 수 있어야 한다.
- root 경계는 component 단위로 검사한다. `/photo`가 `/photos-private`를 포함한다고 판단하거나 `..`, NUL, CR/LF, 절대/상대 root 혼용을 허용하지 않는다.
- loading, 빈 폴더, 인증/네트워크 오류를 서로 다른 상태로 표시하고 오류에는 재시도 동작을 제공한다.
- 폴더 행, 현재 폴더 선택, 닫기, 재시도는 TalkBack 이름과 역할을 가져야 한다. 단순 색상이나 아이콘만으로 상태를 전달하지 않는다.
- 선택한 connection ID, path, title을 함께 저장한다. connection 삭제 또는 path 무효화 뒤에는 안전한 root/다른 연결로 폴백하며 이전 credential이나 stale path로 작업을 시작하지 않는다.

## Queue·결과·보고서 영속 계약

- session key는 연결과 root 범위를 함께 식별해야 한다. 같은 연결의 두 폴더 작업이 덮어쓰지 않아야 한다.
- 각 항목은 ID뿐 아니라 `ID + size + modified time` signature로 식별한다. 내용이 바뀌면 과거 성공·실패·cached·vault 상태를 재사용하지 않는다.
- thumbnail 시도 단계는 0...2로 clamp한다. 성공, 사진 성공, cache hit, 실패, pending은 서로 배타적인 보고서 범주여야 한다.
- cache hit가 확인되면 이전 성공/실패 결과를 제거해 total을 중복 집계하지 않는다.
- 보고서 total은 `cached + 성공 + max(pending, failure)` 계약을 유지하고, 단계별 남은 수는 다음 단계 성공과 unresolved를 포함한다.
- 실패 레코드는 이름, 확장자, 크기, duration, 원인을 보존하되 credential, URL query, token, local cache path를 포함하지 않는다.
- 이어하기는 실패 thumbnail, pending queue, uploaded가 아닌 Vault 항목만 복원하고 이미 uploaded인 항목은 제외한다. path 자연정렬을 사용한다.
- 재개 시 전체 listing이 아직 없으면 저장된 미관찰 항목을 삭제하지 않는다. 전체 확인이 끝났을 때만 signature가 맞지 않거나 사라진 항목을 정리한다.
- 영속 데이터가 손상되었거나 새 schema를 읽지 못하면 crash하지 않고 새 session으로 fail closed한다. 부분 decode 결과를 성공으로 가장하지 않는다.

## Vault 계약

- `.NasFinder-Vault`는 각 미디어 부모 폴더 아래에 두며 사용자 원본과 별개로 취급한다.
- `즉시`는 같은 폴더의 대상 thumbnail이 모두 준비된 뒤 폴더 묶음을 업로드한다. `나중에`는 run finish에서 업로드한다.
- 재개 시 local cache에 이미 있는 thumbnail은 다시 생성하지 않고 즉시 업로드 후보로 사용한다.
- 업로드 성공은 원격 listing으로 파일 존재를 확인한 뒤 기록한다. 취소·부분 실패를 uploaded로 표시하지 않는다.
- verification에서 사라진 과거 uploaded 항목은 pending으로 되돌리고 verified timestamp를 기록한다.
- Vault 읽기는 동일 NAS 경로와 content signature를 기준으로 하므로 다른 connection ID로 같은 NAS에 연결해도 복원할 수 있어야 한다.
- NAS Vault 삭제는 Vault 파일만 지우고 로컬 thumbnail/cache, 원본 미디어, queue report를 임의로 삭제하지 않는다. root 및 symlink 경계를 재확인한다.

## WorkManager와 프로세스 재시작 경계

- unique work 이름은 connection ID를 평문으로 노출하지 않고 연결별로 안정적이어야 한다. 시작 중복 탭은 활성 work를 하나만 유지한다.
- 일반 시작은 `UNMETERED + charging + batteryNotLow`, 명시적 제한 시작은 `CONNECTED + batteryNotLow`다. 제한 실행도 네트워크와 저배터리 보호를 해제하지 않는다.
- Android API 29+ 열 상태는 `PowerManager` NONE/LIGHT에서 계속, MODERATE에서 각 새 항목 전에 500ms pacing, SEVERE/CRITICAL/EMERGENCY/SHUTDOWN 또는 알 수 없는 값에서 `Result.retry()`로 식을 때까지 미룬다. API 26~28은 열 상태를 읽을 수 없으므로 매 항목 500ms pacing을 적용한다.
- 열 상태 판단은 항목 경계에서 수행한다. 이미 쓰는 중인 파일을 중간에 훼손하지 않고, pause/retry 전에 queue·report가 재개 가능한 상태인지 확인한다. cancellation은 thermal retry보다 우선한다.
- 프로세스 재시작 뒤 saved selection을 복원하고 WorkManager DB의 ENQUEUED/BLOCKED/RUNNING/terminal work를 다시 관찰한다. 새 work를 자동 중복 enqueue하지 않는다.
- progress Data는 음수를 0으로 clamp하고 알 수 없는 status는 FAILED로 fail closed한다. RUNNING인데 progress가 아직 없으면 RUNNING 0으로 표시한다.
- `ExistingWorkPolicy.REPLACE`는 사용자의 명시적인 새 시작에만 허용한다. 화면 재구성·observer 재연결·보고서 열기로 기존 작업을 교체하면 안 된다.
- worker cancellation은 네트워크/list/thumbnail 단계에 전파되고 CancellationException을 retry/failure로 바꾸지 않는다. 일반 오류 retry는 bounded해야 한다.
- 완료 output과 마지막 progress는 같은 필드를 사용한다. terminal 상태 뒤 재관찰 시 0으로 회귀하지 않아야 한다.
- queue/report/Vault 영속 저장은 WorkManager progress Data와 분리한다. progress Data 용량에 전체 item 목록이나 실패 상세를 넣지 않는다.

## 진행·보고서 접근성

- 진행 영역은 현재 파일명, 완료, cache hit/건너뜀, 실패, Vault 보관/대기/실패를 의미 있는 단위로 읽는다. 숫자만 나열하지 않는다.
- progress bar에는 현재 값과 전체 값을 제공한다. 전체가 아직 불명확하면 무한 진행 상태임을 알린다.
- 취소 버튼은 WAITING/RUNNING에서만 활성화하고, 취소 요청과 취소 완료를 구분한다. 빠른 중복 탭은 한 번만 처리한다.
- 미리보기 카드는 button role, `미리보기` label과 열기 hint를 가진다. 장식 reflection/overlay는 접근성 트리에서 제외한다.
- 보고서 headline은 완료/미완료/업로드 대기를 텍스트로 전달한다. 실패 상세 disclosure, 이어하기, 폴더 선택, 닫기는 명시적 label을 가진다.
- 숨김 10-tap 제한 시작 overlay는 접근성 트리에서 숨기되, TalkBack 사용자가 제한 실행을 해야 한다면 동등한 명시적 접근 경로를 제공해야 한다. 숨김 제스처만 유일한 기능 경로가 되어서는 안 된다.
- 화면 이탈이나 process recreation 후 TalkBack focus가 파괴된 행을 가리키지 않도록 안정적인 item key를 사용한다.

## 필수 회귀 테스트

1. 폴더 선택: 지원 연결 filter, root/current folder 선택, 파일 제외, loading/empty/error/retry, traversal 방어, connection 삭제 폴백.
2. Queue: signature 변화 invalidation, attempt clamp, cached category 전환, failure redaction, media scope, 미관찰 항목 보존과 full reconciliation.
3. Report: 단계별 success/remaining/total, 사진 성공, folder별 Vault count, 자연정렬, resume eligibility.
4. Vault: 폴더 전체 즉시 업로드, finish-only 나중 업로드, cached resume, cross-connection restore, cancel retry, verification rollback, Vault-only delete.
5. WorkManager JVM/Robolectric: constraints 매핑, API별 thermal continue/500ms/retry 매핑, unique isolation, progress/output snapshot, malformed Data, ENQUEUED/BLOCKED/RUNNING/terminal 매핑, duplicate start/cancel race.
6. 프로세스 재시작 instrumentation: 실행 중 process kill 후 observer·selection·progress 복원, terminal result 복원, 중복 enqueue 없음, queue/Vault resume.
7. 접근성 Compose test: 모든 action semantics, progress range/state description, failure disclosure, stable focus/item key, touch exploration에서 숨김 gesture의 명시적 대체 경로.

현재 JVM의 `SuperThumbnailWorkContractTest`, `SuperThumbnailVaultAndSessionTest`, `RemoteThumbnailCacheTest`는 WorkManager Data 재관찰과 손상 값 fail-closed, 연결별 private unique identity, signature queue/report/resume, Vault staging·검증·삭제 경계와 Vault JPEG 입력 경계를 고정한다. 프로세스 재시작 instrumentation과 실제 NAS E2E는 정식 후보에서 별도로 확인한다.
