# NasFinder 앱 아이콘·타이포그래피 iOS→Android 동등성 기술서

## 1. 원본과 금지사항

iOS asset catalog와 사용자가 제공한 다섯 이미지를 읽기 전용으로 대조했다. Android는 아래 사용자 제공 이미지 byte를 `drawable-nodpi`에 그대로 보존한다. 재그림, crop, filter, 색 보정, 재인코딩을 하지 않으며 preview도 같은 원본 drawable을 사용한다. launcher에서 보이는 외곽 형태만 Android OS의 adaptive icon mask가 결정한다.

| 표시 순서 | 사용자 명칭 | Android 원본 | 크기·alpha | SHA-256 |
|---:|---|---|---|---|
| 1 | 기본 Blue NAS | `app_icon_blue_nas.png` | 1254×1254, opaque | `281c7030e7890b8f858dd2638c489a17a193c55b1772d7f16d3a0d939e40a5ba` |
| 2 | Cyber Vault teal | `app_icon_cyber_vault.png` | 1254×1254, opaque | `2cd8837024481f67131ae924fcfa562a6a0fc13d0ea7e1d173b6210e8384671b` |
| 3 | Vibe Coder blue | `app_icon_vibe_coder.png` | 1254×1254, opaque | `302ce914160732412bbeaebd607bfe960d8ab8fdd9b82a267df27474549b0b12` |
| 4 | Purple | `app_icon_purple_nas.png` | 1254×1254, opaque | `609f5bec2d685d23e992301721724ea7c2810067dab86c34ebf118a4b49e4253` |
| 5 | 네트워크 NAS | `app_icon_nas_radar.jpg` | 1024×1024, opaque JPEG | `6d0f9e5965e94de5672bec42bafe399468753dd8143f2587d3c7c73b5c23805c` |

iOS asset catalog의 기존 네 대응 이미지는 1024×1024 opaque지만 사용자 제공본은 같은 디자인의 1254×1254 별도 원본이며 byte hash가 다르다. 네트워크 NAS는 Android JPEG를 디코딩했을 때 iOS PNG와 같은 픽셀 결과이며, Android의 canonical source는 위 JPEG다.

## 2. Android launcher 계약

- `DefaultLauncherAlias`, `CyberVaultLauncherAlias`, `DigitalRainLauncherAlias`, `PurpleNasLauncherAlias`, `NasRadarLauncherAlias` 다섯 alias가 각 icon을 가리킨다. 기존 설치 호환을 위해 Vibe Coder는 기존 `DigitalRainLauncherAlias` class name을 유지한다.
- 한 시점에 launcher alias는 정확히 하나만 활성화한다. `MainActivity`는 sharesheet, deep link, widget, shortcut을 위해 항상 활성화하며 icon 전환 대상에 포함하지 않는다.
- API 33 이상은 `setComponentEnabledSettings` 한 transaction으로 네 alias의 최종 상태를 적용한다. API 26~32는 활성 non-target을 먼저 끄고 target을 켠다. 실패하면 전환 전 단일 stable icon으로 rollback한다.
- application, alias의 `icon`과 `roundIcon`을 모두 지정한다. launcher가 round icon을 우선하더라도 선택 결과가 달라지지 않아야 한다.
- API 26 이상은 adaptive wrapper가 원본 bitmap을 foreground로 직접 참조한다. API 33 이상 wrapper도 원본 artwork를 유지하며 `monochrome`은 제공하지 않으므로 launcher의 themed icon 색상화 대상이 아니라 원본 색상 아이콘으로 표시된다.
- API 25 이하 fallback도 같은 원본 bitmap을 참조한다. preview에서 별도 축소 PNG나 재인코딩본을 사용하지 않는다.
- theme와 icon은 독립 preference다. 사용자가 아이콘을 명시적으로 고르기 전에는 Digital Rain theme가 Vibe Coder를, 나머지 theme가 기본 Blue NAS를 초기값으로 사용한다. 한 번 명시적으로 선택한 뒤에는 theme 변경이 icon을 덮지 않으며 title/order/hash를 바꾸지 않는다.

## 3. iOS 타이포그래피 실측 계약

iOS는 커스텀 폰트 파일 없이 San Francisco 시스템 글꼴의 semantic style을 사용한다. 주요 대응은 `caption2`, `caption`, `footnote`, `subheadline`, `body`, `headline`, `title3`이며 숫자 진행값은 monospaced digit을 사용한다. Dynamic Type 접근성 크기에서는 연결 화면과 원격 브라우저가 1열로 바뀌고, 브라우저 tile 최소 폭은 78→118pt, 최대 폭은 104→155pt, large poster 최소 폭은 158→270pt로 커진다. 목록 행의 제목은 보통 2줄, 접근성 크기에서는 3줄을 허용한다.

Android에는 SF 글꼴을 복제하지 않는다. 플랫폼 관습과 글리프 coverage를 위해 Roboto/Noto 계열 시스템 sans를 사용하고, iOS semantic hierarchy를 Material 3 `labelSmall/labelMedium/bodySmall/bodyMedium/titleMedium/titleLarge`로 대응한다. 고정 px 텍스트나 전체 앱 fontScale 무시는 금지한다.

## 4. 보이지 않는 UI·clipping 방지 기준

- 모든 사용자 문구는 `sp`/Material typography를 사용하고 fontScale 1.0, 1.3, 1.5, 2.0 및 기기 최대값에서 확인한다.
- 54dp 고정 높이 action button은 큰 글꼴에서 label이 잘리면 최소 높이로 바꾸고 세로 확장을 허용한다. 텍스트를 숨기거나 fontScale을 강제로 낮추지 않는다.
- 주요 제목·설명·오류·진행 상태는 2~3줄 또는 자연 높이를 허용한다. endpoint/path처럼 본질적으로 한 줄인 보조 정보만 ellipsis를 사용하며 전체 값은 접근성 label/value로 제공한다.
- icon-only action은 최소 48dp touch target과 TalkBack label을 갖는다. 장식 icon, reflection, background glyph는 접근성 트리에서 제외한다.
- 색만으로 선택/성공/실패를 전달하지 않는다. 선택 표식, 텍스트, semantics state를 함께 제공한다.
- iOS의 `minimumScaleFactor`는 작은 icon caption의 마지막 수단이다. Android 핵심 action/상태 문구를 임의 축소해 맞추지 않고 layout을 확장한다.
- 밝은/어두운/고대비 launcher에서 foreground/background 대비를 확인한다. 원본이 opaque이므로 adaptive background는 parallax edge 보완용일 뿐 원본 색을 덮어쓰지 않는다.

## 5. 회귀 검증

1. JVM asset test가 다섯 `drawable-nodpi` 파일의 SHA-256을 위 값과 대조한다.
2. icon policy test가 다섯 variant↔alias가 1:1이고 MainActivity가 managed set에 없음을 확인한다.
3. merged manifest에서 application/default/네 alternate alias의 icon·roundIcon, enabled 기본값과 launcher filter를 확인한다.
4. API 26/32/33+에서 각 icon 전환 후 launcher와 app drawer의 실제 icon을 확인하고 재부팅·앱 업데이트 뒤 선택 복원을 확인한다.
5. API 33+ themed icon on/off에서도 원본 색상 아이콘과 선택 alias가 유지되는지 확인한다.
6. Compose screenshot/accessibility test는 fontScale 행렬, portrait/landscape, 최소 폭, 긴 한국어·영문·숫자·경로, light/dark/high-contrast를 포함한다.
