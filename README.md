# NasFinder for Android

NasFinder iPhone 앱의 기능·화면·플랫폼 계약을 Android 네이티브 앱으로 옮기는 프로젝트입니다. 원본 iOS 저장소는 읽기 전용 기준 자료로만 사용합니다.

## 현재 실행 가능한 범위

- Jetpack Compose 기반 iPhone 대응 대시보드·브라우저·미리보기·WebHard와 5개 테마
- Synology, SFTP, SMB, WebDAV, FTP, Dropbox, OneDrive, Google Drive 연결
- 연결 종류별 탐색·전송·파일 작업과 Android Keystore 기반 자격 증명 보관
- 목록·작은/큰 격자·Cover Flow, 정렬·검색·즐겨찾기·원격 썸네일
- Android 공유 메뉴, 받은 파일함, Files/DocumentsProvider, 위젯·바로가기·빠른 설정
- Super Thumbnail의 제한 실행·재개·보고서와 선택적 NAS Vault 보관
- 사용자가 제공한 Blue·Cyber Vault·Vibe Coder·Purple·네트워크 NAS 런처 아이콘 선택
- 공식 GitHub Release만 확인하는 선택적 업데이트 안내

프로토콜별 고급 기능과 Android 플랫폼 통합의 정확한 완료 상태는 기술서 체크리스트를 기준으로 추적합니다. 미구현 항목을 지원되는 것처럼 표시하지 않습니다.

## 기준 기술서

- `docs/IOS_FUNCTIONAL_SPEC.md`: 원격 서비스, 브라우저, 캐시, 미리보기, WebHard 전체 동작
- `docs/IPHONE_DESIGN_PARITY.md`: 화면·테마·수치·상태·제스처와 Compose 대응
- `docs/IOS_PLATFORM_AND_TEST_SPEC.md`: Share, File Provider, Document Picker, Widget과 iOS 테스트 241개의 Android 대응

## 빌드와 테스트

Android SDK 37과 JDK 17 이상이 필요합니다.

```sh
./gradlew testDebugUnitTest assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 실제 NAS/SFTP/SMB/FTP 및 OAuth 계정 E2E는 자격 증명을 저장소에 넣지 않고 별도로 수행해야 합니다.

정식 배포 APK는 최초 릴리스에서 확정한 동일 서명키를 계속 사용해야 합니다. 태그·파일명·검증 계약은 `docs/GITHUB_UPDATES.md`에 기록되어 있으며 서명키와 비밀번호는 저장소에 넣지 않습니다.
서명키 없이 안전하게 준비할 수 있는 빌드·APK 검증 절차는 `docs/RELEASE_CHECKLIST.md`와 `scripts/verify-release-apk.sh`를 따릅니다.
