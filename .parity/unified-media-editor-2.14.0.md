# 통합 미디어 편집기 (Android 2.14.0)

- 모델: Claude Sonnet 5.5 (claude-sonnet-5-5), Gemini 쿼터 소진으로 인계
- 작성 시각: 2026-10-17 17:03 KST (date 실제값 기준, 빌드 번호 202610171700)
- 상태: 소스 수정만 완료. Gradle/ADB/테스트/컴파일 미실행 (중괄호·괄호 균형만 직접 확인).

## 흐름
홈 영상 만들기 -> 단일 미디어 바구니(LocalPhotoLibraryScreen, TRAILER) -> 단일 "영상 편집" 푸터 -> 편집 설정 최상단 "제작 방식" 예고편/뮤직비디오 선택. 홈 선택 팝업, 별도 예고편/뮤직 푸터 없음.

## 우상단 + 메뉴 (영상 만들기/뮤직 의도일 때; 일반 "내 사진·영상"은 기존 "파일에서 추가" 유지)
- 기기 사진·영상: 기존 사진 탭으로 이동
- 파일에서 추가: 기존 OpenMultipleDocuments (documentPicker)
- PhoneHard: 이미지/영상 목록 다이얼로그(체크박스) -> 파일 URI를 제자리 참조(재가져오기/복사 없음)
- NAS·네트워크: 설정된 모든 연결 선택 -> model.beginNetworkMediaPick -> 기존 Browser 화면(선택 목적 mediaPick) -> "영상 만들기에 N개 추가" -> model.stageRemoteMediaForBasket -> MediaPickDelivery -> 바구니 appendPickedUris
- Google 포토: 기존 시스템 사진 선택기(picker, 클라우드 제공자 포함)를 같은 바구니로 반환. 홈 Google 포토 단독 동작(requestGooglePhotosImport)은 변경 없음. googlePhotosVisible일 때만 표시.

## 네트워크 스테이징
- 선택한 파일만 cacheDir/media_basket/<uuid>/<원본이름>에 다운로드(폴더 전체/원본 이동·삭제/해시 중복제거 없음).
- 식별: connection.kind + connection.id + 원격 경로 -> 이미 받은 사본 재사용, 서로 다른 제공자/연결의 같은 이름은 별개 URI라 조용히 제거되지 않음.
- 진행/취소 다이얼로그(Browser), 일부 실패 시 성공분만 추가하고 "N개 추가, M개 실패" 안내. 취소는 이번 실행의 미완성 사본만 삭제하고 브라우저 유지, 기존 바구니 불변.
- 돌아가기/뒤로가기(루트)는 cancelNetworkMediaPick: 바구니·순서 유지. 바구니 선택 목록은 rememberSaveable로 화면 이동 간 보존.
- 혼합 출처 URI는 기존 startLocalMediaTrailer(file:// 포함)로 실제 편집/내보내기까지 전달. file URI 보강: 썸네일 폴백, 비디오 확장자 판별, 이름/크기/날짜, 촬영시각(lastModified) 폴백.

## 2.13.4 후속
- TrailerSettings scenes remember 키에 session.isMusicOverlayMode 추가.
- "제작 방식" 라벨 추가. 편집기 제목/조절 라벨 "영상 편집"으로 통일(결과/모드별 라벨·종류 배지 유지).
- 컨텍스트 메뉴 "예고편 보기"/"예고편 만들기" -> "영상 만들기", 선택 아이콘 설명 "선택 영상 편집". 뮤직비디오 바로가기는 유지.
- build.gradle.kts: 2.14.0 / 416667 / BUILD_NUMBER 202610171700.

## 수정 파일
NasFinderViewModel.kt, NasFinderApp.kt, LocalPhotoLibraryScreen.kt, VideoTrailerScreen.kt, app/build.gradle.kts, 이 문서. ledger.json/Apple/오디오·더킹 미수정.

## 남은 한계
- 컴파일/실기기 미검증. 네트워크 선택은 폴더 단위 이동 시 선택이 초기화되어 폴더마다 추가 후 + 메뉴를 다시 사용.
- media_basket 캐시 사본은 별도 정리 로직 없음(앱 캐시 삭제로 정리).
- Google 포토는 시스템 선택기 경로(인증 API 가져오기 경로는 PhoneHard로 복사하므로 사용하지 않음).
- 픽셀 단위 Apple 동일성 주장 없음.
