# Rime lockscreen implementation plan

Updated: 2026-10-02

Status: agreed design and implementation plan. The calibration menu and complete
preset/depth flow described here are planned work. Existing APK builds do not
establish that these interactions work in either host.

## Goal

Build an Android-native lockscreen and customization experience that follows the
iOS gallery, editor, notification stack and newer adaptive-clock interactions.
Use one presentation implementation in the standalone preview and SystemUI.

The standalone APK must support rapid iteration on ordinary Android devices.
Visual calibration must be accessible from settings and apply without rebuilding,
reinstalling, flashing or restarting SystemUI.

Keep Android authentication, biometrics, bouncer, notification ranking and pipeline,
media sessions, widget providers and system services under their existing owners.
The shared UI must have no `com.android.systemui` or `com.android.internal` imports.

Use ordinary blur through `BackdropSurface`. Shared components describe geometry,
tint, opacity and desired blur strength. Host renderers own platform implementation.
The skin must continue to work when the ROM's ordinary blur renderer changes.

## Reference behavior

Use the supplied iOS 18 screenshots for the gallery, editor and notification
hierarchy. Use Apple's iOS 26/27 references for adaptive clock sizing, wallpaper
subject interaction and the editor's clock-height control.

Apple documents the behavior but does not publish the exact spring constants,
shadow measurements or collision thresholds. Measure those against screenshots
and motion references, then expose their values through runtime calibration.
The initial gallery scale target is approximately 0.80, uniformly around the center.

- Hold empty wallpaper, unlock, and the preset gallery opens as its own page.
- Remove the clock's long-press editor shortcut from the normal lockscreen.
- Browse presets left/right. Swipe up on a preset to reveal confirmed deletion.
  This follows the later agreement to reproduce iOS gestures.
- Tap a preset to activate it and expand back into the lockscreen.
- Customize opens the selected preset's editor.
- Keep a bordered Add New page with a large plus, including when the collection is empty.
- Inside the editor, tap outlined clock/date/widget/control regions to edit them.
- Cancel discards the draft. Done commits it.
- Notifications originate near the bottom. Support Count, Stack and List.
- The stack peels open as rime described from iOS: the front card follows the
  finger; each card behind stays put until the one in front has cleared it; once
  the second card has been revealed the rest opens by itself. Stacked cards show
  a one-line summary in their lip, and the last lip counts what is hidden.
- The editor follows the iOS layout: outlined date and clock, one Font & Color
  sheet of style tiles and swatches, and detailed values behind a "more" button.
- Swipe upward from the middle/deck to reveal notification history within the shared scene.
- Tap groups to expand them; swipe left for options/dismissal; hold notifications for details.
- Keep notification-center and lockscreen presentation in the same visual world.

## Customization page

A fingerprint on the awake lockscreen dismisses the keyguard
(`BiometricUnlockController`, `MODE_UNLOCK_COLLAPSING`), so nothing customizable
can stay on the lockscreen after authentication. The gallery, editor and
calibration share one full-screen page that opens after unlock.

- Host it in the forked AxThemePicker. It is platform-signed and already has the
  wallpaper, Secure settings and widget permissions in its allowlist. A crash
  there cannot take SystemUI down. Add its package to `RimeSharedUI` visibility.
- SystemUI's hold gesture runs the existing keyguard dismiss and then starts the
  page. Settings > Lock screen > Rime opens the same page.
- The page opens on the active preset drawn full screen and shrinks it to the
  gallery scale, so it reads as the lockscreen shrinking.
- The page draws each preset's wallpaper itself. A blur request only blurs what
  is behind the window, so surfaces over a self-drawn wallpaper use a blurred
  copy of it, generated once when the preset is saved and clipped to each
  surface. This needs no SurfaceFlinger requests and gives the preview APK the
  same look.
- The page owns the preset store and applies presets. SystemUI keeps reading the
  active configuration from Settings and the real wallpaper; it never loads
  preset files.
- The lockscreen in SystemUI only displays. Remove in-keyguard editing and the
  redirect to AxThemePicker's `MainActivity` from the adapter.

## Runtime calibration menu

### Entry points

- Preview APK: Preview settings > Lockscreen > Calibration, reached through the
  existing hidden developer controls. Developer controls stay out of normal screenshots.
- ROM: the customization page, from Settings > Lock screen > Rime > Calibration.
  It shows the shared lockscreen scene over the active preset with fixture
  notifications (0/1/5/20, groups, long text) beside the controls.
