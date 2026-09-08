# Magic Magnifier

A live magnifier with no controls. Point it at something; the closer you get, the more it
magnifies. Distance to whatever sits in the middle of the frame is the only input. Once
running the screen is nothing but video — no buttons, no text, no overlays.

Autofocus does not measure distance. It drives a lens to maximise contrast or phase agreement
and reports a *lens position*. Android's Camera2 exposes that as `LENS_FOCUS_DISTANCE` in
diopters, which is only physically meaningful when `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`
reports `CALIBRATED` or `APPROXIMATE`. Plenty of devices report `UNCALIBRATED`, where the
docs say the units "do not correspond to any physical units."

That turns out not to matter. The app never needs metres — it needs a signal that is
*monotonic and repeatable* in distance on one device, and focus distance is that even when
uncalibrated. Distances are therefore treated as **nominal metres** throughout, and the
magnification curve is refitted per device from telemetry when the hardware is uncalibrated.

## Taking a snapshot

There is no shutter button, and a single tap is out of the question -- at 5x a palm brushing
the glass would fire it constantly. The gesture is summoned instead:

1. **Touch anywhere.** A black-and-white ring appears under your finger and starts to fade.
2. **Touch inside that ring** while it is still visible. The image stops dead, the screen
   blinks black then white, and the captured frame is held for a moment before live resumes.
   The file lands in `Pictures/Magic Magnifier`, at full capture resolution, framed exactly as
   it was on screen.
3. **Or don't.** Let the ring fade -- about three seconds -- and nothing happened.

Because the confirming touch is anchored to a place, an accidental brush cannot reach it: it
would have to land twice, in the same spot, inside the window. A touch that misses the ring
re-arms at the new point rather than doing nothing.

The ring is two adjacent opaque bands, one white and one black. That is not decoration. The
app has no idea what is behind it -- white paper, black text, a bright screen -- and a
single-colour ring vanishes against its own colour exactly when it is needed. With two bands
touching, one of them always contrasts.

The confirmation is screen-wide for a reason. It used to be the ring pulsing outward, which
turned out to be invisible in use: the ring is centred on the touch point, so the only visual
confirmation the app gave was underneath the finger that triggered it. The blink is black
*then* white for the same reason the ring is two-tone -- a white flash barely registers on a
white price tag, and a black one vanishes against a dark subject, so one phase always
contrasts. If a save fails, two slower black-only pulses follow, which is the only way an app
with no interface can say so.

Touches that start at the very edge of the screen are ignored, because they are on their way
to being a back, home or recents gesture. Without that, a ring appeared every time you
navigated -- and two quick back-swipes could put the second inside the first's ring and fire
the shutter.

Nothing about this reaches the saved file: the ring lives in the view hierarchy, the image
comes from the camera.

## How it decides how much to magnify

The signal path:

```
LENS_FOCUS_DISTANCE (per frame, diopters)
  -> median of 5                       discard single-frame outliers
  -> 1-Euro filter                     steady at rest, responsive in motion
  -> hold while autofocus is scanning  mid-sweep readings are actively misleading
  -> MagnificationCurve                log-log anchor table, distance to zoom ratio
  -> ZoomController                    deadband + multiplicative rate limit
  -> CameraControl.setZoomRatio        at 15Hz
```

**The curve is fitted to the phone, not hardcoded.** `SHAPE` holds hand-tuned proportions; the
endpoints come from the device — the near end from `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, the top
from the reported maximum zoom ratio. So the curvature you would tune by feel survives, while
the range adapts. One binary suits any phone, and each gets its full usable travel instead of
spending half the curve past a focus wall it cannot see through. A fixed-focus camera, which
gives no distance signal at all, correctly stays at 1x.

Two things beyond sensing shape the design:

- **Magnification compounds.** Moving closer already magnifies optically, so apparent on-screen
  size goes as `Z(d)/d`, not `Z(d)`. The curve anchors are deliberately gentler than instinct
  suggests.
- **The minimum-focus wall.** A main camera stops focusing around 10cm. Going closer needs
  another lens. Most modern phones expose that through a zoom range dipping below 1.0 and
  handle it invisibly; where they don't, `LensStrategy` rebinds to a close-focusing physical
  camera and the swap is masked by holding the last frame and dissolving.

## Build and install

Needs JDK 17 and an Android SDK; `local.properties` points at the latter. Android 10 (API 29)
or newer -- `MediaStore` needs no permission from there up, which keeps the app to exactly one
system dialog in its life, the camera prompt on first launch.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pobox.magicmagnifier/.MagnifierActivity
```

Connecting a phone from WSL2, which has no USB passthrough by default — enable Wireless
debugging on the phone under Developer options, then:

```bash
adb pair <phone-ip>:<pair-port>     # one time, with the code the phone shows
adb connect <phone-ip>:5555
adb devices                          # confirm before building
```

## Tuning

Two loops, and they are different jobs.

**Off device.** Everything that decides how the app *feels* is pure logic, so it runs on the
JVM with no phone attached:

```bash
tools/curve-check/run.sh           # correctness and feel checks
tools/curve-check/run.sh Fit       # the curve each device would get, and how much it can reach
tools/curve-check/run.sh Sweep     # 1-Euro minCutoff/beta vs rest stability and tracking
tools/curve-check/run.sh Sweep2    # zoom rate limit vs tracking and autofocus-step absorption
tools/curve-check/run.sh Deadband  # deadband vs stillness at rest
```

Worth knowing when touching the filter: `beta` is in Hz per (metre/second). Textbook 1-Euro
values near 0.5 assume pixels or degrees, where velocities run into the hundreds. Distance in
metres moves at about 0.3 m/s, so a small beta leaves the filter effectively fixed and lagging.

