# 소스 페이지 + 공유 바스켓 트레이 2.14.3 (code 416670)
- 영상 만들기: 사진/달력/폰하드/즐겨찾기/NAS·네트워크/파일/Google 포토를 HorizontalPager + ScrollableTabRow 페이지로 제공. + 메뉴 제거.
- 폰하드 빈 목록 원인: 담기 후보를 매니페스트 기반 inboxFiles()에서 구했음 → 실제 등록 디렉터리(inboxStore.listPhoneHardFolder) 탐색으로 교체.
- 즐겨찾기: 기존 loadFavoriteThumbnail 재사용. 폴더는 폰하드 페이지/기존 선택 브라우저로 이동.
- 파일/Google 포토: 안내 페이지 + 사용자가 누를 때만 시스템 선택기 실행.
- 하단 BasketTray: 기존 selected(순서 리스트)를 그대로 사용, X는 바스켓에서만 제거. 푸터 개수/복사/편집 동일 리스트.
- 페이지 내 체크(폰하드/즐겨찾기)는 "선택한 항목 담기" 전까지 바스켓과 분리.

## 보정 패스 (2026-10-17 18:04 KST, 동일 2.14.3 / 416670)
- `selectedUris()`를 `selected.map(Uri::parse)`로 변경: 복사/편집기 콜백이 하단 트레이와 동일한 선택 순서를 사용(재정렬 없음).
- `chosenPhoneHard`/`chosenFavorites`를 `rememberSaveable`+listSaver로 변경(순서 유지, 커밋 전 선택이 구성 변경/NAS 이동 후 보존되도록).
- 빌드 `assembleDebug` exit 0, `adb install -r` Success, 재실행 PID 20137, APK sha256 73cd790f…c758.
- 미검증: 실제 NAS 왕복/구성 변경 런타임, 제거 후 재추가 순서의 UI 확인(TM 몫).

## 탈출/복귀 보정 (2026-10-17, 동일 2.14.3 / 416670)
- 증명된 원인: 시스템 뒤로가기(BackHandler)가 파일/Google 등 비-사진 탭에서 곧바로 onBack=Dashboard 로 갔음(탭 이탈 경로 없음).
- 수정: 비-사진 탭에서 뒤로 → 사진 탭 복귀, 사진 탭에서만 화면 종료. 파일/Google 페이지에 "사진 탭으로 돌아가기" 버튼.
- Google "첫화면": 런타임 미재현. 가설=외부 선택기 중 프로세스 종료 시 화면이 Dashboard로 초기화. 방어: NasFinderApp이 소스 화면(rememberSaveable)을 기억해 재시작 시 LocalPhotoLibrary 복원.
- OS 한계: 시스템 DocumentsUI 자체에는 앱이 닫기 버튼을 넣을 수 없음(제스처 뒤로가기/앱 전환만).

## TM 통합 관찰과 한계 (18:10 KST)
- 현재 요청 범위Android만수정. Apple앱2.14.2미변경; 앱Git커밋/푸시/릴리즈없음. 사전기존dirty변경전부보존.
- 최초전체화면Dialog구현과첫읽기작업은 사용자 추가방향(가로페이지/공통썸네일트레이)을 반영하려고 CLI98600/99388만정확히SIGINT중단한것이며한도/도구장애아님. 그부분소스재사용후완성. 전체이번변경파일은LocalPhotoLibraryScreen/NasFinderViewModel/NasFinderApp/build.gradle및이문서/계약Android구간. '최종CLI가App/ViewModel미수정'은앞선부분구현을제외한다는의미.
- 외부조사비적용(로컬기존구현수정). 같은대화Gemini429한도확인으로Claude Sonnet5.5low재사용; 단일가용후보/기존논리업무자문재사용. 새 테스트/계측/린트없음. 문법/컴파일/필수범위는구현자의자체점검과정상빌드로확인.
- TM 실제화면:18:01~18:02 사진/달력/폰하드/즐겨찾기/NAS등출처탭과공유하단빈바구니/공통복사·영상편집버튼확인. 사진/달력페이지표시확인. 썸네일이담긴트레이및즐겨찾기실제파일가져오기는아직직접확인하지못함.
- 사용자실제오류18:05:Files시스템선택창에서닫기경로불명확,Google포토선택시첫화면. TM18:07 파일선택기최근목록(선택0)에서정상AndroidBack(CUA미러링키보드)으로파일탭복귀를직접확인. 파일선택/가져오기/복사/내보내기는실행안함.
- Google소스페이지→Google포토검색/사진/컬렉션을표시하는실제시스템선택기진입은18:09직접확인. 이때취소후화면추적이동시재설치와겹쳐복귀버그재현/해결증거로쓰지않음.
- BackHandler의모든탭Dashboard복귀는코드상확인되어비사진탭→사진탭으로수정. 외부선택기후재생성으로Dashboard초기화되는것은가능한가설이며원인확정아님. rememberSaveable화면경로복원보강. 실제Google왕복결과는사용자에게18:10비동기재확인요청.
- 마지막빌드assembleDebug exit0/install-r Success. 설치APK와빌드APK SHA256둘다a8c0f142130b7137932216df71ba65e36f4c2b0ee8d1520d105e5e9da8aaa854. 설치versionName2.14.3/code416670직접확인. PID26470은구현자실행관찰값.
- 일반scrcpyCLI는CUA앱선택불가여서기존설치scrcpy를실행하는태스크전용임시.app메타데이터래퍼로정상CUA화면관찰경로복구. 새소프트웨어설치/권한/클립보드동기화없음. 모든UI입력CUA키보드만사용,ADBinput/UIAutomator/osascript미사용. 회전/태블릿/GoogleTV/픽셀비교미확인. 앱포커스변경으로CUA행동거부가나면재조회후범위내행동만수행.
- 네이티브파일창에는앱내버튼을삽입할수없으므로닫기추가완료로보고하지않음. 앱파일/Google페이지에는사진탭복귀버튼추가. 실제NAS왕복/혼합출처복사·영상내보내기/회전시보존/제거후재추가의실제입출력은미확인.

## 사용자 후속 확인 및 새 범위 (18:12~18:14 KST)
- 사용자는18:12 Google포토진입이현재는된다고직접확인. 직전질문응답의첫홈화면은취소가아니라진입실패같았다는정정이며, 이후현재정상이라는최신답변을우선한다. 원인을역으로확정하지않음.
- 이어서Files/Google도별도화면없이같은연속성원함. Android공식EmbeddedPhotoPicker신규지원조사후2.15.0별도inline범위진행. 현재phoneAPI37/SDKextu22, projectAGP9.3/compile37는공식최소API34/ext15/AGP9.2조건충족. Google보안표면직접내장, Files기기미디어폴더inline로별도작업.