- Both entry points render the same shared calibration panel.
- The page saves one versioned JSON value to `Settings.Secure`, which caps a value
  at 32,768 characters. SystemUI already observes Secure settings and applies the
  new revision at a frame boundary. Judge final blur on the real lockscreen.

### Controls

| Group | Runtime values |
| --- | --- |
| Clock | Preferred and minimum height, permitted vertical offset, proximity gap before contraction, contraction/restore response |
| Notifications | Bottom reserve, stack peek gap, rear-card scale, list spacing, corner radius, shadow elevation/opacity, highlight strength |
| Gestures | Drag activation distance, flick velocity threshold, settling threshold, spring stiffness/damping, scroll decay |
| Gallery | Uniform preview scale, centered position, page gap, corner radius, transition spring |
| Appearance | Surface tint/opacity, ordinary blur strength, readable foreground contrast |
| Depth diagnostics | Mask visibility, crop/source bounds, subject outline, clock outline and transform revision |

Keep clock-family capability limits authoritative. A calibration value cannot force
an unsupported face to stretch or allow clock/widgets to overlap required system input.
Use dp for layout values, sp for text and normalized coordinates for source geometry.
Physical pixels are measured rendering outputs rather than device-specific presets.

Start with live visual updates, numeric entry, sliders, reset per section, reset
all, Save and Cancel for the values the current step needs, and add controls as
each feature lands. Versioned JSON import/export and a saved/candidate comparison
mode come once those values exist. All tuning controls use the same
validated settings model; the hosts differ only in storage and platform actions.

Changes preview in memory. Save persists a validated snapshot; Cancel restores the
saved snapshot. Store updates off the UI thread and apply one settings revision at
a frame boundary so related values stay consistent. Reject non-finite numbers and
invalid ranges. Invalid imported or persisted data falls back to known defaults.

Expose diagnostic overlays for clock/deck bounds, viewport/insets, mask/crop alignment,
measured card heights and scene state. Include a freeze/scrub control to inspect
intermediate notification expansion and gallery poses. Diagnostics are opt-in and
contain no private notification payloads.

Calibration changes must not regenerate segmentation masks or restart host services.
Keep the ROM blur-request limit intact. Fix transform errors in the common geometry
instead of making manual mask offsets the normal alignment mechanism.

Adding new behavior or a new calibration control still requires a code build.
Changing values of implemented controls must not require one.

## Shared presentation and host boundaries

Extend the existing `rime-ui` structure:

```text
shared-ui/
    lockscreen/        scenes, clock layout, gallery, preset editor
    notifications/     deck, grouping presentation, count/list transitions
    customization/     clock/widget/shortcut pickers and calibration panel
    backdrop/          BackdropSurface and neutral renderer contract
    models/            notifications, presets, depth geometry, capabilities
    settings/          validation, codecs and calibration settings
preview-app/
    public Android bindings, mock state, local assets/presets, developer controls
systemui-adapter/
    real pipeline/authentication/clock/widget/wallpaper/depth bindings
AxThemePicker (fork)
    customization page host, preset store and preset application
```

The shared layer owns presentation state and gesture interpretation. Host adapters
provide current models and execute authorized actions through existing backends.
Scene changes remain observable state rather than callbacks that silently open an
unrelated native panel. QS replacement remains deferred.

Use the same scene and component renderers for live display, editing and gallery
previews. Supply explicit preview bounds and scale rather than copying visual code.

## Presets and AxThemePicker compatibility

A versioned preset stores:

- Stable ID and display name.
- Wallpaper asset identity, source dimensions and crop.
- Clock family, variant, color, size and position.
- Widget selections, layout and configuration.
- Bottom shortcuts.
- Depth preference and associated mask metadata/assets.
- Optional home-wallpaper pairing and DND association.

Rime owns the complete preset store. Import the existing Axion configuration and
mirror supported fields for the active preset into legacy settings. Preserve fields
that AxThemePicker cannot represent. Inactive presets retain their own configuration.
Notification delivery, privacy and ranking stay under Android policy.

The forked AxThemePicker hosts the customization page and retains useful existing
wallpaper/clock code. Compatibility may change when the required feature cannot fit the legacy
model. Shared UI and Android backend boundaries remain mandatory.

Browsing previews does not apply every preset. Explicit selection applies one.
Serialize application, validate assets first and journal multi-service changes.
Commit the active ID after wallpaper and settings application succeeds. Recover
the previous configuration after partial failure or interruption. Do not claim an
atomic transaction across WallpaperManager and Settings providers.

While a selection applies, show a loading state on the selected card until the
wallpaper, mask and colors have settled. Setting the lock wallpaper also makes it
the system color source, so a switch can recolor the system.

