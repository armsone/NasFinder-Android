# iOS ↔ Android UI parity catalog

최신 비교 기준은 [`LATEST_IOS_PARITY_MANIFEST.json`](../LATEST_IOS_PARITY_MANIFEST.json)이다. iOS 제품 화면과 로컬 iOS 저장소의 최신 UI 소스를 읽기 전용 기준으로 사용하며, Android 고유 시스템 파일 선택기·권한 화면은 의도된 플랫폼 차이로 유지한다.

## 최종 Android 실기기 캡처

- [`thumbnail_cache.png`](android/thumbnail_cache.png): 썸네일 캐시 사용량·자동 정리·삭제 상태

## 재생성 명령

연결된 기기 ID를 확인한 뒤 앱에서 대응 화면을 연 상태로 실행한다.

```sh
adb -s <device-id> shell screencap -p /sdcard/nasfinder-ui.png
adb -s <device-id> pull /sdcard/nasfinder-ui.png docs/ui-parity/android/<state-id>.png
```

검증 기기와 상태 진입 결과는 [`DEVICE_TEST_REPORT.md`](../DEVICE_TEST_REPORT.md)에 기록한다.
