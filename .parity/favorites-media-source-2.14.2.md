# 영상 만들기 + 메뉴 즐겨찾기 (Android, 2.14.2 / 416669)

- 담당: Claude Sonnet 5.5. 수정 파일: ui/LocalPhotoLibraryScreen.kt(폰하드와 NAS·네트워크 사이 "즐겨찾기" 메뉴와 선택 대화상자, 가져오는 중 대화상자), ui/NasFinderViewModel.kt(beginNetworkMediaPick 시작 경로 인자, addFavoriteFileToBasket, stageMedia 공통화), app/build.gradle.kts(버전).
- 동작: 기존 remoteFavorites 재사용. 폴더는 기존 네트워크 미디어 선택 브라우저를 즐겨찾기 경로에서 열고, 네트워크 파일은 FavoriteItemResolver로 현재 항목을 확인한 뒤 기존 staging(연결+경로 식별 캐시)으로 바스켓에 추가. 폰하드 폴더는 해당 폴더의 미디어 선택 대화상자, 파일은 기존 appendPickedUris. 비미디어는 비활성+안내, 연결 없음/이동·삭제는 메시지(성공 표시 없음), 즐겨찾기가 없으면 안내 문구. 브라우저 URL 즐겨찾기는 제외.
- 보존: 원본·즐겨찾기 저장소 변경 없음. 실패·취소 시 기존 바스켓 유지, 교차 제공자 해시 없음.
- 빌드/설치: ./gradlew assembleDebug exit 0, 192.168.0.253:39513 install -r Success(데이터 유지), EnamelLauncherAlias 실행, versionName 2.14.2/versionCode 416669 확인.
- 미확인: 실제 + 메뉴 화면, 즐겨찾기 실사용 추가, 폰하드 즐겨찾기 폴더 경로 접두 일치 동작, 이동·삭제 항목.