Editor changes use a draft. Dragging and color changes do not repeatedly rewrite
the active system configuration. Cancel discards the draft; Done applies it once.

## Adaptive clock and depth wallpaper

### Existing code to preserve and adapt

- `AxWallpaperEffects/WallpaperDepthService.kt` generates masks for wallpaper crops
  and display layouts. `DepthMaskUtils.kt` encodes normalized subject contours.
- `DepthWallpaperProvider.kt` delivers active masks asynchronously and rejects stale refreshes.
- `ClockDepthController.kt` maps subject paths into clock coordinates using bounds,
  scale, crop and wallpaper zoom.
- AxThemePicker's `PreviewClock.kt` already supplies explicit preview source bounds.

The current Rime controller selects native presentation when depth or widgets are
enabled, or when the clock is not the wide face. Completing their adapters is a
prerequisite for removing those gates.
Keep the existing clock path-operation ANR fix.

### Common geometry contract

Carry wallpaper/preset identity and revision, source dimensions, crop, subject mask,
viewport/insets, gallery transform, clock bounds, widget reservation and deck position.
Use one transform chain for wallpaper framing, subject clipping, clock rendering
and the visible backdrop shape. A mask generated for one asset/crop must not appear
on another preset while asynchronous work completes.

Each preset and thumbnail needs its own mask association. The existing provider's
global active mask cannot serve every gallery card. Preserve its format where useful;
extend delivery/storage to supply explicit preset data. Keep segmentation off the
UI thread and out of drag frames. Cache decoded geometry by asset/mask revision.

AxWallpaperEffects only reacts to the active wallpaper and writes one Secure value
per display layout. Per-preset masks need a request path there, which makes it a
third APK in the batch. `safe-build.sh rime-lockscreen` builds SystemUI and
AxThemePicker only; add AxWallpaperEffects before the ROM build.

Account for crop or zoom changes even when screen dimensions remain unchanged.
Invalidate transform caches explicitly. Adapt the depth feature where necessary
to support gallery scaling, clock resizing and widget layout.

Notifications consume free space before contracting the clock. Contraction starts
at the calibrated proximity gap, responds continuously to the drag and reverses
smoothly. Restore the user's chosen height when space becomes available. Keep the
current default clock position and allow the user to move it higher within safe bounds.

Provide sizing capabilities for every Axion clock family. Faces with compact/large
variants transition those variants; resizable faces use their supported height range.
Reserve measured widget space and preserve provider/event ownership in the host.

Axion has 16 clock types; the shared UI draws two. The wide face ships first. The
other faces follow after the first working ROM build and keep native presentation
until then; host them as their native views through `LockscreenContentRenderer`. `ClockSettingsRepository` is a global
object, so gallery cards cannot show different native faces at once until clock
views accept per-instance settings. The standalone APK cannot prove native faces,
real widgets, real blur or authentication; those are SystemUI checks.

The wallpaper subject may occlude the clock. Its mask must constrain both clock
tint and the ordinary backdrop request. Parent composable clipping alone is not
proof that a native blur request is clipped correctly.

The clock stays blurred and sits behind the subject. Pick one of two approaches
with a preview spike before the ROM build:

1. Subject layer: draw the wallpaper's subject pixels, clipped by the contour,
   above the clock. The blur region stays a plain glyph shape. This needs the
   wallpaper bitmap, exact alignment with the wallpaper window and the same dim
   the keyguard scrim applies behind it.
2. Cut-out: subtract the contour from the glyph shape for both tint and blur.
   Simplify the contour once at load, compute off the main thread with the latest
   request winning, and reuse the last result during drags.

Neither may run a path operation on the main thread per frame. The watchdog
restarts SystemUI after a 4 s main-thread stall.

## Surface and notification corrections

- Confine blur and tint to each visible surface at every scale/position.
- Release or suppress hidden backdrop requests; prevent detached rectangular bands.
- Add effective alpha and visibility to `BackdropStyle`. Shared layout sets them
  from the values it already uses to fade, scale and hide cards; a Compose layer's
  alpha, scale or clip does not reach a native blur region. The SystemUI renderer
  maps them to region alpha, scaled corner radius and no request when hidden.
- Every visible surface keeps blur. Hidden, fully faded and off-screen surfaces
  hold no request. Report the live request count in the `RimeLockscreen` dump;
  the expected peak is about 12 with the list expanded. That is above the stock
  limit of 10, so the ROM's raised limit stays.
