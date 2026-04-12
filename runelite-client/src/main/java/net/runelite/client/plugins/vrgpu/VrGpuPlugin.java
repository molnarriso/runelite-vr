/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.vrgpu;

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import net.runelite.api.AABB;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.LocalPoint;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.ScriptID;
import net.runelite.api.TextureProvider;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.VarClientInt;
import net.runelite.api.WallObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.vrgpu.config.AntiAliasingMode;
import net.runelite.client.plugins.vrgpu.config.UIScalingMode;
import net.runelite.client.plugins.vrgpu.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.opengl.WGL;
import org.lwjgl.openxr.XrFovf;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrView;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import net.runelite.client.plugins.vrgpu.openxr.XrContext;
import net.runelite.client.plugins.vrgpu.openxr.XrEyeSwapchain;
import net.runelite.client.plugins.vrgpu.openxr.XrInput;
import org.lwjgl.BufferUtils;

@PluginDescriptor(
	name = "VR GPU",
	description = "OpenXR VR rendering — replaces GPU Plugin",
	conflicts = "GPU",
	loadInSafeMode = false
)
@Slf4j
public class VrGpuPlugin extends Plugin implements DrawCallbacks
{
	static final int MAX_DISTANCE = 184;
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	private static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private VrGpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private RegionManager regionManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	/** OpenXR context — null if no VR runtime is available. */
	private XrContext xrContext;
	/** Per-eye swapchains — null if xrContext is null. Index 0 = left, 1 = right. */
	private XrEyeSwapchain[] eyeSwapchains;
	/** Controller input — null if xrContext is null. */
	private XrInput xrInput;
	/** True between a successful beginXrFrame() and the matching endXrFrame() call. */
	private boolean xrFrameStarted;

	// --- Stereo rendering state (T3.x) ---

	/**
	 * Default world scale: meters per OSRS local unit.
	 * 1 tile = 128 units ≈ 0.1 m → 0.1 / 128 ≈ 0.000781.
	 */
	private static final float DEFAULT_WORLD_SCALE = 0.000781f;
	private static final float VR_STAGE_CHARACTER_OFFSET_Y = -1.0f;
	private static final float VR_STAGE_CHARACTER_OFFSET_Z = -0.5f;
	private static final int VR_DESKTOP_AIM_PITCH = 256; // ~45 degrees from horizon in JAU

	/**
	 * Stage-space Y of the world anchor (where the OSRS camera maps to).
	 * Sampled once from the first VR frame as (initialEyeY - 0.7 m).
	 * NaN until that first frame runs.
	 */
	private float vrWorldAnchorY = Float.NaN;

	/** Which eye is currently being rendered: 0 = left, 1 = right, -1 = no VR. */
	private int currentEye = -1;

	/** FBO handle acquired for the left eye this frame (valid until releaseImage). */
	private int vrLeftEyeFbo;

	/** Opaque zone coords [zx, zz] recorded during the left-eye pass. */
	private final List<int[]> vrOpaqueZones = new ArrayList<>(256);
	/** Entity projections parallel to {@link #vrOpaqueZones}. */
	private final List<Projection> vrOpaqueProjs = new ArrayList<>(256);

	/** Alpha zone coords [level, zx, zz] recorded during the left-eye pass. */
	private final List<int[]> vrAlphaZones = new ArrayList<>(256);
	/** Entity projections parallel to {@link #vrAlphaZones}. */
	private final List<Projection> vrAlphaProjs = new ArrayList<>(256);

	/** Toplevel scene saved during preSceneDrawToplevel, used in the right-eye replay. */
	private Scene vrScene;

	/** Eye views located this frame; comes from xrContext.locateViews(). */
	private XrView.Buffer vrViews;

	/**
	 * Projection built from the left VR eye pose each frame, used by the face-priority sorter
	 * in drawTemp/drawDynamic so that player models are backface-culled relative to the VR
	 * camera direction rather than the fixed OSRS 2D camera direction.
	 */
	private Projection vrSorterProjection;

	// --- Debug ray visualisation ---
	private int glDebugProgram;
	private int uniDebugWorldProj;
	private int debugVboId;
	private int debugVaoId;
	/** Scratch buffer for per-frame debug vertices (max 16 verts × 7 floats). */
	private java.nio.FloatBuffer debugRayFb;
	/** Scratch buffers for depth-buffer picking (single pixel read). */
	private java.nio.FloatBuffer depthReadBuf;
	private java.nio.IntBuffer fboReadBuf;
	/** Scratch buffer used when desktop-only re-sorting needs alpha faces but should discard opaque output. */
	private java.nio.IntBuffer desktopSorterScratchOpaque;
	/** Controller depth-buffer hits in OSRS world coords; null if no hit last frame. */
	private float[] vrLeftRayHit;
	private float[] vrRightRayHit;
	/** Persistent click-feedback marker: OSRS world position + button + timestamp. */
	private float[] vrLastClickHit;
	/** Persistent OSRS-space click ray [ox,oy,oz,dx,dy,dz] for diagnostics. */
	private float[] vrLastClickRay;
	/** Persistent ground-hit marker [x,y,z]. */
	private float[] vrLastGroundHit;
	/** Persistent reconstructed desktop screen-ray ground hit [x,y,z]. */
	private float[] vrLastDesktopRayHit;
	/** Persistent desktop-camera aim target [x,y,z] in GPU convention for diagnostics. */
	private float[] vrDesktopCameraAimTarget;
	/** Last requested desktop camera yaw/pitch targets for diagnostics. */
	private int[] vrDesktopCameraAimAngles;
	/** Persistent dispatch tile [sceneX, sceneY]. */
	private int[] vrLastDispatchSceneTile;
	/** Persistent raw walk params [p0, p1] sent to menuAction. */
	private int[] vrLastWalkParams;
	/** Persistent selected scene tile from the client after walk processing [sceneX, sceneY]. */
	private int[] vrLastClientSelectedSceneTile;
	/** Persistent client destination local point [x, y, z]. */
	private float[] vrLastClientDestination;
	private int vrLastClickButton; // MouseEvent.BUTTON1 or BUTTON3
	private long vrLastClickTimeMs;
	/** Previous-frame combined LMB/RMB values for rising-edge detection. */
	private float prevLmb; // max(leftTrigger, rightTrigger)
	private float prevRmb; // max(leftSqueeze,  rightSqueeze)
	/** Pending click produced on render thread, consumed on client thread. */
	private volatile float[] vrPendingClickHit;
	private volatile int vrPendingClickButton; // MouseEvent.BUTTON1 or BUTTON3
	/** OSRS-space ray [ox,oy,oz,dx,dy,dz] set on render thread when a click is detected; consumed on client thread. */
	private volatile float[] vrPendingClickRay;
	/** Pending staged walk dispatch [sceneX, sceneY, localX, localY(height), localZ]. */
	private volatile float[] vrPendingWalkInspect;
	/** Pending staged walk canvas point [x, y]. */
	private volatile int[] vrPendingWalkCanvasPoint;
	/** True once we have moved the live canvas mouse to the staged walk point. */
	private volatile boolean vrPendingWalkMousePrimed;
	/** Remaining ticks before staged walk dispatch. */
	private volatile int vrPendingWalkInspectRetries;

	/**
	 * Number of VAOs in {@code vaoO} that were drawn (but not reset) during the
	 * left-eye drawPass. Replayed and reset in the right-eye pass.
	 */
	private int vrPassOpaqueCount;
	/**
	 * Number of VAOs in {@code vaoDesktopO} filled during the left-eye pass for the
	 * desktop spectator camera. These remain separate so opaque temp/dynamic faces
	 * do not inherit the VR eye's sorted/culling decisions.
	 */
	private int vrPassDesktopOpaqueCount;
	/**
	 * Number of VAOs in {@code vaoPO} that were drawn (but not reset) during the
	 * left-eye drawPass. Replayed and reset in the right-eye pass.
	 */
	private int vrPassPlayerCount;
	/**
	 * Number of VAOs in {@code vaoDesktopPO} that were filled during the left-eye pass
	 * for the desktop spectator camera. These stay separate from {@code vaoPO}
	 * because player/temp faces must be sorted from the desktop camera, not the VR eye.
	 */
	private int vrPassDesktopPlayerCount;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	static final Shader DEBUG_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "debug_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "debug_frag.glsl");

	static int glProgram;
	private int glUiProgram;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene;
	private boolean sceneFboValid;
	private int rboColorBuffer;
	private int rboDepthBuffer;

	private int textureArrayId;

	private final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	private int lastAnisotropicFilteringLevel = -1;

	private GpuFloatBuffer uniformBuffer;

	private int cameraYaw, cameraPitch;

	private VAOList vaoO;
	private VAOList vaoA;
	private VAOList vaoPO;
	private VAOList vaoDesktopO;
	private VAOList vaoDesktopA;
	private VAOList vaoDesktopPO;

	private SceneUploader clientUploader, mapUploader;
	private FacePrioritySorter facePrioritySorter;

	static class SceneContext
	{
		final int sizeX, sizeZ;
		Zone[][] zones;

		private int cameraX, cameraY, cameraZ;
		private int minLevel, level, maxLevel;
		private Set<Integer> hideRoofIds;

