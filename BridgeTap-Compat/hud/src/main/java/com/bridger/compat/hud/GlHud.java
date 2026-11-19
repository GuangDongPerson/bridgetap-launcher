package com.bridger.compat.hud;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Draws the overlay with the client's own font, inside the client's own GUI pass.
 *
 * <h2>Why the font is passed in instead of rasterised here</h2>
 * The client's {@code FontRenderer} draws from {@code assets/minecraft/textures/font/ascii.png},
 * which a resource pack replaces wholesale - using it is the only way the overlay matches the pack.
 * It also removes the whole AWT texture-upload path and its {@code GL_UNPACK_ALIGNMENT} pitfall.
 *
 * <h2>Why methods are matched by descriptor, not by name</h2>
 * Lunar keeps MCP names ({@code drawString}, {@code getStringWidth}). Badlion runs every class
 * through Odin, which flattens member names too: its font renderer is {@code avn} and the 5-argument
 * draw is {@code a(String,float,float,int,boolean)}. A name lookup finds nothing there - which is
 * how the previous build ended up logging {@code hud disabled: no usable drawString overload on avn}
 * and drawing nothing at all.
 *
 * <p>The descriptor {@code (Ljava/lang/String;FFIZ)I} is identical on both, because it describes the
 * shape of the call rather than what somebody decided to name it.
 *
 * <h2>Why no glPushAttrib</h2>
 * {@code FontRenderer} and {@code Gui.drawRect} both route state through {@code GlStateManager},
 * which keeps its own shadow copy of every GL flag. {@code glPushAttrib}/{@code glPopAttrib} would
 * restore the real GL state behind that cache and desynchronise it - the classic 1.8.9
 * HUD-injection bug. Only the matrix stack is touched here, via {@code glPushMatrix}, which
 * {@code GlStateManager} does not cache.
 */
public final class GlHud {
   private static final int PADDING = 4;
   private static final int LINE_GAP = 2;
   /**
    * Consecutive failures before the overlay gives up. Reset on every successful frame, so only a
    * genuinely broken draw path reaches it; a one-off GL hiccup does not kill the HUD for a session.
    */
   private static final int MAX_FAILURES = 20;
   /** 1.8.9's font is 9px tall; used only when the client exposes no height field. */
   private static final int DEFAULT_FONT_HEIGHT = 9;

   /** Which {@code FontRenderer} draw overload the client exposes. */
   private static final int FONT_FLOAT_SHADOW = 0;
   private static final int FONT_FLOAT = 1;
   private static final int FONT_INT = 2;
   private static final int FONT_INT_SHADOW = 3;

   /**
    * Geometry is re-read every frame, so the agent can push new values when the config file changes
    * (the external injector edits it while the game is running). Not final for that reason.
    */
   private volatile int originX;
   private volatile int originY;
   private volatile double scale;
   private volatile boolean background;
   private volatile boolean shadow;
   private final String logPath;

   private final Object font;
   private final Method drawString;
   private final int fontVariant;
   private final Method stringWidth;
   private final Method drawRect;
   /** {@code GlStateManager.color(FFFF)V}, used to put the client's cached colour back. */
   private final Method gsmColor;
   /**
    * The rest of {@code GlStateManager}, reached reflectively.
    *
    * <p>Driving {@code GL11} directly leaves the client's shadow copy of each flag disagreeing with
    * the driver. That is fine while we are the only writer, but {@code FontRenderer} goes through
    * {@code GlStateManager} for its own state, and a stale cache there means it skips a call it
    * thinks is already applied. Going through the same object keeps both views identical - which is
    * what the client's own HUD modules do, and why their text comes out at full brightness at the
    * exact same point in the frame.
    */
   private final Method gsmDisableLighting;
   private final Method gsmEnableTexture2D;
   private final Method gsmEnableBlend;
   private final Method gsmTryBlendFunc;
   private final Method gsmEnableAlpha;
   private final Method gsmDisableDepth;
   private final int fontHeight;

   /**
    * Scratch for {@code glGet*} — allocated by LWJGL, not by {@code ByteBuffer.allocateDirect()}.
    *
    * <p>LWJGL2 resolves the native address through its own {@code org.lwjgl.DirectBuffer} interface.
    * A JDK direct buffer obtained via {@code asIntBuffer()} does not implement it, the JNI writes
    * nothing useful, and the read-back is leftover memory: the first probe reported
    * {@code viewport=0x0+327680+-805175296} both times it ran, which looked exactly like "no GL
    * context" and made the overlay refuse to draw. {@code BufferUtils} hands back LWJGL's own
    * direct buffers, which is the only form these calls accept.
    */
   private final FloatBuffer color;
   private final IntBuffer viewport;
   /** GUI-scaled viewport size, derived from the raw viewport and the GUI scale factor. */
   private double scaledWidth;
   private double scaledHeight;

