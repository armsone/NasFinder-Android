# NasFinder iOS 기능 기술서 — Android 이식 기준

## 2026-08-15 MOV Range 요청 수 재감사

iOS의 영상 썸네일은 `RemoteVideoStreamingLoader`의 256KiB 최대 chunk, loader-local contained-range cache, 동일 item 생성 coordinator, 파일·폴더 byte budget과 20초 timeout을 갖는다. 정상적인 full chunk 응답에서는 요청 수가 제한되지만, 256KiB는 최소 read-ahead가 아니다. AVFoundation이 서로 다른 작은 range를 빠르게 요청하면 byte budget 안에서 요청 횟수가 크게 늘 수 있고 request-count hard cap도 없다.

따라서 iOS에도 다음 보강이 필요하다. 이 내용은 Android 기술서에만 기록했으며 보호 대상 iOS 저장소는 수정하지 않았다.

- 최소 aligned read-ahead block과 overlapping/adjacent range 병합
- loader별·item별·folder별 request-count hard cap
- range cache entry/metadata cap과 O(n) lookup 방지
- tiny disjoint range fake-service 테스트와 실제 MOV 폴더 request-count 계측

> 조사 기준: 로컬 iOS NasFinder 저장소의 `Core/Models`, `Core/Services`, `Features/Browser`, `Features/Favorites`, `Features/Inbox`, `Features/WebHard`, `Features/Preview`, `NasFinderShared` 소스. 원본 iOS 저장소는 읽기 전용으로 조사했다. 이 문서는 Android 구현의 동작 계약이며, iOS 프레임워크 이름은 대응 동작을 명확히 할 때만 병기한다.

## 1. 공통 도메인 계약

### 1.1 연결

지원 연결은 Synology, SFTP, SMB, WebDAV, FTP, Dropbox, OneDrive, Google Drive다. 기본 포트/루트는 각각 `5001 / /`, `22 / .`, `445 / /`, `9800 / /`, `21 / /`, 클라우드 3종은 `443 / /`다. Synology와 WebDAV 및 OAuth 클라우드만 TLS 플래그를 유지한다. Files 앱 위치(File Provider)는 Synology와 SFTP만 지원한다. Android에서는 동일한 목록과 기본값을 유지하되 Files 앱 통합은 Storage Access Framework/DocumentsProvider로 별도 구현한다.

연결 모델 필드: UUID, 표시명, 종류, host, port, username, rootPath, TLS 여부, SFTP 신뢰 호스트 키, 생성일. SFTP의 빈 루트는 `.`, 나머지의 빈 루트는 `/`로 정규화한다. 시작 위치 기억은 연결 ID·경로·제목을 저장하며, 다시 열 때 설정 루트 밖이면 루트로 되돌린다.

서버 주소 입력은 host, `scheme://host[:port]`, `[IPv6]:port`, 비괄호 IPv6를 받는다. URL 사용자명/비밀번호, query, fragment, 루트 이외 경로는 거절한다. 포트 범위는 1…65535다. 허용 scheme은 SFTP=`sftp/ssh`, Synology=`http/https`, WebDAV=`http/https/webdav`, SMB=`smb`, FTP=`ftp`다. Synology에서 scheme이 바뀌고 포트를 사용자가 고정하지 않았다면 5000/5001을 동기화한다.

근거: `NasFinder/Core/Models/Connection.swift`, `ServerAddressParser.swift`, `NasFinder/Core/Services/ConnectionStore.swift`.

### 1.2 원격 파일

`RemoteFileItem` 대응 모델은 connectionID, path, provider opaque ID, parent provider ID, revision/eTag, name, folder/file, size, modifiedAt, content type을 가진다. 안정 ID는 provider ID가 있으면 `connectionID:remote:providerID`, 없으면 `connectionID:path`다. MIME/UTI와 확장자를 함께 사용해 이미지·영상·Quick Look 가능 문서를 판정한다. Android도 MIME만 믿지 말고 확장자 fallback을 유지한다.

숨김 정책은 이름이 `.`으로 시작하는 모든 항목을 브라우저에서 제외한다. 폴더, 이미지, 영상, 오디오, PDF, 압축, 일반 문서별 아이콘을 구분한다.

근거: `NasFinder/Core/Models/RemoteFileItem.swift`, `RemoteFileVisibilityPolicy.swift`.

### 1.3 서비스 인터페이스

모든 백엔드는 다음 계약을 공유한다.

- 필수: 폴더 목록, 파일 다운로드, 연결 테스트.
- 선택: 진행률 다운로드, byte-range 읽기, 서버 썸네일, 대기 중 썸네일 인증 작업 취소.
- 쓰기: 폴더 생성, 이름 변경, 삭제(재귀 여부), 업로드, 복사, 이동.
- 진행 단계: preparing, reading, writing, committing, deleting, rollingBack, completed. 단위는 bytes/items이며 operation UUID와 현재 경로를 전달한다.
- 충돌 정책: `fail`, `skip`, `replace`(파일만; 폴더 교체 금지), `keepBoth`(`Photo (1).jpg` 형태).
- 전송 전략: automatic(서버측 우선 후 streaming), serverSideOnly, streaming. streaming move는 목적지 commit 후에만 원본을 삭제한다.
- 취소는 구조적 취소를 따르며, 이미 변경된 항목은 부분 결과에 남긴다. 결과에는 성공/건너뜀/실패, 취소 여부, rollback 상태가 포함된다.
- 예상 크기를 아는 다운로드는 실제 파일 크기를 검증하고 불일치 시 불완전 다운로드 오류로 처리한다.

