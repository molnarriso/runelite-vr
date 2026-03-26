# RuneLite VR — Implementation Brief

> **Audience:** Future implementation agents and contributors.
> **Purpose:** Complete technical context for building VR support into this RuneLite fork.
> Read this document fully before touching any code.

---

## 1. Vision

RuneLite VR renders Old School RuneScape as a **mixed-reality tabletop experience**.
The player sits at a desk wearing a VR/MR headset. The OSRS game world appears anchored to the desk — buildings ~50 cm tall, the ground roughly at table level. No locomotion, no first-person view. The HMD pose drives the camera directly (full 6DOF). The player uses a **regular mouse and keyboard** (seated position assumed).

There is no VR controller support planned. Clicking is done with the mouse on the desktop mirror window.

---

## 2. Architecture Decision

**Fork `GpuPlugin` into `VrGpuPlugin`.**

The GPU plugin (`runelite-client/.../plugins/gpu/GpuPlugin.java`) already owns the OpenGL context via LWJGL, implements `DrawCallbacks`, and controls the full render pipeline. VR requires rendering two eyes per frame and submitting to an OpenXR swapchain — this is a deep change to the render loop, not something that can be bolted on from outside.

`VrGpuPlugin` is a direct fork that:
- Replaces `GpuPlugin` (annotated as conflicting)
- Adds OpenXR session management
- Renders the scene twice per frame (one per eye)
- Derives the view/projection matrices from HMD eye poses + a user-configured **world anchor**
- Blits the left eye to the desktop AWT canvas as a mirror

The stock `GpuPlugin` and `VrGpuPlugin` **must not run together** — they would both try to own the GL context.

---

## 3. World Anchor

The world anchor is how the player positions the OSRS world in physical room space. Three user-configurable parameters:

| Parameter | Type | Description |
|---|---|---|
| `worldScale` | float | Meters per OSRS local unit. Default ~`0.000781` (1 tile ≈ 10 cm, 1 tile = 128 OSRS units) |
| `worldOffset` | vec3 (X, Y, Z) | Translation of OSRS origin in room space (meters). Lets user slide the world onto the desk. |
| `worldYaw` | float | Rotation around room's vertical (Y) axis (degrees). Lets user spin the world to face them. |

**Transform chain (OSRS local → room space):**
```
room_pos = Translate(worldOffset) × RotateY(worldYaw) × Scale(worldScale) × osrs_pos
```

**Per-eye view matrix:**
```
worldProj[eye] = OpenXR_Projection[eye] × inv(eyePose[eye]) × WorldAnchor
```

Where:
- `OpenXR_Projection[eye]` — built from `XrFovf` tangent half-angles (standard OpenXR formula)
- `inv(eyePose[eye])` — inverse of the XrPosef for this eye (position + orientation in stage space)
- `WorldAnchor` = `Translate(worldOffset) × RotateY(worldYaw) × Scale(worldScale)`

This replaces the current single `worldProj` matrix entirely. The existing `uniWorldProj` uniform slot is reused.

### Fog / UBO camera position
The vertex shader UBO (`cameraX, cameraY, cameraZ, cameraPitch, cameraYaw`) is used for fog
distance calculations. For VR, extract the approximate OSRS-space camera position by
inverting the WorldAnchor and applying it to the eye position:
```
osrs_cam_pos = inv(WorldAnchor) × eyePose[LEFT].position
```
Set UBO pitch/yaw to approximate values (or disable fog with `uniUseFog = 0` initially).

---

## 4. Render Loop — Current vs VR

### Current GpuPlugin loop (per desktop frame)
```
preSceneDraw()         → upload UBO (yaw/pitch/camXYZ), build single worldProj, bind fboScene
drawZoneOpaque()×N     → render opaque geometry to fboScene
drawZoneAlpha()×N      → render alpha geometry to fboScene
postSceneDraw()        → unbind fboScene
draw(overlayColor)     → blitSceneFbo() + drawUi() + awtContext.swapBuffers()
```

### VR loop (per HMD frame)
```
xrWaitFrame()          → blocks until compositor wants a new frame
xrBeginFrame()

FOR each eye IN [LEFT, RIGHT]:
  xrAcquireSwapchainImage()
  bind XR swapchain FBO (backed by XR swapchain image)
  compute worldProj[eye] from HMD pose + WorldAnchor
  upload UBO (camera position approximated from eye pose in OSRS space)
  drawZoneOpaque() × N
  drawZoneAlpha() × N
  IF eye == LEFT: drawUi()   ← UI overlay only on left eye (or both, TBD)
  xrReleaseSwapchainImage()

xrEndFrame()           → submit both eye layers to compositor

// Mirror to desktop
blitXrEyeToCanvas(LEFT)
awtContext.swapBuffers()
```

