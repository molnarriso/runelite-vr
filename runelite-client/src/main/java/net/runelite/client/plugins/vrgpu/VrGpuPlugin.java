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
import java.awt.GraphicsConfiguration;
import java.awt.Image;
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
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
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
	/** True between a successful beginXrFrame() and the matching endXrFrame() call. */
	private boolean xrFrameStarted;

	// --- Stereo rendering state (T3.x) ---

	/**
	 * Default world scale: meters per OSRS local unit.
	 * 1 tile = 128 units ≈ 0.1 m → 0.1 / 128 ≈ 0.000781.
	 */
	private static final float DEFAULT_WORLD_SCALE = 0.000781f;

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

	/**
	 * Number of VAOs in {@code vaoO} that were drawn (but not reset) during the
	 * left-eye drawPass. Replayed and reset in the right-eye pass.
	 */
	private int vrPassOpaqueCount;
	/**
	 * Number of VAOs in {@code vaoPO} that were drawn (but not reset) during the
	 * left-eye drawPass. Replayed and reset in the right-eye pass.
	 */
	private int vrPassPlayerCount;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

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

					eyeSwapchains = new XrEyeSwapchain[2];
					for (int eye = 0; eye < 2; eye++)
					{
						eyeSwapchains[eye] = new XrEyeSwapchain();
						eyeSwapchains[eye].init(
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

		glBindVertexArray(0);

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
		if (vaoA != null)
		{
			vaoA.free();
		}
		if (vaoPO != null)
		{
			vaoPO.free();
		}
		vaoO = vaoA = vaoPO = null;
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

		// World anchor: fixed in stage space so head tracking gives correct parallax.
		// vrWorldAnchorY is sampled from the initial eye height on the first VR frame.
		// X=0 (stage centre), Z=-1.5m (1.5 m in front of stage origin).
		// OSRS Y increases downward, so the Y scale is negated to flip it upright.
		float worldOffsetX = 0;
		float worldOffsetY = vrWorldAnchorY;  // set once from initial eye height - 0.7 m
		float worldOffsetZ = -1.5f;

		// Chain: Proj × InvEyePose × Translate(worldOffset) × Scale(s,-s,s) × Translate(-cam)
		float[] proj = buildVrProjection(view.fov(), 0.05f);
		Mat4.mul(proj, buildInvEyePose(view.pose()));
		Mat4.mul(proj, Mat4.translate(worldOffsetX, worldOffsetY, worldOffsetZ));
		Mat4.mul(proj, Mat4.scale(DEFAULT_WORLD_SCALE, -DEFAULT_WORLD_SCALE, DEFAULT_WORLD_SCALE));
		Mat4.mul(proj, Mat4.translate(-root.cameraX, -root.cameraY, -root.cameraZ));
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
	 * Returns {@code [clipX, -clipY, clipW / DEFAULT_WORLD_SCALE]}:
	 * <ul>
	 *   <li>clipX/clipY come from the VR eye perspective transform.</li>
	 *   <li>Y is negated to restore CCW-front convention after the Y-scale flip
	 *       (same reason we use {@code glCullFace(GL_FRONT)} in VR mode).</li>
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
		final float oz = -1.5f;
		final float camX = root.cameraX;
		final float camY = root.cameraY;
		final float camZ = root.cameraZ;

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
				// Translate by -camera, scale (OSRS→VR metres, Y-flip), add world offset
				float vx = (wx - camX) * s;
				float vy = -(wy - camY) * s + oy;
				float vz = (wz - camZ) * s + oz;

				// View rotation R^T + translation → eye space
				float ex = r00 * vx + r01 * vy + r02 * vz + tx;
				float ey = r10 * vx + r11 * vy + r12 * vz + ty;
				float ez = r20 * vx + r21 * vy + r22 * vz + tz;

				// Perspective: clip_w = -ez (positive for front objects, camera looks -Z)
				float clipW = -ez;
				out[0] = a * ex + b * ez;  // clip_x
				out[1] = c * ey + d * ez;  // clip_y: Y-up, consistent with worldProjection
				out[2] = clipW / s;        // depth in OSRS units (so >50 clip test holds)
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
			currentEye = 0;
			vrOpaqueZones.clear();
			vrOpaqueProjs.clear();
			vrAlphaZones.clear();
			vrAlphaProjs.clear();
			vrScene = scene;
			vrPassOpaqueCount = 0;
			vrPassPlayerCount = 0;

			// Sample world anchor Y from initial eye height (once per session).
			if (Float.isNaN(vrWorldAnchorY))
			{
				float initialEyeY = vrViews.get(0).pose().position$().y();
				vrWorldAnchorY = initialEyeY - 0.7f;
				log.info("VR world anchor Y set to {} (eyeY={} - 0.7)", vrWorldAnchorY, initialEyeY);
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
		if (xrFrameStarted) { glCullFace(GL_FRONT); }

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
			glCullFace(GL_FRONT); // Y-flip reverses winding; cull front to discard actual back faces
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
					!close || (vrScene.getOverrideAmount() > 0));
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
			eyeSwapchains[1].releaseImage();

			// ---- Desktop mirror: blit left eye to AWT canvas (T3.5) ----
			int defaultFbo = awtContext.getFramebuffer(false);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, vrLeftEyeFbo);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
			int mirrorW = getScaledValue(clientUI.getGraphicsConfiguration().getDefaultTransform().getScaleX(), client.getCanvasWidth());
			int mirrorH = getScaledValue(clientUI.getGraphicsConfiguration().getDefaultTransform().getScaleY(), client.getCanvasHeight());
			glBlitFramebuffer(0, 0, eyeSwapchains[0].getWidth(), eyeSwapchains[0].getHeight(),
				0, 0, mirrorW, mirrorH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

			eyeSwapchains[0].releaseImage();
			currentEye = -1;

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
			sceneFboValid = true;
			return;
		}

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
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

		z.renderAlpha(zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, !close || (scene.getOverrideAmount() > 0));

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
		}
		else
		{
			m.calculateBoundsCylinder();
			VAO o = vaoO.get(size), a = vaoA.get(size);
			int start = a.vbo.vb.position();
			try
			{
				facePrioritySorter.uploadSortedModel(worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

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
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
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
			VAO a = vaoA.get(size);

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
			int end = a.vbo.vb.position();

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023);
			}
		}
		else
		{
			VAO o = vaoO.get(size);
			clientUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
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

		// In VR mode the left eye was already blitted to the AWT canvas in postDrawToplevel.
		if (sceneFboValid && !xrFrameStarted)
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
}