근거: `NasFinder/Core/Services/RemoteFileService.swift`, `NasFinder/Core/Models/RemoteFileOperation.swift`.

### 1.4 경로 및 이름 보안

빈 이름, 공백뿐인 이름, `.`/`..`, `/`, NUL을 포함한 이름을 거절한다. 빈 경로, NUL, `..`, 절대/상대 루트 스타일 불일치를 거절한다. 모든 변경 경로는 설정 root의 구성요소 prefix 내부여야 한다. 단순 문자열 prefix가 아니라 구성요소 단위로 검사한다. SFTP 재귀 변경은 realpath로 심볼릭 링크가 root 밖을 가리키는지도 막는다. Android 이식에서도 이 검증을 백엔드 호출 직전에 다시 수행한다.

근거: `NasFinder/Core/Services/RemotePath.swift`, `SFTPFileService.swift`.

## 2. 프로토콜별 기능

| 백엔드 | 인증 | 목록/다운로드/Range | 썸네일 | 쓰기 능력 | 중요 경계조건 |
|---|---|---|---|---|---|
| Synology | DSM SID, Keychain 세션 재사용 및 만료 시 재로그인 | File Station List/Download, HTTP Range | File Station Thumb, 12초 timeout | 모든 capability | API 탐색→인증→root 단계 진단, 목록 12초 timeout, 서버 작업 polling, 부분 결과/rollback |
| SFTP | password + 반드시 저장된 SSH host key 일치 | SFTP list/read, 캐시 다운로드, 임의 range | 영상 sparse range 생성 | 생성/이름/재귀삭제/업로드/교체/stream copy/server+stream move | 호스트 키 최초/변경 차단, realpath root 탈출 차단, staging/backup 원자 commit |
| SMB2 | username/password | `/`에서 share 목록, share 내부 목록/다운로드/range | 서버 API 없음 | 생성/이름/삭제/업로드/교체/server move | `$`로 끝나는 관리 share 숨김, share 간 rename 금지, 현재 delete는 비재귀 |
| WebDAV | Basic auth, URLSession challenge | PROPFIND Depth 1, GET, Range=206 | 서버 API 없음 | MKCOL/MOVE/DELETE/PUT, 재귀삭제/교체/server move | request 20초/resource 120초, MOVE Overwrite=`F`; 현재 업로드는 파일 전체를 메모리 매핑 |
| FTP | USER/PASS, binary TYPE I, passive | MLSD→실패 시 LIST, RETR, REST+RETR range | 서버 API 없음 | MKD/RNFR+RNTO/RMD·DELE/STOR, 교체/server move | 평문이므로 신뢰 LAN/VPN 경고, EPSV→PASV fallback, 디렉터리 삭제는 비재귀 |
| Dropbox | OAuth2 PKCE + refresh token | list_folder, content download | 없음 | capability 없음 | 목록 pagination 미구현(첫 응답만), 전체 다운로드만 |
| OneDrive | OAuth2 PKCE + refresh token | Graph children/content | 없음 | capability 없음 | 목록 pagination 미구현, 경로 URL encoding |
| Google Drive | OAuth2 PKCE + refresh token | Drive files query(최대 1000), alt=media | 없음 | capability 없음 | 폴더 path→ID 메모리 캐시, Google-native 문서 export 미구현 |

### 2.1 Synology 상세

- 연결 테스트는 기존 SID를 폐기한 뒤 Web API 탐색, 로그인, 시작 폴더 목록의 3단계를 분리한다. 주소/DNS, 네트워크, TLS, Web API, 인증, rootPath 오류를 다른 사용자 메시지로 표시한다.
- 목록은 root `/`이면 `SYNO.FileStation.List.list_share`, 그 외 `list`; size/time 추가 필드, 이름 오름차순을 요청한다.
- 다운로드는 `SYNO.FileStation.Download.download`; 로컬 캐시가 있으면 즉시 사용한다. JSON 응답이 다운로드 파일처럼 온 경우도 오류로 판정한다.
- 썸네일은 이미지/영상에만 `SYNO.FileStation.Thumb.get(size, rotate=0)`을 사용한다. JSON이면 API 오류/미지원으로 해석하고, byte 상한 초과는 즉시 중단한다. NAS 영상 원본 전체 다운로드 fallback은 금지한다.
- 생성=`CreateFolder`, 이름 변경=`Rename`, 삭제=`Delete`, 복사/이동=`CopyMove`, 업로드=`Upload`를 사용한다. keepBoth는 먼저 형제 목록에서 새 이름을 정한다. 서버측 작업은 완료될 때까지 polling한다.
- streaming copy/move는 다운로드→업로드를 사용하며, move는 업로드 성공 뒤 삭제한다. 실패/취소 시 rollback 상태와 부분 성공을 보존한다.
- SID는 endpoint+username 정체성과 묶어 안전 저장하며 1개 로그인 task를 공유한다. 인증 오류 시 SID를 한 번 폐기하고 재인증한다.

