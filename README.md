# Magic Magnifier

A live magnifier with no controls. Point it at something; the closer you get, the more it
magnifies. Distance to whatever sits in the middle of the frame is the only input. Once
running the screen is nothing but video — no buttons, no text, no overlays.

## How it decides how much to magnify

Autofocus does not measure distance. It drives a lens to maximise contrast or phase agreement
and reports a *lens position*. Android's Camera2 exposes that as `LENS_FOCUS_DISTANCE` in
diopters, which is only physically meaningful when `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`
reports `CALIBRATED` or `APPROXIMATE`. Plenty of devices report `UNCALIBRATED`, where the
docs say the units "do not correspond to any physical units."

That turns out not to matter. The app never needs metres — it needs a signal that is
*monotonic and repeatable* in distance on one device, and focus distance is that even when
uncalibrated. Distances are therefore treated as **nominal metres** throughout, and the
magnification curve is refitted per device from telemetry when the hardware is uncalibrated.

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

Two things beyond sensing shape the design:

- **Magnification compounds.** Moving closer already magnifies optically, so apparent on-screen
  size goes as `Z(d)/d`, not `Z(d)`. The curve anchors are deliberately gentler than instinct
  suggests.
- **The minimum-focus wall.** A main camera stops focusing around 10cm. Going closer needs
  another lens. Most modern phones expose that through a zoom range dipping below 1.0 and
  handle it invisibly; where they don't, `LensStrategy` rebinds to a close-focusing physical
  camera and the swap is masked by holding the last frame and dissolving.

## Build and install

Needs JDK 17 and an Android SDK; `local.properties` points at the latter.

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
tools/curve-check/run.sh          # correctness and feel checks
tools/curve-check/run.sh Sweep    # 1-Euro minCutoff/beta vs rest stability and tracking
tools/curve-check/run.sh Sweep2   # zoom rate limit vs tracking and autofocus-step absorption
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
`MagnificationCurve.ANCHORS` is expressed in real centimetres or in that device's own units.

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

**Magnification tops out at 4.8x, at 10 cm.** That is the main camera's close-focus wall, and
measured runs hit it exactly. The curve's anchors below 10 cm are unreachable here, so roughly
half its range is dead on this device while the hardware still offers 10x zoom. Rescaling
`ANCHORS` onto the reachable 50–10 cm band is the obvious next move; the thing to watch when
doing it is whether the framework hands over to the tele lenses, which cannot focus nearer than
40 cm. It did not do so up to 4.8x, but 10x is where it would most plausibly try.

**The ultra-wide is a worse macro lens than it looks.** It focuses to 5 cm, but magnification
goes as focal length over distance, so 2.2 mm at 5 cm is optically *worse* than 6.3 mm at
10 cm. After sensor-size differences it buys perhaps 20%, for a 12 MP sensor instead of 200 MP.
`LensStrategy` will not switch to it here anyway, since the logical camera's sub-1.0 zoom range
sets `frameworkHandlesCrossover`.

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
