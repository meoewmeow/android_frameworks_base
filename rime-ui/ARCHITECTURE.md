# Rime UI architecture

This project is a standalone visual layer. `shared-ui` owns presentation and
small, platform-neutral state/action contracts. It does not own notification,
keyguard, media-session, tile, or authentication behavior.

## Current extraction (September 30)

The approved direction is now to develop the ROM-inspired Axion wide lockscreen
in this APK, including its settings, and later reuse this exact visual library
for the ROM notification and QS replacement.

Implemented in shared code:

- `AxionClock`: the existing ROM wide font asset, four-digit public-API outline
  measurement, Axion weight/height axes and scale range. Its backdrop is a glyph
  shape, never a clock panel. The stock renderer supplies translucent glyphs.
- `LockscreenSettings` and `LockscreenSettingsPanel`: bounded size/position,
  date/color/dual-tone/digit appearance, and common surface settings. The preview
  persists this locally. `AxionSettingsCodec` maps the supported old clock keys;
  actual ROM observation/display-profile resolution remains future adapter work.
- `AxionClockGeometry` and `AxionClockEditModel`: the actual pure geometry and
  edit model copied from the ROM's ClockSettingsModels/AxThemePicker ClockEditModel,
  retaining Apache headers. Repository constants are supplied through
  `AxionClockLimits`; no SystemUI repository is imported. The height-only handle
  uses the same corner resize calculation with zero width change. Preview scene
  bounds allow the resulting center offset while clamping the visible top edge.
- `LockscreenSceneState`: one synchronous expansion/scroll driver observed by
  both the clock and notifications. Gestures use the fixed root coordinate space,
  freeze travel at pointer-down, and transfer residual movement between expansion
  and scrolling. Only release settling/fling uses a coroutine animation.
- `NotificationDeck`: stable entry keys, natural measured heights, three visible
  collapsed containers and host-ordered app groups. Back-card content is clipped
  and faded; text is not squeezed vertically to fit a different front card.
  Structural list offsets animate separately from pointer-driven expansion.
  This is new shared presentation code informed by the ROM and Apple references;
  it is not an extraction of the whole Axion notification stack or AOSP algorithm.
- `EditableControlGrid` and `ControlCenterLayout`: host-independent module packing,
  reorder/remove/add/resize state persisted with shared appearance settings. Actions
  still go to the host's existing controls, rather than a new tile backend.
- `UiNotification`: app icon, text, progress and non-reply action descriptors.
  Mock host stress controls insert notifications and change progress; public
  NotificationListener mapping retains Android intents in the preview host.
- `UiPalette.surfaceStyle`: one appearance source and style factory shared by
  notifications, media and Control Center controls.

The original ROM files are still untouched. They are not yet consumers of this
new library; future integration must replace visual callers with shared
components, not duplicate this implementation. Other Axion faces, widgets,
media-art/depth effects, weather/Smartspace producers and AOD/hardware behavior
remain unimplemented here. Authentication is always host-owned.

A stock-device preview proves layout and interactions, not SurfaceFlinger blur.
There are no screenshot-copy blur effects or renderer-specific skin parameters.

## Module and package layout

```text
rime-ui/
  shared-ui/                         Android library; public AndroidX Compose only
    src/main/java/dev/rime/ui/
      models/                         Notification, media, controls, clock, keyguard
      state/                          NotificationSource, SystemControls, etc.
      lockscreen/                     Lockscreen presentation
      notifications/                  Notification Center and notification cards
      controlcenter/                   Control Center presentation
      media/                           Shared media card
      components/                      Native Compose primitives
      backdrop/                        BackdropSurface contract and Compose bridge
      settings/                        Shared appearance model, editor, legacy codec
  preview-app/                        Ordinary installable Android application
    src/main/java/dev/rime/preview/
      mock/                            Fake state and developer controls
      wallpaper/                       Window wallpaper host and wallpaper colors
      notifications/                   Optional NotificationListenerService adapter
      ui/                              Hidden developer gesture and debug sheet
  systemui-adapter/                    Future host mapping; not wired into SystemUI yet
```

