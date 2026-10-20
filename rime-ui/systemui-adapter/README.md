# First opt-in SystemUI host

The adapter sources are now included in `SystemUI-core`; `RimeSharedUI` compiles
exactly the same UI sources/assets as the Gradle preview. This is a first opt-in
trial, not a completed replacement of every clock/widget/depth presentation.

Implement `RimeUiHost` using existing SystemUI repositories/interactors/controllers:

- `NotificationSource`: ordered, sensitivity-filtered UI models from the existing
  pipeline; activate/dismiss/action callbacks forwarded to the existing handlers.
  Do not sort/rank in the shared layer. Remote input and arbitrary app RemoteViews
  remain host-owned; the current normalized model supports basic text, progress,
  app icon and ordinary non-reply actions.
- `LockscreenSettingsSource`: observe existing Axion settings and supply the shared
  `LockscreenSettings` value. Use `AxionSettingsCodec` for the supported legacy
  clock keys. Resolve phone/tablet/display-profile key suffixes in this adapter,
  not in shared code. Preserve unrelated and unsupported settings. The codec is
  a mapping helper, not a production settings observer.
- `KeyguardState`/unlock: reflect actual keyguard state; forward requests into the
  existing authentication flow. A shared fingerprint icon does not authenticate.
- `SystemControls`/media: adapt existing tile and media repositories; leave their
  lifecycle, availability and session behavior in SystemUI.
- `BackdropSurfaceRenderer`: obtain an Outline from the requested shape. For glyph
  outlines use the ROM's ordinary custom-path blur support; for panels use normal
  corner radii. Own all drawable/surface lifetimes here. The same shared request
  must remain usable when the ROM renderer changes globally.

Reuse the shared `AxionClock`, `NotificationDeckGeometry`, `NotificationDeck`,
`NotificationSurface`, `LockscreenSceneState`, `ClockEditModel`,
`LockscreenSettingsPanel`, and `EditableControlGrid`. Do not
copy them into SystemUI. The old native stack experiment is retained as WIP source, but its production
controller now always disables it. Persisted experimental settings cannot replace
the native fallback. Shared Rime presentation does not use that layout algorithm.

## Lockscreen-first integration requirements

QS replacement is deferred. Embed the shared lockscreen before changing other panels.

- Supply `LockscreenCustomization.theme` from the existing theme/clock color flow.
  Auto is the default for new settings; explicit Axion clock overrides remain authoritative.
- Supply `UiLockscreenContent` from the selected `AxClockProvider`/`ClockRegistry`
  controller: preferred/minimum clock height and whether direct height resizing is
  supported. The default APK renderer only implements Axion wide and system sans;
  it is not a replacement for the rest of Axion's clock catalog.
- Implement `LockscreenContentRenderer` for the existing clock family and widget
  host. Use `ClockWidgetGridMetrics.reservedHeightDp`/the measured widget host size
  for reserved space. Clock events, doze, complications, widget providers and
  interaction handlers retain their existing ownership. Faces with compact/large
  variants should transition those variants rather than arbitrarily stretch artwork.
- Keep `DepthWallpaperProvider`/`ClockDepthController` in the host. The clock
  renderer must update source bounds, wallpaper crop/scale and mask alignment
  whenever shared clock height or position changes, including every drag frame.
  Depth is not implemented or verified in the stock APK.
- `requestWallpaperGallery` must authorize through the real keyguard/bouncer path
  before invoking its callback. Preview authorization is simulated. Supply profile
  thumbnails/assets and apply wallpaper through the proper privileged wallpaper
  facilities; do not capture the wallpaper window to manufacture previews.
- Reuse `LockscreenGalleryScene` for selection/Customize/Add. Profile storage and
  wallpaper application belong to the host. Keep detailed shared settings accessible
  from Customize and map edits to supported Axion settings without deleting others.

**Required for the final SystemUI build:** provide an ordinary AOSP backdrop blur
renderer for notification surfaces and clock glyph requests. The preview's tint-only
renderer is not acceptable as the final SystemUI implementation. Verify actual blur
on the target phone by direct observation, plus region budget/lifecycle checks; a
screenshot or registered blur region alone does not prove rendering. No skin-specific
Liquid Glass switches or renderer settings may be introduced.


## Current trial and build status

Since 2026-10-02 the shared UI edits nothing on the lockscreen: `RimeUiRoot` has
no editor, and the gallery, editor and calibration are separate page composables
(`PresetGallery`, `PresetEditor`, `CalibrationPage`). The adapter sources were
adjusted to that contract but not compiled. Statements below about editing on
the keyguard describe the installed trial APK, not the current sources.

`RimeLockscreenController` is attached to the existing notification shade window.
The trial is off by default (`rime_lockscreen_enabled=0`). It renders the shared
wide clock and normalized notifications only on the awake keyguard with OPLUS_BIG
wide selected and no widgets/depth. Unsupported cases, native shade, authentication,
unlock transitions and AOD retain native presentation.

The native clock and notification containers are clipped only during the trial;
their controllers, layout state, ranking and lifecycle remain in place. Native
fingerprint, shortcuts, status region and navigation keep their own views/touches.
Shared settings receive bottom-area touches only while customization is active.
Existing keyguard authorization gates editing and rejects untrusted writes.

