# Rime presentation preview

The preview now uses the ROM-inspired Axion wide clock and one bottom notification
deck. The same shared library owns clock geometry, the settings editor/validation,
notification surfaces and deck transitions, and Control Center controls. Production
SystemUI has not been integrated or rebuilt.

## Rapid iteration

```sh
./run-preview.sh                       # one connected Android device
./run-preview.sh DEVICE_SERIAL         # choose a device
./run-preview.sh DEVICE_SERIAL --developer
```

This runs the guarded standalone APK build plus the pure presentation tests,
checks the artifact, installs only `dev.rime.preview`, and directly launches its
Activity. It never flashes a ROM or invokes Soong. Read `last-build-result.txt`;
failed builds leave an old APK on disk which must not be installed.

Build alone: `./safe-build.sh preview`. Boundary scan: `./verify-boundary.sh`.
Android SDK 36 builds the APK; runtime minimum is Android 8/API 26. Android 13 is
sufficient for the connected-phone preview.

## Interactions

- Hold the wallpaper or the clock without moving: the lock screen gallery. The
  active lock screen shrinks to 80% and its neighbours slide in. Moving past touch
  slop ends the hold, so a slow swipe never opens it.
- In the gallery: swipe left/right to browse, tap a page to apply it and return,
  swipe a page up for the delete button, tap outside a page or press Back to
  leave. The last page is Add New (Photos, or the current wallpaper); it is the
  only page when the collection is empty. Presets and their photos are stored
  inside the APK; the device wallpaper is never changed.
- A photo preset is drawn by the app, and its surfaces show a softened copy of
  the photo. A preset that follows the device wallpaper stays tint-only here.
- Customize opens the editor for that page, and Add New opens it for the new
  photo. The date and the clock are outlined: tap the clock for the Font & Color
  sheet (style tiles and colour swatches), tap the date to put it above, below or
  hide it. Drag the clock to move it, drag its corner handle to change height,
  drag or pinch the photo to reposition it. The "…" button holds the detailed
  values. Everything in the editor is a draft. Done stores and applies the draft
  once and returns to the lockscreen; Cancel or Back drops it, and a new photo
  that is cancelled leaves nothing behind.
- The notification stack is the front card with the lips of two cards behind it;
  each lip shows a one-line summary. Drag it upward and it peels open: the front
  card follows the finger, and each card behind stays put until the one in front
  has cleared it. Once the second card has been revealed the rest opens by itself
  into a list, and the clock contracts as the list approaches it. A short fast
  upward flick opens it too, and so does a tap on the closed stack or one of its
  lips. Dragging down closes it; a small drag springs back. The open list has an
  ✕ above it: one tap arms it as "Clear", a second clears every notification
  that can be cleared.
  At full expansion, continued upward dragging scrolls the list, and the clock
  and date scroll away with it; cards move into the room the clock leaves and
  fade only at the top of the screen. Scrolling back brings the clock back, and
  pulling down past the top closes the stack. A list that
  fits under the clock opens only as far as it needs, so the clock keeps its
  size; the clock contracts only for what does not fit. How calmly the stack
  opens is the "Opening speed" value in Calibration.
- Tap an app group to open its members; Show less folds that group. Tap an
  individual notification to open its host action, or details for a mock.
- Swipe left partway for Options/Clear; swipe farther to dismiss. Ongoing items
  cannot be cleared. Long-press opens details and available host actions.
- Pull down from the upper region on the right: Control Center; left: Notification
  Center. Begin inside the screen because Android reserves physical edge gestures.
- Control Center: + enters editing; drag modules to reorder, use the corner
  handle to cycle supported sizes, minus to remove, and Add a control to restore
  modules. Long-press a normal module to expand it.
- Swipe upward near the bottom of an overlay: return to the previous scene.
- Fingerprint affordance requests a simulated unlock from the preview host.
- Two-finger stationary hold, or long-press the small battery/status region:
  hidden developer controls. A debug APK can cold-launch with `--ez developer true`.
  The Notification motion slider freezes expansion to inspect intermediate poses
  while inserting/removing notifications or updating progress.
- A debug APK imports a photo already in its files directory, for checks without
  the system picker: `--es importPhoto name.jpg`.
- Developer controls > Calibration: the shared calibration page. It shows the
  lockscreen over the active wallpaper with test notifications (0/1/5/20, long
  text, groups) and a deck that can still be dragged. Values apply as they
  change; Save persists them, Cancel restores the saved ones, Move swaps the
  panel between top and bottom. Export writes
  `Android/data/dev.rime.preview/files/rime-calibration.json`; Import reads the
  same file as a candidate and refuses anything that is not calibration data.

Clock edits, color/date/dual-tone choices, and the shared notification/QS surface
radius, tint opacity and ordinary blur request persist locally across app restarts.
Developer controls generate 0/1/5/20 notifications, grouped/long content, progress
updates, insertion/removal and media/device-state fixtures. Real notifications are
optional through public NotificationListener access; without it the app uses mocks.

## Blur and integration limits

`BackdropSurface` accepts a geometric shape/mask, tint, opacity and blur radius.
The clock supplies actual digit outlines rather than a rectangular background.
The stock APK renderer fills that shape with translucency; it does not provide
true backdrop blur or pretend a screenshot is wallpaper. The SystemUI renderer
can later implement the same request through ordinary AOSP blur.

The first extracted clock face is the wide Axion face. Other clock families,
widgets, depth segmentation, media artwork effects and AOD/hardware integrations
are not externalized in this pass. See [ARCHITECTURE.md](ARCHITECTURE.md) and
[systemui-adapter/README.md](systemui-adapter/README.md) for ownership and integration.