근거: `NasFinder/Core/Services/SynologyFileService.swift`, `SynologySessionStore.swift`, `SynologyConnectionDiagnostics.swift`, `SynologyMultipartFormData.swift`.

### 2.2 SFTP 상세

- 현대 알고리즘 우선이지만 호환성을 위해 AES-CTR, group14, RSA 등을 포함한 전체 Citadel 알고리즘 집합을 허용한다. 재연결은 하지 않으며 connect timeout은 일반 30초, 취소로 transport를 닫아야 하는 작업은 20초다.
- 서버 공개키 OpenSSH 문자열이 저장 키와 정확히 같아야 한다. 최초 연결이면 fingerprint 확인을 요구하고, 기존 키 변경은 관리자 확인 전 차단한다. fingerprint는 SSH key blob SHA-256 base64(no padding)다.
- 목록에서 `.`/`..`를 제외하고 permission type bits 또는 long listing으로 폴더를 판정한다.
- 다운로드는 128 KiB chunk, range는 최대 256 KiB chunk로 읽는다. 다운로드 직전 live stat 크기를 우선해 무결성을 검증하고, 검증 뒤에만 공용 다운로드 캐시에 발행한다.
- 생성/rename/delete/copy/move는 lexical root 검증에 더해 canonical root/realpath를 검사한다. staging 이름과 backup을 사용하고 rename 결과를 재조회하여 commit을 검증한다. 디렉터리 복사는 하위 결과를 모두 수집하며 실패 시 새 목적지 tree 정리를 시도한다.
- 영상 썸네일은 sparse 임시 파일에 head/tail range를 채워 AVFoundation으로 생성한다. 기본 small=512/128 KiB, medium=768/256 KiB, large=1024/512 KiB이며 총 1.5 MiB 이내다. adaptive 모드는 head 256 KiB + tail 64 KiB씩 예산까지 늘린다. JPEG 최대 치수는 192/384/720이다.

근거: `NasFinder/Core/Services/SFTPFileService.swift`, `SFTPFileMutationSupport.swift`, `NasFinderSSHHostKeyValidator.swift`, `SFTPConnectionDiagnostics.swift`.

### 2.3 SMB/WebDAV/FTP 상세 및 현재 제약

- SMB root는 파일 경로가 아니라 계정에 보이는 share 목록이다. 숨은 관리 share(`$`)는 제외한다. 각 작업 후 share disconnect/logoff를 시도한다. 현재 구현은 충돌 정책을 세밀하게 적용하지 않고 upload library 기본 동작에 의존하며, copy가 구현되지 않았다. Android는 iOS와 기능 표시는 맞추되 교체/충돌 동작을 명시적으로 보완하기 전 capability 이상을 노출하지 않는다.
- WebDAV 목록은 `resourcetype/getcontentlength/getlastmodified`를 XML multistatus로 파싱하고 자기 폴더 response를 제외한다. 허용 상태는 목록 207, 다운로드 200, range 206, MKCOL 201, MOVE 201/204, DELETE 200/204, PUT 200/201/204다. TLS flag로 http/https를 선택한다. 현재 copy는 없고 move/rename만 서버측 MOVE다.
- FTP는 MLSD를 우선하고 오래된 ipTIME용 Unix LIST 파서를 fallback한다. range는 REST 뒤 제한 길이만 수신한다. 업로드 충돌은 fail/skip/replace/keepBoth를 구현한다. FTP는 암호화하지 않으므로 UI에 신뢰 LAN 또는 VPN 권고를 반드시 표시한다. 현재 copy는 없고 move capability는 RNFR/RNTO 기반이다.

근거: `NasFinder/Core/Services/SMBFileService.swift`, `WebDAVFileService.swift`, `FTPFileService.swift`.

### 2.4 클라우드 OAuth 및 목록

OAuth는 시스템 인증 세션과 Authorization Code + PKCE(S256), random state를 사용한다. Dropbox는 offline token, Google은 offline+consent를 요청한다. callback state 불일치는 실패다. 만료 60초 전 refresh하며 새 refresh token이 없으면 기존 것을 보존한다. refresh token이 없는데 만료되면 재로그인을 요구한다.

토큰/비밀번호는 Android Keystore로 암호화된 저장소에 보관해야 하며 평문 preferences에 두지 않는다. iOS는 기기 전용 AfterFirstUnlock Keychain과 앱/extension access group을 쓴다.

근거: `NasFinder/Core/Services/CloudOAuthAuthorizer.swift`, `CloudOAuthConfiguration.swift`, `CloudDriveFileService.swift`, `KeychainCredentialStore.swift`.

## 3. 파일 브라우저

### 3.1 목록·검색·정렬

화면 진입 시 원격 목록을 읽고 dot-hidden 항목을 제거한 뒤 동일 배열을 list/grid/cover flow/검색/선택/연속 미리보기에 사용한다. 첫 로드 중에는 progress, 실패+빈 목록이면 재시도, 성공+빈 목록이면 빈 폴더, 검색 결과 없음이면 검색 empty state를 보인다. 변경 뒤 refresh는 진행 중이던 이전 목록 요청이 끝난 다음 새 요청을 시작해 오래된 응답이 변경 결과를 덮지 않게 한다.