Key difference: the scene geometry upload (SceneUploader) happens **once** — it fills static
VBOs. The render pass is called twice using different `worldProj` matrices. SceneUploader does
not need modification.

---

## 5. OpenXR Integration

### 5a. Dependency — NOT YET ADDED
LWJGL 3.3.2 (already in the project) includes OpenXR bindings, but they are **not yet declared**
in `libs.versions.toml` or `build.gradle.kts`. This is the first code change needed.

Add to `libs.versions.toml`:
```toml
lwjgl-openxr = { module = "org.lwjgl:lwjgl-openxr", version.ref = "lwjgl" }
```

Add to `runelite-client/build.gradle.kts` (alongside existing lwjgl entries):
```kotlin
api(libs.lwjgl.openxr)
// native binaries for each platform (same platform list as lwjgl-core):
runtimeOnly(variantOf(libs.lwjgl.openxr) { classifier("natives-$platform") })
```

### 5b. OpenXR session lifecycle

```
xrCreateInstance         → extensions: XR_KHR_opengl_enable, XR_EXT_debug_utils (optional)
xrGetSystem              → HMD system id
XrGraphicsBindingOpenGLWin32KHR  → link to AWTContext's HGLRC + HDC (Windows)
                           XrGraphicsBindingOpenGLXlibKHR for Linux
xrCreateSession
xrCreateReferenceSpace   → XR_REFERENCE_SPACE_TYPE_STAGE (floor level)
                           STAGE puts Y=0 at floor, which matches "world on the desk" mental model
xrEnumerateSwapchainFormats → pick GL_SRGB8_ALPHA8 or GL_RGBA8
FOR each eye:
  xrCreateSwapchain      → width/height from XrViewConfigurationView
  xrEnumerateSwapchainImages → get GL texture names
  FOR each image: glGenFramebuffers, attach texture → fboEye[eye][imageIndex]
```

The AWTContext (LWJGL/AWT bridge) exposes the native GL context handles needed by
`XrGraphicsBindingOpenGLWin32KHR`. Check `rlawt` source for how to get HGLRC/HDC — it wraps
`wglGetCurrentContext()` / `wglGetCurrentDC()` internally and those can be retrieved via JNA or
reflection.

### 5c. Per-frame XR calls

```java
XrFrameState frameState = xrWaitFrame(session, ...);
if (frameState.shouldRender()) {
    xrBeginFrame(session, ...);
    XrView[] views = xrLocateViews(session, viewLocateInfo, viewState);  // eye poses
    // ... render both eyes ...
    xrEndFrame(session, [compositionLayerProjection]);
}
```

`xrLocateViews` fills `XrView[]` (2 elements for stereo), each with `XrPosef pose` and
`XrFovf fov`. These are in STAGE reference space.

### 5d. Projection matrix from XrFovf

```java
// Standard OpenXR tangent-half-angle projection:
float left   = (float) Math.tan(fov.angleLeft());
float right  = (float) Math.tan(fov.angleRight());
float up     = (float) Math.tan(fov.angleUp());
float down   = (float) Math.tan(fov.angleDown());
float nearZ  = 0.05f;   // meters — adjust to match fog/scene scale
float farZ   = 100.0f;

// Build column-major 4x4 projection (same convention as GpuPlugin Mat4.projection)
```

Use the existing `Mat4` utility class in the GPU plugin for matrix math.

---

## 6. Key Files Reference

