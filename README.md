# Slot Alarm Clock

A 24-hour alarm clock for Android, styled as a machined metal slot machine.

Four independent dials roll like a fruit machine's reels — with real motion blur and a sprung
mechanical overshoot when they land — over a brushed-chrome plate. A metal rocker
switch arms the alarm. The same clock is available as a home-screen widget.

---

## Read this first: move the project out of OneDrive

The project currently sits in `Documents\Default Project`, which is inside a **OneDrive**-synced
folder. OneDrive continuously locks and rewrites files under `app\build` while Gradle is running,
which produces sporadic, confusing build failures and slow incremental builds.

Move it somewhere local before you build:

```powershell
Move-Item "C:\Users\ajeet\OneDrive\Documents\Default Project\SlotAlarmClock" "C:\AndroidProjects\SlotAlarmClock"
```

Then open `C:\AndroidProjects\SlotAlarmClock` in Android Studio.

---

## Requirements

| | |
|---|---|
| Android Studio | Ladybug or newer, with the **JDK 17** toolchain |
| Android SDK | **API 36** platform + Build-Tools 35.0.0 (Android Studio will offer to install these) |
| Device / emulator | Android 8.0 (API 26) or newer |

Everything else — Gradle 8.14.6, AGP 8.13.2, Kotlin 2.2.21 and the Compose BOM — is pinned in
`gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`. The Gradle wrapper
JAR and scripts are already checked in, so a clean clone needs no setup.

## Build and run

```powershell
cd C:\AndroidProjects\SlotAlarmClock
.\gradlew.bat :app:installDebug
```

Or just press **Run** in Android Studio.

## Add the widget

1. Run the app once so Android registers the widget provider.
2. Long-press the home screen → **Widgets** → **Slot Alarm** → drag the widget out.
3. Tap the clock face to open the app; tap the **ON/OFF** switch to arm the alarm without
   opening anything.

---

## What it does

### The dials carry two jobs

There is only one clock metaphor in this app, and it does both jobs.

* **Clock mode (default)** — the dials show the live 24-hour time and are inert. They roll on
  their own whenever a digit changes.
* **Set mode** — tap the `ALARM 07:30` strip and the *same* dials hand over to alarm editing.
  Now they are directly draggable: flick one vertically and it spins, blurs, and settles onto
  the nearest face with a haptic tick on every digit it passes. Tap **SET** and the dials spring
  back into clock mode.

This avoids the usual split where a beautiful display sits next to a grey system time picker.

### 24-hour correctness

Each digit is its own reel, and the faces are declared explicitly rather than assuming 0–9:

```kotlin
val HourTensSpec   = ReelSpec(listOf(0, 1, 2))   // the tens digit can never pass 2
val HourOnesSpec   = ReelSpec((0..9).toList())
val MinuteTensSpec = ReelSpec((0..5).toList())
val MinuteOnesSpec = ReelSpec((0..9).toList())
```

The hour reel can therefore never spin its way to 27. Combinations that would still overflow
(hour-tens 2 with hour-ones 7) are clamped to 23, which spins the ones reel back to 3.

### The alarm is real

* `AlarmManager.setAlarmClock` — always exact, and it does **not** need the exact-alarm
  permission, so nothing has to be granted by hand for the alarm to work. It also puts the
  alarm in the system status bar.
* A foreground service owns the ringing: the default alarm ringtone looping, a vibrating
  pattern, and a notification with a **full-screen intent** so it reaches the lock screen.
* **Snooze 5 min** and **Dismiss** work straight from the notification.
* Dismissing re-arms for the next day, so an enabled alarm is a daily alarm.
* A 10-minute auto-stop means it can never ring forever if you're away from the phone.
* `BootReceiver` re-arms after a reboot. It listens for `BOOT_COMPLETED` (delivered after the
  first unlock) rather than `LOCKED_BOOT_COMPLETED`, because the alarm time lives in
  credential-encrypted storage that is unreadable before unlock — firing earlier would read
  defaults and silently cancel the alarm.

---

## How the metal works

This is the part worth reading, because it is where the interesting engineering is.