The shared package root is `dev.rime.ui`. The preview app uses
`dev.rime.preview`. A future ROM adapter can use a SystemUI-owned package, but
only that adapter may import `com.android.systemui.*` or
`com.android.internal.*` types.

## Runtime boundary

`RimeUiRoot` receives a `RimeUiHost`, a `BackdropSurfaceRenderer`, and the
current scene. It presents lockscreen, Notification Center, Control Center,
and unlocked wallpaper states in one immersive tree. Gesture transitions are
reported to the host; the preview Activity contributes no toolbar or bottom
navigation.

```text
preview-app  ── fake/public Android sources + preview actions ──┐
                                                                 ├─ RimeUiRoot
SystemUI     ── future repository/controller adapters ──────────┘
```

The host contract exposes shared models for notifications, media, controls,
keyguard state, and clock/date. Its action methods express user intent (for
example, activate or dismiss a notification, request unlock, or request a Wi-Fi
change). A host must forward those intents to the existing platform behavior.
The shared UI does not rank or post notifications, authenticate, run biometric
or bouncer flows, manage media sessions, own QS tile lifecycles, or call status
bar services.

The preview app supplies deterministic mock state and action handling. Its
optional `NotificationListenerService` maps public listener data into shared
notification models; that source is opt-in and mock data remains the default.
`WallpaperHost` sets `FLAG_SHOW_WALLPAPER` on a transparent edge-to-edge Activity
window and reads wallpaper colors for text contrast. Test images are optional
and separate from the default wallpaper path. The developer sheet opens through
a two-finger hold, so it never appears in normal preview screenshots.
The future SystemUI adapter maps existing SystemUI repositories, interactors,
controllers, and notification rows into the same contracts. It will not replace
those backends.

## Backdrop boundary

All translucent surfaces use `BackdropSurface` with a `BackdropStyle`: shape,
corner radius, tint, tint opacity, and desired blur strength. The renderer is
provided by the host. Shared components never create or configure
`BackgroundBlurDrawable`, `RenderEffect`, `SurfaceControl`, or a SystemUI blur
controller.

The preview renderer paints a translucent tinted surface as a visual fallback;
it does not claim to provide backdrop blur. A future SystemUI renderer can turn
the same style into the existing ordinary AOSP/SystemUI blur request. The skin
will not carry renderer-specific or Liquid Glass parameters.

## iOS 27 reference points

The presentation was checked against Apple's current [Lock Screen guide](https://support.apple.com/guide/iphone/access-features-from-the-lock-screen-iphcd5c65ccf/27/ios/27), [notification guide](https://support.apple.com/guide/iphone/view-and-respond-to-notifications-iph6534c01bc/27/ios/27), [Control Center guide](https://support.apple.com/guide/iphone/iph59095ec58/27/ios/27), and [Lock Screen customization guide](https://support.apple.com/guide/iphone/create-a-custom-lock-screen-iph4d0e6c351/27/ios/27).

The scene structure follows these observed behaviors: wallpaper remains the
continuous background; the clock dominates the upper lockscreen; notifications
enter from and stack near the lower area; app groups collapse into a stack with
the latest item on top; taps expand groups, holds expose quick actions, and
side-swipes clear items. Control Center uses unequal grouped controls, tall
brightness/volume sliders, hold actions, and a right-edge group indicator.
Android uses its own system font and open/native icon shapes rather than Apple
assets.