   /**
    * How many consecutive "no context" verdicts before drawing anyway.
    *
    * <p>The probe exists to pick a usable hook site, not to veto drawing forever. If it keeps
    * disagreeing with reality, painting into a context-less thread is a harmless no-op on Windows,
    * whereas refusing to paint is a guaranteed blank screen.
    */
   private static final int FORCE_AFTER = 40;

   private int failures;
   private int probeFailures;
   private boolean firstLogged;
   private boolean glLogged;
   private boolean noContextLogged;
   private boolean noFontLogged;
   private int frameCount;
   /** Directory the flag files and the phase log live in - the deploy dir. */
   private final java.io.File dir;
   /** {@code hud.nostate}: skip every snapshot/GlStateManager call. Bisect switch. */
   private final boolean skipState;
   /** {@code hud.nomatrix}: skip the push/translate/scale. Bisect switch. */
   private final boolean skipMatrix;
   /** {@code hud.notext}: draw the panel, not the glyphs. Bisect switch. */
   private final boolean skipText;
   /**
    * Client GUI scale factor; 0 means "auto", which is resolved the way the game does it.
    *
    * <p>Needed because the overlay draws in GUI-scaled pixels but may be hooked after every
    * renderer, where the active projection is the 3D one - so the 2D projection is set up here
    * rather than inherited.
    */
   private int guiScale;

   /** Client's {@code GlStateManager}, handed over by the agent because its name is client-specific. */
   private final Class<?> glState;
   /** Method names on that class: color, disableLighting, enableTexture2D, enableBlend,
    * tryBlendFuncSeparate, enableAlpha, disableDepth - in MCP spelling or obfuscated. */
   private final String[] glNames;

   public GlHud(Object font, int originX, int originY, double scale, boolean background,
         boolean shadow, String logPath, Class<?> glState, String[] glNames) {
      this.font = font;
      this.color = newFloatBuffer(16);
      this.viewport = newIntBuffer(16);
      this.originX = originX;
      this.originY = originY;
      this.scale = scale;
      this.background = background;
      this.shadow = shadow;
      this.logPath = logPath;
      this.glState = glState;
      this.glNames = glNames;
      java.io.File base = new java.io.File(logPath).getParentFile();
      this.dir = base == null ? new java.io.File(".") : base;
      this.skipState = flag("hud.nostate");
      this.skipMatrix = flag("hud.nomatrix");
      this.skipText = flag("hud.notext");

      Class<?> fontType = font.getClass();

      // Ordered most- to least- informative. Named lookup first: Lunar ships its own
      // renderString(String,float,float,int,boolean) wrapper with the same descriptor as the vanilla
      // drawString, and picking it by descriptor alone selects the wrapper - which neither applies
      // the drop shadow nor expands the section-sign colours the way FontRenderer does.
      Method draw = named(fontType, "drawString", String.class, float.class, float.class, int.class,
         boolean.class);
      int variant = FONT_FLOAT_SHADOW;
      if (draw == null) {
         draw = named(fontType, "drawStringWithShadow", String.class, float.class, float.class,
            int.class);
         variant = FONT_FLOAT;
      }
      if (draw == null) {
         draw = byDescriptor(fontType, "(Ljava/lang/String;FFIZ)I");
         variant = FONT_FLOAT_SHADOW;
      }
      if (draw == null) {
         draw = byDescriptor(fontType, "(Ljava/lang/String;III)I");
         variant = FONT_INT;
      }
      if (draw == null) {
         draw = byDescriptor(fontType, "(Ljava/lang/String;IIIZ)V");
         variant = FONT_INT_SHADOW;
      }
      if (draw == null) {
         // Last resort: a client that renamed nothing but reshaped the call.
         draw = named(fontType, "drawString", String.class, float.class, float.class, int.class,
            boolean.class);
         variant = FONT_FLOAT_SHADOW;
      }
      if (draw == null) {
         draw = named(fontType, "drawStringWithShadow", String.class, float.class, float.class,
            int.class);
         variant = FONT_FLOAT;
      }
      if (draw != null) {
         try {
            draw.setAccessible(true);
         } catch (Throwable ignored) {
            draw = null;
         }
      }
      this.drawString = draw;
      this.fontVariant = variant;

      Method width = byDescriptor(fontType, "(Ljava/lang/String;)I");
      if (width == null) {
         width = named(fontType, "getStringWidth", String.class);
      }
      if (width != null) {
         try {
            width.setAccessible(true);
         } catch (Throwable ignored) {
            width = null;
         }
      }
      this.stringWidth = width;
      this.fontHeight = resolveFontHeight(fontType);

      // Lunar has the unobfuscated Gui; Badlion does not. A null here just switches the panel
      // background to the built-in quad path - it no longer disables the background outright.
      Method rect = null;
      try {
         Class<?> gui = Class.forName("net.minecraft.client.gui.Gui", false, fontType.getClassLoader());
         rect = gui.getMethod("drawRect", int.class, int.class, int.class, int.class, int.class);
         rect.setAccessible(true);
      } catch (Throwable t) {
         rect = null;
      }
      this.drawRect = rect;

      // GlStateManager is the only handle on the client's cached current colour. Restoring the GL
      // colour behind its back leaves the cache lying, and the next Gui.drawRect that happens to use
      // the same colour is then skipped - which is how a black tint survived on Lunar's buttons.
      Method gsm = null;
      Method lighting = null;
      Method texture = null;
      Method blend = null;
      Method blendFunc = null;
      Method alpha = null;
      Method noDepth = null;
      try {
         Class<?> stateManager = this.glState;
         if (stateManager == null) {
            for (String candidate : new String[] {
                  "net.minecraft.client.renderer.GlStateManager", "bfl"}) {
               try {
                  stateManager = Class.forName(candidate, false, fontType.getClassLoader());
                  break;
               } catch (Throwable ignored) {
                  // try the next spelling
               }
            }
         }
         String[] names = this.glNames != null && this.glNames.length >= 7 ? this.glNames
            : new String[] {"color", "disableLighting", "enableTexture2D", "enableBlend",
               "tryBlendFuncSeparate", "enableAlpha", "disableDepth"};
         if (stateManager != null) {
            gsm = staticMethod(stateManager, names[0], float.class, float.class, float.class,
               float.class);
            lighting = staticMethod(stateManager, names[1]);
            texture = staticMethod(stateManager, names[2]);
            blend = staticMethod(stateManager, names[3]);
            blendFunc = staticMethod(stateManager, names[4],
               int.class, int.class, int.class, int.class);
            alpha = staticMethod(stateManager, names[5]);
            noDepth = staticMethod(stateManager, names[6]);
         }
      } catch (Throwable t) {
         // keep the direct GL path
      }
      this.gsmColor = gsm;
      this.gsmDisableLighting = lighting;
      this.gsmEnableTexture2D = texture;
      this.gsmEnableBlend = blend;
      this.gsmTryBlendFunc = blendFunc;
      this.gsmEnableAlpha = alpha;
      this.gsmDisableDepth = noDepth;
   }

