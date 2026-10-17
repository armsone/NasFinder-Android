# 편집기 모드 UX 개정 (Android 2.13.4)

- 모델: Claude Sonnet 5.5 (claude-sonnet-5-5), Gemini 쿼터 소진으로 인계
- 작성 시각: 2026-10-17 16:49:52 KST (date 실제값)
- 상태: 소스 수정만 완료. Gradle/ADB/테스트 미실행.

## 변경
- NasFinderApp.kt: 홈 "영상 만들기"가 선택 다이얼로그 없이 바로 미디어 선택(LocalPhotoLibrary, TRAILER)으로 이동. 선택 다이얼로그 제거.
- LocalPhotoLibraryScreen.kt: 하단 "예고편 제작"/"뮤직비디오"를 단일 "영상 편집"으로 교체(기본 TRAILER 시작). 명시적 MUSIC 경로는 기존대로 뮤직비디오로 시작. 복사/미리보기/달력/선택 동작 유지.
- VideoTrailerScreen.kt: 설정 시트 최상단에 "예고편 | 뮤직비디오" 선택기. 같은 편집기에서 전환, 추가 화면/다이얼로그 없음. 작업(busy/내보내기/분석) 중 선택기와 만들기 버튼 비활성. 전환 완료 시 계획값(길이/총길이/소스)만 세션에서 다시 동기화.
- NasFinderViewModel.kt: switchTrailerMode() 추가. 순서/선택 소스, BGM, 정책, 자동 더킹 유지. 파생 출력(output)만 비움. 음악->예고편 시 음악 모드가 건너뛴 영상 하이라이트(AudioAnalysisService)와 사진 비교(analyzeTrailerPhotos)를 실제로 실행한 뒤 전환. 토큰 및 디렉터리/상태 검사로 오래된 비동기 결과 무시. 분석 실패 시 모드 변경 없이 메시지만 표시.
- TrailerPlan.kt: TrailerSession.highlightAnalyzed 추가(음악 시작/재개 음악 초안은 false).
- build.gradle.kts: 2.13.4 / 416667 / BUILD_NUMBER 202610171649.

## 유지된 동작
- "보관한 편집" 이름, 초안 저장/재개의 모드와 소스 저장, 초안 개수 제한/마이그레이션/삭제 변경 없음. 전환만으로 초안을 저장하지 않음(닫을 때 기존 경로로만 저장).
- 원본 파일 변경/삭제 없음, 오디오/더킹 알고리즘 미변경. ledger.json과 Apple 저장소 미수정.

## 남은 위험 / 미수행
- 컴파일 미검증(문법/시그니처/중괄호/스코프는 직접 점검). 호스트 빌드/설치는 Apple 빌드 후 TM 배정.
- 전환 후 재생성한 결과가 아직 없으면 미리보기 화면이 아닌 준비 화면 뒤 시트가 보임.
- 뮤직 초안 재개 후 예고편 전환 시 분석을 다시 수행(이미 분석된 경우도 중복 가능).
