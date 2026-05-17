# RuneLite VR Notes

We are building a RuneLite VR GPU plugin: OpenXR stereo rendering for the 3D world, plus VR presentation and interaction for the normal RuneLite UI.

## UI / Render Categories

1. **Canvas UI panel**
   The whole normal client `BufferProvider` is copied into one texture and rendered as a flat VR panel. This includes inventory, chat, minimap, widgets, and most normal overlays.

2. **Actor UI billboards**
   Actor-attached 2D UI is captured through `RenderCallback.addEntity(..., ui=true)` when the renderable is an `Actor`. Intended for hitsplats, health bars, and similar actor UI; captured pixels are re-rendered as world billboards.

3. **3D scene geometry**
   The OSRS scene is rendered by `VrGpuPlugin` through `DrawCallbacks`, using OpenGL shaders, VAOs/VBOs, zones, dynamic models, and per-eye VR projection.

4. **World-anchored UI billboards**
   Special 2D UI is shown as world-anchored billboards, not through `addEntity`. Context hints are self-rendered from the top live vanilla `MenuEntry`; right-click menus are cropped from the already-rendered vanilla canvas.

## Main VR Files

- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrGpuPlugin.java`: plugin entry point, DrawCallbacks, scene rendering, UI capture, VR frame flow.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrController.java`: per-hand controller snapshot, button hysteresis, edge detection, and OSRS-space ray derivation.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrControllers.java`: owns both controllers, primary-hand selection, and context-menu owner state.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrUi.java`: flat canvas UI panel and pointer rendering.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrBillboardRenderer.java`: actor/context/menu billboard textures.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrInteraction.java`: controller interaction and click handling.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrSceneRaycaster.java`: VR ray picking against scene/world objects.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/openxr/`: OpenXR context, frame lifecycle, swapchains, and input. `OpenXrFrame` owns the active frame state, view location, eye FBO acquire/release, eye clears, and submit decisions so XR eye targets are frame infrastructure, not a side effect of scene rendering.

## VR Interaction Rules

- `VrControllers.primary()` is the source of truth for the active pointer hand. It defaults to right, flips to the hand that dispatched a click, and is overridden by the latched context-menu owner while a vanilla menu is open.
- Both controller rays should render whenever VR is active. Rendering is independent of primary-hand state.
- Trigger and squeeze use hysteresis in `VrController`: press at `0.7`, release at `0.5`. Do not reintroduce raw threshold checks at call sites.
- Context-menu open/close lifecycle follows `client.isMenuOpen()` edges. Remember the previous value, react to true/false transitions, and avoid separate sentinel menu-open state.
- `clearVrMenuOverlay()` only clears captured VR overlay pixels/pointer state. Do not use it to declare the vanilla menu closed or to clear controller menu ownership; `syncVrMenuOpenState()` handles that from `client.isMenuOpen()`.
- Ray-leave menu cancel should select the visible vanilla `Cancel` row with a synthetic canvas click. Guard duplicate cancel dispatches while waiting for the `client.isMenuOpen()` false edge, because a second click can become a world action.
- `dispatchCanvasMouseClick(...)` is the synthetic click path and should mark the VR desktop click internally. Direct `client.menuAction(...)` paths need explicit marker handling if they should be hidden from normal desktop-click diagnostics.

## Useful RuneLite sources

- `runelite-api/src/main/java/net/runelite/api/Client.java`: menu entry access, rasterizer access, selected widget state, and core client dimensions.
- `runelite-api/src/main/java/net/runelite/api/MenuEntry.java`: option/target/action fields used to reconstruct hover context text.
- `runelite-api/src/main/java/net/runelite/api/events/PostMenuSort.java`: fires after menu sorting; useful hook for authoritative top menu entry state.
- `runelite-api/src/main/java/net/runelite/api/FontID.java`: OSRS cache font ids; `BOLD_12` maps to the normal bold menu/context font.
- `runelite-api/src/main/java/net/runelite/api/FontTypeFace.java`: actual Jagex bitmap font interface, including width/baseline/text drawing.
- `runelite-client/src/main/java/net/runelite/client/ui/FontManager.java`: RuneLite bundled RuneScape-style TTF fonts for offscreen Java2D rendering.
- `runelite-client/src/main/java/net/runelite/client/plugins/interacthighlight/InteractHighlightOverlay.java`: example of using the top sorted `MenuEntry` as the current hovered action.
- `cache/src/main/java/net/runelite/cache/FontName.java`: cache font archive names such as `b12_full`.
- `cache/src/main/java/net/runelite/cache/item/Rasterizer2D.java`: cache-side raster buffer switching model that would be useful to expose at runtime later.

# AI Workflows
- after edit, run ```./gradlew :client:compileJava``` to check compilation error. Do not run tests or try to execute the program.
- each edit needs to be manually confirmed by user in VR headset.
