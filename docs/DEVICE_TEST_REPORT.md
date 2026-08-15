# Android 실기기 테스트 보고서

## 환경

- 일자: 2026-08-15
- 기기: Samsung SM-F968N
- OS: Android 16, API 36
- 앱: NasFinder 1.0 (`versionCode 1`, `targetSdk 37`)
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 크기: 31,823,290 bytes
- SHA-256: `df8659fedaee4e79d76b56ff5f88b85b565ababf9a9dc5bb3549dafc8546d28c`

## 확인한 항목

| 항목 | 결과 |
|---|---|
| debug APK 설치·cold/warm launch | 통과 |
| Dashboard 빈 상태·dark system theme | 통과; 검정 문자 대비 문제를 발견해 테마 그라데이션과 EmptyState 색을 수정한 후 재확인 |
| `nasfinder://inbox` | Inbox 진입 통과 |
| `nasfinder://webhard` | WebHard 진입 통과 |
| `nasfinder://browser` | 내장 WebView 진입·Google 표시 통과 |
| 8종 원격 연결 폼 | Synology, SFTP, SMB, WebDAV, FTP, Dropbox, OneDrive, Google Drive 노출 확인 |
| SFTP 인증 UI | 비밀번호/개인키 선택과 key 안내 확인 |
| Synology OTP UI | 6–8자리 선택 OTP와 비저장 안내 확인 |
| 설정 | 5개 테마, cache 사용량, Super Thumbnail 빈 연결 상태 확인 |
| WebHard HTTP | 폰에 표시된 사설 LAN 주소로 Mac에서 list API 접속 통과 |
| WebHard 브라우저 UI | 파일·폴더 queue, folder picker, drag/drop, 3종 보기, preview·진행률을 포함한 최신 HTML 응답과 빈 list API 확인 |
| WebHard background 정책 | 앱 background 전환 후 listener 자동 정지 확인 |
| WebHard 주소 필터 | `192.0.0.4`는 표시되지 않음; RFC1918 LAN과 명시적 Tailscale/WireGuard interface만 허용 |
| 가로 PhoneHard 포스터 | 서버 실행+포스터 선택 후 전체 화면 Cover Flow 진입, 빈 상태·닫기·경로·배경 overflow 접근성 노출 확인 |
| iPhone 실제 화면 26장 재대조 | Dashboard 그룹, 설정 테마·아이콘, Browser 3종 보기·정렬 bottom sheet, 세로 포스터와 가로 Cover Flow 구조 반영 |
| Synology 실제 목록 | 테스트 NAS의 영상 폴더에서 32개 MOV 목록·크기·수정일 확인 |
| Synology MOV 썸네일 | 서버 미리보기 부재 시 bounded Range frame fallback으로 세로 list/poster와 가로 Cover Flow에 실제 영상 프레임 표시 확인 |
| Range 요청 병합 | 빈 썸네일 캐시에서 화면 내 MOV 10개 생성 시 57 requests, 예상 7.8 MB, 실제 2.3 MB 확인. 수정 전 1,075회 이상 반복 요청 재현 후 앱 중지·block cache 적용 |
| 썸네일 캐시 보존 | 실기기 검증 전 기존 cache를 별도 이름으로 이동하고, 테스트 생성 cache만 제거한 뒤 기존 cache 원위치 복원 확인 |
| 시스템 Back | WebHard에서 앱을 종료하지 않고 Dashboard로 복귀 확인 |
| 런처 아이콘 | Vibe 선택 시 Digital Rain alias 단독 활성, System 복원 시 기본 alias 단독 활성 확인 |
| Super Thumbnail 전용 화면 | Dashboard에서 진입, iPhone 대응 setup/action/storage 카드·빈 연결 상태·Back 복귀 확인 |
| 최신 iPhone Browser back | 32dp 원형·48dp 터치 영역과 `이전 폴더` 접근성 라벨 확인; 650ms 실기기 입력으로 500ms long-press Dashboard 직행 통과 |
| 최신 Super Thumbnail 폴더 선택 | 폴더 행과 하단 안내 어느 쪽을 눌러도 NAS 선택 화면 진입 확인 |
| Super Thumbnail 보관 시점 | iPhone과 같은 trailing 메뉴로 `즉시`·`나중에`를 선택하도록 맞춤 |
| 파일 작업 붙여넣기 | iPhone과 동일하게 원격 복사·이동 항목이 있으면 현재 폴더에 적용하고, 비어 있으면 Android 파일 선택기를 여는 이중 동작 확인 |
| 썸네일 캐시 화면 | 실제 19.4 MB·32개 표시, 128/256/512 MB 자동 정리 기준, 30일·최대 5,000개 정책, 확인 후 캐시만 삭제하는 전용 화면 확인 |
| AndroidRuntime fatal log | 테스트 후 발견되지 않음 |

## 로컬 회귀 검증

- `testDebugUnitTest`: 최신 소스 158개, failure/error 0
- `assembleDebug`, `assembleRelease`: 성공
- release APK는 정식 signing key가 없어 의도된 unsigned 산출물이며 공개·배포하지 않음
- 최종 debug APK 재설치: `adb install -r` 성공

## WebHard 외부 접속 경계

- Wi-Fi LAN은 `10/8`, `172.16/12`, `192.168/16`을 지원한다. Android 핫스팟에 상대 기기를 연결해도 같은 사설 LAN 방식으로 접속할 수 있다.
- 일반 셀룰러 회선은 통신사 CGNAT·인바운드 firewall 때문에 인터넷에서 폰으로 직접 접속하는 것을 보장할 수 없다.
- 원격 접속은 폰과 상대 기기가 같은 Tailscale/WireGuard 네트워크에 있을 때 해당 VPN IPv4 주소를 표시하는 방식으로 지원한다. 일반 셀룰러/CLAT interface의 주소를 공개 주소처럼 잘못 안내하지 않는다.

## 남은 실사용 테스트

실제 Synology NAS는 목록과 MOV 썸네일 Range 경로까지 확인했다. Synology의 업로드·이동·복사·삭제, FTP/SFTP/SMB/WebDAV 서버와 cloud OAuth 계정은 실기기 E2E로 확인하지 않았다. 이 항목들은 소스·JVM 계약 테스트는 통과했지만 실제 서버 호환성 검증이 남아 있다.