검색은 trim 후 이름 부분 일치이며 case/diacritic/width insensitive, 현재 locale을 사용한다. 정렬 필드는 이름·날짜·크기·종류, 방향은 오름/내림, 폴더 우선 on/off, 이름 그룹 우선순위는 숫자/한글/외국어 3종이다. 날짜/크기 없는 항목은 방향과 무관하게 끝에 둔다. tie-break는 이름 그룹→localized standard name→path다. 종류는 폴더 우선 key, 파일은 확장자→contentType→`file`이다.

근거: `NasFinder/Features/Browser/FileBrowserViewModel.swift`.

### 3.2 탐색과 레이아웃

- 보기: 자세히 목록, 작은 썸네일, 포스터, Cover Flow. 설정은 보존된다.
- root부터 현재 경로까지 breadcrumb를 만들고 상위 폴더로 이동한다. 현재 경로는 연결 root 밖으로 나갈 수 없다.
- 폴더 탭은 하위 브라우저, 파일 탭은 미리보기. 미디어 미리보기의 순서는 현재 검색·정렬 결과를 따른다.
- 길게 누르기/컨텍스트 패널에서 즐겨찾기, 정보, 공유, 받은 파일에 저장, 이름 변경, 복사/이동 준비, 삭제를 제공하되 service capability에 따라 비활성/숨김 처리한다.
- 선택 모드는 화면에 보이는 항목 전체 선택/해제, 파일만 공유·다운로드, copy/move/delete를 지원한다. 폴더는 다운로드/공유 대상에서 제외한다.
- Cover Flow는 이미지/영상 중심의 전체 화면형 표시와 별도 back/more chrome을 사용하며 방향 변화에 대응한다.
- 네트워크 traffic 및 썸네일 진행 배너, 공유 준비, 받은 파일 저장, 파일 작업 진행/취소/결과 배너를 동시에 우선순위에 맞춰 표시한다.

근거: `NasFinder/Features/Browser/FileBrowserView.swift`, `FileBrowserCoverFlowView.swift`, `FileBrowserContainerView.swift`, `PageNetworkTrafficTracker.swift`, `RemoteFileInfoView.swift`.

### 3.3 변경 작업 조정

- 업로드는 선택 파일을 순차 처리하고 기본 충돌 정책은 keepBoth다. Android content URI 권한은 각 파일 처리 동안만 유지한다.
- clipboard는 한 연결의 항목만 받는다. 다른 서버로 paste는 현재 차단하며 “안전한 검증 전송 단계에서 지원 예정” 오류를 낸다.
- paste는 자동 전략+keepBoth. move 성공 항목은 clipboard에서 제거하고 실패 항목만 남긴다.
- delete는 폴더에 recursive=true를 요청하며 확인 dialog가 선행한다.
- 취소해도 이미 성공한 원격 변경은 보존하고 별도 refresh한다. 실패 요약은 성공/실패 수와 최대 3개 상세를 보여준다.
- 작업 중 화면 꺼짐을 막고 완료/취소 시 해제한다. 작업과 refresh를 중복 시작하지 않는다.
- 공유는 모든 파일을 다운로드한 뒤 작업 전용 임시 폴더에 안전한 고유 이름으로 hard-link 또는 1 MiB chunk 복사한다. 24시간 지난 공유 임시 폴더만 정리한다.

근거: `NasFinder/Features/Browser/FileOperationCoordinator.swift`, `FileBrowserView.swift`.

## 4. 썸네일과 캐시

### 4.1 요청 순서

1. 메모리 rendered-image cache 확인(최대 240개/64 MiB).
2. 자동 disk cache, 이어서 Super Thumbnail cache 확인. 큰 크기가 없으면 small key fallback.
3. 백엔드 서버 썸네일 요청.
4. 이미지 원본 또는 range 기반 영상 썸네일 생성.
5. Wi-Fi이고 Quick Look 지원 문서일 때만 원본 다운로드 후 로컬 문서 썸네일 생성.
6. 실패하면 아이콘을 유지하고 negative cache: 일반 일시 실패 60초, 영상/비정상 응답 5분, 네트워크 조건/셀룰러 제한은 30초.

동시 원격 썸네일 작업은 최대 3개다. 서버 응답도 downsample 후 UI bitmap으로 만든다. 피부톤 dominant 비율 42% 이상이면 설정에 따라 blur한다.

### 4.2 저장 및 제한

