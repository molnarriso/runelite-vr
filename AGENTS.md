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

5. **Spectator capture window**
   Optional spectator mode renders a separate desktop capture view. The HMD stereo path uses live OpenXR poses; only the spectator pass uses a smoothed camera. The spectator render target keeps the native eye projection/aspect to avoid distortion, then the desktop window applies a 16:9 crop and optional crop margins for capture.

## Main VR Files

- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrGpuPlugin.java`: plugin entry point, DrawCallbacks, scene rendering, UI capture, VR frame flow, joystick zoom/orbit state, and shared per-frame VR world transform inputs.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrCamera.java`: VR camera pose/projection helpers, live HMD pose access, smoothed spectator pose, desktop camera helpers, and projection builders. The VR world projection applies zoom pullback plus joystick yaw around the player-head anchor; sorter projections must mirror that transform.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrController.java`: per-hand controller snapshot, button hysteresis, edge detection, thumbstick sampling, and OSRS-space ray derivation. Ray derivation applies the inverse VR camera boom yaw so picking stays aligned with the rendered world.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrControllers.java`: owns both controllers, primary-hand selection, context-menu owner state, and per-frame OSRS ray recomputation with the current VR camera transform.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrControllerModel.java`: controller mesh/debug ray vertex generation. Stage-space and OSRS-world-space rendering share one mesh generator; the world-space sink applies the inverse camera boom yaw so controller models remain visually aligned.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrUi.java`: flat canvas UI panel and pointer rendering.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrBillboardRenderer.java`: actor/context/menu billboard textures. Billboard placement/facing mirrors the current VR camera boom yaw before converting stage offsets back to OSRS world vertices.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrSpectatorWindow.java`: Swing desktop window for spectator capture; reads the spectator FBO, applies aspect/crop settings, and presents the capture image.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrInteraction.java`: controller interaction and click handling.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/VrSceneRaycaster.java`: VR ray picking against scene/world objects.
- `runelite-client/src/main/java/net/runelite/client/plugins/vrgpu/openxr/`: OpenXR context, frame lifecycle, swapchains, and input. `OpenXrFrame` owns the active frame state, view location, eye FBO acquire/release, eye clears, and submit decisions so XR eye targets are frame infrastructure, not a side effect of scene rendering.

## VR Render / Camera Rules

- `VrCamera` is the central place for eye pose and projection construction. Avoid ad-hoc `XrView.pose()` math in render helpers; pass projections/camera data into helpers instead.
- HMD rendering should use live OpenXR poses for comfort and low latency. Do not smooth the headset path for capture quality.
- Spectator capture may use a separate smoothed camera and a separate FBO/window, but should reuse the same scene replay and overlay target pipeline as the HMD eyes wherever possible.
- Everything visible in VR should also be visible in the spectator target: canvas panel, billboards, controller models/rays, and menu/context UI. Menu/splash states without a 3D scene should still render the VR canvas panel and stage-space controller/ray layer.
- Do not create separate bespoke spectator overlay drawing paths unless a layer truly cannot share the target renderer.

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
