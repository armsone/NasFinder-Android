# Android 구현 상태

이 문서는 iPhone 기술서의 계약을 현재 Android 소스와 대조한 스냅샷이다. `소스 구현`은 Factory 연결과 정적 의존성 확인까지 끝냈다는 뜻이며, 실제 서버·계정 E2E 완료를 뜻하지는 않는다. 2026-08-18 기준 Android 37에서 `testDebugUnitTest` 169개와 `lintDebug`·`assembleDebug`가 통과했다. Samsung SM-F968N(Android 16)에 최신 debug APK를 데이터 유지 방식으로 재설치해 앱 실행까지 확인했다. 실제 Synology MOV 썸네일, Browser back long-press, Super Thumbnail 폴더 선택과 보관 시점 배치 검증은 2026-08-15 실기기 기록을 유지한다.

### Factory와 의존성

- `RemoteFileServiceFactory`는 8종을 각각 `SynologyFileService`, `SftpFileService`, `SmbFileService`, `WebDavFileService`, `FtpFileService`, 공통 `CloudDriveFileService`에 연결한다. fallback 분기는 현재 enum 밖의 방어 코드다.
- REST·WebDAV·Synology·Cloud는 `okhttp 5.3.0`, SFTP는 `com.github.mwiede:jsch 2.28.3`(`com.jcraft.jsch` 호환 package), SMB2/3는 `smbj 0.14.0`을 직접 선언한다.
- 직접 사용하는 coroutine API는 `kotlinx-coroutines-android 1.9.0`을 선언한다. Android runtime의 `org.json`과 별도로 JVM 테스트에는 `org.json:json 20240303`을 선언한다.
- 공통 `readRange` 계약은 요청당 8MiB로 제한한다. HTTP 서버가 Range를 무시한 `200` 응답은 offset 0의 요청 길이까지만 읽고 즉시 닫으며, offset이 0보다 크면 전체 파일을 건너뛰어 읽지 않고 `Unsupported`로 종료한다.

## 원격 연결

| 연결 | 목록·다운로드 | 쓰기 작업 | 인증·안전 | 현재 제한 |
|---|---|---|---|---|
| Synology | 소스 구현·목록/썸네일 실기기 확인 | 생성·이름 변경·삭제·복사·이동·multipart keepBoth 업로드(SID query), bounded HTTP Range | DSM SID, 평문 password 호환+저장하지 않는 6–8자리 OTP, root·Content-Range·크기 검증·원자 완료 | 테스트 NAS의 영상 폴더 목록·MOV Range 썸네일 확인; mutation E2E 필요 |
| SFTP | 소스 구현 | 생성·이름 변경·재귀 삭제·복사·이동·keepBoth 업로드, seek 기반 bounded read | 비밀번호 또는 PEM/OpenSSH 개인키, SHA-256 호스트키 고정, realpath root, 원격 크기 경계 | 연결 모델에 별도 passphrase 필드가 없어 암호 없는 개인키만 지원; 실제 SSH 서버 E2E 필요 |
| SMB | 소스 구현 | 생성·이름 변경·재귀 삭제·동일 공유 내 원자 우선 이동·streaming 파일/재귀 폴더 복사·keepBoth 업로드, offset bounded read | SMB2/3, share/root 경계, source/destination reparse point 차단, staging cleanup | SMBJ만으로 share 열거 불가하여 `/공유이름` root 필요; 공유 간 copy/move 미지원 |
| WebDAV | 소스 구현 | MKCOL·MOVE·COPY·서버 재귀 DELETE·조건부 keepBoth PUT, bounded HTTP Range | Basic 인증, same-origin Destination/href, XXE 차단, deterministic temp+Range 재개·Content-Range·크기 검증 | 실제 서버 E2E 필요 |
| FTP | 소스 구현 | 생성·이름 변경·재귀 삭제·RNFR/RNTO 우선 이동·streaming/staging 파일·폴더 복사·keepBoth 업로드, REST bounded read | EPSV→PASV(광고 host 무시), root·경로·CRLF·하위 폴더 경계, 링크 차단, 실패 staging cleanup | 평문 전송; REST 미지원 서버는 bounded read 불가; 실제 서버 E2E 필요 |
| Dropbox | 소스 구현 | 생성·이름 변경·삭제·`copy_v2`·`move_v2`·autorename keepBoth·upload session, bounded HTTP Range | PKCE S256 OAuth·refresh rotation 또는 수동 token, cursor pagination, root·범위 경계 | provider console callback 등록·실제 계정 E2E 필요 |
| OneDrive | 소스 구현 | 생성·이름 변경·삭제·비동기 copy monitor·parentReference move·keepBoth upload session, bounded HTTP Range | PKCE S256 OAuth·refresh rotation 또는 수동 token, nextLink same-host·copy monitor 허용-host·범위 경계 | 교차 drive move 미지원; provider console callback 등록·실제 계정 E2E 필요 |
| Google Drive | 소스 구현 | 생성·이름 변경·삭제·파일 copy·addParents/removeParents move·keepBoth resumable 업로드, binary 파일 bounded HTTP Range | PKCE S256 OAuth·refresh rotation 또는 수동 token, pageToken·폴더 ID cache·root·범위 경계 | API 제약상 폴더 copy·native 문서 export/range 미지원; custom scheme console 정책·실제 E2E 필요 |

