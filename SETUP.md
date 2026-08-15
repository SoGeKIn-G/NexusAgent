# Setup

The toolchain is **already installed and working** on this machine. This document records
what was installed and where, so it can be reproduced or repaired.

No Android Studio. Everything builds from the command line, which suits a 7.4 GB machine —
Studio would idle at ~2 GB alongside the Gradle daemon.

---

## What's installed

| Component | Version | Location |
|---|---|---|
| JDK | Microsoft OpenJDK 21.0.12 | `C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot` |
| Gradle | 9.6.1 | `D:\dev\gradle-9.6.1` |
| Android SDK | cmdline-tools + platform-tools | `D:\dev\Android\Sdk` |
| Platforms | android-36, android-37.0, android-37.1 | `…\Sdk\platforms` |
| Build-tools | 36.0.0, 36.1.0, 37.0.0 | `…\Sdk\build-tools` |
| Gradle cache | — | `D:\dev\gradle-home` |

Everything except the JDK lives on `D:` — `C:` has under 24 GB free and the Android
defaults would have filled it.

### Environment variables (already set, User scope)

```
JAVA_HOME         C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot
ANDROID_HOME      D:\dev\Android\Sdk
ANDROID_SDK_ROOT  D:\dev\Android\Sdk
GRADLE_USER_HOME  D:\dev\gradle-home
```

`PATH` additionally contains the JDK `bin`, Gradle `bin`, `platform-tools` (for `adb`),
and `cmdline-tools\latest\bin`.

> These were set with `[Environment]::SetEnvironmentVariable(..., "User")`, so they
> persist across reboots — but **any terminal open at the time won't see them**. Open a
> new one.

The Android SDK licences (7 agreements) have been accepted; they are recorded as hash
files in `D:\dev\Android\Sdk\licenses\`.

---

## Daily commands

### The short way

```powershell
cd D:\PROJECTS\NEXUS_AGENT
.\run.ps1              # build + install + launch on the phone
.\run.ps1 -Logs        # ...and then stream the perception log
.\run.ps1 -TestOnly    # just the JVM unit tests, no device needed
.\run.ps1 -Clean       # wipe build outputs first
```

[run.ps1](run.ps1) sets its own environment, so it works in any terminal — including one
that was already open before the toolchain was installed.

### The long way

Run these from `D:\PROJECTS\NEXUS_AGENT`.

```powershell
.\gradlew.bat assembleDebug          # build the APK
.\gradlew.bat installDebug           # build + install to the connected phone
.\gradlew.bat :core:model:test       # unit tests (JVM, no device needed)
.\gradlew.bat clean                  # wipe build outputs

adb devices                          # is the phone connected?
adb logcat -s NexusPerception        # watch the accessibility service
adb uninstall com.nexusagent.debug   # remove the app
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`

---

## Connecting your phone

1. **Settings → About phone → tap "Build number" 7 times** → developer options unlock.
2. **Settings → System → Developer options**, enable:
   - ✅ **USB debugging**
   - ✅ **Stay awake** (screen stays on while charging — you'll want this constantly)
3. Plug in via USB. On the phone, change the USB mode to **File Transfer (MTP)** —
   "Charging only" blocks adb on many devices.
4. Accept the **"Allow USB debugging?"** prompt. Tick *Always allow from this computer*.

Verify:

```powershell
adb devices
```

```
List of devices attached
R58M20XXXXX     device
```

**`unauthorized`** → re-accept the prompt on the phone.
**Empty list** → try a different cable. Many USB cables are charge-only, and this wastes
more time than any other step here.

---

## Gemini API key

Not needed until M4, but get it now.

1. <https://aistudio.google.com/apikey> → **Create API key** (free tier, no card)
2. Paste it into the app's Settings screen when M4 lands — **not** into source code.

> ⚠️ `.gitignore` excludes `local.properties` and `secrets.properties`. The key is stored
> in encrypted DataStore at runtime and must never be committed.

Free-tier limits, roughly 10–15 requests/min and 250–1,500/day on Flash. One agent step =
one request. Verify current numbers at <https://ai.google.dev/gemini-api/docs/rate-limits>.

---

## Version notes

These were resolved against what is actually published, not guessed. If you ever bump
them, note the constraints that bit us:

- **AGP 9 has built-in Kotlin.** Do **not** apply `org.jetbrains.kotlin.android`. In
  `:core:model`, `org.jetbrains.kotlin.jvm` must be applied **without a version** — the
  KGP is already on the build classpath, and specifying a version fails.
- **KSP decoupled from Kotlin's version** at 2.3.0. It is no longer `<kotlin>-<ksp build>`.
- **`compileSdk` must be 37**, required by `androidx.core:1.19`, `lifecycle:2.11`, and
  `hilt-navigation-compose:1.4`.
- **`:core:model` pins `jvmTarget` to 17.** Kotlin otherwise defaults to the JDK running
  Gradle (21), which Gradle rejects as inconsistent with the Java tasks.
- The AGP 9 `sourceSets` DSL changed; `src/main/kotlin` is picked up automatically, so no
  manual `srcDirs` registration is needed (and the old form now fails on library modules).

---

## Adding Android Studio later (optional)

Nothing here blocks it. Install Studio, and when it asks for an SDK location point it at
`D:\dev\Android\Sdk` — it will adopt this setup as-is. Worth doing if you want Compose
visual previews around M6; unnecessary before then.
