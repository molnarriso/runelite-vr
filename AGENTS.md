# RuneLite VR Notes

We are building a RuneLite VR GPU plugin: OpenXR stereo rendering for the 3D world, plus VR presentation and interaction for the normal RuneLite UI.

## UI / Render Categories

1. **Canvas UI panel**
   The whole normal client `BufferProvider` is copied into one texture and rendered as a flat VR panel. This includes inventory, chat, minimap, widgets, and most normal overlays.

2. **Actor UI billboards**
   Actor-attached 2D UI is captured through `RenderCallback.addEntity(..., ui=true)` when the renderable is an `Actor`. Intended for hitsplats, health bars, and similar actor UI; captured pixels are re-rendered as world billboards.

3. **3D scene geometry**
   The OSRS scene is rendered by `VrGpuPlugin` through `DrawCallbacks`, using OpenGL shaders, VAOs/VBOs, zones, dynamic models, and per-eye VR projection.

4. **Canvas-cropped billboards**
   Special 2D UI is cropped from the already-rendered canvas, not from `addEntity`. Current examples are context hints and right-click menus. These are then shown as world-anchored billboards.

## Main VR Files

- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrGpuPlugin.java`: plugin entry point, DrawCallbacks, scene rendering, UI capture, VR frame flow.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrUi.java`: flat canvas UI panel and pointer rendering.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrBillboardRenderer.java`: actor/context/menu billboard textures.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrInteraction.java`: controller interaction and click handling.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrSceneRaycaster.java`: VR ray picking against scene/world objects.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/openxr/`: OpenXR context, swapchains, and input.


# AI Workflows
- after edit, run ```./gradlew :client:compileJava``` to check compilation error. Do not run tests or try to execute the program.
- each edit needs to be manually confirmed by user in VR headset.