## 앱 기능

| 영역 | 상태 | 내용 |
|---|---|---|
| 대시보드·테마 | 소스 구현 | iPhone 대응 구조, 5개 테마, 서비스별 색상, 사용자 제공 원본 byte의 Blue NAS/Cyber Vault/Vibe Coder/Purple/네트워크 NAS 런처 아이콘 5종과 legacy·adaptive·round wrapper, 선택 영속화·실패 rollback; 5종 picker UI 연결 후 실기기 재검증 필요 |
| 연결 편집 | 완료 | 8종 연결, SFTP 호스트키·개인키, Synology OTP, cloud client ID·PKCE login·callback·refresh·수동 token fallback·logout |
| 파일 브라우저 | 완료 | 자연어·숫자 정렬, locale 검색, 목록·작은/큰 격자, 2줄 제목 위계, 숨김 정책, 상위 폴더 |
| 파일 작업 UI | 부분 완료 | 생성, 이름 변경, 삭제 확인, SAF 업로드, 복사·이동 대상 선택; SMB 공유 간 copy/move와 Google Drive 폴더 copy는 backend가 명시적으로 거절 |
| 다운로드·미리보기·공유 | 완료 | 7일/512MB 원본 cache, 이미지 zoom/pan·slideshow·이전/다음·가로 reflection, audio/video 재생·seek·gesture·2.5초 control, PDF 페이지·zoom, TalkBack 대체 조작, 공통 공유, 실패 시 ACTION_VIEW fallback |
| 원격 이미지·영상 썸네일 | 소스 구현·Synology MOV 실기기 확인 | refresh/session 요청 수·예상/실제 byte 예산과 snapshot Flow·안전 reset, same-origin server thumbnail 우선(4MiB·JSON/error 상한), 실패 시 이미지는 bounded 원본·영상은 MediaDataSource sparse frame, 64KiB read-ahead block cache·동시 2개·파일당 64/세션 1,024 요청 hard cap·파일당 예상/실제 각 4MiB, 전체 영상 fallback 금지, 1024px downsample, 32MB memory/128MB disk LRU, 중복·negative cache; 빈 캐시 MOV 10개 57 requests/2.3MB 실측 |
| 원격 즐겨찾기 | 완료 | shelf, 파일·폴더 toggle, 연결 삭제 정리 |
| 웹 브라우저 | 완료 | 안전한 HTTP/HTTPS WebView, navigation, 즐겨찾기 패널·홈 지정·편집·삭제, 30분 session 보존·만료 정리·홈 복귀, cookie/UA 경계·512MB 제한·원자 download와 받은 파일/네트워크 위치 저장 |
| 받은 파일 | 완료 | 최대 50개 원자 수신, deep link/ACTION_VIEW import, 다중 선택·전체 공유·확인 삭제·NAS 순차 전송·부분 성공 요약 |
| 폰하드(WebHard) | 소스 구현 | 앱 전용 저장소와 HTTP API, 비밀번호, 원자 upload, 보안 헤더, background stop, 파일·폴더 혼합 queue/drag-drop, 목록·작게·포스터 보기, 가로 포스터 Cover Flow·배경 선택, 선택 폴더 재귀 다운로드, 이미지·영상 preview, 업로드 진행률, RFC1918 LAN·핫스팟·명시적 Tailscale/WireGuard 주소 노출 |
| Android DocumentsProvider | 소스 구현 | Factory의 8종 연결을 SAF 위치로 제공하고 원격 thumbnail·bounded cache를 사용; SFTP는 폴더 생성·이름 변경·삭제·이동, 나머지는 안전한 읽기 전용이며 SMB는 `/공유이름` root, cloud는 유효 token 필요 |
| 홈 화면 위젯 | 완료 | 연결 수·받은 파일 수·기본 연결 표시 |
| 슈퍼 썸네일 | 소스 구현 | 선택 connection+root 영속·Work 입력, WorkManager unique work, 표준 시작은 비종량 네트워크+충전+저배터리 보호, 명시적 제한 실행은 연결된 네트워크+저배터리 보호, dot-hidden 제외 BFS 재귀, 10,000항목/24깊이/256MiB 예산, signature 기반 session report/resume, `.NasFinder-Vault` SHA-256 JPEG·UUID staging→검증 rename·3회 재시도·검증/부분 삭제 보고, cache miss Vault 복원, iOS 호환 `.workers-v1` 90초 worker heartbeat·180초 token lease·peer 결과 복원·선점 항목 즉시 게시, API 29+ thermal pacing/retry(API 26–28 500ms pacing), 진행·취소·부분 실패 |