   /**
    * Applies geometry changes without rebuilding the renderer.
    *
    * <p>The agent calls this reflectively when {@code config/bridgetap.properties} changes on disk,
    * which is what makes "move the panel from the injector window" take effect on the next frame
    * instead of after a restart.
    */
   public void applyConfig(int x, int y, double nextScale, boolean nextBackground, boolean nextShadow) {
      this.originX = x;
      this.originY = y;
      this.scale = nextScale > 0 ? nextScale : 1.0D;
      this.background = nextBackground;
      this.shadow = nextShadow;
   }

   private static Method staticMethod(Class<?> type, String name, Class<?>... params) {
      try {
         Method m = type.getMethod(name, params);
         m.setAccessible(true);
         return m;
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Puts every state the glyphs depend on into the "plain 2D text" configuration.
    *
    * <p>Called once per frame and again before each line. Going through {@code GlStateManager} when
    * it is reachable keeps the client's cache in step; the direct GL calls are the fallback for a
    * client that renamed it.
    */
   private void applyDrawState() {
      if (this.gsmDisableLighting != null) {
         invoke0(this.gsmDisableLighting);
      } else {
         GL11.glDisable(GL11.GL_LIGHTING);
      }
      if (this.gsmEnableTexture2D != null) {
         invoke0(this.gsmEnableTexture2D);
      } else {
         GL11.glEnable(GL11.GL_TEXTURE_2D);
      }
      if (this.gsmEnableBlend != null) {
         invoke0(this.gsmEnableBlend);
      } else {
         GL11.glEnable(GL11.GL_BLEND);
      }
      if (this.gsmTryBlendFunc != null) {
         try {
            this.gsmTryBlendFunc.invoke(null, Integer.valueOf(770), Integer.valueOf(771),
               Integer.valueOf(1), Integer.valueOf(0));
         } catch (Throwable ignored) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         }
      } else {
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      }
      if (this.gsmEnableAlpha != null) {
         invoke0(this.gsmEnableAlpha);
      } else {
         GL11.glEnable(GL11.GL_ALPHA_TEST);
      }
      GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
      if (this.gsmDisableDepth != null) {
         invoke0(this.gsmDisableDepth);
      } else {
         GL11.glDisable(GL11.GL_DEPTH_TEST);
      }
      if (this.gsmColor != null) {
         try {
            this.gsmColor.invoke(null, Float.valueOf(1.0F), Float.valueOf(1.0F), Float.valueOf(1.0F),
               Float.valueOf(1.0F));
         } catch (Throwable ignored) {
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
         }
      } else {
         GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
      }
   }

   private static void invoke0(Method method) {
      try {
         method.invoke(null);
      } catch (Throwable ignored) {
         // best effort; the direct GL call already happened for the flags that matter
      }
   }

   /**
    * Draws the panel; {@code true} means pixels were actually produced.
    *
    * <p>The return value is load-bearing: a GL call on a thread without a current context does not
    * always throw, so without this check the caller cannot tell "drew" from "silently did nothing"
    * and never falls back to another hook site.
    */
   public boolean render(List<String> lines) {
      if (this.drawString == null) {
         if (!this.noFontLogged) {
            this.noFontLogged = true;
            trace("hud disabled: no usable draw method on "
               + (this.font == null ? "null font" : this.font.getClass().getName())
               + " (probed (String,FFIZ)I / (String,FFI)I / (String,III)I / (String,IIIZ)V)");
         }
         return false;
      }
      if (this.failures >= MAX_FAILURES) {
         trace("render disabled after " + this.failures + " failures; restart the client");
         return false;
      }
      if (lines == null || lines.isEmpty()) {
         trace("render skipped: empty line list");
         return false;
      }
      if (!glReady()) {
         return false;
      }
      boolean pushed = false;
      boolean pushedProjection = false;
      phase("enter");
      try {
         int lineHeight = this.fontHeight + LINE_GAP;
         int textWidth = 0;
         for (String line : lines) {
            textWidth = Math.max(textWidth, widthOf(line));
         }
         int panelWidth = textWidth + PADDING * 2;
         int panelHeight = lineHeight * lines.size() + PADDING * 2 - LINE_GAP;

         if (!this.glLogged) {
            this.glLogged = true;
            logGlState();
         }
         phase("measured " + panelWidth + "x" + panelHeight + " via " + this.drawString.getName()
            + " variant " + this.fontVariant);

         if (!this.skipMatrix) {
            // Own 2D projection: the hook may sit after every renderer (Badlion's HUD method is
            // never called - the client draws its own), where the active projection is the 3D
            // camera one and a translate by (175,50) lands somewhere off screen.
            int rawWidth = this.viewport.get(2);
            int rawHeight = this.viewport.get(3);
            int factor = this.guiScale;
            if (factor <= 0) {
               factor = 1;
               while (factor < 4 && rawWidth / (factor + 1) >= 320 && rawHeight / (factor + 1) >= 240) {
                  factor++;
               }
            }
            this.scaledWidth = rawWidth / (double)factor;
            this.scaledHeight = rawHeight / (double)factor;

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            pushedProjection = true;
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, this.scaledWidth, this.scaledHeight, 0.0D, -1.0D, 1.0D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            pushed = true;
            GL11.glLoadIdentity();
            GL11.glTranslatef(this.originX, this.originY, 0.0F);
            GL11.glScalef((float)this.scale, (float)this.scale, 1.0F);
         }

         // The overlay is 2D and must not be depth-tested. Gui.drawRect used to disable depth
         // internally; drawing the panel ourselves means nothing does it for us, and with depth
         // testing left on the text is occluded by whatever the world pass left in the depth buffer
         // - visible for the first frame (clean buffer) and gone afterwards. GlState.restore puts
         // both flags back at the end of the frame.
         phase("matrix pushed (skip=" + this.skipMatrix + ")");
         if (!this.skipState) {
            applyDrawState();
         }
         phase("gl state applied (skip=" + this.skipState + ")");

         if (this.background) {
            int edge = 0x41FFFFFF;
            rect(0, 0, panelWidth, panelHeight, 0x9F101010);
            rect(0, 0, panelWidth, 1, edge);
            rect(0, panelHeight - 1, panelWidth, panelHeight, edge);
            rect(0, 0, 1, panelHeight, edge);
            rect(panelWidth - 1, 0, panelWidth, panelHeight, edge);
         }

         phase("background drawn");
         int y = PADDING;
         for (String line : lines) {
            if (!this.skipText) {
               draw(line, PADDING, y);
            }
            y += lineHeight;
         }
         phase("glyphs drawn (skip=" + this.skipText + ")");

         if (pushed) {
            GL11.glPopMatrix();
            pushed = false;
         }
         if (pushedProjection) {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            pushedProjection = false;
         }
         phase("done");
         this.frameCount++;

         this.failures = 0;
         if (!this.firstLogged) {
            this.firstLogged = true;
            trace("hud drawn: " + lines.size() + " line(s), panel " + panelWidth + "x" + panelHeight
               + " at " + this.originX + "," + this.originY + " via " + this.drawString.getName()
               + " (variant " + this.fontVariant + ", rect quad, thread "
               + Thread.currentThread().getName() + ")");
            // Also to the game log: hud.log sits in a directory every past injection has written
            // to, and a still-running older client holds that file open. System.out always lands in
            // .minecraft/logs/latest.log, which is never shared.
            System.out.println("[BridgeTap] hud drawn (" + lines.size() + " line(s), "
               + this.drawString.getName() + ", " + Thread.currentThread().getName() + ")");
         }
         return true;
      } catch (Throwable t) {
         this.failures++;
         trace("hud failure " + this.failures + "/" + MAX_FAILURES + ": " + describe(t));
         return false;
      } finally {
         // The matrix push must be undone even when the draw throws. A leaked stack entry shifts
         // every later GUI element by hudX/hudY, which reads as "the screen flickers a couple of
         // times and then the text disappears".
         if (pushed) {
            try {
               GL11.glPopMatrix();
            } catch (Throwable ignored) {
               // context already gone; nothing to restore
            }
         }
         // Put the frame back to the state a 1.8.9 GUI pass expects instead of replaying a snapshot.
         // The snapshot needed a dozen glGet* round trips - three glGetBoolean, two glGetInteger,
         // two glGetFloat, glGetTexEnvi - and one of them faults inside nvoglv64 on this client.
         // What the GUI needs afterwards is known without asking.
         try {
            if (this.gsmColor != null) {
               this.gsmColor.invoke(null, Float.valueOf(1.0F), Float.valueOf(1.0F),
                  Float.valueOf(1.0F), Float.valueOf(1.0F));
            } else {
               GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            }
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
         } catch (Throwable ignored) {
            // the caller's frame is already finished; a failed restore must not break it further
         }
      }
   }

   /**
    * The GL state this overlay may disturb, read back before drawing and put back afterwards.
    *
    * <p>{@code glPushAttrib}/{@code glPopAttrib} would be the natural tool and is not usable here:
    * {@code GlStateManager} keeps a shadow copy of these flags, so restoring the driver state alone
    * leaves the client believing something that is no longer true. Reading and writing individual
    * flags keeps both sides convergent, and the one value the shadow copy cannot be told about -
    * the current colour - is pushed through {@code GlStateManager.color} itself.
    */
   static final class GlState {
      /** GL 1.4 pnames for the per-face blend queries; LWJGL 2's GL11 does not export them. */
      private static final int BLEND_SRC_ALPHA = 0x80CB;
      private static final int BLEND_DST_ALPHA = 0x80C9;

      private final boolean texture;
      private final boolean blend;
      private final boolean depth;
      private final boolean alpha;
      private final boolean lighting;
      private final boolean cull;
      private final int blendSrc;
      private final int blendDst;
      private final int texEnvMode;
      private final int alphaFunc;
      private final float alphaRef;
      private final float[] color = new float[4];

      private GlState() {
         this.texture = GL11.glGetBoolean(GL11.GL_TEXTURE_2D);
         this.blend = GL11.glGetBoolean(GL11.GL_BLEND);
         this.depth = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
         this.alpha = GL11.glGetBoolean(GL11.GL_ALPHA_TEST);
         this.lighting = GL11.glGetBoolean(GL11.GL_LIGHTING);
         this.cull = GL11.glGetBoolean(GL11.GL_CULL_FACE);
         // GL_BLEND_SRC / GL_BLEND_DST are not valid pnames for glGetInteger in GL 1.1 - the
         // query returns 0 with GL_INVALID_ENUM, and restoring blendFunc(0,0) turns blending into
         // "draw nothing". The per-face-alpha forms are the ones that actually answer.
         this.blendSrc = GL11.glGetInteger(BLEND_SRC_ALPHA);
         this.blendDst = GL11.glGetInteger(BLEND_DST_ALPHA);
         int mode = GL11.GL_MODULATE;
         try {
            mode = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE);
         } catch (Throwable ignored) {
            // some drivers refuse the query; GL_MODULATE is the 1.8.9 default anyway
         }
         this.texEnvMode = mode;
         this.alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
         java.nio.FloatBuffer ref = org.lwjgl.BufferUtils.createFloatBuffer(1);
         GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF, ref);
         this.alphaRef = ref.get(0);
         GL11.glGetFloat(GL11.GL_CURRENT_COLOR, java.nio.FloatBuffer.wrap(this.color));
      }