		SceneContext(int sizeX, int sizeZ)
		{
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			zones = new Zone[sizeX][sizeZ];
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z] = new Zone();
				}
			}
		}

		void free()
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z].free();
				}
			}
		}
	}

	SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	private SceneContext root;
	private SceneContext[] subs;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	// Uniforms
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
	private int uniExpandedMapLoadingChunks;
	private int uniSmoothBanding;
	private int uniWorldProj;
	private static int uniEntityProj;
	static int uniEntityTint;
	private int uniBrightness;
	private int uniTex;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextures;
	private int uniTextureAnimations;
	private int uniBlockMain;
	private int uniTextureLightMode;
	private int uniTick;
	private int uniColorblindIntensity;
	private int uniUiColorblindIntensity;
	static int uniBase;

	private static Projection lastProjection;

	@Override
	protected void startUp()
	{
		// Explicitly stop GPU plugin so the conflict is resolved immediately on
		// every launch without relying on saved profile state.
		pluginManager.getPlugins().stream()
			.filter(p -> p.getClass().getSimpleName().equals("GpuPlugin"))
			.findFirst()
			.ifPresent(gpuPlugin ->
			{
				try
				{
					pluginManager.setPluginEnabled(gpuPlugin, false);
					pluginManager.stopPlugin(gpuPlugin);
				}
				catch (PluginInstantiationException e)
				{
					log.warn("Could not stop GpuPlugin", e);
				}
			});

		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		clientUploader = new SceneUploader(renderCallbackManager);
		mapUploader = new SceneUploader(renderCallbackManager);
		facePrioritySorter = new FacePrioritySorter(clientUploader);
		clientThread.invoke(() ->
		{
			try
			{
				fboScene = -1;
				lastAnisotropicFilteringLevel = -1;

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCapabilities = GL.createCapabilities();

				log.info("Using device: {}", glGetString(GL_RENDERER));
				log.info("Using driver: {}", glGetString(GL_VERSION));

				if (!glCapabilities.OpenGL33)
				{
					throw new RuntimeException("OpenGL 3.3 is required but not available");
				}

				lwjglInitted = true;

				checkGLErrors();
				if (log.isDebugEnabled() && glCapabilities.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						// [LWJGL] OpenGL debug message
						//	ID: 0x20071
						//	Source: API
						//	Type: OTHER
						//	Severity: NOTIFICATION
						//	Message: Buffer detailed info: Buffer object 2 (bound to GL_PIXEL_UNPACK_BUFFER_ARB, usage hint is GL_STREAM_DRAW) has been mapped WRITE_ONLY in SYSTEM HEAP memory (fast).
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false);

						// [LWJGL] OpenGL debug message
						//	ID: 0x20052
						//	Source: API
						//	Type: PERFORMANCE
						//	Severity: MEDIUM
						//	Message: Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false);
					}
				}

				setupSyncMode();

				// Initialise OpenXR. Fails gracefully if no runtime / headset is present
				// so the plugin still works as a regular GPU plugin during development.
				try
				{
					long hglrc = WGL.wglGetCurrentContext();
					long hdc   = WGL.wglGetCurrentDC();
					xrContext  = new XrContext();
					xrContext.init(hglrc, hdc);

					xrInput = new XrInput();
					xrInput.init(xrContext.getInstance(), xrContext.getSession());

					eyeSwapchains = new XrEyeSwapchain[2];
					for (int eye = 0; eye < 2; eye++)
					{
						eyeSwapchains[eye] = new XrEyeSwapchain();
						eyeSwapchains[eye].init(
							xrContext.getInstance(),
							xrContext.getSession(),
							xrContext.getEyeWidth()[eye],
							xrContext.getEyeHeight()[eye],
							GL_RGBA8);
					}
					log.info("OpenXR initialised — VR rendering enabled");
				}
				catch (Exception e)
				{
					log.warn("OpenXR unavailable, running in non-VR mode: {}", e.getMessage());
					destroyXr();
				}

				initBuffers();
				initVao();
				initProgram();
				initInterfaceTexture();
				if (glCapabilities.OpenGL45)
				{
					glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
				}

				client.setDrawCallbacks(this);
				client.setGpuFlags(DrawCallbacks.GPU
					| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
					| DrawCallbacks.ZBUF
				);
				client.setExpandedMapLoading(config.expandedMapLoadingZones());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					startupWorldLoad();
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});

				shutDown();
			}
			return true;
		});
	}

	private void startupWorldLoad()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);

			if (lwjglInitted)
			{
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				root.free();

				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				shutdownFbo();
				destroyXr();
			}

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (debugCallback != null)
			{
				debugCallback.free();
				debugCallback = null;
			}

			glCapabilities = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	VrGpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(VrGpuPluginConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(VrGpuPluginConfig.GROUP))
		{
			if (configChanged.getKey().equals("unlockFps")
				|| configChanged.getKey().equals("vsyncMode")
				|| configChanged.getKey().equals("fpsTarget"))
			{
				log.debug("Rebuilding sync mode");
				clientThread.invokeLater(this::setupSyncMode);
			}
			else if (configChanged.getKey().equals("expandedMapLoadingChunks"))
			{
				clientThread.invokeLater(() ->
				{
					client.setExpandedMapLoading(config.expandedMapLoadingZones());
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				client.setGpuFlags(DrawCallbacks.GPU
					| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
					| DrawCallbacks.ZBUF
				);
			}
			else if (configChanged.getKey().equals("uiScalingMode") || configChanged.getKey().equals("colorBlindMode"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					shutdownProgram();
					initProgram();
				});
			}
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		VrGpuPluginConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: VrGpuPluginConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + config.uiScalingMode().ordinal() + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
			}
			return null;
		});
		template.addInclude(VrGpuPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		glDebugProgram = DEBUG_PROGRAM.compile(template);
		uniDebugWorldProj = glGetUniformLocation(glDebugProgram, "worldProj");

		glBindVertexArray(0);

		initDebugVao();
		initUniforms();
	}

	private void initUniforms()
	{
		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
		uniEntityProj = glGetUniformLocation(glProgram, "entityProj");
		uniEntityTint = glGetUniformLocation(glProgram, "entityTint");
		uniSmoothBanding = glGetUniformLocation(glProgram, "smoothBanding");
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
	}

	private void shutdownProgram()
	{
		glDeleteProgram(glProgram);
		glProgram = 0;

		glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		if (glDebugProgram != 0) { glDeleteProgram(glDebugProgram); glDebugProgram = 0; }
		if (debugVaoId != 0) { glDeleteVertexArrays(debugVaoId); debugVaoId = 0; }
		if (debugVboId != 0) { glDeleteBuffers(debugVboId); debugVboId = 0; }
	}

	private void initVao()
	{
		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;
	}

	private void initBuffers()
	{
		uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		initGlBuffer(glUniformBuffer);
		Zone.initBuffer();

		vaoO = new VAOList();
		vaoA = new VAOList();
		vaoPO = new VAOList();
		vaoDesktopO = new VAOList();
		vaoDesktopA = new VAOList();
		vaoDesktopPO = new VAOList();
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(glUniformBuffer);
		uniformBuffer = null;
		Zone.freeBuffer();

		if (vaoO != null)
		{
			vaoO.free();
		}
		if (vaoDesktopO != null)
		{
			vaoDesktopO.free();
		}
		if (vaoA != null)
		{
			vaoA.free();
		}
		if (vaoDesktopA != null)
		{
			vaoDesktopA.free();
		}
		if (vaoPO != null)
		{
			vaoPO.free();
		}
		if (vaoDesktopPO != null)
		{
			vaoDesktopPO.free();
		}
		vaoO = vaoA = vaoPO = vaoDesktopO = vaoDesktopA = vaoDesktopPO = null;
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initFbo(int width, int height, int aaSamples)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		// Create and bind the FBO
		fboScene = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		// Color render buffer
		rboColorBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboColorBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_RGBA, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColorBuffer);

		// Depth render buffer
		rboDepthBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepthBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_DEPTH_COMPONENT32F, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepthBuffer);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("FBO is incomplete. status: " + status);
		}

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	private void shutdownFbo()
	{
		if (fboScene != -1)
		{
			glDeleteFramebuffers(fboScene);
			fboScene = -1;
		}

		if (rboColorBuffer != 0)
		{
			glDeleteRenderbuffers(rboColorBuffer);
			rboColorBuffer = 0;
		}

		if (rboDepthBuffer != 0)
		{
			glDeleteRenderbuffers(rboDepthBuffer);
			rboDepthBuffer = 0;
		}
	}

	private void destroyXr()
	{
		if (xrInput != null)
		{
			xrInput.destroy();
			xrInput = null;
		}
		if (eyeSwapchains != null)
		{
			for (XrEyeSwapchain sc : eyeSwapchains)
			{
				if (sc != null)
				{
					sc.destroy();
				}
			}
			eyeSwapchains = null;
		}
		if (xrContext != null)
		{
			xrContext.destroy();
			xrContext = null;
		}
		vrWorldAnchorY = Float.NaN;
	}

	// -------------------------------------------------------------------------
	// VR helper methods (T3.2)
	// -------------------------------------------------------------------------

	/**
	 * Compute the worldProj matrix for the given eye from the located XrView.
	 * <p>
	 * The chain is: VrProjection(fov) × InvEyePose × Scale(worldScale) × Translate(-cam)
	 * <p>
	 * This maps OSRS local-unit world coordinates to OpenXR clip space using
	 * the OSRS hyperbolic depth convention (clip_w = -z_eye, clearDepth=0, GL_GREATER).
	 *
	 * @param eye 0 = left, 1 = right
	 */
	private float[] computeVrWorldProj(int eye)
	{
		XrView view = vrViews.get(eye);

		// Character anchor: the local player is kept at a fixed room-space location.
		// vrWorldAnchorY is sampled from the initial eye height on the first VR frame.
		// X=0 (stage centre), Z=-0.5m (0.5 m in front of the head at recenter/start).
		// OSRS Y increases downward, so the Y scale is negated to flip it upright.
		// VR also needs an X flip to match the headset view handedness; without it the
		// whole image appears mirrored left-right in the HMD.
		float worldOffsetX = 0;
		float worldOffsetY = vrWorldAnchorY;
		float worldOffsetZ = VR_STAGE_CHARACTER_OFFSET_Z;
		float anchorWorldX = getVrAnchorWorldX();
		float anchorWorldY = getVrAnchorWorldY();
		float anchorWorldZ = getVrAnchorWorldZ();

		// Chain: Proj × InvEyePose × Translate(worldOffset) × Scale(-s,-s,s) × Translate(-cam)
		float[] proj = buildVrProjection(view.fov(), 0.05f);
		Mat4.mul(proj, buildInvEyePose(view.pose()));
		Mat4.mul(proj, Mat4.translate(worldOffsetX, worldOffsetY, worldOffsetZ));
		Mat4.mul(proj, Mat4.scale(-DEFAULT_WORLD_SCALE, -DEFAULT_WORLD_SCALE, DEFAULT_WORLD_SCALE));
		Mat4.mul(proj, Mat4.translate(-anchorWorldX, -anchorWorldY, -anchorWorldZ));
		return proj;
	}

	/**
	 * Build an asymmetric perspective projection matrix compatible with the OSRS depth
	 * convention.
	 * <p>
	 * Input: OpenXR eye space (X right, Y up, Z backward; z &lt; 0 for front objects).
	 * Output: clip space where clip_w = -z (positive for front objects), enabling
	 * GL_GREATER depth with clearDepth=0.
	 *
	 * @param fov  XrFovf with angleLeft/Right/Up/Down in radians
	 * @param near near plane distance in meters (e.g. 0.05)
	 */
	private static float[] buildVrProjection(XrFovf fov, float near)
	{
		float tanL = (float) Math.tan(fov.angleLeft());   // negative
		float tanR = (float) Math.tan(fov.angleRight());  // positive
		float tanU = (float) Math.tan(fov.angleUp());     // positive
		float tanD = (float) Math.tan(fov.angleDown());   // negative

		float a = 2.0f / (tanR - tanL);                          // x scale
		float b = (tanR + tanL) / (tanR - tanL);                 // x asymmetric offset
		float c = 2.0f / (tanU - tanD);                          // y scale
		float d = (tanU + tanD) / (tanU - tanD);                 // y asymmetric offset
		float n2 = 2.0f * near;                                  // 2*near for clip_z

		// Column-major 4×4 (columns stored contiguously).
		// clip = M * [x, y, z, 1]:
		//   clip_x = a*x + b*z     → ndc_x in [-1,1] across angleLeft..angleRight
		//   clip_y = c*y + d*z     → ndc_y in [-1,1] across angleDown..angleUp  (Y-up)
		//   clip_z = 2n            → constant; gives hyperbolic depth ndc_z = 2n/(-z)
		//   clip_w = -z            → positive for front objects (z < 0 in OpenXR)
		return new float[]{
			a,  0,  0,  0,   // col 0
			0,  c,  0,  0,   // col 1
			b,  d,  0, -1,   // col 2
			0,  0, n2,  0,   // col 3
		};
	}

	/**
	 * Build the inverse-pose view matrix from an XrPosef.
	 * <p>
	 * InvPose = R^T × Translate(-position), stored column-major for OpenGL.
	 *
	 * @param pose eye pose in stage space
	 */
	private static float[] buildInvEyePose(XrPosef pose)
	{
		// Quaternion components
		float qx = pose.orientation().x();
		float qy = pose.orientation().y();
		float qz = pose.orientation().z();
		float qw = pose.orientation().w();
		// Eye position
		float px = pose.position$().x();
		float py = pose.position$().y();
		float pz = pose.position$().z();

		// Build rotation matrix R from unit quaternion
		float r00 = 1 - 2 * (qy * qy + qz * qz);
		float r01 = 2 * (qx * qy + qw * qz);
		float r02 = 2 * (qx * qz - qw * qy);
		float r10 = 2 * (qx * qy - qw * qz);
		float r11 = 1 - 2 * (qx * qx + qz * qz);
		float r12 = 2 * (qy * qz + qw * qx);
		float r20 = 2 * (qx * qz + qw * qy);
		float r21 = 2 * (qy * qz - qw * qx);
		float r22 = 1 - 2 * (qx * qx + qy * qy);

		// t = -(R^T * p).  With code's r_ab = R^T[a][b], row a of R^T = (r_a0, r_a1, r_a2).
		float tx = -(r00 * px + r01 * py + r02 * pz);
		float ty = -(r10 * px + r11 * py + r12 * pz);
		float tz = -(r20 * px + r21 * py + r22 * pz);

		// Column-major R^T: col j = (R^T[0][j], R^T[1][j], R^T[2][j]) = (r0j, r1j, r2j).
		return new float[]{
			r00, r10, r20, 0,   // col 0
			r01, r11, r21, 0,   // col 1
			r02, r12, r22, 0,   // col 2
			tx,  ty,  tz,  1,   // col 3
		};
	}

	// -------------------------------------------------------------------------
	// Debug ray visualisation
	// -------------------------------------------------------------------------

	private void initDebugVao()
	{
		debugVaoId = glGenVertexArrays();
		debugVboId = glGenBuffers();
		debugRayFb  = BufferUtils.createFloatBuffer(768); // 2 rays + tile outlines + click marker + entity tile, 7 floats/vert
		depthReadBuf = BufferUtils.createFloatBuffer(1);
		fboReadBuf   = BufferUtils.createIntBuffer(1);
		desktopSorterScratchOpaque = BufferUtils.createIntBuffer(FacePrioritySorter.MAX_FACE_COUNT * (VAO.VERT_SIZE >> 2) * 3);

		glBindVertexArray(debugVaoId);
		glBindBuffer(GL_ARRAY_BUFFER, debugVboId);
		// Pre-allocate GPU buffer for the max vertex count.
		glBufferData(GL_ARRAY_BUFFER, (long) 768 * Float.BYTES, GL_STREAM_DRAW);

		// Attribute 0: vec3 position (OSRS world coords)
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 7 * Float.BYTES, 0L);
		// Attribute 1: vec4 colour (RGBA)
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_FLOAT, false, 7 * Float.BYTES, (long) (3 * Float.BYTES));

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	/**
	 * Build controller ray vertices into {@link #debugRayFb}.
	 * Per controller: ray line + crosshair at endpoint + tile outline on the ground.
	 * Returns number of floats written.
	 */
	private int buildDebugRayVerts(float[] leftHit, float[] rightHit)
	{
		final float s = DEFAULT_WORLD_SCALE;
		final float oy = vrWorldAnchorY;
		final float oz = -1.5f;
		final float camX = root.cameraX;
		final float camY = root.cameraY;
		final float camZ = root.cameraZ;
		final float RAY_M = 5f;          // ray length in metres
		final float CROSS = 17f;         // crosshair arm half-length in OSRS units (~3× smaller)
		final float TILE  = 128f;        // one tile in OSRS local units

		debugRayFb.clear();

		// Colors: idle=hand color (left=green, right=blue), trigger=yellow (LMB), squeeze=red (RMB).
		boolean lTrig = xrInput.getLeftTrigger()  > 0.7f, lSqz = xrInput.getLeftSqueeze()  > 0.7f;
		boolean rTrig = xrInput.getRightTrigger() > 0.7f, rSqz = xrInput.getRightSqueeze() > 0.7f;

		float[][] controllers = {
			// px, py, pz, dx, dy, dz, r, g, b
			xrInput.isLeftActive()  ? new float[]{
				xrInput.getLeftPosX(),  xrInput.getLeftPosY(),  xrInput.getLeftPosZ(),
				xrInput.getLeftDirX(),  xrInput.getLeftDirY(),  xrInput.getLeftDirZ(),
				lTrig ? 1f : (lSqz ? 1f : 0f),
				lTrig ? 1f : 1f,
				lSqz || lTrig ? 0f : 0f
			} : null,
			xrInput.isRightActive() ? new float[]{
				xrInput.getRightPosX(), xrInput.getRightPosY(), xrInput.getRightPosZ(),
				xrInput.getRightDirX(), xrInput.getRightDirY(), xrInput.getRightDirZ(),
				rTrig ? 1f : (rSqz ? 1f : 0f),
				rTrig ? 1f : 0f,
				rTrig || rSqz ? 0f : 1f
			} : null,
		};

		for (int ci = 0; ci < controllers.length; ci++)
		{
			float[] c = controllers[ci];
			if (c == null) continue;
			float px = c[0], py = c[1], pz = c[2];
			float dx = c[3], dy = c[4], dz = c[5];
			float r = c[6], g = c[7], b = c[8];

			// Convert stage-space (metres) to OSRS world coordinates.
			float ox2 = camX - px / s;
			float oy2 = camY - (py - oy) / s;
			float oz2 = camZ + (pz - oz) / s;

			float[] hit = (ci == 0) ? leftHit : rightHit;
			float ex, ey, ez;
			if (hit != null)
			{
				ex = hit[0]; ey = hit[1]; ez = hit[2];
			}
			else
			{
				ex = camX - (px + dx * RAY_M) / s;
				ey = camY - ((py + dy * RAY_M) - oy) / s;
				ez = camZ + ((pz + dz * RAY_M) - oz) / s;
			}

			// Ray line: origin → endpoint
			debugRayFb.put(ox2).put(oy2).put(oz2).put(r).put(g).put(b).put(1f);
			debugRayFb.put(ex) .put(ey) .put(ez) .put(r).put(g).put(b).put(1f);

			// Crosshair at endpoint (3 axes)
			debugRayFb.put(ex - CROSS).put(ey).put(ez).put(r).put(g).put(b).put(1f);
			debugRayFb.put(ex + CROSS).put(ey).put(ez).put(r).put(g).put(b).put(1f);
			debugRayFb.put(ex).put(ey - CROSS).put(ez).put(r).put(g).put(b).put(1f);
			debugRayFb.put(ex).put(ey + CROSS).put(ez).put(r).put(g).put(b).put(1f);
			debugRayFb.put(ex).put(ey).put(ez - CROSS).put(r).put(g).put(b).put(1f);
			debugRayFb.put(ex).put(ey).put(ez + CROSS).put(r).put(g).put(b).put(1f);

			// Tile outline on the ground at the hit tile.
			// Snap ex/ez to tile-grid SW corner; use ey for ground height.
			if (hit != null)
			{
				float tx = (float) (((int) ex >> 7) << 7);
				float tz = (float) (((int) ez >> 7) << 7);
				float ty = ey;
				// 4 segments: SW→SE, SE→NE, NE→NW, NW→SW
				debugRayFb.put(tx       ).put(ty).put(tz       ).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx + TILE).put(ty).put(tz       ).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx + TILE).put(ty).put(tz       ).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx + TILE).put(ty).put(tz + TILE).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx + TILE).put(ty).put(tz + TILE).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx       ).put(ty).put(tz + TILE).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx       ).put(ty).put(tz + TILE).put(r).put(g).put(b).put(0.7f);
				debugRayFb.put(tx       ).put(ty).put(tz       ).put(r).put(g).put(b).put(0.7f);
			}
		}

		// Persistent click diagnostics: visible for 3 seconds.
		if (vrLastClickHit != null && System.currentTimeMillis() - vrLastClickTimeMs < 3000)
		{
			float cx = vrLastClickHit[0], cy = vrLastClickHit[1], cz = vrLastClickHit[2];
			final float BIG = 150f;
			final float SMALL = BIG / 3f;

			if (vrLastClickRay != null)
			{
				float rox = vrLastClickRay[0], roy = vrLastClickRay[1], roz = vrLastClickRay[2];
				float rdx = vrLastClickRay[3], rdy = vrLastClickRay[4], rdz = vrLastClickRay[5];
				float far = 6000f;
				float rex = rox + rdx * far;
				float rey = roy + rdy * far;
				float rez = roz + rdz * far;
				// Orange = actual VR click ray in OSRS coords.
				debugRayFb.put(rox).put(roy).put(roz).put(1f).put(0.55f).put(0f).put(1f);
				debugRayFb.put(rex).put(rey).put(rez).put(1f).put(0.55f).put(0f).put(1f);
			}

			if (vrLastGroundHit != null)
			{
				float gx = vrLastGroundHit[0], gy = vrLastGroundHit[1], gz = vrLastGroundHit[2];
				// Yellow = resolved VR ground intersection point.
				debugRayFb.put(gx - SMALL).put(gy).put(gz        ).put(1f).put(1f).put(0f).put(1f);
				debugRayFb.put(gx + SMALL).put(gy).put(gz        ).put(1f).put(1f).put(0f).put(1f);
				debugRayFb.put(gx        ).put(gy).put(gz - SMALL).put(1f).put(1f).put(0f).put(1f);
				debugRayFb.put(gx        ).put(gy).put(gz + SMALL).put(1f).put(1f).put(0f).put(1f);
			}

			if (vrDesktopCameraAimTarget != null)
			{
				double cameraApiX = client.getCameraFpX();
				double cameraApiY = client.getCameraFpY();
				double cameraApiZ = client.getCameraFpZ();
				double cameraPitchFp = client.getCameraFpPitch();
				double cameraYawFp = client.getCameraFpYaw();
				float originX = (float) cameraApiX;
				float originY = (float) cameraApiZ;
				float originZ = (float) cameraApiY;
				// Blue cross = actual hidden desktop camera position after steering.
				debugRayFb.put(originX - BIG).put(originY).put(originZ).put(0.2f).put(0.5f).put(1f).put(1f);
				debugRayFb.put(originX + BIG).put(originY).put(originZ).put(0.2f).put(0.5f).put(1f).put(1f);
				debugRayFb.put(originX).put(originY - BIG).put(originZ).put(0.2f).put(0.5f).put(1f).put(1f);
				debugRayFb.put(originX).put(originY + BIG).put(originZ).put(0.2f).put(0.5f).put(1f).put(1f);
				debugRayFb.put(originX).put(originY).put(originZ - BIG).put(0.2f).put(0.5f).put(1f).put(1f);
				debugRayFb.put(originX).put(originY).put(originZ + BIG).put(0.2f).put(0.5f).put(1f).put(1f);
				float dirApiX = (float) (-Math.cos(cameraPitchFp) * Math.sin(cameraYawFp));
				float dirApiY = (float) (Math.cos(cameraPitchFp) * Math.cos(cameraYawFp));
				float dirApiZ = (float) Math.sin(cameraPitchFp);
				float dirX = dirApiX;
				float dirY = dirApiZ;
				float dirZ = dirApiY;
				float far = 6000f;
				// Lime = actual desktop camera center ray read back from the client after steering.
				debugRayFb.put(originX).put(originY).put(originZ).put(0.4f).put(1f).put(0.1f).put(1f);
				debugRayFb.put(originX + dirX * far).put(originY + dirY * far).put(originZ + dirZ * far).put(0.4f).put(1f).put(0.1f).put(1f);

				float tx = vrDesktopCameraAimTarget[0];
				float ty = vrDesktopCameraAimTarget[1];
				float tz = vrDesktopCameraAimTarget[2];
				// Purple = line from actual desktop camera position to the intended VR ground target.
				debugRayFb.put(originX).put(originY).put(originZ).put(0.8f).put(0.1f).put(1f).put(0.95f);
				debugRayFb.put(tx).put(ty).put(tz).put(0.8f).put(0.1f).put(1f).put(0.95f);

				float pax = getVrAnchorWorldX();
				float pay = getVrAnchorWorldY();
				float paz = getVrAnchorWorldZ();
				// Aqua = player anchor to intended target, i.e. the desired forward-facing direction.
				debugRayFb.put(pax).put(pay).put(paz).put(0.1f).put(0.9f).put(1f).put(0.9f);
				debugRayFb.put(tx).put(ty).put(tz).put(0.1f).put(0.9f).put(1f).put(0.9f);
			}

			if (vrLastDesktopRayHit != null)
			{
				float hx = vrLastDesktopRayHit[0], hy = vrLastDesktopRayHit[1], hz = vrLastDesktopRayHit[2];
				// White cross = where the reconstructed desktop screen ray hits the ground.
				debugRayFb.put(hx - BIG).put(hy).put(hz      ).put(1f).put(1f).put(1f).put(1f);
				debugRayFb.put(hx + BIG).put(hy).put(hz      ).put(1f).put(1f).put(1f).put(1f);
				debugRayFb.put(hx      ).put(hy).put(hz - BIG).put(1f).put(1f).put(1f).put(1f);
				debugRayFb.put(hx      ).put(hy).put(hz + BIG).put(1f).put(1f).put(1f).put(1f);
			}

			if (vrLastDispatchSceneTile != null)
			{
				float dtx = vrLastDispatchSceneTile[0] * TILE;
				float dtz = vrLastDispatchSceneTile[1] * TILE;
				float dty = vrLastGroundHit != null ? vrLastGroundHit[1] : cy;
				// Red = tile params actually sent to menuAction.
				debugRayFb.put(dtx       ).put(dty).put(dtz       ).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx + TILE).put(dty).put(dtz       ).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx + TILE).put(dty).put(dtz       ).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx + TILE).put(dty).put(dtz + TILE).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx + TILE).put(dty).put(dtz + TILE).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx       ).put(dty).put(dtz + TILE).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx       ).put(dty).put(dtz + TILE).put(1f).put(0f).put(0f).put(1f);
				debugRayFb.put(dtx       ).put(dty).put(dtz       ).put(1f).put(0f).put(0f).put(1f);
			}

			if (vrLastClientDestination != null)
			{
				float dx2 = vrLastClientDestination[0];
				float dy2 = vrLastClientDestination[1];
				float dz2 = vrLastClientDestination[2];
				// Cyan = client local destination after the walk click.
				debugRayFb.put(dx2 - BIG).put(dy2).put(dz2      ).put(0f).put(1f).put(1f).put(1f);
				debugRayFb.put(dx2 + BIG).put(dy2).put(dz2      ).put(0f).put(1f).put(1f).put(1f);
				debugRayFb.put(dx2      ).put(dy2).put(dz2 - BIG).put(0f).put(1f).put(1f).put(1f);
				debugRayFb.put(dx2      ).put(dy2).put(dz2 + BIG).put(0f).put(1f).put(1f).put(1f);
			}

		}

		debugRayFb.flip();
		return debugRayFb.limit();
	}

	/**
	 * Project the given stage-space ray into the eye's viewport, read the depth buffer at
	 * that pixel, and reconstruct the OSRS world-space intersection point.
	 *
	 * @return float[3] OSRS world coords, or null if no geometry was hit (depth == 0).
	 */
	private float[] sampleDepthAtRay(int eye,
		float rox, float roy, float roz,
		float rdx, float rdy, float rdz)
	{
		// Project a point far along the ray to find the viewport direction.
		final float FAR = 10f;
		final float s = DEFAULT_WORLD_SCALE;
		final float anchorY = vrWorldAnchorY;
		final float anchorZ = VR_STAGE_CHARACTER_OFFSET_Z;
		final float anchorWorldX = getVrAnchorWorldX();
		final float anchorWorldY = getVrAnchorWorldY();
		final float anchorWorldZ = getVrAnchorWorldZ();

		float testX = rox + rdx * FAR;
		float testY = roy + rdy * FAR;
		float testZ = roz + rdz * FAR;

		// Stage space → OSRS world
		float wx = anchorWorldX - testX / s;
		float wy = anchorWorldY - (testY - anchorY) / s;
		float wz = anchorWorldZ + (testZ - anchorZ) / s;

		// OSRS world → clip space
		float[] proj = computeVrWorldProj(eye);
		float cx  = proj[0]*wx + proj[4]*wy + proj[8]*wz  + proj[12];
		float cy  = proj[1]*wx + proj[5]*wy + proj[9]*wz  + proj[13];
		float cw  = proj[3]*wx + proj[7]*wy + proj[11]*wz + proj[15];
		if (cw <= 0) return null; // behind camera

		float ndcX = cx / cw;
		float ndcY = cy / cw;

		int vpW = eyeSwapchains[eye].getWidth();
		int vpH = eyeSwapchains[eye].getHeight();

		// NDC → pixel (GL_LOWER_LEFT, GL_ZERO_TO_ONE: Y up from bottom)
		int px = Math.round((ndcX + 1f) * 0.5f * vpW);
		int py = Math.round((ndcY + 1f) * 0.5f * vpH);
		px = Math.max(0, Math.min(vpW - 1, px));
		py = Math.max(0, Math.min(vpH - 1, py));

		// Read depth: bind the current draw FBO also as read so glReadPixels sees scene depth.
		fboReadBuf.clear();
		glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, fboReadBuf);
		int eyeFbo = fboReadBuf.get(0);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, eyeFbo);

		depthReadBuf.clear();
		glReadPixels(px, py, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depthReadBuf);
		float depth = depthReadBuf.get(0);

		if (depth < 0.001f) return null; // no geometry (background)

		// Reconstruct eye-space position: depth = 2*near / (-z_eye)
		final float near = 0.05f;
		float zEye = -2f * near / depth;

		// Get per-eye FOV params to unproject X and Y
		XrView view = vrViews.get(eye);
		XrFovf fov = view.fov();
		float tanL = (float) Math.tan(fov.angleLeft());
		float tanR = (float) Math.tan(fov.angleRight());
		float tanU = (float) Math.tan(fov.angleUp());
		float tanD = (float) Math.tan(fov.angleDown());
		float a  = 2f / (tanR - tanL);
		float b  = (tanR + tanL) / (tanR - tanL);
		float c2 = 2f / (tanU - tanD);
		float d  = (tanU + tanD) / (tanU - tanD);

		// ndc_x = -a*xEye/zEye - b  →  xEye = -(ndcX + b) * zEye / a
		float xEye = -(ndcX + b) * zEye / a;
		float yEye = -(ndcY + d) * zEye / c2;

		// Eye space → stage space: stagePos = R * eyePos + eyeOrigin
		XrPosef pose = view.pose();
		float qx = pose.orientation().x(), qy = pose.orientation().y();
		float qz = pose.orientation().z(), qw = pose.orientation().w();
		float epx = pose.position$().x(), epy = pose.position$().y(), epz = pose.position$().z();

		float r00 = 1-2*(qy*qy+qz*qz), r01 = 2*(qx*qy-qw*qz), r02 = 2*(qx*qz+qw*qy);
		float r10 = 2*(qx*qy+qw*qz), r11 = 1-2*(qx*qx+qz*qz), r12 = 2*(qy*qz-qw*qx);
		float r20 = 2*(qx*qz-qw*qy), r21 = 2*(qy*qz+qw*qx), r22 = 1-2*(qx*qx+qy*qy);

		float stX = r00*xEye + r01*yEye + r02*zEye + epx;
		float stY = r10*xEye + r11*yEye + r12*zEye + epy;
		float stZ = r20*xEye + r21*yEye + r22*zEye + epz;

		// Stage space → OSRS world
		float osrsX = anchorWorldX - stX / s;
		float osrsY = anchorWorldY - (stY - anchorY) / s;
		float osrsZ = anchorWorldZ + (stZ - anchorZ) / s;

		return new float[]{osrsX, osrsY, osrsZ};
	}

	/**
	 * Draw controller aim rays for the given eye using the debug line shader.
	 * Must be called with blend/cull/depth already disabled.
	 */
	private void drawDebugRays(int eye)
	{
		if (xrInput == null || Float.isNaN(vrWorldAnchorY)) return;
		if (!xrInput.isLeftActive() && !xrInput.isRightActive()) return;

		// Sample depth buffer on eye=1 (right eye FBO bound, fresh scene depth).
		if (eye == 1)
		{
			vrLeftRayHit  = xrInput.isLeftActive()  ? sampleDepthAtRay(1,
				xrInput.getLeftPosX(),  xrInput.getLeftPosY(),  xrInput.getLeftPosZ(),
				xrInput.getLeftDirX(),  xrInput.getLeftDirY(),  xrInput.getLeftDirZ()) : null;
			vrRightRayHit = xrInput.isRightActive() ? sampleDepthAtRay(1,
				xrInput.getRightPosX(), xrInput.getRightPosY(), xrInput.getRightPosZ(),
				xrInput.getRightDirX(), xrInput.getRightDirY(), xrInput.getRightDirZ()) : null;
		}

		int n = buildDebugRayVerts(vrLeftRayHit, vrRightRayHit);
		if (n == 0) return;

		glBindVertexArray(debugVaoId);
		glBindBuffer(GL_ARRAY_BUFFER, debugVboId);
		glBufferSubData(GL_ARRAY_BUFFER, 0, debugRayFb);

		glUseProgram(glDebugProgram);
		glUniformMatrix4fv(uniDebugWorldProj, false, computeVrWorldProj(eye));
		glLineWidth(3f);
		glDrawArrays(GL_LINES, 0, n / 7);

		glBindVertexArray(0);
		glUseProgram(glProgram);

		// Rising-edge click detection — runs once per frame on eye=1.
		// Either trigger (LMB) or either squeeze (RMB) at the right-controller ray position.
		if (eye == 1)
		{
			float lmb = Math.max(xrInput.getLeftTrigger(),  xrInput.getRightTrigger());
			float rmb = Math.max(xrInput.getLeftSqueeze(),  xrInput.getRightSqueeze());

			// Diagnostic: log when any button crosses 30%
			if ((lmb > 0.3f && prevLmb <= 0.3f) || (rmb > 0.3f && prevRmb <= 0.3f))
			{
				log.info("VR input: lt={} rt={} ls={} rs={} leftHit={} rightHit={}",
					xrInput.getLeftTrigger(), xrInput.getRightTrigger(),
					xrInput.getLeftSqueeze(), xrInput.getRightSqueeze(),
					vrLeftRayHit != null, vrRightRayHit != null);
			}

			// Prefer the hand that just pressed; fall back to any valid hit.
			float[] activeHit = null;
			if (xrInput.getRightTrigger() >= 0.7f || xrInput.getRightSqueeze() >= 0.7f)
				activeHit = vrRightRayHit;
			if (activeHit == null && (xrInput.getLeftTrigger() >= 0.7f || xrInput.getLeftSqueeze() >= 0.7f))
				activeHit = vrLeftRayHit;
			if (activeHit == null)
				activeHit = vrRightRayHit != null ? vrRightRayHit : vrLeftRayHit;

			if (activeHit != null)
			{
				boolean isLmb = lmb >= 0.7f && prevLmb < 0.7f;
				boolean isRmb = rmb >= 0.7f && prevRmb < 0.7f;
				if (isLmb || isRmb)
				{
					int btn = isLmb ? MouseEvent.BUTTON1 : MouseEvent.BUTTON3;
					vrPendingClickHit = activeHit.clone();
					vrPendingClickButton = btn;
					vrLastClickHit = activeHit.clone();
					vrLastClickButton = btn;
					vrLastClickTimeMs = System.currentTimeMillis();

					// Compute OSRS-space ray from the active controller for client-thread raycast.
					// Right controller takes priority; fall back to left if right is not active.
					boolean useRight = xrInput.getRightTrigger() >= 0.7f || xrInput.getRightSqueeze() >= 0.7f;
					float spx, spy, spz, sdx, sdy, sdz;
					if (useRight || !xrInput.isLeftActive())
					{
						spx = xrInput.getRightPosX(); spy = xrInput.getRightPosY(); spz = xrInput.getRightPosZ();
						sdx = xrInput.getRightDirX(); sdy = xrInput.getRightDirY(); sdz = xrInput.getRightDirZ();
					}
					else
					{
						spx = xrInput.getLeftPosX(); spy = xrInput.getLeftPosY(); spz = xrInput.getLeftPosZ();
						sdx = xrInput.getLeftDirX(); sdy = xrInput.getLeftDirY(); sdz = xrInput.getLeftDirZ();
					}
					final float s = DEFAULT_WORLD_SCALE;
					float anchorWorldX = getVrAnchorWorldX();
					float anchorWorldY = getVrAnchorWorldY();
					float anchorWorldZ = getVrAnchorWorldZ();
					float rox = anchorWorldX - spx / s;
					float roy = anchorWorldY - (spy - vrWorldAnchorY) / s;
					float roz = anchorWorldZ + (spz - VR_STAGE_CHARACTER_OFFSET_Z) / s;
					float rdx;
					float rdy;
					float rdz;
					if (activeHit != null)
					{
						// First diagnostic fix: derive the click ray from the visual depth hit.
						// This keeps the client-thread ray aligned with the ray the user actually sees.
						rdx = activeHit[0] - rox;
						rdy = activeHit[1] - roy;
						rdz = activeHit[2] - roz;
						float len = (float) Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
						if (len > 1e-6f)
						{
							rdx /= len;
							rdy /= len;
							rdz /= len;
						}
						else
						{
							rdx = -sdx;
							rdy = -sdy;
							rdz = sdz;
						}
					}
					else
					{
						// Y is flipped: OSRS Y increases downward, stage Y increases upward.
						rdx = -sdx;
						rdy = -sdy;
						rdz = sdz;
					}
					vrPendingClickRay = new float[]{rox, roy, roz, rdx, rdy, rdz};
					vrLastClickRay = vrPendingClickRay.clone();
					log.info("VR {} triggered: depth=({},{},{}) stageDir=({},{},{}) clickDir=({},{},{})",
						isLmb ? "LMB" : "RMB",
						activeHit[0], activeHit[1], activeHit[2],
						-sdx, -sdy, sdz,
						rdx, rdy, rdz);
				}
			}
			prevLmb = lmb;
			prevRmb = rmb;
		}
	}

	/**
	 * Build a {@link Projection} for the face-priority sorter that uses the VR eye's view
	 * direction rather than the OSRS 2D camera direction.
	 * <p>
	 * The sorter uses {@code project()} to:
	 * <ol>
	 *   <li>Clip vertices behind the camera ({@code p[2] < 50}).</li>
	 *   <li>Compute 2D positions ({@code p[0]/p[2]}, {@code p[1]/p[2]}) for a cross-product
	 *       winding test that decides which faces are front-facing.</li>
	 *   <li>Sort faces by depth ({@code p[2] - zero}).</li>
	 * </ol>
	 * Returns {@code [clipX, clipY, clipW / DEFAULT_WORLD_SCALE]}:
	 * <ul>
	 *   <li>clipX/clipY come from the VR eye perspective transform.</li>
	 *   <li>X and Y are both flipped in the OSRS→VR world transform, preserving winding
	 *       so normal back-face culling still applies in VR.</li>
	 *   <li>clipW (view-space depth in VR metres) is divided by the world scale
	 *       to convert back to OSRS units so that the {@code > 50} clip threshold holds.</li>
	 * </ul>
	 */
	private Projection buildVrSorterProjection(int eye)
	{
		XrView view = vrViews.get(eye);
		XrPosef pose = view.pose();
		XrFovf fov = view.fov();

		float qx = pose.orientation().x();
		float qy = pose.orientation().y();
		float qz = pose.orientation().z();
		float qw = pose.orientation().w();
		float px = pose.position$().x();
		float py = pose.position$().y();
		float pz = pose.position$().z();

		// R^T rows (view rotation matrix, same derivation as buildInvEyePose)
		final float r00 = 1 - 2 * (qy * qy + qz * qz);
		final float r01 = 2 * (qx * qy + qw * qz);
		final float r02 = 2 * (qx * qz - qw * qy);
		final float r10 = 2 * (qx * qy - qw * qz);
		final float r11 = 1 - 2 * (qx * qx + qz * qz);
		final float r12 = 2 * (qy * qz + qw * qx);
		final float r20 = 2 * (qx * qz + qw * qy);
		final float r21 = 2 * (qy * qz - qw * qx);
		final float r22 = 1 - 2 * (qx * qx + qy * qy);

		// Translation t = -R^T * eyePos
		final float tx = -(r00 * px + r01 * py + r02 * pz);
		final float ty = -(r10 * px + r11 * py + r12 * pz);
		final float tz = -(r20 * px + r21 * py + r22 * pz);

		// Asymmetric perspective scale/offset from FOV angles
		float tanL = (float) Math.tan(fov.angleLeft());
		float tanR = (float) Math.tan(fov.angleRight());
		float tanU = (float) Math.tan(fov.angleUp());
		float tanD = (float) Math.tan(fov.angleDown());
		final float a = 2f / (tanR - tanL);
		final float b = (tanR + tanL) / (tanR - tanL);
		final float c = 2f / (tanU - tanD);
		final float d = (tanU + tanD) / (tanU - tanD);

		final float s = DEFAULT_WORLD_SCALE;
		final float oy = vrWorldAnchorY;
		final float oz = VR_STAGE_CHARACTER_OFFSET_Z;
		final float camX = getVrAnchorWorldX();
		final float camY = getVrAnchorWorldY();
		final float camZ = getVrAnchorWorldZ();

		return new Projection()
		{
			@Override
			public float[] project(float wx, float wy, float wz)
			{
				return project(wx, wy, wz, new float[3]);
			}

			@Override
			public float[] project(float wx, float wy, float wz, float[] out)
			{
				// Translate by -camera, scale (OSRS→VR metres, X/Y flips), add world offset
				float vx = -(wx - camX) * s;
				float vy = -(wy - camY) * s + oy;
				float vz = (wz - camZ) * s + oz;

				// View rotation R^T + translation → eye space
				float ex = r00 * vx + r01 * vy + r02 * vz + tx;
				float ey = r10 * vx + r11 * vy + r12 * vz + ty;
				float ez = r20 * vx + r21 * vy + r22 * vz + tz;

				// Perspective: clip_w = -ez (positive for front objects, camera looks -Z)
				float clipW = -ez;
				// The VR world projection flips X to correct the headset image handedness.
				// FacePrioritySorter does its own 2D winding test on projected X/Y to decide
				// which one-sided temp/model faces are front-facing before they are uploaded.
				// Without mirroring clip_x here too, that winding test sees the opposite
				// orientation and uploads the wrong faces, which makes players/NPCs appear
				// partially or almost completely culled in VR.
				out[0] = -(a * ex + b * ez); // clip_x
				out[1] = c * ey + d * ez;  // clip_y: Y-up, consistent with worldProjection
				out[2] = clipW / s;        // depth in OSRS units (so >50 clip test holds)
				return out;
			}
		};
	}

	private Projection buildDesktopSorterProjection()
	{
		final float cameraYaw = getSafeDesktopCameraYawRad();
		final float cameraPitch = getSafeDesktopCameraPitchRad();
		final float pitchSin = (float) Math.sin(cameraPitch);
		final float pitchCos = (float) Math.cos(cameraPitch);
		final float yawSin = (float) Math.sin(cameraYaw);
		final float yawCos = (float) Math.cos(cameraYaw);
		final float cameraX = (float) client.getCameraFpX();
		final float cameraY = (float) client.getCameraFpZ();
		final float cameraZ = (float) client.getCameraFpY();

		return new Projection()
		{
			@Override
			public float[] project(float wx, float wy, float wz)
			{
				return project(wx, wy, wz, new float[3]);
			}

			@Override
			public float[] project(float wx, float wy, float wz, float[] out)
			{
				// Match Perspective.localToCanvasGpu(), but with the plugin's x,height,z axis order.
				final float fx = wx - cameraX;
				final float fy = wz - cameraZ;
				final float fz = wy - cameraY;

				final float x1 = fx * yawCos + fy * yawSin;
				final float y1 = fy * yawCos - fx * yawSin;
				final float y2 = fz * pitchCos - y1 * pitchSin;
				final float z1 = y1 * pitchCos + fz * pitchSin;

				out[0] = x1;
				out[1] = y2;
				out[2] = z1;
				return out;
			}
		};
	}

	// -------------------------------------------------------------------------

	static void updateEntityProjection(Projection projection)
	{
		if (lastProjection != projection)
		{
			float[] p = projection instanceof FloatProjection ? ((FloatProjection) projection).getProjection() : Mat4.identity();
			glUniformMatrix4fv(uniEntityProj, false, p);
			lastProjection = projection;
		}
	}

	@Override
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		SceneContext ctx = context(scene);
		if (ctx != null)
		{
			ctx.cameraX = (int) cameraX;
			ctx.cameraY = (int) cameraY;
			ctx.cameraZ = (int) cameraZ;
			ctx.minLevel = minLevel;
			ctx.level = level;
			ctx.maxLevel = maxLevel;
			ctx.hideRoofIds = hideRoofIds;
		}

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			this.cameraYaw = client.getCameraYaw();
			this.cameraPitch = client.getCameraPitch();
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
		else
		{
			Scene toplevel = client.getScene();
			vaoO.addRange(null, toplevel);
			vaoPO.addRange(null, toplevel);
			vaoDesktopO.addRange(null, toplevel);
			vaoDesktopPO.addRange(null, toplevel);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}
	}

	private void preSceneDrawToplevel(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw)
	{
		scene.setDrawDistance(getDrawDistance());

		// UBO
		uniformBuffer.clear();
		uniformBuffer
			.put(cameraYaw)
			.put(cameraPitch)
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);

		checkGLErrors();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		if (xrFrameStarted)
		{
			// VR path — left eye setup (T3.1)
			vrViews = xrContext.locateViews();
			if (xrInput != null)
			{
				xrInput.sync(xrContext.getSession(), xrContext.getStageSpace(),
					xrContext.getPendingDisplayTime());
			}
			currentEye = 0;
			vrOpaqueZones.clear();
			vrOpaqueProjs.clear();
			vrAlphaZones.clear();
			vrAlphaProjs.clear();
			vrScene = scene;
			vrPassOpaqueCount = 0;
			vrPassDesktopOpaqueCount = 0;
			vrPassPlayerCount = 0;
			vrPassDesktopPlayerCount = 0;

			// Sample world anchor Y from initial eye height (once per session).
			if (Float.isNaN(vrWorldAnchorY))
			{
				float initialEyeY = vrViews.get(0).pose().position$().y();
				vrWorldAnchorY = initialEyeY + VR_STAGE_CHARACTER_OFFSET_Y;
				log.info("VR world anchor Y set to {} (eyeY={} {}m)", vrWorldAnchorY, initialEyeY, String.format("%+.1f", VR_STAGE_CHARACTER_OFFSET_Y));
			}

			// Build sorter projection after vrWorldAnchorY is guaranteed non-NaN.
			vrSorterProjection = buildVrSorterProjection(0);

			vrLeftEyeFbo = eyeSwapchains[0].acquireImage();
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, vrLeftEyeFbo);
		}
		else
		{
			// Desktop path — setup FBO and anti-aliasing
			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownFbo();

				// Bind default FBO to check whether anti-aliasing is forced
				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
				final int forcedAASamples = glGetInteger(GL_SAMPLES);
				final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
				final int samples = forcedAASamples != 0 ? forcedAASamples :
					Math.min(antiAliasingMode.getSamples(), maxSamples);

				log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, maxSamples, forcedAASamples);

				initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
				lastAntiAliasingMode = antiAliasingMode;
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		}

		// Clear scene
		int sky = client.getSkyboxColor();
		glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		// Setup anisotropic filtering
		final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

		if (textureArrayId != -1 && lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
		{
			textureManager.setAnisotropicFilteringLevel(textureArrayId, anisotropicFilteringLevel);
			lastAnisotropicFilteringLevel = anisotropicFilteringLevel;
		}

		// Setup viewport
		if (xrFrameStarted)
		{
			// VR: render to full left-eye swapchain resolution
			glViewport(0, 0, eyeSwapchains[0].getWidth(), eyeSwapchains[0].getHeight());
		}
		else
		{
			int renderWidthOff = client.getViewportXOffset();
			int renderHeightOff = client.getViewportYOffset();
			int renderCanvasHeight = canvasHeight;
			int renderViewportHeight = viewportHeight;
			int renderViewportWidth = viewportWidth;
			if (client.isStretchedEnabled())
			{
				Dimension dim = client.getStretchedDimensions();
				renderCanvasHeight = dim.height;

				double scaleFactorY = dim.getHeight() / canvasHeight;
				double scaleFactorX = dim.getWidth() / canvasWidth;

				// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
				final int padding = 1;

				// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
				renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
				renderViewportWidth = (int) Math.ceil(scaleFactorX * (renderViewportWidth)) + padding * 2;

				// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
				renderHeightOff = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
				renderWidthOff = (int) Math.floor(scaleFactorX * (renderWidthOff)) - padding;
			}

			glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);
		}

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final int fogDepth = config.fogDepth();
		glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glUniform1i(uniFogDepth, fogDepth);
		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());

		// Brightness happens to also be stored in the texture provider, so we use that
		TextureProvider textureProvider = client.getTextureProvider();
		glUniform1f(uniBrightness, (float) textureProvider.getBrightness());
		glUniform1f(uniSmoothBanding, config.smoothBanding() ? 0f : 1f);
		glUniform1f(uniTextureLightMode, config.brightTextures() ? 1f : 0f);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// avoid textures animating during loading
			glUniform1i(uniTick, client.getGameCycle() & 127);
		}

		// Calculate projection matrix
		final float[] projectionMatrix;
		if (xrFrameStarted)
		{
			// VR: per-eye perspective from HMD pose + world anchor (T3.2)
			projectionMatrix = computeVrWorldProj(0);
		}
		else
		{
			projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
			Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
			Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
			Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
			Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		}
		glUniformMatrix4fv(uniWorldProj, false, projectionMatrix);

		glUniformMatrix4fv(uniEntityProj, false, Mat4.identity());

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Bind uniforms
		glUniformBlockBinding(glProgram, uniBlockMain, 0);
		glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1

		// Enable face culling.
		// In VR mode the Y scale is negated, which reverses winding order for every
		// triangle.  Switch to GL_FRONT so the rasterizer still discards the correct side.
		glEnable(GL_CULL_FACE);
		if (xrFrameStarted) { glCullFace(GL_BACK); }

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		checkGLErrors();
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
		}
	}

	private void postDrawToplevel()
	{
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		if (xrFrameStarted)
		{
			// Draw controller rays over left eye (state: blend/cull/depth already disabled).
			drawDebugRays(0);

			// Render a true desktop spectator pass from the vanilla/staged desktop camera
			// instead of mirroring the left eye. This lets us validate staged WALK clicks
			// against the same screen-space view the client consumes.
			renderDesktopSpectatorPass();

			// ---- Right eye pass (T3.3) ----
			int rightFbo = eyeSwapchains[1].acquireImage();
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, rightFbo);

			int sky = client.getSkyboxColor();
			glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			glViewport(0, 0, eyeSwapchains[1].getWidth(), eyeSwapchains[1].getHeight());

			glUseProgram(glProgram);
			glUniformMatrix4fv(uniWorldProj, false, computeVrWorldProj(1));
			glUniformMatrix4fv(uniEntityProj, false, Mat4.identity());
			glUniform4i(uniEntityTint, 0, 0, 0, 0);

			glEnable(GL_CULL_FACE);
			glCullFace(GL_BACK); // X/Y flips preserve winding parity; normal back-face culling applies
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);
			glDepthFunc(GL_GREATER);
			glEnable(GL_DEPTH_TEST);

			// Replay opaque zones from left-eye pass
			final int offset = SCENE_OFFSET >> 3;
			for (int i = 0; i < vrOpaqueZones.size(); i++)
			{
				int[] zz = vrOpaqueZones.get(i);
				updateEntityProjection(vrOpaqueProjs.get(i));
				Zone z = root.zones[zz[0]][zz[1]];
				if (z.initialized)
				{
					z.renderOpaque(zz[0] - offset, zz[1] - offset,
						root.minLevel, root.level, root.maxLevel, root.hideRoofIds);
				}
			}

			// Replay drawPass geometry: scene underlay tiles + opaque actors (NPCs, players).
			// These were drawn but not reset during the left-eye drawPass; reset them here.
			glUniform3i(uniBase, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, Mat4.identity());
			for (int i = 0; i < vrPassOpaqueCount; i++)
			{
				vaoO.vaos.get(i).draw();
				vaoO.vaos.get(i).reset();
			}
			vrPassOpaqueCount = 0;

			if (vrPassPlayerCount > 0)
			{
				glDepthMask(false);
				for (int i = 0; i < vrPassPlayerCount; i++) { vaoPO.vaos.get(i).draw(); }
				glDepthMask(true);
				glColorMask(false, false, false, false);
				for (int i = 0; i < vrPassPlayerCount; i++) { vaoPO.vaos.get(i).draw(); vaoPO.vaos.get(i).reset(); }
				glColorMask(true, true, true, true);
				vrPassPlayerCount = 0;
			}

			// Replay alpha zones from left-eye pass (already sorted during left-eye pass)
			vaoA.unmap();
			for (int i = 0; i < vrAlphaZones.size(); i++)
			{
				int[] za = vrAlphaZones.get(i);
				int level = za[0], zx = za[1], zzc = za[2];
				Zone z = root.zones[zx][zzc];
				if (!z.initialized)
				{
					continue;
				}
				updateEntityProjection(vrAlphaProjs.get(i));
				glUniform4i(uniEntityTint, 0, 0, 0, 0);
				int dx = root.cameraX - ((zx - offset) << 10);
				int dz = root.cameraZ - ((zzc - offset) << 10);
				boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;
			z.renderAlpha(zx - offset, zzc - offset, cameraYaw, cameraPitch,
				root.minLevel, root.level, root.maxLevel, level, root.hideRoofIds,
				!close || (vrScene.getOverrideAmount() > 0), false);
			}

			// Cleanup temp alpha models deferred from left-eye PASS_ALPHA
			for (int x = 0; x < root.sizeX; ++x)
			{
				for (int z = 0; z < root.sizeZ; ++z)
				{
					root.zones[x][z].removeTemp();
				}
			}

			glDisable(GL_BLEND);
			glCullFace(GL_BACK); // restore default before leaving VR render path
			glDisable(GL_CULL_FACE);
			glDisable(GL_DEPTH_TEST);

			// Draw controller rays over right eye before releasing swapchain image.
			drawDebugRays(1);
			eyeSwapchains[1].releaseImage();

			eyeSwapchains[0].releaseImage();
			currentEye = -1;

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
			sceneFboValid = true;
			return;
		}

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
	}

	private static final class DesktopViewport
	{
		final int x;
		final int y;
		final int width;
		final int height;

		private DesktopViewport(int x, int y, int width, int height)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}
	}

	private DesktopViewport getDesktopViewport()
	{
		int renderWidthOff = client.getViewportXOffset();
		int renderHeightOff = client.getViewportYOffset();
		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		int renderCanvasHeight = canvasHeight;
		int renderViewportHeight = client.getViewportHeight();
		int renderViewportWidth = client.getViewportWidth();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			renderCanvasHeight = dim.height;

			double scaleFactorY = dim.getHeight() / canvasHeight;
			double scaleFactorX = dim.getWidth() / canvasWidth;
			final int padding = 1;

			renderViewportHeight = (int) Math.ceil(scaleFactorY * renderViewportHeight) + padding * 2;
			renderViewportWidth = (int) Math.ceil(scaleFactorX * renderViewportWidth) + padding * 2;
			renderHeightOff = (int) Math.floor(scaleFactorY * renderHeightOff) - padding;
			renderWidthOff = (int) Math.floor(scaleFactorX * renderWidthOff) - padding;
		}

		int y = renderCanvasHeight - renderViewportHeight - renderHeightOff;
		return new DesktopViewport(renderWidthOff, y, renderViewportWidth, renderViewportHeight);
	}

	private void ensureDesktopSceneFbo()
	{
		final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
		final Dimension stretchedDimensions = client.getStretchedDimensions();
		final int canvasWidth = client.getCanvasWidth();
		final int canvasHeight = client.getCanvasHeight();

		final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
		final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

		if (lastStretchedCanvasWidth != stretchedCanvasWidth
			|| lastStretchedCanvasHeight != stretchedCanvasHeight
			|| lastAntiAliasingMode != antiAliasingMode
			|| fboScene == -1)
		{
			shutdownFbo();

			glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
			final int forcedAASamples = glGetInteger(GL_SAMPLES);
			final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
			final int samples = forcedAASamples != 0 ? forcedAASamples :
				Math.min(antiAliasingMode.getSamples(), maxSamples);

			log.debug("Desktop spectator AA samples: {}, max samples: {}, forced samples: {}",
				samples, maxSamples, forcedAASamples);

			initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);
			lastStretchedCanvasWidth = stretchedCanvasWidth;
			lastStretchedCanvasHeight = stretchedCanvasHeight;
			lastAntiAliasingMode = antiAliasingMode;
		}
	}

	private float[] computeDesktopWorldProj()
	{
		float desktopCameraPitch = getSafeDesktopCameraPitchRad();
		float desktopCameraYaw = getSafeDesktopCameraYawRad();
		float desktopCameraX = (float) client.getCameraFpX();
		float desktopCameraY = (float) client.getCameraFpZ();
		float desktopCameraZ = (float) client.getCameraFpY();

		float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(projectionMatrix, Mat4.projection(client.getViewportWidth(), client.getViewportHeight(), 50));
		Mat4.mul(projectionMatrix, Mat4.rotateX(desktopCameraPitch));
		Mat4.mul(projectionMatrix, Mat4.rotateY(desktopCameraYaw));
		// cameraFp uses API convention: X=east, Y=north, Z=height.
		// Scene/world rendering matrices use GPU convention: X=east, Y=height, Z=north.
		Mat4.mul(projectionMatrix, Mat4.translate(-desktopCameraX, -desktopCameraY, -desktopCameraZ));
		return projectionMatrix;
	}

	private void uploadMainCameraUniforms(float cameraYaw, float cameraPitch, float cameraX, float cameraY, float cameraZ)
	{
		uniformBuffer.clear();
		uniformBuffer
			.put(cameraYaw)
			.put(cameraPitch)
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();
		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);
	}

	private void renderDesktopSpectatorPass()
	{
		ensureDesktopSceneFbo();
		int sky = client.getSkyboxColor();
		DesktopViewport vp = getDesktopViewport();
		float desktopCameraYawRad = getSafeDesktopCameraYawRad();
		float desktopCameraPitchRad = getSafeDesktopCameraPitchRad();
		int desktopCameraYawJau = radiansToJau(desktopCameraYawRad);
		int desktopCameraPitchJau = radiansToJau(desktopCameraPitchRad);
		int desktopCameraX = (int) client.getCameraFpX();
		int desktopCameraY = (int) client.getCameraFpZ();
		int desktopCameraZ = (int) client.getCameraFpY();

		uploadMainCameraUniforms(
			desktopCameraYawRad,
			desktopCameraPitchRad,
			(float) client.getCameraFpX(),
			(float) client.getCameraFpZ(),
			(float) client.getCameraFpY());

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		glDpiAwareViewport(vp.x, vp.y, vp.width, vp.height);

		glUseProgram(glProgram);
		glUniformMatrix4fv(uniWorldProj, false, computeDesktopWorldProj());
		glUniformMatrix4fv(uniEntityProj, false, Mat4.identity());
		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		final int offset = SCENE_OFFSET >> 3;
		for (int i = 0; i < vrOpaqueZones.size(); i++)
		{
			int[] zz = vrOpaqueZones.get(i);
			updateEntityProjection(vrOpaqueProjs.get(i));
			Zone z = root.zones[zz[0]][zz[1]];
			if (z.initialized)
			{
				z.renderOpaque(zz[0] - offset, zz[1] - offset,
					root.minLevel, root.level, root.maxLevel, root.hideRoofIds);
			}
		}

		glUniform3i(uniBase, 0, 0, 0);
		glUniformMatrix4fv(uniEntityProj, false, Mat4.identity());
		for (int i = 0; i < vrPassDesktopOpaqueCount; i++)
		{
			vaoDesktopO.vaos.get(i).draw();
			vaoDesktopO.vaos.get(i).reset();
		}
		vrPassDesktopOpaqueCount = 0;

		if (vrPassDesktopPlayerCount > 0)
		{
			glDepthMask(false);
			for (int i = 0; i < vrPassDesktopPlayerCount; i++)
			{
				vaoDesktopPO.vaos.get(i).draw();
			}
			glDepthMask(true);
			glColorMask(false, false, false, false);
			for (int i = 0; i < vrPassDesktopPlayerCount; i++)
			{
				vaoDesktopPO.vaos.get(i).draw();
				vaoDesktopPO.vaos.get(i).reset();
			}
			glColorMask(true, true, true, true);
			vrPassDesktopPlayerCount = 0;
		}

		vaoA.unmap();
		for (int i = 0; i < vrAlphaZones.size(); i++)
		{
			int[] za = vrAlphaZones.get(i);
			int level = za[0], zx = za[1], zzc = za[2];
			Zone z = root.zones[zx][zzc];
			if (!z.initialized)
			{
				continue;
			}

			updateEntityProjection(vrAlphaProjs.get(i));
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			int dx = desktopCameraX - ((zx - offset) << 10);
			int dz = desktopCameraZ - ((zzc - offset) << 10);
			boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;
			z.renderAlpha(zx - offset, zzc - offset, desktopCameraYawJau, desktopCameraPitchJau,
				root.minLevel, root.level, root.maxLevel, level, root.hideRoofIds,
				!close || (vrScene.getOverrideAmount() > 0), true);
		}

		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		// Restore the main-scene camera uniforms before continuing with the stereo VR path.
		uploadMainCameraUniforms(cameraYaw, cameraPitch, root.cameraX, root.cameraY, root.cameraZ);
	}

	private float getSafeDesktopCameraYawRad()
	{
		double yaw = client.getCameraFpYaw();
		if (yaw < 0)
		{
			yaw = client.getCameraYaw() * Perspective.UNIT;
		}
		return (float) yaw;
	}

	private float getSafeDesktopCameraPitchRad()
	{
		double pitch = client.getCameraFpPitch();
		if (pitch < 0)
		{
			pitch = client.getCameraPitch() * Perspective.UNIT;
		}
		return (float) pitch;
	}

	private void blitSceneFbo()
	{
		int width = lastStretchedCanvasWidth;
		int height = lastStretchedCanvasHeight;

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		int defaultFbo = awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL_COLOR_BUFFER_BIT, GL_NEAREST);

		// Reset
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

		checkGLErrors();
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		updateEntityProjection(entityProjection);

		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		// Record zone for right-eye replay (T3.3)
		if (currentEye == 0)
		{
			vrOpaqueZones.add(new int[]{zx, zz});
			vrOpaqueProjs.add(entityProjection);
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);

		checkGLErrors();
	}

	private static final int ALPHA_ZSORT_CLOSE = 2048;

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// this is a noop after the first zone
		vaoA.unmap();

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		// Record zone for right-eye replay (T3.3)
		if (currentEye == 0)
		{
			vrAlphaZones.add(new int[]{level, zx, zz});
			vrAlphaProjs.add(entityProjection);
		}

		updateEntityProjection(entityProjection);
		glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		int dx = ctx.cameraX - ((zx - offset) << 10);
		int dz = ctx.cameraZ - ((zz - offset) << 10);
		boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0)
		{
			z.alphaSort(zx - offset, zz - offset, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, ctx.cameraX, ctx.cameraZ, ctx.zones);
		}

			z.renderAlpha(zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, !close || (scene.getOverrideAmount() > 0), false);

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		updateEntityProjection(projection);

		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			vaoO.addRange(projection, scene);
			vaoPO.addRange(projection, scene);
			vaoDesktopO.addRange(projection, scene);
			vaoDesktopPO.addRange(projection, scene);

			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				glUniform3i(uniBase, 0, 0, 0);

				int sz = vaoO.unmap();
				for (int i = 0; i < sz; ++i)
				{
					vaoO.vaos.get(i).draw();
					if (!xrFrameStarted) { vaoO.vaos.get(i).reset(); }
				}
				if (xrFrameStarted) { vrPassOpaqueCount = sz; }

				if (xrFrameStarted)
				{
					vrPassDesktopOpaqueCount = vaoDesktopO.unmap();
				}

				sz = vaoPO.unmap();
				if (sz > 0)
				{
					glDepthMask(false);
					for (int i = 0; i < sz; ++i) { vaoPO.vaos.get(i).draw(); }
					glDepthMask(true);

					glColorMask(false, false, false, false);
					for (int i = 0; i < sz; ++i)
					{
						vaoPO.vaos.get(i).draw();
						if (!xrFrameStarted) { vaoPO.vaos.get(i).reset(); }
					}
					glColorMask(true, true, true, true);
					if (xrFrameStarted) { vrPassPlayerCount = sz; }
				}

				if (xrFrameStarted)
				{
					vrPassDesktopPlayerCount = vaoDesktopPO.unmap();
				}
			}
		}
		else if (pass == DrawCallbacks.PASS_ALPHA)
		{
			// In VR left-eye pass, defer removeTemp() so the right eye can still draw temp alpha models.
			// For sub-scenes (non-TOPLEVEL) there is no right-eye replay, so clean up immediately.
			if (xrFrameStarted && scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				return;
			}
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
			}
		}

		checkGLErrors();
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null)
		{
			VAO o = vaoO.get(size);
			clientUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
			if (xrFrameStarted)
			{
				VAO desktopO = vaoDesktopO.get(size);
				clientUploader.uploadTempModel(m, orient, x, y, z, desktopO.vbo.vb);
			}
		}
		else
		{
			m.calculateBoundsCylinder();
			VAO o = vaoO.get(size), a = vaoA.get(size);
			VAO desktopO = xrFrameStarted ? vaoDesktopO.get(size) : null;
			VAO desktopA = xrFrameStarted ? vaoDesktopA.get(size) : null;
			int start = a.vbo.vb.position();
			try
			{
				facePrioritySorter.uploadSortedModel(worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int desktopStart = desktopA != null ? desktopA.vbo.vb.position() : -1;
			if (desktopA != null)
			{
				java.nio.IntBuffer desktopOpaqueBuffer = desktopO != null ? desktopO.vbo.vb : desktopSorterScratchOpaque;
				if (desktopOpaqueBuffer == desktopSorterScratchOpaque)
				{
					desktopSorterScratchOpaque.clear();
				}
				try
				{
					facePrioritySorter.uploadSortedModel(buildDesktopSorterProjection(), m, orient, x, y, z, desktopOpaqueBuffer, desktopA.vbo.vb);
				}
				catch (Exception ex)
				{
					log.debug("error drawing desktop spectator dynamic entity", ex);
				}
			}
			int end = a.vbo.vb.position();
			int desktopEnd = desktopA != null ? desktopA.vbo.vb.position() : -1;

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
				int zx = (x >> 10) + (offset >> 3);
				int zz = (z >> 10) + (offset >> 3);
				Zone zone = ctx.zones[zx][zz];

				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023, xrFrameStarted ? Zone.AlphaModel.VR_ONLY : 0);
				if (desktopA != null && desktopEnd > desktopStart)
				{
					zone.addTempAlphaModel(desktopA.vao, desktopStart, desktopEnd, plane, x & 1023, y, z & 1023, Zone.AlphaModel.DESKTOP_ONLY);
				}
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null)
		{
			// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
			// because they are not depth tested. transparent player faces don't need their own vao because normal
			// transparent faces are already not depth tested
			VAO o = renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH ? vaoPO.get(size) : vaoO.get(size);
			VAO desktopPlayerVao = xrFrameStarted && renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH
				? vaoDesktopPO.get(size)
				: null;
			VAO a = vaoA.get(size);
			VAO desktopA = xrFrameStarted ? vaoDesktopA.get(size) : null;
			VAO desktopO = xrFrameStarted && renderMode != Renderable.RENDERMODE_SORTED_NO_DEPTH
				? vaoDesktopO.get(size)
				: null;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			// In VR mode use the VR eye's projection for face sorting/culling instead of the
			// OSRS 2D camera projection, which only shows faces visible from one fixed direction.
			Projection sortProj = xrFrameStarted ? vrSorterProjection : worldProjection;
			try
			{
				facePrioritySorter.uploadSortedModel(sortProj, m, orient, x, y, z, o.vbo.vb, a.vbo.vb);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int desktopStart = desktopA != null ? desktopA.vbo.vb.position() : -1;

			if (desktopPlayerVao != null || desktopA != null)
			{
				java.nio.IntBuffer desktopOpaqueBuffer = desktopPlayerVao != null
					? desktopPlayerVao.vbo.vb
					: desktopO != null ? desktopO.vbo.vb : desktopSorterScratchOpaque;
				if (desktopOpaqueBuffer == desktopSorterScratchOpaque)
				{
					desktopSorterScratchOpaque.clear();
				}
				try
				{
					facePrioritySorter.uploadSortedModel(buildDesktopSorterProjection(), m, orient, x, y, z, desktopOpaqueBuffer, desktopA.vbo.vb);
				}
				catch (Exception ex)
				{
					log.debug("error drawing desktop spectator entity", ex);
				}
			}
			int end = a.vbo.vb.position();
			int desktopEnd = desktopA != null ? desktopA.vbo.vb.position() : -1;

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023, xrFrameStarted ? Zone.AlphaModel.VR_ONLY : 0);
				if (desktopA != null && desktopEnd > desktopStart)
				{
					zone.addTempAlphaModel(desktopA.vao, desktopStart, desktopEnd, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023, Zone.AlphaModel.DESKTOP_ONLY);
				}
			}
		}
		else
		{
			VAO o = vaoO.get(size);
			clientUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
			if (xrFrameStarted)
			{
				VAO desktopO = vaoDesktopO.get(size);
				clientUploader.uploadTempModel(m, orient, x, y, z, desktopO.vbo.vb);
			}
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate)
		{
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		processPendingStagedWalk();

		// Process any pending VR controller click (ray produced on render thread).
		float[] clickRay = vrPendingClickRay;
		if (clickRay != null)
		{
			vrPendingClickRay = null;
			int button = vrPendingClickButton;
			float[] clickHit = vrPendingClickHit; // depth-buffer position for WALK fallback
			vrPendingClickHit = null;

			WorldView wvClick = client.getTopLevelWorldView();
			if (wvClick != null)
			{
				float ox = clickRay[0], oy = clickRay[1], oz = clickRay[2];
				float dx = clickRay[3], dy = clickRay[4], dz = clickRay[5];

				VrGroundHit groundHit = vrIntersectGround(ox, oy, oz, dx, dy, dz, wvClick, clickHit);
				List<VrMenuHit> hits = vrRaycastScene(ox, oy, oz, dx, dy, dz, wvClick, groundHit);

				StringBuilder menuLog = new StringBuilder();
				menuLog.append("VR ").append(button == MouseEvent.BUTTON1 ? "LMB" : "RMB")
					.append(" menu (").append(hits.size()).append(" hits)");

				if (groundHit != null)
				{
					menuLog.append(" ground=(").append(groundHit.sceneX).append(",").append(groundHit.sceneY)
						.append(") t=").append(String.format("%.1f", groundHit.t))
						.append(" local=(").append(String.format("%.1f", groundHit.x)).append(',')
						.append(String.format("%.1f", groundHit.y)).append(',')
						.append(String.format("%.1f", groundHit.z)).append(')');
				}
				menuLog.append('\n');

				int entryCount = 0;
				for (VrMenuHit hit : hits)
				{
					for (VrMenuEntry entry : hit.entries)
					{
						menuLog.append(String.format("  %2d. %s %s  [t=%.1f, %s]\n",
							++entryCount, entry.option, entry.target, hit.t, hit.entityType));
					}
				}

				if (groundHit != null)
				{
					menuLog.append(String.format("  %2d. Walk here  [scene=%d,%d, t=%.1f]\n",
						++entryCount, groundHit.sceneX, groundHit.sceneY, groundHit.t));
				}
				menuLog.append(String.format("  %2d. Cancel\n", ++entryCount));
				log.info("{}", menuLog);
				log.info("VR click diag: rayOrigin=({},{},{}) rayDir=({},{},{}) depthHit={} groundHit={}",
					String.format("%.1f", ox), String.format("%.1f", oy), String.format("%.1f", oz),
					String.format("%.4f", dx), String.format("%.4f", dy), String.format("%.4f", dz),
					clickHit == null ? "null" : String.format("(%.1f,%.1f,%.1f)", clickHit[0], clickHit[1], clickHit[2]),
					groundHit == null ? "null" : String.format("(scene=%d,%d local=%.1f,%.1f,%.1f t=%.1f)",
						groundHit.sceneX, groundHit.sceneY, groundHit.x, groundHit.y, groundHit.z, groundHit.t));

				vrLastGroundHit = groundHit == null ? null : new float[]{groundHit.x, groundHit.y, groundHit.z};
				updateClientWalkDiagnostics(wvClick);

				VrMenuEntry defaultEntry = !hits.isEmpty() && !hits.get(0).entries.isEmpty()
					? hits.get(0).entries.get(0)
					: null;

				if (button == MouseEvent.BUTTON1)
				{
					if (defaultEntry != null)
					{
						vrLastWalkParams = null;
						vrLastDispatchSceneTile = defaultEntry.action == MenuAction.WALK ? null : new int[]{defaultEntry.p0, defaultEntry.p1};
						log.info("VR LMB dispatch: {} {} action={} p0={} p1={} id={} itemId={}",
							defaultEntry.option, defaultEntry.target, defaultEntry.action,
							defaultEntry.p0, defaultEntry.p1, defaultEntry.id, defaultEntry.itemId);
						client.menuAction(defaultEntry.p0, defaultEntry.p1, defaultEntry.action,
							defaultEntry.id, defaultEntry.itemId, defaultEntry.option, defaultEntry.target);
					}
					else if (groundHit != null)
					{
						cancelPendingWalkInspection();
						vrLastWalkParams = null;
						vrLastClientSelectedSceneTile = null;
						vrLastClientDestination = null;
						vrLastDispatchSceneTile = new int[]{groundHit.sceneX, groundHit.sceneY};
						aimDesktopCameraAtGroundHit(groundHit);
						beginStagedWalkDispatch(groundHit);
					}
				}
			}
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	private void rebuild(WorldView wv)
	{
		SceneContext ctx = context(wv);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
				{
					continue;
				}

				assert zone.initialized;
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				clientUploader.zoneSize(scene, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					o = new VBO(sz);
					o.init(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					a = new VBO(sz);
					a.init(GL_STATIC_DRAW);
					a.map();
				}

				zone.init(o, a);

				clientUploader.uploadZone(scene, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, canvasWidth * canvasHeight * 4L, GL_STREAM_DRAW);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		ByteBuffer interfaceBuf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		}
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		// --- OpenXR event polling (frame begin moved to end of draw) ---
		if (xrContext != null)
		{
			if (!xrContext.pollEvents())
			{
				// Runtime signalled EXITING or LOSS_PENDING — stop the plugin.
				// End any in-flight frame before stopping.
				if (xrFrameStarted)
				{
					xrContext.endXrFrame();
					xrFrameStarted = false;
				}
				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin after XR exit", ex);
					}
				});
				return;
			}
		}

		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureArrayId == -1 && textureProvider != null)
		{
			// lazy init textures as they may not be loaded at plugin start.
			// this will return -1 and retry if not all textures are loaded yet, too.
			textureArrayId = textureManager.initTextureArray(textureProvider);
			if (textureArrayId > -1)
			{
				// if texture upload is successful, compute and set texture animations
				float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUseProgram(0);
			}
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		prepareInterfaceTexture(canvasWidth, canvasHeight);

		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if (sceneFboValid)
		{
			blitSceneFbo();
		}

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		try
		{
			awtContext.swapBuffers();
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}

		drawManager.processDrawComplete(this::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		// Submit the XR frame with stereo projection layers when the scene was rendered,
		// or with empty layers on the login/loading screen.
		if (xrFrameStarted)
		{
			if (sceneFboValid && xrContext.getViews() != null)
			{
				xrContext.endXrFrameStereo(
					xrContext.getViews(),
					eyeSwapchains[0].getSwapchain(),
					eyeSwapchains[1].getSwapchain());
			}
			else
			{
				xrContext.endXrFrame();
			}
			xrFrameStarted = false;
		}

		// Begin the NEXT frame now so xrFrameStarted=true when preSceneDraw fires.
		// xrWaitFrame inside beginXrFrame will pace us to the display rate.
		if (xrContext != null && xrContext.isSessionRunning())
		{
			xrContext.beginXrFrame();
			xrFrameStarted = true;
		}

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		glUseProgram(glUiProgram);
		glUniform1i(uniTex, 0);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);
		glUniform1f(uniUiColorblindIntensity, config.colorBlindIntensity());

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, dim.width, dim.height);
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
			final AffineTransform t = graphicsConfiguration.getDefaultTransform();
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), canvasWidth), getScaledValue(t.getScaleY(), canvasHeight));
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/hybrid isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR || uiScalingMode == UIScalingMode.HYBRID ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
			.order(ByteOrder.nativeOrder());

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState())
		{
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
		if (state == GameState.STARTING)
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
			}
			textureArrayId = -1;
			lastAnisotropicFilteringLevel = -1;
		}
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSubScene(worldView, scene);
			return;
		}

		if (nextZones != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = nextZones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed
						if (!zone.initialized)
						{
							zone.unmap();
							zone.initialized = true;
							zone.free();
						}
					}
				}
				latch.countDown();
			});
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			nextZones = null;
			nextRoofChanges = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		regionManager.prepare(scene);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				ctx.zones[x][z].cull = true;
			}
		}

		Map<Integer, Integer> roofChanges = new HashMap<>();

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		final GameState gameState = client.getGameState();
		if (prev.isInstance() == scene.isInstance()
			&& gameState == GameState.LOGGED_IN)
		{
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();

			for (int x = 0; x < SCENE_ZONES; ++x)
			{
				next:
				for (int z = 0; z < SCENE_ZONES; ++z)
				{
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz))
					{
						if (scene.isInstance())
						{
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8)
							{
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8)
								{
									for (int level = 0; level < 4; ++level)
									{
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate)
										{
											log.error("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty)
						{
							continue;
						}

						assert old.sizeO > 0 || old.sizeA > 0;

						// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
						// Sometimes groups split or merge, so we can't copy the zone in that case
						for (int level = 0; level < 4; level++)
						{
							for (int tx = 0; tx < 8; tx++)
							{
								for (int tz = 0; tz < 8; tz++)
								{
									int prid = prids[level][(ox << 3) + tx][(oz << 3) + tz];
									int nrid = nrids[level][(x << 3) + tx][(z << 3) + tz];

									if (prid != nrid && (prid == 0 || nrid == 0))
									{
										log.trace("Roof mismatch: {} -> {}", prid, nrid);
										continue next;
									}

									Integer orid = roofChanges.putIfAbsent(prid, nrid);
									if (orid == null)
									{
										log.trace("Roof change: {} -> {}", prid, nrid);
									}
									else if (orid != nrid)
									{
										log.trace("Roof mismatch: {} -> {} vs {}", prid, nrid, orid);
										continue next;
									}
								}
							}
						}

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				if (newZones[x][z] == null)
				{
					newZones[x][z] = new Zone();
				}
			}
		}

		// size the zones which require upload
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x)
		{
			for (int z = 0; z < NUM_ZONES; ++z)
			{
				Zone zone = newZones[x][z];
				if (!zone.initialized)
				{
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					mapUploader.zoneSize(scene, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				}
				else
				{
					reused++;
				}
			}
		}
		log.debug("Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, (len * Zone.VERT_SIZE * 3) / 1024,
			lena, (lena * Zone.VERT_SIZE * 3) / 1024);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
			{
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
				{
					Zone zone = newZones[x][z];

					if (zone.initialized)
					{
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
		{
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
			{
				Zone zone = newZones[x][z];

				if (!zone.initialized)
				{
					mapUploader.uploadZone(scene, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz)
	{
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x)
		{
			if (x < 0 || x >= NUM_ZONES)
			{
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z)
			{
				if (z < 0 || z >= NUM_ZONES)
				{
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized)
				{
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		SceneContext ctx0 = subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final SceneContext ctx = new SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, zone, x, z);
			}
		}

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				mapUploader.uploadZone(scene, zone, x, z);
			}
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		SceneContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					zone.free();
				}
				else
				{
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) // NOPMD: ForLoopCanBeForeach
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		checkGLErrors();
	}

	private void swapSub(Scene scene)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}

	private int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	// -------------------------------------------------------------------------
	// VR ray-scene intersection (client thread)
	// -------------------------------------------------------------------------

	/** Holds one intersected entity and its available menu options. */
	private static final class VrMenuHit
	{
		float t;
		String entityType;
		String entityName;
		int sceneX;
		int sceneY;
		List<VrMenuEntry> entries = new ArrayList<>();
	}

	private static final class VrMenuEntry
	{
		String option;
		String target;
		MenuAction action;
		int p0;
		int p1;
		int id;
		int itemId = -1;
	}

	private static final class VrGroundHit
	{
		float t;
		float x;
		float y;
		float z;
		int sceneX;
		int sceneY;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.WALK
			|| action == MenuAction.NPC_FIRST_OPTION
			|| action == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| action == MenuAction.GROUND_ITEM_FIRST_OPTION
			|| action == MenuAction.PLAYER_FIRST_OPTION
			|| action == MenuAction.EXAMINE_NPC
			|| action == MenuAction.EXAMINE_OBJECT
			|| action == MenuAction.EXAMINE_ITEM_GROUND)
		{
			log.info("MenuOptionClicked diag: action={} option='{}' target='{}' p0={} p1={} id={} itemId={}",
				action, event.getMenuOption(), event.getMenuTarget(),
				event.getParam0(), event.getParam1(), event.getId(), event.getItemId());
			if (action == MenuAction.WALK)
			{
				WorldView wv = client.getTopLevelWorldView();
				updateClientWalkDiagnostics(wv);
				logClientWalkState("from MenuOptionClicked", wv);
			}
		}
	}

	private static final class VrRenderablePlacement
	{
		final Renderable renderable;
		final int orientation;
		final int x;
		final int y;
		final int z;

		private VrRenderablePlacement(Renderable renderable, int orientation, int x, int y, int z)
		{
			this.renderable = renderable;
			this.orientation = orientation;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	/**
	 * Cast a ray through the scene, collecting all intersected entities sorted by distance.
	 * Tests actors and tile entities against actual model geometry, plus ground tiles for Walk here.
	 * Must be called on the client thread.
	 */
	private List<VrMenuHit> vrRaycastScene(float ox, float oy, float oz,
		float dx, float dy, float dz, WorldView wv, VrGroundHit groundHit)
	{
		List<VrMenuHit> hits = new ArrayList<>();
		int plane = wv.getPlane();

		for (NPC npc : wv.npcs())
		{
			if (npc == null) continue;
			VrMenuHit hit = buildNpcHit(ox, oy, oz, dx, dy, dz, plane, npc);
			if (hit != null)
			{
				hits.add(hit);
			}
		}

		for (Player player : wv.players())
		{
			if (player == null || player == client.getLocalPlayer())
			{
				continue;
			}

			VrMenuHit hit = buildPlayerHit(ox, oy, oz, dx, dy, dz, plane, player);
			if (hit != null)
			{
				hits.add(hit);
			}
		}

		Tile[][][] sceneTiles = wv.getScene().getTiles();
		if (plane >= 0 && plane < sceneTiles.length && sceneTiles[plane] != null)
		{
			for (int scX = 0; scX < wv.getSizeX(); scX++)
			{
				for (int scY = 0; scY < wv.getSizeY(); scY++)
				{
					Tile tile = sceneTiles[plane][scX][scY];
					if (tile == null) continue;

					for (GameObject obj : tile.getGameObjects())
					{
						if (obj == null || obj.getId() == -1) continue;
						if (!obj.getSceneMinLocation().equals(tile.getSceneLocation()))
						{
							continue;
						}
						VrMenuHit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"GameObject",
							clampScene(obj.getX() >> 7, wv.getSizeX()),
							clampScene(obj.getY() >> 7, wv.getSizeY()),
							obj.getId(),
							new VrRenderablePlacement(obj.getRenderable(), obj.getModelOrientation() & 2047, obj.getX(), obj.getZ(), obj.getY()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					WallObject wall = tile.getWallObject();
					if (wall != null && wall.getId() != -1)
					{
						VrMenuHit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"WallObject",
							scX,
							scY,
							wall.getId(),
							new VrRenderablePlacement(wall.getRenderable1(), 0, wall.getX(), wall.getZ(), wall.getY()),
							new VrRenderablePlacement(wall.getRenderable2(), 0, wall.getX(), wall.getZ(), wall.getY()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null && deco.getId() != -1)
					{
						VrMenuHit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"DecorativeObject",
							scX,
							scY,
							deco.getId(),
							new VrRenderablePlacement(deco.getRenderable(), 0,
								deco.getX() + deco.getXOffset(), deco.getZ(), deco.getY() + deco.getYOffset()),
							new VrRenderablePlacement(deco.getRenderable2(), 0,
								deco.getX() + deco.getXOffset2(), deco.getZ(), deco.getY() + deco.getYOffset2()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					GroundObject ground = tile.getGroundObject();
					if (ground != null && ground.getId() != -1)
					{
						VrMenuHit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"GroundObject",
							scX,
							scY,
							ground.getId(),
							new VrRenderablePlacement(ground.getRenderable(), 0, ground.getX(), ground.getZ(), ground.getY()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					List<TileItem> items = tile.getGroundItems();
					if (items != null && !items.isEmpty())
					{
						float itemT = groundHit != null && groundHit.sceneX == scX && groundHit.sceneY == scY
							? groundHit.t
							: rayBoxTest(ox, oy, oz, dx, dy, dz,
								scX * 128f, tileHeightAtScene(wv, scX, scY, plane) - 60f, scY * 128f,
								scX * 128f + 128f, tileHeightAtScene(wv, scX, scY, plane) + 16f, scY * 128f + 128f);
						if (itemT <= 0f)
						{
							continue;
						}

						for (TileItem item : items)
						{
							if (item == null) continue;
							VrMenuHit hit = buildGroundItemHit(itemT, scX, scY, item);
							if (hit != null)
							{
								hits.add(hit);
							}
						}
					}
				}
			}
		}

		hits.sort((a, b) -> Float.compare(a.t, b.t));
		return hits;
	}

	private VrMenuHit buildNpcHit(float ox, float oy, float oz, float dx, float dy, float dz, int plane, NPC npc)
	{
		Model model = npc.getModel();
		LocalPoint lp = npc.getLocalLocation();
		if (model == null || lp == null)
		{
			return null;
		}

		float t = rayTestModel(ox, oy, oz, dx, dy, dz, model, npc.getCurrentOrientation() & 2047,
			lp.getX(), Perspective.getTileHeight(client, lp, plane), lp.getY());
		if (t <= 0f)
		{
			return null;
		}

		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}

		VrMenuHit hit = new VrMenuHit();
		hit.t = t;
		hit.entityType = "npc";
		hit.entityName = safeName(npc.getName(), "NPC");
		hit.sceneX = lp.getX() >> 7;
		hit.sceneY = lp.getY() >> 7;

		MenuAction[] actions = {
			MenuAction.NPC_FIRST_OPTION,
			MenuAction.NPC_SECOND_OPTION,
			MenuAction.NPC_THIRD_OPTION,
			MenuAction.NPC_FOURTH_OPTION,
			MenuAction.NPC_FIFTH_OPTION
		};
		addEntries(hit, comp != null ? comp.getActions() : null, actions, npc.getIndex(), 0, 0, hit.entityName);
		addEntry(hit, "Examine", hit.entityName, MenuAction.EXAMINE_NPC, 0, 0, npc.getIndex(), -1);
		return hit;
	}

	private VrMenuHit buildPlayerHit(float ox, float oy, float oz, float dx, float dy, float dz, int plane, Player player)
	{
		Model model = player.getModel();
		LocalPoint lp = player.getLocalLocation();
		if (model == null || lp == null)
		{
			return null;
		}

		float t = rayTestModel(ox, oy, oz, dx, dy, dz, model, player.getCurrentOrientation() & 2047,
			lp.getX(), Perspective.getTileHeight(client, lp, plane), lp.getY());
		if (t <= 0f)
		{
			return null;
		}

		VrMenuHit hit = new VrMenuHit();
		hit.t = t;
		hit.entityType = "player";
		hit.entityName = safeName(player.getName(), "Player");
		hit.sceneX = lp.getX() >> 7;
		hit.sceneY = lp.getY() >> 7;

		MenuAction[] actions = {
			MenuAction.PLAYER_FIRST_OPTION,
			MenuAction.PLAYER_SECOND_OPTION,
			MenuAction.PLAYER_THIRD_OPTION,
			MenuAction.PLAYER_FOURTH_OPTION,
			MenuAction.PLAYER_FIFTH_OPTION,
			MenuAction.PLAYER_SIXTH_OPTION,
			MenuAction.PLAYER_SEVENTH_OPTION,
			MenuAction.PLAYER_EIGHTH_OPTION
		};
		addEntries(hit, client.getPlayerOptions(), actions, player.getId(), 0, 0, hit.entityName);
		return hit.entries.isEmpty() ? null : hit;
	}

	private VrMenuHit buildObjectHit(float ox, float oy, float oz, float dx, float dy, float dz,
		String entityType, int sceneX, int sceneY, int objId, VrRenderablePlacement... placements)
	{
		ObjectComposition def = client.getObjectDefinition(objId);
		if (def == null)
		{
			return null;
		}
		if (def.getImpostorIds() != null)
		{
			ObjectComposition impostor = def.getImpostor();
			if (impostor != null)
			{
				def = impostor;
			}
		}

		float bestT = Float.MAX_VALUE;
		for (VrRenderablePlacement placement : placements)
		{
			if (placement == null || placement.renderable == null)
			{
				continue;
			}

			Model model = placement.renderable.getModel();
			if (model == null)
			{
				continue;
			}

			float t = rayTestModel(ox, oy, oz, dx, dy, dz, model, placement.orientation, placement.x, placement.y, placement.z);
			if (t > 0f && t < bestT)
			{
				bestT = t;
			}
		}

		if (bestT == Float.MAX_VALUE)
		{
			return null;
		}

		VrMenuHit hit = new VrMenuHit();
		hit.t = bestT;
		hit.entityType = entityType;
		hit.entityName = safeName(def.getName(), "Object");
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;

		MenuAction[] actions = {
			MenuAction.GAME_OBJECT_FIRST_OPTION,
			MenuAction.GAME_OBJECT_SECOND_OPTION,
			MenuAction.GAME_OBJECT_THIRD_OPTION,
			MenuAction.GAME_OBJECT_FOURTH_OPTION,
			MenuAction.GAME_OBJECT_FIFTH_OPTION
		};
		addEntries(hit, def.getActions(), actions, objId, sceneX, sceneY, hit.entityName);
		addEntry(hit, "Examine", hit.entityName, MenuAction.EXAMINE_OBJECT, sceneX, sceneY, objId, -1);
		return hit;
	}

	private VrMenuHit buildGroundItemHit(float t, int sceneX, int sceneY, TileItem item)
	{
		ItemComposition def = client.getItemDefinition(item.getId());
		if (def == null)
		{
			return null;
		}

		VrMenuHit hit = new VrMenuHit();
		hit.t = t;
		hit.entityType = "ground-item";
		hit.entityName = safeName(def.getName(), "Item");
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;

		addEntry(hit, "Take", hit.entityName, MenuAction.GROUND_ITEM_FIRST_OPTION, sceneX, sceneY, item.getId(), item.getId());
		addEntry(hit, "Examine", hit.entityName, MenuAction.EXAMINE_ITEM_GROUND, sceneX, sceneY, item.getId(), item.getId());
		return hit;
	}

	private static void addEntries(VrMenuHit hit, String[] options, MenuAction[] actions, int id, int p0, int p1, String target)
	{
		if (options == null)
		{
			return;
		}

		for (int i = 0; i < options.length && i < actions.length; i++)
		{
			String option = options[i];
			if (option == null || option.isEmpty() || "null".equals(option))
			{
				continue;
			}
			addEntry(hit, option, target, actions[i], p0, p1, id, -1);
		}
	}

	private static void addEntry(VrMenuHit hit, String option, String target, MenuAction action, int p0, int p1, int id, int itemId)
	{
		VrMenuEntry entry = new VrMenuEntry();
		entry.option = option;
		entry.target = target;
		entry.action = action;
		entry.p0 = p0;
		entry.p1 = p1;
		entry.id = id;
		entry.itemId = itemId;
		hit.entries.add(entry);
	}

	private VrGroundHit vrIntersectGround(float ox, float oy, float oz, float dx, float dy, float dz, WorldView wv, float[] fallbackHit)
	{
		VrGroundHit best = null;
		Tile[][][] tiles = wv.getScene().getTiles();
		int plane = wv.getPlane();
		if (plane >= 0 && plane < tiles.length && tiles[plane] != null)
		{
			for (int sceneX = 0; sceneX < wv.getSizeX(); sceneX++)
			{
				for (int sceneY = 0; sceneY < wv.getSizeY(); sceneY++)
				{
					Tile tile = tiles[plane][sceneX][sceneY];
					if (tile == null)
					{
						continue;
					}

					VrGroundHit hit = intersectGroundTile(ox, oy, oz, dx, dy, dz, wv, plane, sceneX, sceneY, tile);
					if (hit != null && (best == null || hit.t < best.t))
					{
						best = hit;
					}
				}
			}
		}

		if (best == null && fallbackHit != null)
		{
			VrGroundHit fallback = new VrGroundHit();
			fallback.x = fallbackHit[0];
			fallback.y = fallbackHit[1];
			fallback.z = fallbackHit[2];
			fallback.sceneX = clampScene((int) fallbackHit[0] >> 7, wv.getSizeX());
			fallback.sceneY = clampScene((int) fallbackHit[2] >> 7, wv.getSizeY());
			fallback.t = distanceAlongRay(ox, oy, oz, dx, dy, dz, fallback.x, fallback.y, fallback.z);
			best = fallback;
		}
		return best;
	}

	private VrGroundHit intersectGroundTile(float ox, float oy, float oz, float dx, float dy, float dz,
		WorldView wv, int plane, int sceneX, int sceneY, Tile tile)
	{
		SceneTileModel model = tile.getSceneTileModel();
		if (model != null)
		{
			int[] faceX = model.getFaceX();
			int[] faceY = model.getFaceY();
			int[] faceZ = model.getFaceZ();
			int[] vertexX = model.getVertexX();
			int[] vertexY = model.getVertexY();
			int[] vertexZ = model.getVertexZ();
			if (faceX != null && faceY != null && faceZ != null && vertexX != null && vertexY != null && vertexZ != null)
			{
				VrGroundHit best = null;
				for (int i = 0; i < faceX.length; i++)
				{
					int a = faceX[i];
					int b = faceY[i];
					int c = faceZ[i];
					float t = rayTriangleMT(ox, oy, oz, dx, dy, dz,
						vertexX[a], vertexY[a], vertexZ[a],
						vertexX[b], vertexY[b], vertexZ[b],
						vertexX[c], vertexY[c], vertexZ[c]);
					if (t > 0f && (best == null || t < best.t))
					{
						best = buildGroundHit(ox, oy, oz, dx, dy, dz, t, sceneX, sceneY);
					}
				}
				if (best != null)
				{
					return best;
				}
			}
		}

		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint == null)
		{
			return null;
		}

		float baseX = sceneX * 128f;
		float baseZ = sceneY * 128f;
		float swY = tileHeightAtScene(wv, sceneX, sceneY, plane);
		float seY = tileHeightAtScene(wv, sceneX + 1, sceneY, plane);
		float neY = tileHeightAtScene(wv, sceneX + 1, sceneY + 1, plane);
		float nwY = tileHeightAtScene(wv, sceneX, sceneY + 1, plane);

		float t1 = rayTriangleMT(ox, oy, oz, dx, dy, dz,
			baseX, swY, baseZ,
			baseX + 128f, seY, baseZ,
			baseX + 128f, neY, baseZ + 128f);
		float t2 = rayTriangleMT(ox, oy, oz, dx, dy, dz,
			baseX, swY, baseZ,
			baseX + 128f, neY, baseZ + 128f,
			baseX, nwY, baseZ + 128f);

		float t = -1f;
		if (t1 > 0f)
		{
			t = t1;
		}
		if (t2 > 0f && (t < 0f || t2 < t))
		{
			t = t2;
		}
		return t > 0f ? buildGroundHit(ox, oy, oz, dx, dy, dz, t, sceneX, sceneY) : null;
	}

	private static VrGroundHit buildGroundHit(float ox, float oy, float oz, float dx, float dy, float dz, float t, int sceneX, int sceneY)
	{
		VrGroundHit hit = new VrGroundHit();
		hit.t = t;
		hit.x = ox + dx * t;
		hit.y = oy + dy * t;
		hit.z = oz + dz * t;
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;
		return hit;
	}

	private static float tileHeightAtScene(WorldView wv, int sceneX, int sceneY, int plane)
	{
		int clampedX = clampScene(sceneX, wv.getSizeX() + 1);
		int clampedY = clampScene(sceneY, wv.getSizeY() + 1);
		return wv.getTileHeights()[plane][clampedX][clampedY];
	}

	private static String safeName(String value, String fallback)
	{
		return value == null || value.isEmpty() || "null".equals(value) ? fallback : value;
	}

	private static int clampScene(int coord, int size)
	{
		return Math.max(0, Math.min(coord, size - 1));
	}

	private static int getWalkSceneParamOffset(WorldView wv)
	{
		return wv != null && wv.isTopLevel() ? SCENE_OFFSET : 0;
	}

	private float getVrAnchorWorldX()
	{
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		if (lp != null)
		{
			return lp.getX();
		}
		return root.cameraX;
	}

	private float getVrAnchorWorldY()
	{
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		WorldView wv = client.getTopLevelWorldView();
		if (lp != null && wv != null)
		{
			return wv.getTileHeight(lp.getX(), lp.getY(), wv.getPlane());
		}
		return root.cameraY;
	}

	private float getVrAnchorWorldZ()
	{
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		if (lp != null)
		{
			return lp.getY();
		}
		return root.cameraZ;
	}

	private void cancelPendingWalkInspection()
	{
		vrPendingWalkInspect = null;
		vrPendingWalkCanvasPoint = null;
		vrPendingWalkMousePrimed = false;
		vrPendingWalkInspectRetries = 0;
	}

	private void beginStagedWalkDispatch(VrGroundHit groundHit)
	{
		vrPendingWalkInspect = new float[]{
			groundHit.sceneX, groundHit.sceneY,
			groundHit.x, groundHit.y, groundHit.z
		};
		vrPendingWalkCanvasPoint = null;
		vrPendingWalkMousePrimed = false;
		vrPendingWalkInspectRetries = 1;
		log.info("VR staged walk begin: scene=({}, {}) local=({},{},{}) delayTicks={}",
			groundHit.sceneX, groundHit.sceneY,
			String.format("%.1f", groundHit.x), String.format("%.1f", groundHit.y), String.format("%.1f", groundHit.z),
			vrPendingWalkInspectRetries);
	}

	private void aimDesktopCameraAtGroundHit(VrGroundHit groundHit)
	{
		applyDesktopCameraMaxZoomOut();

		double cameraApiX = client.getCameraFpX();
		double cameraApiY = client.getCameraFpY();
		double cameraApiZ = client.getCameraFpZ();
		Player player = client.getLocalPlayer();
		LocalPoint playerLocal = player != null ? player.getLocalLocation() : null;
		double playerX = playerLocal != null ? playerLocal.getX() : getVrAnchorWorldX();
		double playerZ = playerLocal != null ? playerLocal.getY() : getVrAnchorWorldZ();
		double targetX = groundHit.x;
		double targetZ = groundHit.z;
		double dx = targetX - playerX;
		double dz = targetZ - playerZ;
		double horizontal = Math.hypot(dx, dz);
		if (horizontal < 1e-3)
		{
			horizontal = 1e-3;
		}

		int desiredYaw = radiansToJau(Math.atan2(-dx, dz));
		int desiredPitch = VR_DESKTOP_AIM_PITCH;

		client.setCameraPitchRelaxerEnabled(true);
		client.setCameraSpeed(999f);
		client.setCameraYawTarget(desiredYaw);
		client.setCameraPitchTarget(desiredPitch);

		vrDesktopCameraAimTarget = new float[]{groundHit.x, groundHit.y, groundHit.z};
		vrDesktopCameraAimAngles = new int[]{desiredYaw, desiredPitch};
		log.info("VR desktop camera aim: targetScene=({}, {}) targetLocalGpu=({},{},{}) desiredYaw={} desiredPitch={} currentYaw={} currentPitch={}",
			groundHit.sceneX, groundHit.sceneY,
			String.format("%.1f", groundHit.x), String.format("%.1f", groundHit.y), String.format("%.1f", groundHit.z),
			desiredYaw, desiredPitch,
			client.getCameraYaw(), client.getCameraPitch());
	}

	private void applyDesktopCameraMaxZoomOut()
	{
		int fixedMin = client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN);
		int fixedMax = client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MAX);
		int resizableMin = client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN);
		int resizableMax = client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MAX);
		int fixedBefore = client.getVarcIntValue(VarClientInt.CAMERA_ZOOM_FIXED_VIEWPORT);
		int resizableBefore = client.getVarcIntValue(VarClientInt.CAMERA_ZOOM_RESIZABLE_VIEWPORT);

		client.setVarcIntValue(VarClientInt.CAMERA_ZOOM_FIXED_VIEWPORT, fixedMin);
		client.setVarcIntValue(VarClientInt.CAMERA_ZOOM_RESIZABLE_VIEWPORT, resizableMin);
		client.runScript(ScriptID.CAMERA_DO_ZOOM, fixedMin, resizableMin);

	}

	private static int radiansToJau(double radians)
	{
		int angle = (int) Math.round(radians / Perspective.UNIT);
		angle %= 2048;
		if (angle < 0)
		{
			angle += 2048;
		}
		return angle;
	}

	private net.runelite.api.Point projectStagedWalkCanvasPoint(float[] pending, WorldView wv)
	{
		int centerX = client.getViewportXOffset() + client.getViewportWidth() / 2;
		LocalPoint tileCenter = LocalPoint.fromScene((int) pending[0], (int) pending[1], wv);
		if (tileCenter != null)
		{
			Polygon poly = Perspective.getCanvasTilePoly(client, tileCenter);
			if (poly != null && poly.npoints > 0)
			{
				int sx = 0;
				int sy = 0;
				for (int i = 0; i < poly.npoints; i++)
				{
					sx += poly.xpoints[i];
					sy += poly.ypoints[i];
				}
				return new net.runelite.api.Point(centerX, sy / poly.npoints);
			}
		}

		net.runelite.api.Point projected = Perspective.localToCanvas(
			client,
			wv.getId(),
			Math.round(pending[2]),
			Math.round(pending[4]),
			Math.round(pending[3]));
		if (projected == null)
		{
			return null;
		}
		return new net.runelite.api.Point(centerX, projected.getY());
	}

	private void primeCanvasMouseForWalk(int canvasX, int canvasY)
	{
		Canvas targetCanvas = canvas != null ? canvas : client.getCanvas();
		if (targetCanvas == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		targetCanvas.dispatchEvent(new MouseEvent(
			targetCanvas,
			MouseEvent.MOUSE_ENTERED,
			now,
			0,
			canvasX,
			canvasY,
			0,
			false,
			MouseEvent.NOBUTTON));
		targetCanvas.dispatchEvent(new MouseEvent(
			targetCanvas,
			MouseEvent.MOUSE_MOVED,
			now,
			0,
			canvasX,
			canvasY,
			0,
			false,
			MouseEvent.NOBUTTON));
	}

	private float[] reconstructDesktopScreenRayGroundHit(int canvasX, int canvasY, WorldView wv)
	{
		final float viewportXMiddle = client.getViewportWidth() / 2f;
		final float viewportYMiddle = client.getViewportHeight() / 2f;
		final float viewportXOffset = client.getViewportXOffset();
		final float viewportYOffset = client.getViewportYOffset();
		final float zoom3d = client.getScale();

		float sx = canvasX - viewportXOffset - viewportXMiddle;
		float sy = canvasY - viewportYOffset - viewportYMiddle;

		double cameraPitch = client.getCameraFpPitch();
		double cameraYaw = client.getCameraFpYaw();
		float pitchSin = (float) Math.sin(cameraPitch);
		float pitchCos = (float) Math.cos(cameraPitch);
		float yawSin = (float) Math.sin(cameraYaw);
		float yawCos = (float) Math.cos(cameraYaw);

		float x1 = sx;
		float y2 = sy;
		float z1 = zoom3d;

		// Invert the projection and camera rotation used by Perspective.localToCanvasGpu.
		float y1 = y2 * pitchSin + z1 * pitchCos;
		float fz = y2 * pitchCos - z1 * pitchSin;
		float fx = x1 * yawCos - y1 * yawSin;
		float fy = y1 * yawCos + x1 * yawSin;

		float originX = (float) client.getCameraFpX();
		float originY = (float) client.getCameraFpZ();
		float originZ = (float) client.getCameraFpY();
		float dirX = fx;
		float dirY = fz;
		float dirZ = fy;
		float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
		if (len < 1e-6f)
		{
			return null;
		}
		dirX /= len;
		dirY /= len;
		dirZ /= len;

		VrGroundHit desktopGroundHit = vrIntersectGround(originX, originY, originZ, dirX, dirY, dirZ, wv, null);
		if (desktopGroundHit == null)
		{
			return null;
		}

		return new float[]{desktopGroundHit.x, desktopGroundHit.y, desktopGroundHit.z};
	}

	private void processPendingStagedWalk()
	{
		float[] pending = vrPendingWalkInspect;
		if (pending == null)
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			cancelPendingWalkInspection();
			return;
		}

		if (vrPendingWalkInspectRetries > 0)
		{
			int ticksRemainingAfterThis = vrPendingWalkInspectRetries - 1;
			vrPendingWalkInspectRetries--;
			if (ticksRemainingAfterThis > 0)
			{
				return;
			}
		}

		net.runelite.api.Point canvasPoint = projectStagedWalkCanvasPoint(pending, wv);
		if (canvasPoint == null)
		{
			log.info("VR staged walk: projection failed scene=({}, {}) local=({},{},{})",
				(int) pending[0], (int) pending[1],
				String.format("%.1f", pending[2]), String.format("%.1f", pending[3]), String.format("%.1f", pending[4]));
			cancelPendingWalkInspection();
			return;
		}

		net.runelite.api.Point rawProjected = Perspective.localToCanvas(
			client,
			wv.getId(),
			Math.round(pending[2]),
			Math.round(pending[4]),
			Math.round(pending[3]));
		float[] desktopRayHit = reconstructDesktopScreenRayGroundHit(canvasPoint.getX(), canvasPoint.getY(), wv);
		vrLastDesktopRayHit = desktopRayHit;
		vrPendingWalkCanvasPoint = new int[]{canvasPoint.getX(), canvasPoint.getY()};
		vrLastWalkParams = new int[]{canvasPoint.getX(), canvasPoint.getY()};
		net.runelite.api.Point mouseBefore = client.getMouseCanvasPosition();
		if (!vrPendingWalkMousePrimed)
		{
			primeCanvasMouseForWalk(canvasPoint.getX(), canvasPoint.getY());
			vrPendingWalkMousePrimed = true;
			vrPendingWalkInspectRetries = 1;
			log.info("VR staged walk mouse prime: scene=({}, {}) dispatchCanvas=({}, {}) mouseBefore={} mouseAfter={} delayTicks={}",
				(int) pending[0], (int) pending[1],
				canvasPoint.getX(), canvasPoint.getY(),
				mouseBefore,
				client.getMouseCanvasPosition(),
				vrPendingWalkInspectRetries);
			return;
		}
		log.info("VR staged walk dispatch: scene=({}, {}) rawCanvas={} dispatchCanvas=({}, {}) desktopRayHit={} local=({},{},{})",
			(int) pending[0], (int) pending[1],
			rawProjected,
			canvasPoint.getX(), canvasPoint.getY(),
			desktopRayHit == null ? "null" : String.format("(%.1f,%.1f,%.1f)", desktopRayHit[0], desktopRayHit[1], desktopRayHit[2]),
			String.format("%.1f", pending[2]), String.format("%.1f", pending[3]), String.format("%.1f", pending[4]));
		client.menuAction(canvasPoint.getX(), canvasPoint.getY(), MenuAction.WALK, 0, 0, "Walk here", "");
		updateClientWalkDiagnostics(wv);
		logClientWalkState("after staged walk dispatch", wv);
		cancelPendingWalkInspection();
	}

	private void updateClientWalkDiagnostics(WorldView wv)
	{
		if (wv != null)
		{
			Tile selected = wv.getSelectedSceneTile();
			if (selected != null && selected.getSceneLocation() != null)
			{
				vrLastClientSelectedSceneTile = new int[]{
					selected.getSceneLocation().getX(),
					selected.getSceneLocation().getY()
				};
			}
			else
			{
				vrLastClientSelectedSceneTile = null;
			}
		}
		else
		{
			vrLastClientSelectedSceneTile = null;
		}

		LocalPoint destination = client.getLocalDestinationLocation();
		if (destination != null)
		{
			int plane = wv != null ? wv.getPlane() : client.getPlane();
			vrLastClientDestination = new float[]{
				destination.getX(),
				Perspective.getTileHeight(client, destination, plane),
				destination.getY()
			};
		}
		else
		{
			vrLastClientDestination = null;
		}
	}

	private void logClientWalkState(String context, WorldView wv)
	{
		Tile selected = wv != null ? wv.getSelectedSceneTile() : null;
		LocalPoint destination = client.getLocalDestinationLocation();
		log.info("VR walk state {}: selectedSceneTile={} destinationLocal={}",
			context,
			selected != null && selected.getSceneLocation() != null
				? "(" + selected.getSceneLocation().getX() + "," + selected.getSceneLocation().getY() + ")"
				: "null",
			destination != null
				? "(" + destination.getX() + "," + destination.getY() + ")"
				: "null");
	}

	private static float distanceAlongRay(float ox, float oy, float oz, float dx, float dy, float dz, float x, float y, float z)
	{
		float vx = x - ox;
		float vy = y - oy;
		float vz = z - oz;
		return vx * dx + vy * dy + vz * dz;
	}

	private static float rayTestModel(float ox, float oy, float oz,
		float dx, float dy, float dz,
		Model m, int orient, int entityX, int entityY, int entityZ)
	{
		AABB aabb = m.getAABB(orient);
		float cx = entityX + aabb.getCenterX();
		float cy = entityY + aabb.getCenterY();
		float cz = entityZ + aabb.getCenterZ();
		float ex = aabb.getExtremeX() + 16f;
		float ey = aabb.getExtremeY() + 16f;
		float ez = aabb.getExtremeZ() + 16f;
		if (rayBoxTest(ox, oy, oz, dx, dy, dz, cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez) < 0f)
		{
			return -1f;
		}

		float[] vx = m.getVerticesX();
		float[] vy = m.getVerticesY();
		float[] vz = m.getVerticesZ();
		int[] fi1 = m.getFaceIndices1();
		int[] fi2 = m.getFaceIndices2();
		int[] fi3 = m.getFaceIndices3();
		if (vx == null || vy == null || vz == null || fi1 == null || fi2 == null || fi3 == null)
		{
			return -1f;
		}

		int sin = Perspective.SINE[orient];
		int cos = Perspective.COSINE[orient];
		float minT = Float.MAX_VALUE;
		for (int f = 0; f < m.getFaceCount(); f++)
		{
			int i0 = fi1[f];
			int i1 = fi2[f];
			int i2 = fi3[f];

			float ax = vx[i0];
			float ay = vy[i0];
			float az = vz[i0];
			float wax = entityX + (az * sin + ax * cos) / 65536f;
			float way = entityY + ay;
			float waz = entityZ + (az * cos - ax * sin) / 65536f;

			float bx = vx[i1];
			float by = vy[i1];
			float bz = vz[i1];
			float wbx = entityX + (bz * sin + bx * cos) / 65536f;
			float wby = entityY + by;
			float wbz = entityZ + (bz * cos - bx * sin) / 65536f;

			float cx2 = vx[i2];
			float cy2 = vy[i2];
			float cz2 = vz[i2];
			float wcx = entityX + (cz2 * sin + cx2 * cos) / 65536f;
			float wcy = entityY + cy2;
			float wcz = entityZ + (cz2 * cos - cx2 * sin) / 65536f;

			float t = rayTriangleMT(ox, oy, oz, dx, dy, dz,
				wax, way, waz,
				wbx, wby, wbz,
				wcx, wcy, wcz);
			if (t > 0f && t < minT)
			{
				minT = t;
			}
		}
		return minT < Float.MAX_VALUE ? minT : -1f;
	}

	/**
	 * Möller-Trumbore ray-triangle intersection.
	 * Tests both faces (no backface culling).
	 *
	 * @return ray parameter t &gt; 0 if hit, else -1
	 */
	private static float rayTriangleMT(
		float ox, float oy, float oz, float dx, float dy, float dz,
		float v0x, float v0y, float v0z,
		float v1x, float v1y, float v1z,
		float v2x, float v2y, float v2z)
	{
		float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
		float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;
		float hx = dy * e2z - dz * e2y;
		float hy = dz * e2x - dx * e2z;
		float hz = dx * e2y - dy * e2x;
		float a = e1x * hx + e1y * hy + e1z * hz;
		if (a > -1e-5f && a < 1e-5f) return -1f; // parallel to triangle plane
		float f = 1f / a;
		float sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;
		float u = f * (sx * hx + sy * hy + sz * hz);
		if (u < 0f || u > 1f) return -1f;
		float qx = sy * e1z - sz * e1y;
		float qy = sz * e1x - sx * e1z;
		float qz = sx * e1y - sy * e1x;
		float v = f * (dx * qx + dy * qy + dz * qz);
		if (v < 0f || u + v > 1f) return -1f;
		float t = f * (e2x * qx + e2y * qy + e2z * qz);
		return t > 1e-3f ? t : -1f;
	}

	/**
	 * Ray vs axis-aligned bounding box (slab method).
	 *
	 * @return ray parameter t at entry, or -1 if no intersection with t &gt; 0
	 */
	private static float rayBoxTest(
		float ox, float oy, float oz, float dx, float dy, float dz,
		float minX, float minY, float minZ, float maxX, float maxY, float maxZ)
	{
		float tmin = Float.NEGATIVE_INFINITY;
		float tmax = Float.POSITIVE_INFINITY;

		if (Math.abs(dx) < 1e-6f)
		{
			if (ox < minX || ox > maxX) return -1f;
		}
		else
		{
			float t1 = (minX - ox) / dx;
			float t2 = (maxX - ox) / dx;
			if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
		}

		if (Math.abs(dy) < 1e-6f)
		{
			if (oy < minY || oy > maxY) return -1f;
		}
		else
		{
			float t1 = (minY - oy) / dy;
			float t2 = (maxY - oy) / dy;
			if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
		}

		if (Math.abs(dz) < 1e-6f)
		{
			if (oz < minZ || oz > maxZ) return -1f;
		}
		else
		{
			float t1 = (minZ - oz) / dz;
			float t2 = (maxZ - oz) / dz;
			if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
		}

		if (tmax < tmin || tmax < 0f) return -1f;
		return tmin > 0f ? tmin : tmax;
	}
}