Stack mechanics were also inspected in [Sonner](https://github.com/emilkowalski/sonner)
through its [live example](https://ui.shadcn.com/docs/components/radix/sonner), and
[dokar3/compose-sonner](https://github.com/dokar3/compose-sonner). Measured heights,
stable keys and separate collapsed/list offsets informed the implementation; toast
lifetimes and notification backends were not adopted. Apple's internal spring
parameters are not public, and this preview is not claimed to be a verified 1:1
copy. Other Android projects reviewed were Iconify (rooted styling) and iStage
(KLCK visual reference); iStage code/assets were not copied because its license
forbids modification and redistribution.


## WIP checkpoint review

The inspected checkpoint is `e48112e06f704c82c64742c827b7dff4e928f452`.
Nothing from that work is deleted or moved by this project.

At inspection time the branch also had 24 tracked dirty paths. Several extend
the same clock and notification-stack presentation experiments listed below;
the rest include SystemUI build/test wiring and other existing work. All of
those edits remain untouched and outside `rime-ui`.

Production dependencies that remain outside `shared-ui` (the extraction above supersedes the earlier decision to use these only as visual reference):

- `AxClockView`, `AxClockHost`, and `ClockSettingsRepository` add clock-owned
  `BackgroundBlurDrawable` setup and clock-specific blur radius/transparency
  settings. They are coupled to SystemUI and implement the surface below the
  new `BackdropSurface` boundary.
- `NotificationBackgroundView` adds notification-row-owned blur behavior.
- `NotificationStackScrollLayout`, `StackScrollAlgorithm`,
  `NotificationPanelViewController`, shelf/row/view-state changes, and related
  tests implement custom notification stacking and lockscreen gesture/layout
  behavior inside AOSP algorithms. The new presentation must not depend on
  those edits.
- Checkpoint-only wallpaper/icon/clock blur settings and their SettingsProvider
  registration are specific to those experiments, not shared UI contracts.

Pure clock and notification-deck presentation rules have now been extracted,
while SystemUI/AOSP backdrop implementation remains a future host responsibility. The current
worktree also has uncommitted changes to `BackgroundBlurDrawable`,
`SurfaceControl`, and blur tests; they appear to be separate renderer work and
remain untouched by this project.

Separate framework work in the checkpoint is not part of the skin and remains
untouched: the cached `WINDOW_IGNORE_SECURE` implementation (`IgnoreSecureUtils`
and its `Window`, `SurfaceView`, and `WindowManagerGlobal` call sites),
`ComputerEngine` package-visibility behavior, and the broader
first-boot/settings defaults in `DatabaseHelper`. The defaults file mixes
blur-related entries with device preferences, so it should remain separately
reviewable rather than being copied into this project.

## Integration status

`systemui-adapter` is compiled into `SystemUI-core` and `RimeSharedUI` builds the
same shared sources as the Gradle preview. It is an opt-in trial, off by default.
Its current state is in [systemui-adapter/README.md](systemui-adapter/README.md);
the agreed direction and step order are in [lockscreen_plan.md](lockscreen_plan.md).

## Proximity layout and customization update

Clock size now depends on the actual notification top edge and the free space
above it. Expansion moves the notifications first; the clock keeps its chosen
height/position until the reserved clock/date/widget region approaches them.
It then contracts and, only if minimum height is exhausted, moves within the safe
area. Default vertical placement is retained. The detailed position slider now
permits negative offsets down to -170dp; the visible clock is clamped to status-bar
insets rather than to that stored offset alone.

A short upward flick can settle to expanded even from nearly zero expansion.
Movement accumulated before touch-slop recognition is included, and the existing
velocity tracker drives settling. Slow drags remain synchronous.

`LockscreenContentRenderer` provides neutral clock and widget slots. The scene
knows only the host-selected clock ID, size capabilities, reserved widget height
and depth availability. The APK still has two clock faces; full Axion catalog and
real widgets/depth are future SystemUI renderer work. No clock controller, widget
host or depth service is imported into shared-ui.

`LockscreenGalleryScene` opens on long-press of empty lockscreen space. It uses
horizontal profile previews, Customize, and Add with Photos/current-system-wallpaper
choices. The same scene is usable by SystemUI. Stock previews cannot obtain a
scaled bitmap of the system wallpaper: its profile stays transparent over the real
wallpaper surface; selected photo assets can be rendered normally. This is a
platform-specific preview limitation, not a screenshot-copy workaround.

Preview profile metadata and chosen photo assets are saved inside this APK's private
data, separate from actual device wallpaper settings. Existing appearance settings
remain supported. Host-supplied colors replace the fixed accent and Auto clock color;
explicit color overrides persist. Production gallery authorization, wallpaper
application and per-profile Axion settings remain adapter responsibilities.