- 자동 썸네일 disk cache: 기본 256 MiB, 선택 가능한 용량 옵션, 최대 5000개, 30일, LRU형 수정일 갱신/오래된 것 우선 제거.
- 다운로드 cache: key=`formatVersion+connectionID+path+modifiedAt+size` SHA-256, 최대 512 MiB, 7일. SFTP/Synology 다운로드가 사용한다.
- Super Thumbnail은 별도 cache와 lifetime network byte 통계를 가진다. session key는 `connectionID|선택 rootPath`이며 항목 ID·크기·수정시각 signature로 pending/cached/성공 단계/사진 성공/실패와 Vault 상태를 앱 전용 저장소에 영속한다. 재개 시 pending·실패 또는 Vault 미완료 항목만 복원하고 signature가 달라진 파일은 새 작업으로 취급한다.
- 선택적으로 원격 각 미디어 폴더의 숨김 `.NasFinder-Vault`에 생성 결과를 업로드한다. 저장명은 engine version+NFC 파일명+크기+수정시각의 SHA-256인 `v1-<64hex>.jpg`다. UUID `.upload-*.tmp` staging을 먼저 올려 목록에서 확인한 뒤 최종명으로 rename하고 결과를 재조회하며, 실패하면 staging을 정리하고 최대 3회(250ms 간격) 재시도한다. 폴더 단위 now/later 업로드, NAS 실제 보관본 검증시각, waiting/pending/uploaded/failed 폴더별 보고를 유지한다.
- 여러 기기가 같은 root의 Vault를 동시에 만드는 경우 root의 `.NasFinder-Vault/.workers-v1/worker-<workerID>.json`을 30초마다 갱신한다. worker 유효기간은 90초이며 `expiresAt`은 Swift `JSONEncoder` 기본 Date 형식(2001-01-01 기준 초)이다. peer는 최초 750ms 뒤와 이후 5초 간격으로 확인한다. 동일 항목은 `.claim-v1-<64hex>/.owner.json`의 worker ID·UUID token·180초 만료 lease로 선점한다. 활성 lease 또는 이미 생성된 JPEG가 있으면 상대 기기 결과를 다시 확인하며 기다리고, 만료 lease만 회수한다. 생성 성공한 선점 항목은 폴더 종료를 기다리지 않고 단일 항목으로 Vault에 게시하고 token이 일치할 때만 lease를 지운다.
- 로컬 thumbnail cache miss에서는 같은 NAS 경로와 content signature의 Vault JPEG를 먼저 읽고 실제 JPEG·크기·decode 경계를 검증해 앱 소유 cache로 복원한다. Synology File Station API가 106/107/119로 SID 만료를 알리면 기존 SID를 버리고 요청을 새 SID로 한 번만 재구성하며, multipart Vault upload의 SID도 body가 아닌 query에 둔다.
- Vault 삭제는 사용자가 선택한 root 안에서만 BFS하고 dot-hidden 폴더는 건너뛰되 정확히 `.NasFinder-Vault`인 폴더만 연다. 그 안의 파일을 삭제한 뒤 폴더를 제거하며 성공 수와 실패 경로를 보존한다. 일반 숨김 폴더·symlink/root 밖 경로와 가시 폴더의 사용자 파일은 삭제하지 않는다.
- 셀룰러 영상 썸네일: 전체 24 MiB, 항목당 4 MiB. 일반 bounded: 폴더 256 MiB, 항목 16 MiB. SFTP 폴더 18,000,000 bytes. complete-file pass는 항목 24→40→64 MiB, 최종 상한 128 MiB다.
- 자동 preheat 상한: Synology 256 MiB, SFTP 18,000,000 bytes, 셀룰러 24 MiB, complete-file 64 GiB. Super Thumbnail의 표준 시작은 foreground, 배터리 20% 초과, 비종량 Wi-Fi와 외부 전원을 요구한다. 제한 실행은 앱 활성·저배터리 보호를 유지하면서 Wi-Fi/외부 전원 조건만 명시적으로 우회하며 셀룰러에서는 24 MiB 예산을 적용한다. 선택한 폴더의 재귀 후보 수집도 최초 항목 처리와 동일한 실행 조건을 사용해야 하고 외부 전원을 다시 무조건 요구해서는 안 된다. 조건 미충족 상태의 숨은 시작은 시작 버튼 10회 탭으로만 열리며 5회째부터 남은 횟수를 표시한다. 중단/재개 queue와 ETA 관측 최대 160개를 저장한다.
- Super Thumbnail 항목 경계의 열 상태 정책은 nominal에서 계속, fair에서 항목마다 500ms pacing, serious/critical/unknown에서 5초 간격으로 식을 때까지 일시 정지다. 취소는 이 대기와 Vault 협업 대기에도 즉시 전파되어야 한다.
- 네트워크가 metered→unmetered Wi-Fi로 바뀌면 셀룰러 budget을 reset하고 진행 중 cell request를 새 정책으로 재시작한다.
- Super Thumbnail 최근 작업은 연결 ID+경로로 식별하며 각 행의 메뉴에서 개별 삭제할 수 있다. 삭제 대상이 마지막/이전 선택과 같으면 해당 복원 포인터도 함께 비워 삭제한 항목이 다시 나타나지 않게 한다.

근거: `NasFinder/Features/Browser/RemoteThumbnailView.swift`, `ThumbnailPreheater.swift`, `RemoteVideoThumbnailGenerator.swift`, `SuperThumbnailCache.swift`, `SuperThumbnailVault.swift`, `SuperThumbnailQueueStore.swift`, `FileProviderThumbnailCache.swift`, `DownloadCache.swift`.

## 5. 즐겨찾기

### 5.1 원격 파일 즐겨찾기

파일/폴더의 안정 ID 기준 toggle, 삭제, 순서 변경을 지원하고 앱 그룹 preferences에 JSON으로 저장한다. 홈의 가로 shelf는 탭으로 열기, 길게 누른 뒤 수평 12pt 이상 이동 시 reorder, 삭제 UI를 제공한다. 폴더 타일은 하위 목록의 첫 이미지/영상 최대 9개 mosaic를 표시하며 60초 메모리 cache를 쓴다. 연결이 삭제되거나 credential이 없으면 해당 favorite 열기 실패를 표시해야 한다.

