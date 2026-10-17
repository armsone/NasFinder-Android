# 달력 선택 요약·빠른 동작 행

2026-10-17 01:00 KST 요청. 범위는 첨부한 달력 선택 요약 행과 전날/오늘/해제 세 버튼이다. 전체 화면/전체 제품 parity 감사가 아니다. 기존 허용된 연속 교체 설치를 유지하고 테스트 러너·Git·배포는 수행하지 않는다.

원본 Android780×142/iPhone776×72 PNG를 보존하고 manifest에SHA256기록. 모두사용자크롭이며전체앱경계/밀도는미확인. 위계·한줄/두줄·버튼간격문제는명확하나정확한논리크기·색은이미지만으로판정하지않는다. 숫자1일5개/8일30개는기기실제선택차이(Content/state)이고교정대상이아니다. 기존서로다른테마는보존하며변경범위는행구조/크기/간격이다.

|Route/state|Element|Dimension/action|iOS reference|Android before|Required action|Evidence/status|
|---|---|---|---|---|---|---|
|calendar populated|summary/action row|Visual placement|left counts/right actions single row|actions above counts, two rows|single row both|source applied, runtime pending|
|calendar populated|3 actions|Visual spacing|crowded adjacent text|wide full-width distribution|44×44 logical targets,8 gap;left/right12|source applied, runtime pending|
|calendar populated|previous/today/clear|Functional|existing selection handlers|existing handlers|retain data and disabled rules|source unchanged handlers;not full interaction matrix|
|calendar populated|counts/theme|Content/state|user8days30items/red|user1day5items/blue|retain realdata/theme|outside visual token matching scope|

구현: AppleCalendarView/AndroidCalendarScreen 및 제품 버전. 공통2.11.3/build202610170102(Android code412602). iPhone/Mac2.11.1→2.11.3,Android2.11.2→2.11.3. 원본/공통바구니기능보존.

## 01:06 KST 빌드·설치·화면 근거

- Apple iOS/Mac BUILD SUCCEEDED, Android BUILD SUCCESSFUL(1분30초). 세기기 데이터유지 교체 설치·재실행 완료, Android실제versionName2.11.3/code412602 확인, Mac제품2.11.3/서명확인. iPhone도설치/launch성공.
- CUA iPhone/Trifold 양쪽 달력에서 왼쪽선택요약/오른쪽세버튼한줄배치와버튼간격확인. 빈선택에서전날클릭→10월16일선택(아이폰1개,안드폰5개)및요약수정확인. 원본변경없음.
- 버튼명/실행함수는그대로이고실제입력영역44×44pt/dp,간격8pt/dp는소스근거다. iPhone/Mac의기존ui배율을적용한다. 모든손가락입력/큰글자/가로방향/접근성/픽셀정규화대조를완료한것은아니다.
- 기존ledger65개미확인행을보존하고이번3행추가. validator구조정상72행/4완료/68열림(exit3). 기존전체프로젝트항목과이번정밀capture/actionmatrix가남아있어‘전체parity완료’는주장하지않는다. 요청한행수정·교체설치·실화면확인은완료.
- 테스트러너/커밋/푸시/사이트/공개배포없음. 원본사용자첨부PNG/해시manifest는같은폴더보존,live화면은CUA관찰이며재현가능한고정fixture쌍PNG는미저장.
