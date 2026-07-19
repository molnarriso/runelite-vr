<a href="https://www.youtube.com/watch?v=O1FB-BpxEIw">
  <img src="https://img.youtube.com/vi/O1FB-BpxEIw/0.jpg" alt="RuneLite VR Tutorial Island Playthrough" width="300">
</a>

[RuneLite VR Tutorial Island Playthrough](https://www.youtube.com/watch?v=O1FB-BpxEIw)

# RuneLite VR

This is a VR mod of RuneLite. It uses OpenXR for stereo rendering, and installs as a plugin into the
RuneLite you already have.

It is very crude right now. Currently in MVP phase. The goal is to get OpenXR stereo world rendering working, present the normal RuneLite UI in VR, and gather feedback from people who are willing to try an early build.

Feedback is very welcome, especially around comfort, controls, UI placement, readability, and what feels broken in real play.

For mod discussion, join the Discord: [https://discord.gg/mguXmu6FKw](https://discord.gg/mguXmu6FKw)

> [!WARNING]
> Do not do anything risky with this build. Do not go to the Wilderness. Do not boss. Do not do dangerous activities. Do not bring items you care about.
>
> Compliance with OSRS ToS is unclear and you might get banned. Don't use this on your main until this gets cleared with Jagex. 
> This is very clunky and still experimental. Input, menus, camera comfort, UI interaction, and rendering can all be awkward or unreliable.


## Before you start

* **Windows.** Linux is not packaged yet.
* **A headset with a working OpenXR runtime.** Tested with Oculus Quest + Virtual Desktop. Connect
  the headset and start the runtime *before* launching the game.
* **RuneLite, installed and launched at least once through the Jagex Launcher**, logged in to your
  account. That is what downloads the client and saves your login. Step 3 starts the game directly,
  without the Jagex Launcher, so it relies on that saved session — if you have never logged in this
  way, do that first and make sure the game works normally.

## Installing

Download the jar from the [Releases](../../releases) page. There is nothing to clone and nothing to
compile — this works with your existing RuneLite and does not modify it.

The filename tells you what it is: `vrgpu-0.1.1-rl1.12.33.jar` is VR build 0.1.1, for RuneLite
client 1.12.33.

Close RuneLite, then open **PowerShell** (Start menu → type "powershell") and run these three steps.

### Step 1 — Point VR at your dedicated GPU

Only needed on laptops with switchable graphics, and only once. If your PC has a single graphics
card, skip to step 2.

VR only works on the graphics card your headset is plugged into, and Windows decides that per
program. This is the same thing as picking "High performance" in Windows graphics settings.

```powershell
reg add "HKCU\SOFTWARE\Microsoft\DirectX\UserGpuPreferences" /v "$env:LOCALAPPDATA\RuneLite\jre\bin\java.exe" /t REG_SZ /d "GpuPreference=2;" /f
```

### Step 2 — Install the plugin

Replace `vrgpu.jar` with the path to the file you downloaded.

```powershell
mkdir -Force "$env:USERPROFILE\.runelite\sideloaded-plugins"
copy "vrgpu.jar" "$env:USERPROFILE\.runelite\sideloaded-plugins\"
```

### Step 3 — Launch the game

Use this command instead of your usual RuneLite shortcut, every time you want VR. Normal RuneLite
keeps working exactly as before.

```powershell
& "$env:LOCALAPPDATA\RuneLite\jre\bin\java.exe" -ea -cp "$env:USERPROFILE\.runelite\repository2\*" net.runelite.client.RuneLite --developer-mode
```

VR starts on its own — there is nothing to enable. It switches off the built-in GPU plugin while it
runs. To go back to the normal client, turn **VR GPU** off in the plugin list.

## Troubleshooting

**The game runs, but stays on the monitor and the log mentions
`XR_ERROR_GRAPHICS_DEVICE_INVALID`.** It is rendering on the wrong graphics card. Redo step 1. If it
still happens, add that same `java.exe` under NVIDIA Control Panel → Manage 3D settings → Program
Settings and set it to the high-performance GPU, since that can override the Windows setting.

**It worked before, and stopped after RuneLite updated.** Likely. The plugin is built against one
RuneLite version, and your launcher updates the client silently. It does not break on every update,
only when something it uses actually changes. Check the Releases page for a newer build.

**No mention of VR anywhere in the log.** The plugin was not picked up. Check that `vrgpu.jar` really
is in `%USERPROFILE%\.runelite\sideloaded-plugins`, and that you launched with the step 3 command —
launching RuneLite normally will ignore it.

**Errors about `DevToolsPlugin` and `VarbitID`, then a `ClientUI` crash.** Harmless, ignore them.
They come from a developer-only plugin that cannot start on a released client, and have nothing to
do with VR.

## How it works

Not needed to play — this is just what the commands in step 3 are for.

* **Why this isn't just a normal plugin.** VR cannot be installed the usual way. It is not on the
  Plugin Hub and cannot be loaded by the normal RuneLite shortcut, which is what the launch command
  works around. Making it a proper one-click plugin is something that will need to be worked out
  with the RuneLite and OSRS developers.
* **Why `-cp ...\repository2\*` and a class name instead of `-jar`.** That cached client jar has no
  entry point recorded in it and none of its dependencies inside it, so the whole folder goes on the
  classpath and the entry point is named explicitly.
* **Why `-ea`.** Developer mode shows a fatal error dialog without assertions enabled.
* **Why that `java.exe`.** The client needs Java 11 or newer, and whatever `java` is on your PATH may
  be older. RuneLite ships its own.

## For developers

The plugin sources live in `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu`.

### Running from source

This repo is a RuneLite fork with the VR plugin built in, so a client built from it already has VR.
None of the install steps above apply. Requires JDK 11+.

```powershell
./gradlew :client:shadowJar
java -jar runelite-client/build/libs/client-<version>-shaded.jar
```

Or open the repo in your IDE as a Gradle project and run `net.runelite.client.RuneLite` with module
classpath `runelite.client.main` and the project root as the working directory.

Switchable-graphics laptops still need step 1, against whichever `java.exe` the IDE launches with.

### Building the release jar

`vrgpu-plugin/` is a separate build that packages the plugin for the Releases page. It compiles the
same sources in place, so there is only ever one copy of the code.

```powershell
./gradlew -p vrgpu-plugin jar
```

Produces `vrgpu-plugin/build/libs/vrgpu-dev-rl<version>.jar`. The client and LWJGL versions it
targets live in `vrgpu-plugin/gradle.properties`; override them per build when needed:

```powershell
./gradlew -p vrgpu-plugin jar -PruneliteVersion=1.12.33 -PlwjglVersion=3.3.2
```

Releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml), triggered by
pushing a `v*` tag. It builds from the tagged commit and opens a **draft** release with the jar and
`LICENSE` attached, which you then review and publish.

## License

BSD 2-Clause — see [LICENSE](LICENSE).

This project is a fork of [RuneLite](https://github.com/runelite/runelite/); see the original repo
for the underlying client. The VR renderer is derived from RuneLite's GPU plugin, so the licence
travels with it — `LICENSE` is included alongside every released jar.