근거: `NasFinder/Core/Models/FavoriteItem.swift`, `Core/Services/FavoriteStore.swift`, `Features/Favorites/FavoriteViews.swift`.

### 5.2 웹 즐겨찾기

HTTP/HTTPS만 허용한다. canonical key는 scheme/host 소문자화, fragment 제거, 빈 path `/`, 말단 slash 제거, 기본 80/443 제거다. 중복은 canonical key로 판정한다. 추가/삭제/전체삭제/reorder/첫 항목을 홈페이지로 설정한다. JSON archive version 1, UTI `com.intosharp.hanclip.browser-favorites`, 확장자 `.hanclipfavorites`로 import/export한다. import는 유효 URL만 추가하고 중복 수를 보고한다. 공유 inbox에 들어온 archive는 import 성공 뒤 삭제한다.

근거: `NasFinder/Features/Browser/BrowserFavoritesStore.swift`.

## 6. 웹 브라우저

주소에 scheme이 없으면 `https://`를 붙이고 HTTP/HTTPS+host만 허용한다. 기본 주소는 Google이며 첫 웹 즐겨찾기가 있으면 홈페이지 역할을 한다. 주소 bar는 뒤로/닫기, 입력/지우기/이동 또는 로딩 중지, 새로고침, 즐겨찾기 패널을 제공한다. 새 창 target은 같은 WebView에서 연다. HTTP/HTTPS 외 navigation은 OS external intent로 보낸다.

재생 중인 video/audio는 브라우저 이탈 시 pause한다. WebView는 30분간 메모리에 보관하여 history/session을 복원하고 만료 시 stop/폐기한다. 즐겨찾기 버튼은 단일/복합 gesture로 toggle과 패널 열기를 구분하며 패널에서 favicon, 홈페이지 지정, 편집/삭제를 제공한다.

attachment 또는 표시 불가 MIME은 다운로드로 전환한다. 파일명에서 `/ : \\`를 `-`로 치환한다. 다운로드 중 화면 꺼짐을 막고 취소 가능하다. 완료 뒤 “받은 파일에 저장” 또는 “네트워크 위치 선택”을 고른다. 전자는 Shared Inbox로 commit, 후자는 NAS upload 화면으로 보낸다. 어느 경로도 완료되지 않으면 임시 파일을 정리한다.

근거: `NasFinder/Features/Browser/WebBrowserView.swift`, `BrowserFavoritesStore.swift`.

## 7. 받은 파일(Inbox)과 공유 수신

### 7.1 저장 형식과 원자성

공유 extension/외부 open/download가 넘긴 파일은 App Group의 private Inbox 폴더와 JSON manifest로 관리한다. record는 UUID, 원래 이름, 실제 저장 이름, content type, byte count, import time을 가진다. manifest/파일 접근은 process lock + file coordination으로 직렬화한다. import는 UUID 기반 실제 파일명으로 copy한 뒤 allocated byte count를 계산하고 record를 append한다. manifest append 실패 시 방금 저장한 파일을 rollback한다.

원래 파일명은 basename만 취하고 비거나 `.`/`..`면 source fallback을 쓴다. 저장 확장자는 영숫자만, 소문자화하여 path injection을 막는다. manifest의 stored filename은 basename과 동일해야 하고 standardized URL이 Inbox 내부인지 검증한다. 중복 record ID/filename은 거절한다. 삭제는 파일과 record를 함께 조정하며 실패 시 reload로 실제 상태를 다시 반영한다.

근거: `NasFinderShared/SharedInbox.swift`, `NasFinderShare/ShareViewController.swift`.

### 7.2 UI와 NAS 전송

최신 import 순, 같은 시각이면 이름 오름차순이다. 행에는 썸네일/아이콘, 이름, 크기, 수신 시각, 공유를 표시한다. 탭은 전체 화면 미리보기, swipe/context는 삭제, 일반 파일은 NAS로 보내기를 제공한다. 선택 모드에서는 regular file만 선택 가능하며 전체 선택/해제와 일괄 NAS 전송을 지원한다. 폴더 record는 전송 불가다.

`nasfinder://inbox?id=<UUID>` deep link는 inbox를 열고 해당 record를 바로 미리본다. 외부 file URL open은 security scope 동안 import하고 같은 흐름을 실행한다. NAS 목적지 화면은 저장된 연결→폴더를 탐색하고 upload capability가 있는 서비스에만 전송하며 기본 충돌은 keepBoth다.

Inbox 자체는 로컬 `RemoteFileService` adapter로 목록/download를 제공하여 원격 미리보기와 썸네일 UI를 그대로 재사용한다.

근거: `NasFinder/Features/Inbox/SharedInboxStore.swift`, `ReceivedFilesView.swift`, `InboxUploadDestinationView.swift`.

## 8. 폰하드(WebHard)

### 8.1 로컬 저장소