      static GlState capture() {
         try {
            return new GlState();
         } catch (Throwable t) {
            return null;
         }
      }

      void restore(Method gsmColor) {
         try {
            // White first: it is what every GUI pass starts from, and it re-syncs GlStateManager's
            // cached colour, which nothing else can reach.
            if (gsmColor != null) {
               gsmColor.invoke(null, Float.valueOf(1.0F), Float.valueOf(1.0F), Float.valueOf(1.0F),
                  Float.valueOf(1.0F));
            } else {
               GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            }
            GL11.glColor4f(this.color[0], this.color[1], this.color[2], this.color[3]);
            GL11.glBlendFunc(this.blendSrc, this.blendDst);
            set(GL11.GL_TEXTURE_2D, this.texture);
            set(GL11.GL_BLEND, this.blend);
            set(GL11.GL_DEPTH_TEST, this.depth);
            set(GL11.GL_ALPHA_TEST, this.alpha);
            set(GL11.GL_LIGHTING, this.lighting);
            set(GL11.GL_CULL_FACE, this.cull);
            // The texture environment mode has no GlStateManager wrapper on 1.8.9, so nothing else
            // in the client restores it. Leaving it on GL_MODULATE changes how every later textured
            // draw combines its colour - on a client whose renderer expects GL_COMBINE that is a
            // state change it never asked for.
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, this.texEnvMode);
            GL11.glAlphaFunc(this.alphaFunc, this.alphaRef);
         } catch (Throwable ignored) {
            // best effort: a dead context restores nothing
         }
      }

      private static void set(int cap, boolean enabled) {
         if (enabled) {
            GL11.glEnable(cap);
         } else {
            GL11.glDisable(cap);
         }
      }
   }

   /**
    * True only when this thread has a usable GL context.
    *
    * <p>Calling GL without a current context does not reliably throw: LWJGL hands the pointer to the
    * driver, nothing writes the output buffer, and the leftover memory reads back as
    * {@code viewport=0x0} with nonsense floats in the projection. Badlion reaches
    * {@code updateDisplay()} from four callers, at least one of which is not the render thread - so
    * the previous build "drew" every frame there and nothing ever appeared. A viewport of zero is
    * the tell, and it lets the caller try the next site instead of counting a silent failure.
    */
   private boolean glReady() {
      try {
         this.viewport.clear();
         GL11.glGetInteger(GL11.GL_VIEWPORT, this.viewport);
         int width = this.viewport.get(2);
         int height = this.viewport.get(3);

         // A zero/absurd viewport usually means no current context, but a client that renders into
         // an offscreen target can leave a stale one behind while GL itself is perfectly usable.
         // GL_VERSION is the tie-breaker: a live context always answers it.
         boolean saneViewport = width > 0 && height > 0 && width <= 16384 && height <= 16384;
         String version = null;
         try {
            version = GL11.glGetString(GL11.GL_VERSION);
         } catch (Throwable ignored) {
            // treated as "no answer"
         }
         boolean liveContext = version != null && !version.isEmpty();

         if (!saneViewport && !liveContext) {
            if (++this.probeFailures >= FORCE_AFTER) {
               if (!this.noContextLogged) {
                  this.noContextLogged = true;
                  trace("probe failed " + this.probeFailures + " times (viewport " + width + "x"
                     + height + ", gl=" + version + "); drawing anyway on "
                     + Thread.currentThread().getName());
               }
               return true;
            }
            if (!this.noContextLogged) {
               this.noContextLogged = true;
               trace("no GL context on thread " + Thread.currentThread().getName()
                  + " (viewport " + width + "x" + height + ", gl=" + version + ", err="
                  + glError() + ") - trying the next draw site");
            }
            return false;
         }
         if (!saneViewport && !this.noContextLogged) {
            this.noContextLogged = true;
            trace("viewport reads " + width + "x" + height + " but GL is alive (" + version
               + ") - drawing anyway on " + Thread.currentThread().getName());
         }
         return true;
      } catch (Throwable t) {
         if (!this.noContextLogged) {
            this.noContextLogged = true;
            trace("gl probe failed on thread " + Thread.currentThread().getName() + ": "
               + describe(t));
         }
         return false;
      }
   }

   /**
    * One line describing the coordinate system the overlay is about to draw in.
    *
    * <p>The panel is drawn in GUI pixels, so it only lands where expected if the client's scaled
    * orthographic projection is still installed at the hook point. If a future client draws nothing,
    * this single line separates "wrong projection" (m00/m11 not matching a scaled ortho) from
    * "wrong font" without another round trip.
    */
   private void logGlState() {
      try {
         this.viewport.clear();
         GL11.glGetInteger(GL11.GL_VIEWPORT, this.viewport);
         this.color.clear();
         GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, this.color);
         String version = null;
         try {
            version = GL11.glGetString(GL11.GL_VERSION);
         } catch (Throwable ignored) {
            // reported as null
         }
         trace("gl: viewport=" + this.viewport.get(0) + "x" + this.viewport.get(1)
            + "+" + this.viewport.get(2) + "+" + this.viewport.get(3)
            + " proj=[" + this.color.get(0) + ", " + this.color.get(5) + ", "
            + this.color.get(10) + ", " + this.color.get(15) + "] at "
            + this.originX + "," + this.originY + " scale " + this.scale
            + " thread " + Thread.currentThread().getName() + " gl=" + version);
      } catch (Throwable t) {
         trace("gl state probe failed: " + describe(t));
      }
   }

   private static String glError() {
      try {
         return String.valueOf(GL11.glGetError());
      } catch (Throwable t) {
         return "?";
      }
   }

   /**
    * Solid rectangle.
    *
    * <p>Deliberately not {@code Gui.drawRect}. That method ends with
    * {@code GlStateManager.color(bgR, bgG, bgB, bgA)} and never puts the colour back, because in
    * vanilla every caller sets its own colour next. Drawn from the tail of
    * {@code renderGameOverlay} there is no such caller: the client's own menu buttons, the hotbar
    * highlight and any other HUD from another mod all inherit a near-black tint. The symptom was
    * exactly that - the BridgeTap panel looked right and every Lunar button turned into a black slab.
    *
    * <p>{@link #quad} reads back every flag and colour it touches and restores the real values.
    */
   private void rect(int x1, int y1, int x2, int y2, int argb) {
      quad(x1, y1, x2, y2, argb);
   }

   /**
    * Solid rectangle with no client dependency.
    *
    * <p>Every touched flag is read back and restored to the value it actually had, rather than reset
    * to a guess: {@code GlStateManager} keeps its own cache of these flags, and putting them back
    * exactly as found is the only way to leave that cache truthful without involving it.
    */
   private void quad(int x1, int y1, int x2, int y2, int argb) {
      // Deliberately query-free. The first version read back TEXTURE_2D, BLEND, DEPTH_TEST and the
      // current colour around every rectangle; each of those is a driver round trip, and that whole
      // family is what faults inside nvoglv64 here. The caller has just put the frame into GUI-text
      // state, so the values are set rather than asked for, and left in that same state afterwards.
      GL11.glDisable(GL11.GL_TEXTURE_2D);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GL11.glColor4f(((argb >> 16) & 0xFF) / 255.0F, ((argb >> 8) & 0xFF) / 255.0F,
         (argb & 0xFF) / 255.0F, ((argb >>> 24) & 0xFF) / 255.0F);

      GL11.glBegin(GL11.GL_QUADS);
      GL11.glVertex2i(x1, y1);
      GL11.glVertex2i(x1, y2);
      GL11.glVertex2i(x2, y2);
      GL11.glVertex2i(x2, y1);
      GL11.glEnd();

      GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
      GL11.glEnable(GL11.GL_TEXTURE_2D);
   }

   private void draw(String line, int x, int y) throws Exception {
      // Every state the glyphs depend on is set again per line, not once per frame. Between two
      // lines there is only a width lookup, but the client's FontRenderer is free to change any of
      // these mid-frame - and on 1.8.9 a single leftover GL_LIGHTING multiplies the per-vertex
      // colour by the material's ambient term, which turns every section-sign colour into the same
      // dim grey. Pinning them here makes the result independent of what the previous caller left
      // behind.
      applyDrawState();
      switch (this.fontVariant) {
         case FONT_FLOAT_SHADOW:
            this.drawString.invoke(this.font, line, Float.valueOf(x), Float.valueOf(y),
               Integer.valueOf(0xFFFFFF), Boolean.valueOf(this.shadow));
            break;
         case FONT_FLOAT:
            this.drawString.invoke(this.font, line, Float.valueOf(x), Float.valueOf(y),
               Integer.valueOf(0xFFFFFF));
            break;
         case FONT_INT:
            this.drawString.invoke(this.font, line, Integer.valueOf(x), Integer.valueOf(y),
               Integer.valueOf(0xFFFFFF));
            break;
         default:
            this.drawString.invoke(this.font, line, Integer.valueOf(x), Integer.valueOf(y),
               Integer.valueOf(0xFFFFFF), Boolean.valueOf(this.shadow));
            break;
      }
   }

   private int widthOf(String line) {
      Method width = this.stringWidth;
      if (width == null) {
         // Rough fallback: 1.8.9's default font advances about 6 px per glyph.
         return line.length() * 6;
      }
      try {
         Object value = width.invoke(this.font, line);
         int px = value instanceof Number ? ((Number)value).intValue() : -1;
         // A method matched only as "takes a String, returns an int" could be something else
         // entirely; an implausible width is treated as "no measurement" rather than trusted.
         if (px < 0 || px > 10000) {
            return line.length() * 6;
         }
         return px;
      } catch (Throwable t) {
         return line.length() * 6;
      }
   }

   /** The client's own line height, so the panel fits the pack's glyph size. */
   private static int resolveFontHeight(Class<?> fontType) {
      for (Class<?> type = fontType; type != null && type != Object.class;
            type = type.getSuperclass()) {
         try {
            for (Field field : type.getDeclaredFields()) {
               if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                  continue;
               }
               String name = field.getName();
               if (name.contains("FONT_HEIGHT") || name.equalsIgnoreCase("fontHeight")) {
                  field.setAccessible(true);
                  int value = field.getInt(null);
                  if (value > 0 && value < 64) {
                     return value;
                  }
               }
            }
         } catch (Throwable ignored) {
            // keep walking up
         }
      }
      return DEFAULT_FONT_HEIGHT;
   }

   /** First declared method on the class or a superclass whose JVM descriptor matches. */
   /** LWJGL's own buffers; a client that stripped {@code BufferUtils} falls back to JDK ones. */
   private static FloatBuffer newFloatBuffer(int size) {
      try {
         return BufferUtils.createFloatBuffer(size);
      } catch (Throwable t) {
         return ByteBuffer.allocateDirect(size * 4).asFloatBuffer();
      }
   }

   private static IntBuffer newIntBuffer(int size) {
      try {
         return BufferUtils.createIntBuffer(size);
      } catch (Throwable t) {
         return ByteBuffer.allocateDirect(size * 4).asIntBuffer();
      }
   }

   private static Method byDescriptor(Class<?> type, String descriptor) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         try {
            for (Method candidate : current.getDeclaredMethods()) {
               if (descriptorOf(candidate).equals(descriptor)) {
                  return candidate;
               }
            }
         } catch (Throwable ignored) {
            // keep walking up
         }
      }
      return null;
   }

   private static Method named(Class<?> type, String name, Class<?>... params) {
      try {
         return type.getMethod(name, params);
      } catch (Throwable t) {
         return null;
      }
   }

   private static String descriptorOf(Method method) {
      StringBuilder out = new StringBuilder("(");
      for (Class<?> param : method.getParameterTypes()) {
         out.append(descriptorOf(param));
      }
      return out.append(')').append(descriptorOf(method.getReturnType())).toString();
   }

   private static String descriptorOf(Class<?> type) {
      if (type == void.class) {
         return "V";
      }
      if (type == boolean.class) {
         return "Z";
      }
      if (type == byte.class) {
         return "B";
      }
      if (type == char.class) {
         return "C";
      }
      if (type == short.class) {
         return "S";
      }
      if (type == int.class) {
         return "I";
      }
      if (type == long.class) {
         return "J";
      }
      if (type == float.class) {
         return "F";
      }
      if (type == double.class) {
         return "D";
      }
      if (type.isArray()) {
         return type.getName().replace('.', '/');
      }
      return "L" + type.getName().replace('.', '/') + ";";
   }

   /**
    * Crash breadcrumb for the first frames of a session.
    *
    * <p>Goes to stdout, so it lands in the client's own {@code latest.log}, which survives a hard
    * process death - a file we hold open does not get flushed when the JVM is killed inside a
    * native call. Two frames is enough to locate the failing GL call and costs eight lines.
    */
   private void phase(String what) {
      if (this.frameCount >= 3) {
         return;
      }
      String line = "frame " + this.frameCount + ": " + what;
      // Opened, written and fsync'd per call: a JVM killed inside a native GL call never flushes
      // anything it still holds open, so a long-lived stream would lose exactly the line that
      // matters. Six lines cost six syscalls.
      try {
         java.io.FileOutputStream out = new java.io.FileOutputStream(
            new java.io.File(this.dir, "hud-phase.log"), true);
         out.write((line + "\n").getBytes("UTF-8"));
         out.getFD().sync();
         out.close();
      } catch (Throwable ignored) {
         // diagnostics only
      }
      System.out.println("[BridgeTap] hud phase " + line);
      System.out.flush();
   }

   /** Called by the agent with {@code GameSettings.guiScale} (0 = auto). */
   public void setGuiScale(int scale) {
      this.guiScale = scale;
   }

   private boolean flag(String name) {
      try {
         return new java.io.File(this.dir, name).isFile();
      } catch (Throwable t) {
         return false;
      }
   }

   private void trace(String line) {
      if (this.logPath == null) {
         return;
      }
      try {
         File out = new File(this.logPath);
         File dir = out.getParentFile();
         if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
         }
         try (FileWriter writer = new FileWriter(out, true)) {
            writer.write("[hud] " + line + "\r\n");
         }
      } catch (Throwable ignored) {
         // a diagnostic must never break the render loop
      }
   }

   private static String describe(Throwable t) {
      StringBuilder sb = new StringBuilder(String.valueOf(t));
      Throwable cause = t.getCause();
      if (cause != null) {
         sb.append(" <- ").append(cause);
      }
      StackTraceElement[] frames = t.getStackTrace();
      for (int i = 0; i < frames.length && i < 6; i++) {
         sb.append(" | ").append(frames[i]);
      }
      return sb.toString();
   }
}