### One material, one tier

`ui/metal/Metal.kt` is the only place that knows what "metal" looks like, and it paints onto a
plain `android.graphics.Canvas` — not a Compose `DrawScope`. That is deliberate: the widget has
to render the identical material into a `Bitmap`, and one shared renderer means the app and the
widget can never drift apart.

The material is a hand-tuned twelve-stop chrome ramp — two blown-out highlights split by a dark
band — with a fine **anisotropic brushing pass** laid over it: one hairline per pixel row, each
with a deterministic alpha, so the surface reads as machined rather than printed. All of it is
baked once per size into a texture `Bitmap` and wrapped in a `BitmapShader`.

### Why there is no `RuntimeShader`

Earlier versions tried to upgrade the grain to an AGSL `RuntimeShader` on Android 13+. It cannot
work, and the reason is worth writing down.

A `RuntimeShader` may only be drawn to a **hardware-accelerated** canvas. A bitmap's canvas is
**always software**. Baking into a bitmap is not optional — it is the thing that lets one renderer
serve the widget, and it is what makes the material immutable — so the AGSL tier was always being
drawn onto a software canvas, and it threw:

```
java.lang.IllegalArgumentException: Software rendering doesn't support RuntimeShader
    at android.graphics.BaseCanvas.throwIfHasHwFeaturesInSwMode(BaseCanvas.java:1089)
```

That is a hard crash on the app's first frame *and* inside every widget update, on every device
from Android 13 up. On Android 8–12 the same code took the gradient branch and worked, which is
why the fault looked like "the widget is blank" rather than "the renderer is broken". The AGSL
shader was replaced by twenty lines of real 1-pixel strokes: no cost, rasterises everywhere, and
identical in the widget.

### Why the material is baked, and why nothing mutable is drawn

All per-frame motion — the specular sweep gliding across the plates — is a cheap, immutable
`LinearGradient` overlay painted on top of the baked material. The bitmap supplies the *material*;
the overlay supplies the *movement*.

That split matters because Compose records draw commands into a display list and may replay them
later. A mutable shader referenced by a paint could leak one frame's uniform into an earlier
recorded op. Every shader this app hands to a canvas is therefore either a `BitmapShader` of baked
pixels or a freshly-built gradient: replayable by construction, not by luck.

### Slot-machine feel

Three details do the heavy lifting (`ui/SlotReel.kt`):

**Motion blur** is genuine multi-sample blur, not a `RenderEffect`. Several copies of the strip
are drawn along the direction of travel with `PorterDuff.Mode.ADD` at `1/N` alpha each. Additive
blending is what stops the overlapping copies from muddying into grey mush — and because the
window behind them is near-black, they read as streaks of light. The crisp pass fades out as the
blur ramps up.

**The landing** uses `easeOutBack` with a modest spring constant, so a reel overshoots by about
5% of one slot and snaps back. That is the clunk. The reels also wrap and pick up a full extra
turn on short hops, so a one-digit change still feels like a spin.

**Drag settles like a spin**, not like a slider: on release the reel animates to the nearest face
using the same easing, with a haptic tick per digit crossed on the way.

### Performance note

The sheen animation runs forever. It is deliberately held as a `State<Float>` and passed down as
a lambda, then read *inside* a draw scope:

```kotlin
val sweep: State<Float> = rememberInfiniteTransition(...).animateFloat(...)
val sheen: () -> Float = { sweep.value }   // read during draw, not during composition
```

Reading `sweep.value` at the top of the composable would recompose the entire screen 60 times a
second. Reading it in the draw lambda invalidates only the draw phase.

---

## The widget

A home-screen widget is a `RemoteViews` description, not a canvas. **It cannot run a shader and
it cannot animate.** Rather than fake it, the widget draws a genuine still frame using the exact
same `Metal` and the exact same `drawReelStrip` the app uses.

* Two `ImageView`s, two bitmaps, two tap targets: the plate opens the app, the switch toggles the
  alarm in place via a broadcast.