Application Support/WebHard 전용 root를 만들고 `completeUntilFirstUserAuthentication` 수준 파일 보호를 사용한다. 목록은 숨김 파일과 symlink/특수 파일을 제외하고 폴더 우선+이름 정렬이다. 모든 path에서 NUL, `.`/`..`를 거절하고 standardized path 및 기존 조상의 resolved symlink가 root 내부인지 검증한다. root 삭제는 금지한다.

업로드는 같은 디렉터리에 숨김 임시 파일로 쓰고 flush/close 후 move로 commit한다. 이름 충돌은 `(1)`…`(9999)`, 이후 UUID prefix로 keepBoth한다. 실패/연결 종료 시 임시 파일을 삭제한다.

근거: `NasFinder/Features/WebHard/WebHardFileStore.swift`.

### 8.2 HTTP 서버/API

선택한 Wi-Fi/셀룰러/이더넷 local address에 HTTP 서버를 시작하고, 앱 background 진입 시 즉시 중지한다. 선택 비밀번호가 비어 있으면 무인증, 있으면 `X-WebHard-Password` header 또는 query password가 정확히 일치해야 한다. 루트 HTML 자체는 인증 없이 배포되지만 모든 API는 인증한다.

- `GET /api/list?path=`: JSON 목록.
- `GET /api/file?path=`: attachment 다운로드.
- `GET /api/preview?path=`: inline preview.
- `POST /api/folder?path=`: 폴더 생성.
- `DELETE /api/item?path=`: 파일/폴더 삭제.
- `PUT /api/file?path=`: raw octet-stream 업로드. Content-Length 필수.

업로드 전 사용 가능 중요 용량에서 50 MiB reserve를 뺀 값보다 큰 요청은 HTTP 507이다. 초기 body/전체 수신 크기가 Content-Length를 넘으면 400, 누락은 411, 비밀번호 오류는 401, route 없음은 404다. 업로드/다운로드 chunk는 각각 수신 packet과 256 KiB 송신이며, UI에 항목별 bytes/percent를 실시간 반영한다. 응답은 `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Connection: close`다.

웹 UI는 list/작은 thumbnail/poster, 폴더 탐색/상위, 파일·폴더 선택 후 받기, 길게 눌러 받기/삭제, 다중 순차 업로드, 새 폴더, 비밀번호 prompt+sessionStorage를 제공한다. 앱 UI도 동일한 3개 보기, Quick Look 로컬 썸네일, 폴더 열기, 공유로 받기, 삭제를 제공한다.

근거: `NasFinder/Features/WebHard/WebHardHTTPServer.swift`, `WebHardServerView.swift`, `WebHardZipArchive.swift`.

## 9. 미리보기 및 재생

### 9.1 콘텐츠 라우팅

- 이미지: 전체 다운로드/cache 후 최대 4096px downsample, pinch 최대 6배, double-tap zoom, 한 손가락 pan, single tap chrome toggle.
- 영상: range+양수 size이면 부분 재생, 아니면 전체 다운로드. 기본 포맷은 AVPlayer 대응, 호환 포맷은 VLC 대응 player를 사용한다.
- 기타 파일: 전체 다운로드 후 Quick Look 대응 viewer(Android ACTION_VIEW/내장 PDF·문서 viewer 조합)로 연다.
- 오류가 최우선, 그 뒤 image/video/QuickLook, 나머지는 loading 상태다.

미디어 sequence는 현재 전달된 image/video만 포함하고 최초 item이 없으면 단일 item으로 fallback한다. 상단에는 닫기, 파일명, 공유; 하단에는 이전/다음, 재생/일시정지, 위치, 반복 모드, 사진 간격을 제공한다. playback mode는 전체 반복/임의/한 항목 반복, 사진 간격은 1/2/3/5/10/15/30초이며 preferences에 저장한다. 기본은 전체 반복/5초다.

### 9.2 제스처·수명주기

영상 수평 drag는 seek, 위쪽 수직 drag는 system volume, 아래쪽 수직 drag는 dismiss다. pinch/pan으로 영상 확대·이동하며 범위를 clamp한다. chrome은 2.5초 뒤 자동 숨김; 사진 slideshow 중에는 완전히 사라지지 않고 10% opacity edge controls/progress를 둔다. background에서는 pause하고, 화면 dismiss 때 player/range task/download/subtitle/thumbnail 작업을 모두 취소한다. 재생 중에는 audio session을 playback으로 활성화하고 화면 꺼짐을 막는다.

### 9.3 Range streaming과 fallback

AVFoundation loader는 custom URL asset으로 content type/length/range 지원을 제공한다. 재생 chunk는 최대 8 MiB, bounded thumbnail chunk는 256 KiB다. 동일 loader의 range cache로 duration probe/재생의 중복 전송을 줄이고, 예약 byte budget으로 동시 요청도 상한을 넘지 않게 한다. 취소 시 등록된 모든 요청 task를 종료한다.

VLC 호환 stream은 synchronous input callback을 async range read에 연결한다. 1회 read 최대 1 MiB, 최근 cache 최대 2 MiB이며 seek 가능하다. range read timeout과 player watchdog을 둔다. 확장자 정책상 호환 player를 선호하는 파일은 VLC로 라우팅한다. 같은 basename의 `.srt/.ass/.ssa/.vtt` 등 외부 자막을 찾아 다운로드·부착하되 자막 실패는 재생을 막지 않는다.