- Use one blur radius for every surface in the window. SurfaceFlinger generates
  one blur per distinct radius per layer and reuses it for each region
  (`SkiaRenderEngine.cpp`, `cachedBlurs`), so a second radius costs a second pass.
- Draw subtle card shadows outside rounded content clipping.
- Keep the readable front card above narrower rear cards with small peeks.
- Preserve measured heights through grouping, insertion, dismissal and scrolling.
- Retain short flick expansion and reversible slow dragging.
- Initialize existing notifications from the real rendered snapshot in SystemUI.
- Forward open/dismiss/available actions into existing Android handlers.
- Keep gallery/editor transitions from resetting the notification deck unnecessarily.

## Standalone APK proof

Treat the existing APK as an unverified baseline. Normal mode remains fullscreen,
wallpaper-first and free of app bars, tabs and visible developer controls.
Use the actual wallpaper window where supported. Selected preset assets supply
gallery images and deterministic depth fixtures. Stock wallpaper visibility does
not grant bitmap access for segmentation.

Prove these paths on an ordinary Android phone without ROM privileges:

1. Cold launch with no notification-listener access; mock state is useful immediately.
2. Hold wallpaper; browse/select presets; verify the clock hold shortcut is absent.
3. Empty collection; Add New; choose a photo; edit; Cancel and Done.
4. Restart the APK; committed assets/configuration persist.
5. Slow, reversed and short flick notification drags; grouping and scrolling.
6. 0/1/5/20 notifications, long text, progress, insertion and dismissal.
7. Depth fixtures during clock resizing, crop changes and gallery scaling.
8. Live calibration, reset, Save/Cancel and export/import without reinstalling.
9. Different viewport sizes, foreground contrast and blur-unavailable fallback.

Capture resulting screenshots, motion evidence and measured geometry tied to the
exact APK. A successful build or attractive resting screenshot does not prove these paths.

## SystemUI proof and recovery

After standalone proof, separately validate the ROM adapter with notifications
already present, real privacy policy, native authentication, fingerprint/footer
input, existing Axion masks, sleep/wake, presets and disabling Rime.

Shared failures must release presentation/input ownership and preserve native unlock.
Keep the persistent failure latch and bounded watchdog. Distinguish caught UI failures
from native fatal signals or a blocked main thread that requires process restart.
Failure-injection recovery needs separate evidence from successful startup.

Keep built, installed and interaction-verified status separate. Verify installed APK
hashes against the successful build. Follow the repository's guarded build and
staged-update procedures, save recovery APKs and obtain approval before a daily-driver
reboot. Target the ROM phone by its verified serial; leave other connected devices alone.
Batch pending SystemUI changes into one build and monitor the persistent build service.

Observe real blur directly on the phone. Screenshots and registered blur requests
alone do not establish that SurfaceFlinger rendered it.

## Implementation order

Steps 1-4 use the preview APK only and need no ROM build. Two ROM builds are
planned in total. Every step ends with its done check; "eyes" marks what only
rime can confirm on the phone.

1. Fix the reported problems. Implemented and checked on the preview phone on
   2026-10-02; the SystemUI renderer change is written but not yet compiled.
   - Turn off the default Compose press overlay, which tints the screen on hold.
   - Remove the clock hold shortcut: a hold on the clock now opens the gallery
     like any other spot. A hold ends when the finger moves, so a slow swipe no
     longer opens it.
   - Add card shadows and the blur visibility contract. Rear cards draw only the
     part not behind the card in front. Cards fade at the deck's edges instead
     of covering the clock or the bottom controls.
   - A short gesture counts as a flick by its average speed, so a slow frame
     cannot swallow it.
   - Keep the deck state across gallery/editor entry.
   - Calibration starts here with only the values these fixes use.
   - Done: unit tests pass; standalone paths 5 and 6.
     Eyes: card readability on a bright wallpaper.
2. Presets and gallery. Implemented and checked on the preview phone on
   2026-10-02. Not checked: choosing a photo in the system picker (the picker
   opens; imports were made from fixture files), and the two-line SystemUI host
   change, which is uncompiled until ROM build 1.
   - Preset store and codec with unit tests, uniform 0.80 gallery, Add New,
     deletion, selection with the loading state, blurred wallpaper copies.
   - The gallery is its own composable beside `RimeUiRoot`, fed by `PresetLibrary`.
     Each page is the full scene scaled as one piece. A page whose wallpaper the
     host cannot read is a window through the black background.
   - Notifications fade out of the active page as it shrinks. Neighbouring pages
     show their wallpaper first and their scene once the gallery is at rest.
   - Done: standalone paths 2, 3 and 4.
