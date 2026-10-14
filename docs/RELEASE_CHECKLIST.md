# Android 정식 Release 체크리스트

이 문서는 다른 armsone Android 앱과 같은 GitHub 직접 설치 업데이트 계보를 관리한다. 공개 APK는 이 Mac의 장기 보관 Android signing keystore를 사용하는 `releaseQa`로 만들며, 저장소와 Release에는 key나 비밀번호를 넣지 않는다. 일반 `release`는 향후 별도 스토어 서명 계보를 위한 unsigned build다.

## 버전과 서명

1. `app/build.gradle.kts`의 `versionCode`를 이전 공개본보다 큰 정수로, `versionName`을 사용자 표시 버전으로 갱신한다.
2. `assembleReleaseQa`로 기존 armsone Android 앱과 같은 이 Mac의 signing key를 사용한다. key 파일은 저장소 밖에서만 보관한다.
3. 서명된 APK 이름은 `NasFinder-Android-{versionName}.apk`로 고정한다.
4. PATH에 Android SDK의 `aapt`, `apksigner`가 있는 검증 환경에서 다음을 실행한다.

```sh
chmod +x scripts/verify-release-apk.sh
NASFINDER_EXPECTED_CERT_SHA256=<보관한 인증서 SHA-256> \
  scripts/verify-release-apk.sh /absolute/path/NasFinder-Android-0.1.0.apk
```

검증기는 package가 `com.armsone.nasfinder`인지, 파일명과 `versionCode`가 일치하는지, debuggable이 아닌지, APK 서명이 유효한지, 지정한 업데이트 인증서와 정확히 같은지 확인하고 SHA-256을 출력한다.

## 공개 전 확인

- 관련 unit test와 `assembleReleaseQa`를 의미 있는 후보에서 한 번 실행한다.
- 직접 설치 업데이트 인증서 SHA-256은 `837bd274f558659a3aec9bd31308b8ac01916c386230f946f5f7347d7f6f9b0f`로 고정한다.
- signing keystore는 저장소 밖 원본과 iCloud Drive 암호화 사본으로 이중 보관하며, 복구 암호는 macOS Keychain에만 둔다.
- 실제 계정·비밀번호·OAuth token·keystore·비밀번호 파일이 APK 외 자산이나 로그에 포함되지 않았는지 확인한다.
- GitHub tag는 `android-v{versionName}`, 설치 파일은 위 APK 하나로 맞추고 배포 정보 `release.json`을 함께 게시하며 본문에 `Android-Version-Code`와 `Build-Number`를 기록한다. draft·prerelease를 정식 업데이트로 사용하지 않는다.
- 공개는 별도 승인과 유효한 GitHub 인증이 있는 경우에만 수행한다. 소스 빌드 성공은 서명·공개 성공을 뜻하지 않는다.

태그는 `android-v{versionName}`, asset은 `NasFinder-Android-{versionName}.apk`다. 검증한 로컬 APK 하나와 그 파일로 생성한 `release.json`을 같은 초안에 올린 뒤 공개한다. 이후 앱은 제품 버전 태그와 본문의 더 높은 내부 코드를 모두 확인한 안정 Release만 안내한다.


## 홈페이지 배포 정보 자동 연결

이 제품의 홈페이지 등록 key: `NasFinder-Android`. 기존 제품의 단순 버전업에서는 홈페이지 소스의 버전·빌드·다운로드·예비 주소를 수정하지 않습니다.

최종 서명·패키징 파일과 실제 제품 버전으로 `release.json`을 만들고 GitHub 릴리스 자산에 함께 게시합니다. 빌드·Android 내부 코드는 현재 빌드의 확인된 값만 넣습니다. 생성 도구는 파일명·크기·SHA-256을 기록하며 기존 앱 서명·업데이터·appcast 검증을 대체하지 않습니다. 파일과 배포 정보를 준비한 뒤 릴리스를 공개하고, 홈페이지의 표시 버전과 실제 다운로드가 일치하는지 확인합니다.

공통 절차와 스키마는 [홈페이지 다운로드 자동화 안내](https://github.com/armsone/Hanstree.com/blob/main/docs/automatic-downloads.md)를 따릅니다. 로컬 도구는 홈페이지 체크아웃의 `build/release-metadata.mjs`입니다.

```sh
node ../NasFinder.com/build/release-metadata.mjs \
  --version "$PRODUCT_VERSION" --artifact "NasFinder-Android=$FINAL_PACKAGE" \
  --output "$RELEASE_DIRECTORY/release.json"
```

명령은 프로젝트 루트에서 실행하고, 위 변수는 실제 빌드의 값과 경로로 설정합니다. 확인된 빌드가 있으면 `--build`, Android 내부 코드가 있으면 `--version-code`를 추가합니다. 기존 `gh release create` 또는 초안 업로드 명령에 생성한 `release.json`도 포함합니다.

새 제품·플랫폼·파일명 규칙 등록, 기능 설명·이미지·지원 정책·개인정보 안내의 실제 변경이 있을 때만 홈페이지를 별도로 수정합니다. TestFlight는 기존 심사·공개 확인 절차를 유지합니다.