부분 AVPlayer 준비 실패 시 compatibility range로 전환한다. compatibility range가 실패/정지하면 compatibility full download를 한 번 시도한다. 전체 다운로드가 20초 동안 byte 진행이 없으면 취소하고 네트워크 오류를 표시한다. 공유는 이미 받은 local file을 임시 폴더에 link 또는 chunk copy한 뒤 OS share sheet에 제공한다.

근거: `NasFinder/Features/Preview/RemotePreviewView.swift`, `RemoteVideoStreamingLoader.swift`, `CompatibilityVideoPlayer.swift`, `SharedFullscreenMediaPlayer.swift`, `ZoomableMediaImageView.swift`.

## 10. 오류·진단·보안 요구

- 공통 오류: 잘못된 설정, credential 없음, 인증 실패, 서버 메시지, 미지원, 응답 해석 실패. UI에는 localized 안전 메시지만 노출하고 token/password/SID/원격 원문 credential을 로그에 남기지 않는다.
- 취소는 NSError의 URL cancelled도 `CancellationError`로 정규화하고 일반 오류 alert를 띄우지 않는다.
- SFTP 진단 단계: 주소, transport, host key, SSH negotiation, 인증, subsystem, remote path, cancelled, unknown.
- Synology 진단 단계: 주소, transport, TLS, Web API, 인증, root path, cancelled, unknown.
- 연결 삭제 시 목록/preferences, 기본 연결, 기억 위치, credential, Synology SID 및 플랫폼 document-provider 등록을 함께 제거하되 각 실패를 개별 보고한다.
- WebDAV/FTP/SMB의 현재 구현에는 Synology/SFTP만큼 강한 공통 root/충돌/transaction 검증이 모두 적용되어 있지 않다. Android는 취약한 동작을 그대로 복제하지 말고 1.4의 공통 보안 계약을 모든 writable backend에 적용한다.
- FTP는 평문. HTTP Synology/WebDAV도 사용자가 TLS를 끈 경우 평문이므로 설정 화면에서 위험을 명시한다.
- 다운로드/미리보기/공유/업로드 임시 파일은 성공·취소·실패에 맞춰 소유자가 정리한다. 완료 전 무결성 검증을 생략하지 않는다.

근거: `NasFinder/Core/Models/NasFinderError.swift`, `RemoteFileOperation.swift`, `Core/Services/*Diagnostics.swift`, `KeychainCredentialStore.swift`, 각 feature coordinator.

## 11. Android 구현 우선순위와 동등성 체크리스트

1. 공통 모델/경로 검증/credential 저장/서비스 capability부터 구현한다.
2. Synology와 SFTP를 완전 구현하고 목록·다운로드·range·thumbnail·모든 변경 작업의 회귀 테스트를 만든다.
3. 동일 브라우저 배열에 검색/정렬/4개 레이아웃/선택/작업 coordinator를 연결한다.
4. 다운로드 및 썸네일 cache key·수명·용량·negative cache·network budget을 맞춘다.
5. 이미지/영상/문서 preview와 range→compatibility→full-download fallback 순서를 맞춘다.
6. Inbox는 app-private storage+원자 manifest, Android Sharesheet/OPEN_DOCUMENT 수신, NAS 업로드를 연결한다.
7. 폰하드는 app-private root와 path/symlink 검사, 동일 REST API/상태 코드/UI를 구현한다.
8. SMB/WebDAV/FTP/클라우드를 capability 이상 노출하지 않도록 순차 연결한다.

릴리스 전에는 다음을 확인한다: 기본 포트/root, 호스트 키 변경 차단, root traversal/symlink 탈출, hidden 파일, 검색 locale, missing metadata 정렬, keepBoth, 폴더 replace 금지, 취소 후 부분 성공, move 원본 삭제 순서, 다운로드 size mismatch, cache eviction, 셀룰러 budget, background pause/server stop, Inbox rollback, WebHard 50 MiB reserve, preview 20초 inactivity, 자막 optional failure, credential/log 비노출.

## 12. 근거 파일 색인

- 모델/연결/오류: `NasFinder/Core/Models/*.swift`
- 공통 서비스/경로/cache/credential: `NasFinder/Core/Services/RemoteFileService.swift`, `RemotePath.swift`, `RemoteFileServiceFactory.swift`, `ConnectionStore.swift`, `KeychainCredentialStore.swift`, `DownloadCache.swift`
- 프로토콜 구현: `SynologyFileService.swift`, `SFTPFileService.swift`, `SMBFileService.swift`, `WebDAVFileService.swift`, `FTPFileService.swift`, `CloudDriveFileService.swift`
- 브라우저/작업/썸네일/웹: `NasFinder/Features/Browser/*.swift`
- 즐겨찾기: `NasFinder/Core/Services/FavoriteStore.swift`, `NasFinder/Features/Favorites/FavoriteViews.swift`
- 받은 파일: `NasFinderShared/SharedInbox.swift`, `NasFinder/Features/Inbox/*.swift`, `NasFinderShare/ShareViewController.swift`
- 폰하드: `NasFinder/Features/WebHard/*.swift`
- 미리보기: `NasFinder/Features/Preview/*.swift`