3. Draft editor and the calibration page. Implemented and checked on the preview
   phone on 2026-10-02. Not checked: pinch on the photo (adb cannot send two
   fingers; the geometry is unit-tested) and the system photo picker.
   - `RimeUiRoot` no longer edits anything. The editor is part of the page: its
     host wrapper keeps every change in a draft, and `PresetLibrary.savePreset`
     receives the result once. A new photo is a draft until Done.
   - `CalibrationPage` is the scene over the active wallpaper with fixture
     notifications. Export and import go through `CalibrationTransfer`; a file
     that is not this version's calibration data is refused and changes nothing.
   - The saved/candidate comparison mode is still open.
   - Done: standalone paths 3 and 8.
   After rime's review the same day, two parts were rebuilt from the iOS
   references instead of the older preview UI:
   - Stack motion is `NotificationDeckGeometry.peel`, one rule for closed,
     opening, open and scrolled. Calibration is version 2: the 8 dp gap became a
     30 dp lip under a new key, and version 1 data keeps its other values.
   - The open list is bottom-anchored: it rises only as far as its own height,
     and the clock gives up room only for what does not fit. Opening continues
     at the finger's speed on a soft spring (`stackOpenStiffness`, calibrated).
   - Opening and scrolling are two steps. While the list scrolls, the clock and
     date move up with it by the same amount and may leave the screen; cards
     fade at the top of the scene, not under the clock.
   - A tap opens the closed stack, the open list has a two-tap Clear control, and
     the handover to self-opening gives a haptic tick (not verifiable over adb).
   - Dragging the stack no longer recomposes every card or the whole scene on
     each frame: unchanged cards skip, and the scene follows a derived frame. On
     the preview phone with nine cards this roughly halved main-thread time per
     frame (recomposition 24 to 13 ms). What remains is the clock resizing.
   - The editor lost its bottom buttons and slider panel. Replacing a preset's
     photo from inside the editor is not built yet.
   - A photo repositioning crash (an inverted range from float rounding) was
     fixed and is covered by a test that sweeps image sizes.
   Design from the iOS screenshots and rime's description of motion first; the
   older preview UI is not a reference.
4. Common geometry, adaptive clock and depth on fixtures.
   - Unit-test the transform chain. Run the subject-layer versus cut-out spike.
   - Done: standalone paths 7 and 9.
5. ROM build 1: SystemUI, AxThemePicker and AxWallpaperEffects in one batch.
   - SystemUI displays only: blur contract in its renderer, calibration from
     Secure settings, hold > unlock > page, depth with the wide face.
   - Draw each blur region from the Compose draw phase instead of one embedded
     View per surface. An embedded View takes the touch, so today a hold on the
     clock would not reach the wallpaper in SystemUI.
   - A deck drag recomposes the whole scene every frame: about 60 ms of main
     thread per frame with nine cards on the debug preview phone. Measure it on
     the ROM phone before the handoff.
   - AxThemePicker hosts the page; AxWallpaperEffects serves per-preset masks.
   - Confirm no new privileged permission is requested before choosing staged
     APK delivery. A new one needs an allowlist entry in the system image, which
     an APK update cannot deliver.
   - Done: the SystemUI proof list. Eyes: real blur, no detached bands, depth
     alignment, preset switching.
6. ROM build 2: corrections found on the phone, plus widgets.
   - Done: the same proof list with widgets enabled. Eyes: widget spacing.
7. Later: the other 14 clock faces, photo shuffle, additional wallpaper types,
   DND-linked presets, media and AOD parity.

## References

- [Apple iOS 18 lockscreen guide](https://support.apple.com/guide/iphone/create-a-custom-lock-screen-iph4d0e6c351/18.0/ios/18.0)
- [Apple current iOS 27 lockscreen guide](https://support.apple.com/guide/iphone/create-a-custom-lock-screen-iph4d0e6c351/27/ios/27)
- [Apple iOS 26 feature list, Adaptive Time](https://www.apple.com/au/os/pdf/All_New_Features_iOS_26_Sept_2025_AU_Final.pdf)
- [Apple iOS 18 notification guide](https://support.apple.com/guide/iphone/view-and-respond-to-notifications-iph6534c01bc/18.0/ios/18.0)
- [Apple wallpaper gallery and photo shuffle](https://support.apple.com/en-us/102638)
- [WWDC22: Complications and widgets](https://developer.apple.com/videos/play/wwdc2022/10050/)
- [WWDC24: Controls across the system](https://developer.apple.com/videos/play/wwdc2024/10157/)
- User-supplied iOS screenshots and ROM screenshots from 2026-10-02.
