<a href="https://www.youtube.com/watch?v=O1FB-BpxEIw">
  <img src="https://img.youtube.com/vi/O1FB-BpxEIw/0.jpg" alt="RuneLite VR Tutorial Island Playthrough" width="300">
</a>

[RuneLite VR Tutorial Island Playthrough](https://www.youtube.com/watch?v=O1FB-BpxEIw)

# RuneLite VR

This is a VR mod of RuneLite.

This project is a fork of [RuneLite](https://github.com/runelite/runelite), licensed under BSD 2 Clause. See the original repo for the underlying client.

It uses OpenXR for VR rendering.

It is very crude right now. Currently in MVP phase. The goal is to get OpenXR stereo world rendering working, present the normal RuneLite UI in VR, and gather feedback from people who are willing to try an early build.

Feedback is very welcome, especially around comfort, controls, UI placement, readability, and what feels broken in real play.

For mod discussion, join the Discord: [https://discord.gg/mguXmu6FKw](https://discord.gg/mguXmu6FKw)

> [!WARNING]
> Do not do anything risky with this build. Do not go to the Wilderness. Do not boss. Do not do dangerous activities. Do not bring items you care about.
>
> Compliance with OSRS ToS is unclear and you might get banned. Don't use this on your main until this gets cleared with Jagex. 
> This is very clunky and still experimental. Input, menus, camera comfort, UI interaction, and rendering can all be awkward or unreliable.


## Running The VR Mod

Open the project in your IDE as a Gradle project.

Run configuration used for development:

* Java: 25
* Module classpath: runelite.client.main
* Main class: net.runelite.client.RuneLite
* Working directory: project root

For CLI users, run:

```powershell
./gradlew :client:compileJava
./gradlew :client:shadowJar
java -jar runelite-client/build/libs/client-1.12.27-SNAPSHOT-shaded.jar
```

Enable the VR GPU plugin in the RuneLite plugin list.

**Make sure your headset is connected during app startup.** Start your OpenXR runtime before launching the client.

This has been tested with Oculus Quest + Virtual Desktop.

This project is a fork of RuneLite, licensed under BSD 2-Clause. See the [original repo](https://github.com/runelite/runelite/) for the underlying client.
