# Browser video fullscreen matchup — 2026-08-21

## Evidence

- Reference: HanClip Android `OnlineMusicBrowserRoute.kt` source implementation.
- Target: NasFinder Android `WebBrowserScreen.kt` source implementation.
- Scope: HTML5/WebView custom video fullscreen on Android, including large and foldable displays.
- Image evidence: no stable paired captures were available in this change. The behavior is implemented from source; visual parity remains unverified until the tri-fold device is connected and exercised.

## Atomic comparison matrix

| Route/state ID | Element/anatomy | Dimension/action | Fixture/profile | HanClip exact reference | NasFinder observed | Difference | Required action | Evidence/confidence | Status/exception proof |
|---|---|---|---|---|---|---|---|---|---|
| `browser_video_inline` | HTML5 video fullscreen control | Tap fullscreen | Android WebView, video page, unfolded large screen | `WebChromeClient.onShowCustomView` stores the supplied custom view and callback | Custom view and callback are now stored | Target previously handled only progress/title | Add custom-view callbacks | Source / High | matched in source |
| `browser_video_fullscreen` | Video custom view | Layout and background | Same | Custom view is reparented into a black `MATCH_PARENT` `FrameLayout` above browser UI | Same overlay structure and sizing | Target previously had no fullscreen container | Add full-size overlay above `Scaffold` | Source / High | matched in source; capture pending |
| `browser_video_fullscreen` | System bars | Enter/exit fullscreen | Same | Hide system bars with transient swipe behavior; restore on exit | Same inset-controller behavior | Target previously left app chrome/system bars visible | Hide and restore system bars with lifecycle-safe effect | Source / High | matched in source |
| `browser_video_fullscreen_back` | Back action | Press system back | Same | Notify the WebView callback and close fullscreen before browser navigation | Same priority order | Target previously navigated WebView history or closed browser | Prioritize fullscreen dismissal | Source / High | matched in source |
| `browser_video_fullscreen_hide` | WebView callback | Site/player exits fullscreen | Same | `onHideCustomView` closes overlay and notifies callback | Same callback path | Target previously ignored the event | Implement hide callback | Source / High | matched in source |
| `browser_video_fullscreen_release` | Retained WebView | Leave browser/session replacement | Same | Notify the active custom-view callback before releasing WebView resources | Callback is notified and fullscreen state cleared before retaining/replacing WebView | Target could otherwise retain a custom view tied to disposed UI | Clear fullscreen state during `AndroidView` release | Source / High | matched in source |

## Verification command

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Physical-device verification remains: on the Samsung tri-fold, open the same video page in both apps, enter fullscreen, swipe transient system bars, exit via the player, re-enter, and exit via system back. No device installation is included in this task.