`RimeSystemUIHost` consumes the rendered pipeline and its existing visibility,
redaction and sensitivity policies. Click/dismiss requests go to existing rows;
arbitrary RemoteViews/reply actions are not synthesized. QS/media panels are not
replaced in this pass. Existing notifications are seeded synchronously from ActiveNotificationListRepository,
then updated by the pipeline render callback. Shared scene state and measured card
heights survive temporary authentication/AOD/native-panel overlays; unlock resets
the scene. Owned touch streams dispatch directly to the protected shared surface,
with cancellation when native authentication takes over. Notification Center stays
in the shared scene; the deferred QS entry still opens native QS.

Gallery entry currently opens the existing AxThemePicker
through ActivityStarter; the standalone profile carousel is not hosted here yet.

`SystemUIBackdropRenderer` requests ordinary BackgroundBlurDrawable regions,
including the shared glyph path. Its native API was found in the connected ROM's
framework dex. Rendering has not been verified on-device. Do not infer true blur
from a screenshot or raise the ROM region budget.

Rime-specific height/extended vertical position/surface appearance use separate
Secure settings, while existing Axion keys and phone/tablet profile resolution
remain in ClockSettingsRepository. No framework image is built or installed by
the SystemUI-only target.

Build with `/home/rime/android/safe-build.sh SystemUI` and check the authoritative
`/home/rime/android/last-build-result.txt`. The APK target is
`out-m1-spacewar/target/product/Spacewar/system_ext/priv-app/SystemUI/SystemUI.apk`.
A failed build leaves the previous artifact on disk; it must not be installed.
The installed ROM SystemUI APK has been saved as `/tmp/rime-rom-SystemUI-before.apk`,
and its signing certificate matches this tree's platform certificate. Use a verified
APK package update for testing; never remount/push system partitions. Package updates require staged installation and a reboot because SystemUI is
persistent. Back up the installed APK and verify both its signer and the build
verdict before staging. Keep the trial disabled until a bounded device check.

## Failure containment (guarded build)

- Disabled/ineligible Rime does not instantiate its host, renderer or composition.
- Rime owns a supervised recomposer rather than using the window's unguarded
  recomposer. Surface attach/measure/layout/draw/input and native blur callbacks
  have a Rime-only exception boundary. Source/policy callbacks use that boundary too.
- A handled failure opens a persistent circuit breaker, clears the Rime enable
  request, removes its surface, restores native clip bounds and stops Rime input
  ownership. Native authentication, physical fingerprint, shortcuts and bottom
  gestures are never removed by this module.
- Native presentation remains visible until the first shared frame actually draws.
  A missing first frame trips the breaker. Activation is recorded in device-protected
  preferences; an unclean activation on the next process start latches Rime off.
- An independent heartbeat watchdog records the disable latch before restarting
  the persistent SystemUI process if its main Looper remains blocked while Rime is
  active. This restores native mode through Android's existing app supervisor.
  A caught Java/UI failure normally requires no process restart.
- The watchdog cannot preserve a blocked main Looper in the same process, and Java
  catches cannot contain native fatal signals or guarantee recovery from OOM. The
  startup latch protects the next launch; stronger isolation requires a separate
  presentation process. No global uncaught exception handler is suppressed.

Rearming requires an explicit increment of the privileged Secure setting
`rime_lockscreen_reset_failures`, followed by enabling `rime_lockscreen_enabled`.
Automatic retries do not reset the failure latch. Depth remains unchanged and
retains native presentation in this first trial.

Both the SystemUI and AxThemePicker ANR traces showed the main thread inside Skia
`Path.op` from `AxClockView.captureRenderedClockBounds`. The redundant union of
its scanline mask with itself was removed. AxThemePicker must be rebuilt because
it bundles its own SystemUICustomizationLib; a SystemUI APK update does not update
that bundled class. Build both APKs in one guarded invocation:
`/home/rime/android/safe-build.sh rime-lockscreen`.

The guarded APKs have compiled. Failure-injection, heartbeat restart, device startup
and customization interaction checks remain pending the connected ROM device.


## Acceptance checks for the host corrections

These changes are not device-validated by compilation. Keep built, installed and
interaction-verified status separate. The installed APK hash must match the exact
build being checked. `dumpsys activity service com.android.systemui/.SystemUIService
RimeLockscreen` reports only presentation state/counts/heights, never notification
text or keys.

Before handing back the trial:

1. With notifications already present, enable Rime and confirm nonzero measured
   card heights on the first awake lockscreen frame; no new notification post is required.
2. Compare collapsed geometry with the standalone APK. Exercise slow drag, reverse
   drag, short upward flick, group expansion and full-list scrolling. Clock contraction
   must still follow deck proximity.
3. Pull down from the left notification region: the shared scene/deck expands and
   the native shade stays closed. Deferred QS remains native.
4. Open notification details; dismiss only a test notification and open its host action.
   Confirm ordering, private-content redaction and backend dismissal policy.
5. Enter/cancel native authentication; sleep/wake; return from a native panel. Confirm
   frame readiness, preserved deck state, native fingerprint/footer input and unlock.
6. Disable Rime and confirm native content/input restoration. The retired stack key
   must have no effect on the native fallback. Failure-injection/watchdog recovery
   need separate device evidence; a healthy launch does not prove those paths.

Depth/widgets/other clock families still select native presentation. Keep device
preferences unchanged. Observe actual blur directly on the phone; screenshots
and registered requests are insufficient rendering evidence.