**On device.** The app has no interface, so it reports through logcat and a CSV:

```bash
adb logcat -s MagMag
adb pull /sdcard/Android/data/com.pobox.magicmagnifier/files/     # telemetry CSVs
```

The startup block prints the focus calibration mode, minimum focus distance, zoom range and
physical camera list — read it first, because whether the device is calibrated decides whether
`MagnificationCurve.SHAPE` is expressed in real centimetres or in that device's own units.

Then hold the phone at measured distances (50, 30, 20, 12, 8, 4 cm) against a textured target
and confirm from the CSV that the filtered value moves monotonically and repeatably. If it is
coarse or non-monotonic on the hardware, that is the real go/no-go for the whole idea.

## Measured: Galaxy S24 Ultra (SM-S928U1, Android 16)

The first device this ran on, and the source of most of the constants in the code.

Focus distance reports `APPROXIMATE`, so distances are **real metres** and the curve anchors
need no refitting. The signal is finely quantised — hundreds of distinct values with roughly
0.1–0.5 cm resolution through the working range — which was the main risk going in and turned
out not to be a problem at all. Autofocus held a confident reading on 97% of frames.

Its lenses:

| id | focal | closest focus | |
|---|---|---|---|
| 0 | 6.3 mm | 10 cm | main, the one we bind |
| 2 | 2.2 mm | 5 cm | ultra-wide |
| 6 | 7.9 mm | 40 cm | 3x tele |
| 7 | 18.6 mm | 80 cm | 5x periscope |

Two things worth knowing before changing anything:

**The close-focus wall is at 10 cm**, and measured runs hit it exactly. This is what drove the
curve to be fitted per device rather than hardcoded. A fixed table running to 2 cm and 15x
spent its whole top half beyond this camera's focus wall, so magnification stopped at 4.8x
while the hardware offered 10x — less than half the range was usable. The fitted curve spans
50–11 cm and 1–10x instead, and reaches the full 10x in focus. Measured on device at 15 cm:
7.2x, against 3.3x under the old table.

**The ultra-wide is a worse macro lens than it looks.** It focuses to 5 cm, but magnification
goes as focal length over distance, so 2.2 mm at 5 cm is optically *worse* than 6.3 mm at
10 cm. After sensor-size differences it buys perhaps 20%, for a 12 MP sensor instead of 200 MP.
`LensStrategy` will not switch to it here anyway, since the logical camera's sub-1.0 zoom range
sets `frameworkHandlesCrossover`.

### On handheld shake at high zoom

The worry was that at high magnification the tap itself would shake the frame, and that
`ImageCapture` needing a further 100-300ms would turn every snapshot into a blur. Two
mitigations went in on that assumption: firing on touch-down rather than touch-up, and the
preview stabilisation already enabled in `CameraEngine`.

It has since been used in earnest, handheld, on electronic shelf price tags in a supermarket
— small, flat, high-contrast text read at roughly 15-25cm, which is squarely where the fitted
curve does its work — with no shake problem. Worth recording because it was a flagged risk
rather than a measured one, and because synthetic `adb shell input tap` events cannot test it:
they do not move the phone.

### On snapshots matching the screen

Binding `ImageCapture` naively saves the sensor's whole 4:3 frame, while the preview crops it
to fill a tall screen. The result is a snapshot far wider than what you were looking at --
measured at aspect 0.750 against the screen's 0.462, which in practice means magnifying a word
and getting a photograph of the entire desk.

The fix is to bind preview, analysis and capture together in a `UseCaseGroup` sharing one
`ViewPort` taken from `PreviewView` itself, so the crop matches by construction instead of by
recomputing an aspect ratio and hoping. Measured after: 0.462 against 0.462.

### On the system gesture margins

The reported insets are not sufficient on their own, and it takes measuring to see it. This
device on gesture navigation reports:

    Insets{left=0, top=128, right=0, bottom=126}

Generous margins top and bottom, and **nothing at all down the sides** — even though
back-swipe is live on both of them. With the system bars hidden Android does not report the
side strips. So the 28 dp floor in `MagnifierActivity` is load-bearing rather than insurance;
relying on the insets alone would leave the back gesture completely unguarded.

Two related traps: the insets must be read at touch time, because
`OnApplyWindowInsetsListener` is never called on this device with the bars hidden and a cached
value stays at zero forever. And Android itself drops edge taps before delivery — a tap at
x=12 never reaches the app, while one at x=20 does — so the app-side check is a second layer
over protection the OS usually provides, for the devices and navigation modes where it does
not.

### On the auto-torch thresholds

Every number in `AutoTorch` came from a measured session; none of them survived contact with
real data in their original form. Three separate bugs, each hiding the next:

1. Turn-*off* tested brightness, so the torch brightened the scene that had triggered it and
   switched itself off — a 1.2 s oscillation. Brightness now only ever turns the light on.
2. The arm distance was a guessed 15 cm. Across a session with 555 consecutive genuinely dark
   frames (luma 12, ISO pinned at 6938, 41.6 ms exposure) the phone never once got that close
   while dark — nearest approach 15.3 cm. Dark frames ran 15.3–75 cm, mean 32 cm, so the gate
   is now 35 cm arm / 50 cm release.
3. The dark-frame counter required darkness and proximity on the same frame, so one autofocus
   hunt reset it — and hunting is what the lens does in the dark. Darkness now accumulates on
   its own and distance is checked only at the moment of firing.

Worth replaying a recorded CSV against any new thresholds before reaching for the phone; that
is how the 35 cm gate was validated.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Apache 2.0 rather than MIT for the express patent grant, which matters more than
usual here: the app is essentially a technique — driving zoom from autofocus distance —
in a patent-dense corner of the camera and AR space. It also matches every dependency,
all of which are Apache 2.0.
