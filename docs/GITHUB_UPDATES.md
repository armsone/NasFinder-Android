# GitHub 선택적 업데이트

NasFinder Android는 공식 GitHub Release에 더 높은 `versionCode`가 있을 때만 사용자에게 선택적으로 안내한다. 확인만 자동이며 APK 다운로드와 Android 설치 화면은 사용자가 각각 눌러야 진행된다.

- API: `https://api.github.com/repos/armsone/NasFinder-Android/releases/latest`
- tag: `android-v{versionCode}`
- APK: `NasFinder-Android-v{versionCode}.apk`

정식 배포는 다른 armsone Android 앱과 동일하게 이 Mac의 장기 보관 signing key로 `releaseQa`를 만들고 검증한 뒤 `android-v{versionCode}` 안정 Release에 게시한다. Draft와 prerelease는 앱의 자동 업데이트 대상으로 사용하지 않는다.
- draft·prerelease·다른 host·다른 파일명·250 MiB 초과 자산은 거절한다.
- Release JSON은 1,000,000자에서 읽기를 중단한다. 응답 전체를 메모리에 읽은 뒤 크기만 검사하지 않는다.
- APK redirect는 최대 5회이며 HTTPS 표준 포트의 GitHub release CDN host만 허용한다. HTTP downgrade, user-info URL, 임의 포트와 유사 도메인은 거절한다.
- 다운로드 뒤 package name, 정확한 versionCode, 현재 설치본과 동일한 서명 인증서를 모두 확인해야 설치 화면을 연다.
- 다운로드 중 `.partial.apk`는 FileProvider에 노출되지 않는 `cache/update-temp`에 쓴다. 검증을 통과한 최종 APK만 `cache/updates`로 옮겨 설치 화면에 read grant한다.
- Release가 없거나 네트워크 확인이 실패하면 앱 사용을 막지 않는다.

매니페스트는 `REQUEST_INSTALL_PACKAGES`만 추가하며 broad storage permission은 사용하지 않는다. 공유 FileProvider는 exported가 아니고 `shares/`, 검증 완료 `updates/`, `SharedInbox/`만 노출한다. updater 임시 폴더, credential, 연결 설정, 일반 cache root는 노출하지 않는다.

배포자는 검증한 동일 서명 APK만 위 규칙으로 공개해야 한다. 단순 `git push`는 업데이트가 아니다. 서명키·비밀번호는 저장소나 Release에 넣지 않는다.