* Refreshed by a self-re-arming `AlarmManager` alarm on each minute boundary. `updatePeriodMillis`
  is unusable here — it bottoms out at 30 minutes.
* `RTC` rather than `RTC_WAKEUP`: a widget is only visible when the device is already awake, so
  waking the screen every minute would be pure battery waste. It also re-renders on resize, on a
  time change, and on a timezone change.
* Bitmaps are capped at 60,000 pixels for the plate and 24,000 for the switch. `RemoteViews`
  travel over Binder, whose transaction buffer is about 1 MB shared across the whole call; a
  full-resolution bitmap for a large widget blows straight through it, and when it does the
  update is silently dropped and the widget sits there blank. The budgets are kept well under
  the ceiling rather than near it, and a render fault degrades to a plain time readout instead of
  an empty frame.

## Permissions, and why each one is there

| Permission | Reason |
|---|---|
| `POST_NOTIFICATIONS` | Requested when you first arm the alarm. The ringing service needs a visible notification. |
| `SCHEDULE_EXACT_ALARM` | Only for the widget's per-minute redraw. The alarm itself doesn't need it. If you decline, the widget still ticks, just with platform drift. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm the alarm after a reboot. |
| `VIBRATE`, `WAKE_LOCK` | Ringing. |
| `FOREGROUND_SERVICE`, `…_MEDIA_PLAYBACK` | Play the ringtone reliably from the background. |
| `USE_FULL_SCREEN_INTENT` | Reach the lock screen when the alarm fires. |

---

## If it crashes on the phone

There is no `adb` on the machine that builds this, so a fault has to explain itself from the
phone alone. Two things exist for that:

* `SlotAlarmApp` installs a last-chance exception handler before anything else runs, and every
  startup milestone is written to `SharedPreferences` as a breadcrumb. So a fault that is too
  early to catch still leaves a trail of how far the process got.
* `MainActivity` checks for a stored report **before** it touches Compose. If there is one, it
  paints it with a plain `TextView` — no custom theme, no Compose — because the fault is most
  likely somewhere in that stack, and the report has to survive it. Tapping the report clears it
  and reloads the clock.

The widget uses the same idea: if a bitmap render throws, it draws the time *and* the one-line
reason into the widget rather than leaving an empty box.

---

## Layout

```
app/src/main/java/com/ajeet/slotalarmclock/
├── SlotAlarmApp.kt                 Installs the crash reporter, first breadcrumb
├── CrashLog.kt                     Breadcrumbs + last-crash report (SharedPreferences)
├── MainActivity.kt                 Clock screen host; permission requests; crash screen
├── RingActivity.kt                 Lock-screen ringing screen; self-closes
├── ui/
│   ├── metal/Metal.kt              ★ The entire material: ramps, brushing, plates, engraving
│   ├── SlotReel.kt                 ★ Reel model, easing, shared strip renderer, component
│   ├── MetalToggle.kt              The rocker switch
│   ├── SlotAlarmClockScreen.kt     Clock face, two modes, dial assembly
│   ├── RingingScreen.kt            Ringing face
│   └── Theme.kt                    Minimal dark Material scheme
├── data/AlarmStore.kt              Single source of truth (SharedPreferences + StateFlow)
├── alarm/
│   ├── AlarmScheduler.kt           setAlarmClock, next-occurrence maths
│   ├── AlarmReceiver.kt            Alarm entry point
│   ├── AlarmRingingService.kt      Sound, vibration, full-screen notification
│   └── BootReceiver.kt             Re-arm after reboot
└── widget/
    ├── SlotClockWidgetProvider.kt  Per-minute refresh, two tap targets
    └── WidgetRenderer.kt           Renders the plate and switch bitmaps
```

★ = the interesting files.

## Known limits

* **The widget does not animate.** This is an Android platform constraint, not an oversight.
  Only the in-app clock rolls.
* **The widget's minute hand can drift** by a few seconds if the exact-alarm permission is
  declined. Nothing else is affected.
* Drag sensitivity is derived from the reel's *actual* measured height rather than a constant, so
  flicking behaves identically whether the dial is 60dp on a narrow phone or 100dp on a tablet.
