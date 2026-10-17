# Android 2.15.0 인라인 Google 포토 / 파일 페이지

- 계약: NasFinder/.sync/product-contract.yaml `editing.unified_media_selector` Android platform_adaptations 먼저 갱신.
- 의존성: androidx.photopicker:photopicker-compose:1.0.0-alpha02 (프로덕션만, testing 아님). alpha라 API 변경 가능.
- Google: `InlineSourcePages.kt` — API34+ & SDK extension 15 가드 후 EmbeddedPhotoPicker 인라인. 미지원/세션 오류에서만 명시 버튼으로 기존 시스템 선택기. grant→바스켓 끝 추가, revoke→canonical 정체성이 같은 항목 제거, 트레이 X→deselectUris 동기화. 페이지가 settled일 때만 세션 생성(오프스크린 세션 없음).
- 파일: MediaStore 이미지/비디오 버킷 폴더 격자(상위 폴더 이동, 뒤로가기는 폴더→사진 탭 순). 새 권한 없음. 보조 '다른 저장소에서 가져오기'는 기존 SAF.
- 트레이 X 터치 영역 48dp. 원본 삭제/전역 revoke 없음.
- 빌드: assembleDebug 성공, 2.15.0 / 416671 설치. 실제 UI 동작 미확인.

## 보정 패스 (2.15.0 / 416671 유지)
- LocalPhotoLibraryScreen.kt onRevoked: 원본 URI 문자열이 아니라 canonicalKeyFor 기준 정체성으로 해제 항목만 제거(생존 항목 순서 유지, 클라우드 opaque URI는 정확 일치).
- InlineSourcePages.kt DeviceFolderBrowserPage: runCatching/getOrDefault(emptyList()) 제거. CancellationException 재던짐, 그 외 실패는 오류 화면(한국어 안내 + 다시 시도 + 다른 저장소에서 가져오기)으로 표시. 정상 빈 목록/권한 없음과 구분, 자동 재시도 없음, 담은 항목·소스·폴더 선택 유지.
- 빌드: ./gradlew assembleDebug --console=plain 직접 exit code task_build_exit=0
- APK sha256: df6205b866e49ce1eabd9cee9da9d228d0cc071c5ee8eda2228fd705e3ef6c43
- 설치: 192.168.0.253:39513 SM_F968N, 활성 서비스 없음 확인 후 adb install -r 성공, 런처 재실행. UI 검증은 TM(CUA) 몫.

## TM 설치·화면 확인 (2026-10-17 18:23 KST)
- 수정본 2.15.0 / versionCode 416671: 빌드 APK와 실기기 base.apk SHA256 일치 (`df6205b866e49ce1eabd9cee9da9d228d0cc071c5ee8eda2228fd705e3ef6c43`). 데이터 유지 교체 설치·실행 확인.
- CUA로 영상 만들기 → Google 포토 탭: 별도 Activity로 전환하지 않고 앱 상단/소스 탭과 하단 공통 바구니 사이에 Google 포토 검색·사진·컬렉션·실제 썸네일 격자가 표시됨. 로딩 후 정상 표시, 화면 영역 겹침 없음.
- 같은 화면에서 파일 탭으로 이동: Camera/Meta AI 폴더의 커버 썸네일·개수 표시, 상단·바구니 유지 확인. 파일 탭의 기본 대상은 권한이 있는 사진·동영상의 기기 폴더이며, 추가 문서 공급자는 보조 시스템 선택 화면으로 접근.
- 이번 화면 확인은 0개 선택 상태. 여러 소스 혼합 선택·트레이 X·복사·영상 내보내기의 전체 실동작은 확인하지 않았음. 원본 변경·내보내기·새 초안 생성 없음. 테스트 러너/계측 실행 없음.
- 현재 실기기는 API37 / SDK extension 22. 미지원 기기 또는 세션 오류에서는 명시적인 시스템 선택 화면 버튼을 제공하며 자동으로 외부 화면을 열지 않음.
- 공식 근거: https://developer.android.com/training/data-storage/shared/photo-picker/embedded ; https://developer.android.com/jetpack/androidx/releases/photopicker . Compose SDK alpha02 사용 사실과 지원 조건은 구현에 반영. Apple 앱은 이번 Android 수정 범위에서 변경·빌드하지 않음.