`SuperThumbnailDataController`는 location별 report/재개 여부, root 검증된 enqueue, NAS Vault 삭제를 UI에 제공한다. Vault 삭제 직후 낙관적인 `UPLOADED` 상태를 먼저 `PENDING_UPLOAD`로 내리고, 부분 실패이면 NAS를 다시 조회해 실제 남은 항목만 `UPLOADED`로 복원한다. 연결 삭제는 해당 connection의 모든 root session을 함께 제거한다.

## 검증 경계

- JVM 회귀 범위는 모델·URL·경로·파일명·이름 우선순위, Synology credential/OTP·multipart SID query, HTTP Range temp 재개와 bounded Content-Range·Range 무시 정책, SFTP credential 분류·암호화 키 제한, SMB·FTP 동일 서버 경로 transfer 정책, Inbox, WebHard API·PhoneHard 브라우저 기능 표식, CloudDrive pagination/upload 및 provider별 copy/move·bounded Range 요청 계약·OneDrive monitor token 경계·Google 폴더 copy/native 문서 제한, WebDAV mutation, thumbnail key/LRU·traffic budget·sparse video seek/요청/실제 byte 상한·64KiB overlapping read 병합·영상 전체 원본 차단 정책, Super Thumbnail 표준/제한 실행 조건·session 영속/재개·Vault filename/staging/검증/root 경계/부분 삭제와 worker 만료·lease 선점/회수/token release·thermal pacing/retry 계약, widget·entry route 계약을 포함한다.
- `testDebugUnitTest` 169개는 failure/error 0으로 통과했고 `lintDebug`·`assembleDebug`로 Android 37 APK·manifest·resource 결합을 확인했다.
- Samsung SM-F968N(Android 16, API 36)에 최종 debug APK를 설치해 cold/warm launch, Dashboard, Inbox·WebHard·Browser deep link, 8종 연결 폼, SFTP 인증 방식, Synology OTP, 설정·Super Thumbnail 빈 상태, WebHard LAN HTTP·풍부한 브라우저 UI·background 자동 정지, 런처 아이콘 전환·복원, 가로 PhoneHard Cover Flow와 Back 동작을 확인했다. 세부 결과는 `DEVICE_TEST_REPORT.md`에 남겼다.
- 실제 NAS·SFTP·SMB·FTP·WebDAV 및 Dropbox/OneDrive/Google 계정은 자격 증명을 저장소에 넣지 않는 별도 E2E가 필요하다.
- `MediaMetadataRetriever`의 원격 `MediaDataSource` sparse frame 추출은 순수 range adapter 계약만 JVM 검증 대상이며, 코덱별 seek 패턴과 프레임 생성 성공 여부는 실제 Android 기기·실제 서버에서 아직 확인하지 않았다. 실패 시 전체 영상을 받지 않고 negative cache로 종료한다.
- 실기기 설치는 이번 실사용 테스트 범위에서 수행했다. 커밋, 푸시, 배포는 수행하지 않았다.