### Must fork / create
| File | Action | Notes |
|---|---|---|
| `runelite-client/.../plugins/gpu/GpuPlugin.java` | **Fork → VrGpuPlugin.java** | 2136 lines. All rendering logic lives here. |
| `runelite-client/.../plugins/gpu/` (whole package) | **Copy → plugins/vrgpu/** | SceneUploader, TextureManager, VAO, VBO, GLBuffer, Zone, FacePrioritySorter, etc. all stay intact. |
| `runelite-client/.../plugins/vrgpu/VrGpuConfig.java` | **Create** | Extend GpuConfig with worldScale, worldOffsetX/Y/Z, worldYaw |
| `runelite-client/.../plugins/vrgpu/openxr/XrSession.java` | **Create** | OpenXR session lifecycle wrapper |
| `runelite-client/.../plugins/vrgpu/openxr/XrSwapchain.java` | **Create** | Per-eye swapchain + FBO management |
| `libs.versions.toml` | **Edit** | Add `lwjgl-openxr` entry |
| `runelite-client/build.gradle.kts` | **Edit** | Add OpenXR api + runtimeOnly natives for all platforms |

### Read-only reference (do not modify)
| File | What it contains |
|---|---|
| `runelite-api/.../hooks/DrawCallbacks.java` | Interface VrGpuPlugin must implement. Methods: `draw()`, `drawScene()`, `drawZoneOpaque()`, `drawZoneAlpha()`, `preSceneDraw()`, `postSceneDraw()`, etc. |
| `runelite-client/.../plugins/gpu/template/vert.glsl` | Scene vertex shader. Inputs: `vertf` (pos), `abhsl` (color), `tex` (uv). UBO: `cameraYaw, cameraPitch, cameraX, cameraY, cameraZ`. Uniform: `mat4 worldProj`, `mat4 entityProj`. |
| `runelite-client/.../plugins/gpu/template/frag.glsl` | Scene fragment shader. Handles texture sampling, HSL→RGB, fog blending. |
| `runelite-client/.../plugins/gpu/template/vertui.glsl` | UI quad vertex shader. |
| `runelite-client/.../plugins/gpu/template/fragui.glsl` | UI fragment shader. Handles SAMPLING_* modes, colorblind, alpha overlay. |
| `runelite-client/.../plugins/gpu/Mat4.java` | Matrix math utility (scale, translate, rotateX/Y, projection, mul). Reuse for OpenXR projection math. |
| `runelite-client/.../plugins/gpu/SceneUploader.java` | Uploads scene geometry to VAOs. **Runs once, not per eye.** No changes needed. |
| `runelite-client/.../plugins/gpu/TextureManager.java` | Manages texture array. No changes needed. |
| `runelite-client/.../plugins/gpu/Zone.java` | Per-zone VAO/VBO. The `drawZoneOpaque` / `drawZoneAlpha` calls in DrawCallbacks drive these. |
| `runelite-client/.../ui/ClientUI.java` | Swing frame + canvas. VrGpuPlugin reads `client.getCanvas()` here same as GpuPlugin. |
| `runelite-client/.../input/MouseManager.java` | Mouse event dispatch. No changes needed — player uses regular mouse. |
| `runelite-client/.../plugins/camera/CameraPlugin.java` | Camera controls. Should be **disabled** when VrGpuPlugin is active (it fights for camera yaw/pitch). Consider adding a conflict annotation. |

---

## 7. Shader Changes

The existing shaders (`vert.glsl`, `frag.glsl`) require **no changes for basic stereo**.
The `worldProj` uniform already accepts any 4×4 matrix — just upload the per-eye matrix before
each eye's draw calls.

Potential future shader work:
- **Eye index uniform** (`uniEyeIndex`) if left/right-specific rendering is needed (e.g. different UI).
- **Fog reprojection**: the UBO `cameraX/Y/Z` is used for fog edge distances. Approximate values
  extracted from the world-space eye position should work. If fog looks wrong, disable it first
  (`glUniform1i(uniUseFog, 0)`).

---

## 8. Config Design

`VrGpuConfig` should extend/mirror `GpuConfig` and add:

```java
@ConfigItem(keyName = "worldScale", name = "World Scale",
    description = "Meters per OSRS local unit. Default: 0.000781 (1 tile ≈ 10 cm)")
default double worldScale() { return 0.000781; }

@ConfigItem(keyName = "worldOffsetX", name = "World Offset X (m)")
default double worldOffsetX() { return 0.0; }

@ConfigItem(keyName = "worldOffsetY", name = "World Offset Y (m)")
default double worldOffsetY() { return -0.8; }   // 80 cm below headset origin → desk height

@ConfigItem(keyName = "worldOffsetZ", name = "World Offset Z (m)")
default double worldOffsetZ() { return -0.5; }   // 50 cm in front

@ConfigItem(keyName = "worldYaw", name = "World Yaw (degrees)",
    description = "Rotation of the game world around vertical axis")
default double worldYaw() { return 0.0; }
```

Consider adding keyboard shortcuts to nudge worldOffset and worldYaw at runtime without
opening the config panel (important for in-headset adjustment).

---

## 9. Mutual Exclusion with GpuPlugin

In `VrGpuPlugin`'s `@PluginDescriptor`:
```java
@PluginDescriptor(
    name = "VR GPU",
    description = "OpenXR VR rendering — replaces GPU Plugin",
    conflicts = "GPU"     // prevents both running simultaneously
)
```

---

## 10. AWTContext and OpenXR Context Sharing

`AWTContext` (from `net.runelite:rlawt`) creates the OpenGL context on the AWT Canvas.
OpenXR needs access to the same context's native handles.

On Windows, `XrGraphicsBindingOpenGLWin32KHR` needs:
- `HGLRC hGLRC` — the OpenGL rendering context handle
- `HDC hDC` — the device context handle

These can be obtained via JNA after `awtContext.createGLContext()`:
```java
// org.lwjgl.opengl.WGL:
long hglrc = WGL.wglGetCurrentContext();
long hdc   = WGL.wglGetCurrentDC();
```

The OpenXR session must be created on the **same thread** that owns the GL context.
GpuPlugin already does all GL work on the client thread — keep this pattern.

On Linux: `XrGraphicsBindingOpenGLXlibKHR` (needs X11 display, drawable, context).
On macOS: OpenXR is not supported (no runtime).

---

## 11. Task List

### Phase 0 — Build setup
- [x] **T0.1** Add `lwjgl-openxr` to `libs.versions.toml` — Added `lwjgl-openxr = { module = "org.lwjgl:lwjgl-openxr", version.ref = "lwjgl" }` entry alongside existing lwjgl entries.
- [x] **T0.2** Add OpenXR api + per-platform runtimeOnly natives to `runelite-client/build.gradle.kts` — Added `api(libs.lwjgl.openxr)` and a separate natives loop for non-macOS platforms (OpenXR has no macOS runtime).
- [x] **T0.3** Verify `./gradlew build` still passes after dep addition — `:client:compileJava` builds successfully; `gradle/verification-metadata.xml` updated with sha256 checksums for all lwjgl-openxr artifacts.

### Phase 1 — Package scaffold
- [x] **T1.1** Create package `net.runelite.client.plugins.vrgpu` — Package directory created as part of copying all GPU plugin files.
- [x] **T1.2** Copy all files from `plugins/gpu/` into `plugins/vrgpu/` (rename GpuPlugin → VrGpuPlugin, GpuConfig → VrGpuConfig) — All 21 Java source files and 13 resource files copied; `GpuPlugin` → `VrGpuPlugin`, `GpuPluginConfig` → `VrGpuPluginConfig`.
- [x] **T1.3** Update all internal package references in copied files — All `plugins.gpu` package declarations, imports, and static imports updated to `plugins.vrgpu`; body-level `GpuPlugin.*` references updated to `VrGpuPlugin.*`.
- [x] **T1.4** Add `@PluginDescriptor(name="VR GPU", conflicts="GPU")` to VrGpuPlugin — Descriptor set with `name="VR GPU"`, `description="OpenXR VR rendering — replaces GPU Plugin"`, `conflicts="GPU"`.
- [x] **T1.5** Verify it compiles and starts up as a normal (non-VR) GPU plugin clone — `:client:compileJava` BUILD SUCCESSFUL with zero errors.

### Phase 2 — OpenXR session management
- [ ] **T2.1** Create `openxr/XrContext.java` — wraps instance, system, session lifecycle
  - `init(long hglrc, long hdc)` — creates XR instance + session
  - `destroy()`
  - Extensions to request: `XR_KHR_opengl_enable`, optionally `XR_EXT_debug_utils`
- [ ] **T2.2** Create `openxr/XrEyeSwapchain.java` — per-eye swapchain
  - `init(XrSession, int width, int height, int format)`
  - `acquireImage()` → returns FBO handle backed by current swapchain texture
  - `releaseImage()`
  - `destroy()`
- [ ] **T2.3** Integrate XrContext into VrGpuPlugin.startUp() / shutDown()
  - After `awtContext.createGLContext()`, call `xrContext.init(wglGetCurrentContext(), wglGetCurrentDC())`
  - Create reference space (STAGE)
  - Create per-eye swapchains using recommended view dimensions from XrViewConfigurationView
- [ ] **T2.4** Add `xrWaitFrame` / `xrBeginFrame` / `xrEndFrame` skeleton to the draw loop
- [ ] **T2.5** Handle session state changes (READY → SYNCHRONIZED → VISIBLE → FOCUSED → STOPPING)

### Phase 3 — Stereo rendering
- [ ] **T3.1** Replace single `fboScene` with per-eye FBOs backed by XR swapchain images
- [ ] **T3.2** In `preSceneDraw()` equivalent: loop over eyes, compute `worldProj[eye]` from:
  - XrFovf → perspective matrix (implement `Mat4.fromXrFov(XrFovf fov, float near, float far)`)
  - XrPosef eye pose → view matrix (implement `Mat4.fromXrPose(XrPosef pose)` as inverse pose matrix)
  - WorldAnchor matrix from config (scale × rotateY(worldYaw) × translate(worldOffset))
  - Final: `worldProj = perspectiveMatrix × viewMatrix × worldAnchor`
- [ ] **T3.3** Call `drawZoneOpaque` + `drawZoneAlpha` once per eye with correct `worldProj`
- [ ] **T3.4** Extract approximate OSRS-space camera position from eye pose for UBO (fog)
- [ ] **T3.5** Blit left eye to AWT canvas in `draw()` for desktop mirror

### Phase 4 — World anchor config
- [ ] **T4.1** Add worldScale, worldOffsetX/Y/Z, worldYaw config items to VrGpuConfig
- [ ] **T4.2** Wire config changes to live-update the WorldAnchor matrix (no restart needed)
- [ ] **T4.3** (Optional) Add keyboard shortcuts for nudging worldOffset + worldYaw at runtime

### Phase 5 — Polish and correctness
- [ ] **T5.1** Disable / conflict CameraPlugin when VrGpuPlugin is active
- [ ] **T5.2** Verify fog renders correctly (or disable as a fallback)
- [ ] **T5.3** Test session teardown/restart (headset removed and re-worn)
- [ ] **T5.4** Verify UI overlay (health bars, chat) renders in left eye
- [ ] **T5.5** Test on SteamVR runtime (primary target)
- [ ] **T5.6** Test on native Oculus/Meta runtime

---

## 12. Key Technical Facts (Quick Reference)

- **LWJGL version**: `3.3.2` — supports OpenXR, just needs the dependency added
- **GL minimum**: 3.3 core (checked at GpuPlugin line 316)
- **UBO layout** (5 floats, std140): `cameraYaw, cameraPitch, cameraX, cameraY, cameraZ`
- **worldProj** current formula: `Scale(zoom) × Perspective(near=50) × RotateX(pitch) × RotateY(yaw) × Translate(-camX,-camY,-camZ)`
- **worldProj VR formula**: `XrPerspective(fov) × inv(eyePose) × WorldAnchor`
- **OSRS tile size**: 128 local units = 1 tile ≈ 0.1m at default scale
- **Scene geometry**: uploaded once to VAOs by SceneUploader; rendered N times per frame (once per eye)
- **FBO multisampling**: `rboColorBuffer` and `rboDepthBuffer` are MSAA renderbuffers — XR swapchain images are non-MSAA; resolve via `glBlitFramebuffer` or render directly
- **UI texture**: uploaded via PBO from `BufferProvider` each frame — unchanged
- **Mat4.java**: column-major float[16] convention — matches OpenGL and OpenXR conventions
- **AWTContext**: from `net.runelite:rlawt` — bridges AWT Canvas to LWJGL GL context
- **OpenXR reference space**: Use `STAGE` (floor at Y=0), so worldOffsetY moves the world up to desk height

---

## 13. Known Test Failures

Running `./gradlew :client:test` will report some failures that are **not caused by VR changes**:

| Test | Failure reason | Status |
|---|---|---|
| `SpecialCounterPluginTest` (3 tests) | Pre-existing bug in upstream RuneLite code | Ignore — fails on unmodified master too |
| `EmojiPluginTest`, `ClientConfigLoaderTest`, `PluginManagerTest` (network tests) | Flaky network/TLS tests that only fail on a cold Gradle cache (`--rerun-tasks` or `clean`) | Ignore — pass on incremental runs and in isolation |

**Rule of thumb:** a normal incremental run (`./gradlew :client:test` without `clean`) should show exactly **3 failures** (SpecialCounter). Any additional failures are worth investigating. A `clean` run may show up to ~9 additional flaky failures that are pre-existing.

---

## 14. Out of Scope (Deliberately)

- **Plugin Hub distribution** — `VrGpuPlugin` owns the OpenGL context and replaces the render loop entirely; Plugin Hub plugins run as guests inside an existing pipeline and cannot do this.
- VR controller input — regular mouse is used
- Locomotion / teleportation
- First-person view
- Per-eye UI rendering differences (start with UI on left eye only)
- macOS support (no OpenXR runtime)
- Comfort vignette / anti-sickness measures
- Passthrough / camera feed compositing (pure VR rendering only)
