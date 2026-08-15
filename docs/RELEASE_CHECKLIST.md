# Android 정식 Release 체크리스트

이 문서는 다른 armsone Android 앱과 같은 GitHub 직접 설치 업데이트 계보를 관리한다. 공개 APK는 이 Mac의 장기 보관 Android signing keystore를 사용하는 `releaseQa`로 만들며, 저장소와 Release에는 key나 비밀번호를 넣지 않는다. 일반 `release`는 향후 별도 스토어 서명 계보를 위한 unsigned build다.

## 버전과 서명

1. `app/build.gradle.kts`의 `versionCode`를 이전 공개본보다 큰 정수로, `versionName`을 사용자 표시 버전으로 갱신한다.
2. `assembleReleaseQa`로 기존 armsone Android 앱과 같은 이 Mac의 signing key를 사용한다. key 파일은 저장소 밖에서만 보관한다.
3. 서명된 APK 이름은 `NasFinder-Android-v{versionCode}.apk`로 고정한다.
4. PATH에 Android SDK의 `aapt`, `apksigner`가 있는 검증 환경에서 다음을 실행한다.

```sh
chmod +x scripts/verify-release-apk.sh
NASFINDER_EXPECTED_CERT_SHA256=<보관한 인증서 SHA-256> \
  scripts/verify-release-apk.sh /absolute/path/NasFinder-Android-v1.apk
```

검증기는 package가 `com.armsone.nasfinder`인지, 파일명과 `versionCode`가 일치하는지, debuggable이 아닌지, APK 서명이 유효한지, 지정한 업데이트 인증서와 정확히 같은지 확인하고 SHA-256을 출력한다.

## 공개 전 확인

- 관련 unit test와 `assembleReleaseQa`를 의미 있는 후보에서 한 번 실행한다.
- 직접 설치 업데이트 인증서 SHA-256은 `837bd274f558659a3aec9bd31308b8ac01916c386230f946f5f7347d7f6f9b0f`로 고정한다.
- signing keystore는 저장소 밖 원본과 iCloud Drive 암호화 사본으로 이중 보관하며, 복구 암호는 macOS Keychain에만 둔다.
- 실제 계정·비밀번호·OAuth token·keystore·비밀번호 파일이 APK 외 자산이나 로그에 포함되지 않았는지 확인한다.
- GitHub tag는 `android-v{versionCode}`, asset은 위 APK 하나로 맞춘다. draft·prerelease를 정식 업데이트로 사용하지 않는다.
- 공개는 별도 승인과 유효한 GitHub 인증이 있는 경우에만 수행한다. 소스 빌드 성공은 서명·공개 성공을 뜻하지 않는다.

태그는 `android-v{versionCode}`, asset은 `NasFinder-Android-v{versionCode}.apk`다. 검증한 로컬 APK 하나만 `gh release create`로 게시한다. 이후 앱은 GitHub API에서 더 높은 versionCode의 안정 Release만 안내한다.
