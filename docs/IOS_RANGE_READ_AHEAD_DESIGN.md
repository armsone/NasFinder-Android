# iOS MOV Range 요청 폭증 보강 설계

## 목적과 보호 범위

이 문서는 iOS `RemoteVideoStreamingLoader`의 작은 MOV Range 요청 폭증을 막기 위한 제안 패치를 설명한다. iOS 원본 저장소는 읽기 전용으로 조사했으며 수정하지 않았다. 실제 변경물은 Android 저장소의 [`patches/IOS_RANGE_READ_AHEAD.patch`](patches/IOS_RANGE_READ_AHEAD.patch) 하나뿐이다.

패치 기준은 iOS HEAD `f5e7007c71970f150d1dc45c5ac83ae816f2074e`에서 아래 두 파일의 현재 내용이다. 두 대상 파일에는 조사 당시 uncommitted 변경이 없었다.

- `NasFinder/Features/Preview/RemoteVideoStreamingLoader.swift`
- `NasFinderTests/RemoteVideoStreamingTaskRegistryTests.swift`

## 발견한 구조적 위험

기존 256KiB 정책은 요청당 최대치일 뿐 최소 read-ahead 크기가 아니다. AVFoundation이 서로 다른 1B~수KiB Range를 반복하면 byte budget을 거의 소모하지 않으면서 많은 `readRange` 호출을 만들 수 있다. item 16MiB, folder 256MiB, 20초 timeout, 동일 item generation coordinator는 데이터·시간·중복 생성은 제한하지만 요청 횟수를 직접 제한하지 않는다.

기존 cache는 동일하거나 포함된 범위만 재사용한다. 인접·부분 중첩 범위를 합치지 않고 entry 상한도 없어서 작은 disjoint 요청이 많으면 metadata가 계속 늘며 `.filter/.max` 조회 비용도 함께 증가한다.

## 제안 계약

### 64KiB 정렬 read-ahead와 256KiB 상한

- bounded thumbnail cache miss는 요청 offset을 64KiB 경계로 내림 정렬한다.
- network fetch는 최소 64KiB를 목표로 하되 기존 256KiB 상한, 파일 끝, 남은 item byte budget을 넘지 않는다.
- 마지막 budget이 정렬 prefix보다 작으면 실제 요청 offset부터 읽는 fallback으로 필요한 byte를 잃지 않는다.
- playback의 기존 unbounded 8MiB 경로는 변경하지 않는다.

이 계약에서는 1B 요청이 64KiB network block 하나를 채우므로 기본 16MiB item budget과 256회 request cap이 같은 최악 경계를 갖는다. 정상 256KiB 요청은 기존처럼 최대 64회 정도로 끝난다.

### loader/item request hard cap

`RemoteVideoStreamingRequestBudget`이 cache miss network 시도만 원자적으로 계수한다. 기본 상한은 loader당 256회다. `RemoteVideoStreamingLoader` 하나가 item 하나를 소유하고 동일 item의 동시 generation은 기존 coordinator가 합치므로, 한 generation의 per-loader/per-item 상한이 된다. cache hit는 계수하지 않으며 실패·timeout 시도는 폭증 방지를 위해 계수한 채 유지한다.

### bounded range cache

- overlap 또는 바로 인접한 entry를 하나로 병합한다.
- 이미 AVFoundation에 제공한 기존 byte가 중첩 영역의 source of truth다.
- entry 수를 기본 256개로 제한해 lookup·merge를 O(256) 이내로 고정한다.
- 실제 Data 총량은 기존 item byte budget으로 계속 제한된다.

### cancellation과 byte accounting

- network 시작 전과 응답 후 `Task.checkCancellation()`을 유지한다.
- network exception/cancellation이면 예약 byte를 반환한다.
- 응답을 받은 뒤 취소되면 실제 받은 byte는 먼저 charge한 다음 취소를 전파한다.
- request cap 거절은 network 호출 전에 예약 byte를 반환한다.
- 기존 loader task registry, generation 20초 deadline, folder/item traffic lease는 그대로 유지한다.

## 추가 회귀 테스트

패치는 실제 protocol 대신 요청을 기록하는 `FakeStreamingRangeService`를 사용해 다음을 고정한다.

1. 작은 겹침 요청 두 개가 정렬된 64KiB network 요청 하나를 공유한다.
2. 작은 인접 block 요청은 각각 bounded fetch한 뒤 cache entry 하나로 병합된다.
3. disjoint cache metadata가 설정된 entry cap을 넘지 않는다.
4. loader request hard cap 이후에는 fake service network 호출이 증가하지 않는다.
5. stalled fake service를 취소하면 cancellation이 도달하고 byte 예약은 0으로 복구된다.

기존 chunk/cache/budget/task registry 테스트는 삭제하거나 약화하지 않는다.

## 적용성과 정적 검증

패치 파일은 544줄이며 SHA-256은 `26a2f4bd605c202a19af35ce922e6cbeed70e0da543a3226eae5b36fb183d8a1`다. iOS 원본에서 복사한 두 대상 파일만 넣은 별도 임시 트리에서 다음을 확인했다.

```text
patch -p1 --dry-run -i IOS_RANGE_READ_AHEAD.patch
patching file 'NasFinder/Features/Preview/RemoteVideoStreamingLoader.swift'
patching file 'NasFinderTests/RemoteVideoStreamingTaskRegistryTests.swift'
```

같은 임시 트리에 실제 patch를 적용한 뒤 두 결과 파일을 설계용 modified copy와 `cmp`로 대조해 일치함을 확인했다. iOS 빌드, XCTest, 시뮬레이터, 실기기는 지시대로 실행하지 않았다. 따라서 Swift 타입 검사와 실제 MOV/NAS 성능 확인은 iOS 담당자가 패치를 검토·적용한 뒤 수행해야 한다.

## 적용 후 권장 검증

1. `RemoteVideoStreamingTaskRegistryTests` 전체 실행.
2. 기존 `RemotePreviewStateTests`의 bounded MOV·stalled range timeout 회귀 실행.
3. fake service에 30,000개의 1B disjoint 요청을 주고 실제 service request가 256회를 넘지 않는 stress test 추가 검토.
4. 한스트리 `loadmovie` 같은 실제 MOV 폴더에서 파일별·폴더별 request count, transferred bytes, cache entry count 계측.
5. 썸네일 취소·화면 이탈 뒤 active range task가 남지 않는지 확인.

동시 cache miss가 같은 정렬 block을 요청하는 아주 짧은 창에서는 둘 다 network를 시작할 수 있다. 제안 패치도 총 요청을 256회로 제한하지만 in-flight 동일 block 공유까지 구현하지는 않는다. 실측에서 중복이 의미 있게 남으면 후속으로 aligned range key 기반 in-flight task coalescing을 추가하는 것이 안전하다.
