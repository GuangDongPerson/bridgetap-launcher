package com.bridger.compat.agent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Finds the client's frame, tick, draw and block-placement entry points without relying on names.
 *
 * <p>Two very different clients have to work through the same code:
 * <ul>
 *   <li>Lunar keeps full MCP names — {@code net.minecraft.client.Minecraft.runTick()},
 *       {@code GuiIngame.renderGameOverlay(float)}.</li>
 *   <li>Badlion runs its classes through Odin, which flattens every class name to {@code a},
 *       {@code aa}, {@code ave} and every member to {@code a}/{@code b}/{@code c}. Verified against
 *       {@code BLClient.jar}: zero {@code net/minecraft/**} entries, 9199 classes named
 *       {@code [a-z]{1,4}}.</li>
 * </ul>
 *
 * <p>What survives re-obfuscation is the call graph, and one edge is unmistakable: exactly one
 * client method calls {@code org.lwjgl.opengl.Display.update()} — the frame presenter. On 1.8.9 that
 * is {@code Minecraft.updateDisplay()}. From there everything else follows:
 *
 * <pre>
 *   Display.update()          <- called by  &lt;mc&gt;.updateDisplay()   (11 insns: profiler + present)
 *   updateDisplay()           <- called by  &lt;mc&gt;.runGameLoop()     (the largest caller)
 *   runGameLoop()             <- calls     &lt;mc&gt;.runTick()          (its largest no-arg void callee)
 *   &lt;mc&gt;.&lt;static self field&gt; = the live instance
 *   &lt;mc&gt;.fields              = player / playerController / fontRenderer, by type shape
 * </pre>
 *
 * <p>Measured on BLClient PRODUCTION4: {@code ave.h()} → {@code ave.s()} (runTick, 1130 insns)
 * inside {@code ave.av()} (runGameLoop, 457 insns), player field {@code ave.h:Lbew;}, controller
 * {@code ave.c:Lbda;} whose {@code a(Lbew;Lbdb;Lzx;Lcj;Lcq;Laui;)Z} is onPlayerRightClick, font
 * {@code ave.k:Lavn;}.
 *
 * <p>Runs once per injection. Reads ~14k class files through the game's own loader, which is a few
 * seconds of work on the JVMTI thread — acceptable for a one-shot attach, and the result is cached.
 */
final class ClassScan {
   /** Everything the hook layer needs, all as JVM internal names / member names. */
   static final class Result {
      String mcClass;
      String instanceField;
      String updateDisplay;
      String runTick;
      String playerField;
      /** {@code Minecraft.theWorld}: {@code f} on Badlion (a {@code WorldClient}). */
      String worldField;
      /** {@code GlStateManager}: the MCP name on Lunar, {@code bfl} on Badlion. */
      String glStateClass;
      /** {@code Minecraft.gameSettings}: {@code t} on Badlion. */
      String settingsField;
      /** {@code GameSettings.keyBindPlayerList}: {@code al} on Badlion. */
      String tabKeyField;
      /** {@code KeyBinding.isKeyDown()}: {@code d} on Badlion. */
      String keyDownMethod;
      /** {@code GameSettings.guiScale}: {@code aL} on Badlion. */
      String guiScaleField;
      /** {@code Framebuffer}: {@code net.minecraft.client.shader.Framebuffer} or {@code bfw}. */
      String framebufferClass;
      /** {@code Framebuffer.framebufferRender(int,int)}: {@code c} on Badlion. */
      String framebufferRenderMethod;
      /**
       * {@code Minecraft.framebufferMc} - the offscreen target the frame is rendered into.
       *
       * <p>Everything visible on screen is drawn into this and then blitted, which is also what a
       * screenshot reads back. Drawing after the blit, as the late hook sites do, shows up on the
       * monitor but never in a screenshot.
       */
      String framebufferField;
      /**
       * True when class and member names are the 1.8.9 obfuscation rather than MCP. Everything that
       * resolves members reflectively consults this to pick the right candidate list.
       */
      boolean obf;
      /**
       * True when {@link #onGroundField} came from a measured map rather than a guess, so the
       * observational fallback must not second-guess it. On Badlion the field is called {@code C}
       * and sits among two dozen other booleans on {@code Entity} - guessing there picks wrong.
       */
      boolean groundTrusted;
      String posX;
      String posY;
      String posZ;
      String onGroundField;
      /** Local player's {@code jump()}: the signal a bridge jump happened. */
      String jumpClass;
      String jumpMethod;
      String jumpDesc;
      /** The class that draws the vanilla HUD: {@code GuiIngame} on MCP, {@code avo} on Badlion. */
      String guiClass;
      String guiMethod;
      String guiDesc;
      /**
       * {@code GuiIngame.renderTooltip(ScaledResolution, float)} - the earliest HUD method that
       * runs unconditionally with the GUI projection already installed.
       *
       * <p>Used as the overlay anchor. Drawing at the end of {@code renderGameOverlay} paints over
       * the tab list, because that method draws the player list itself; drawing here puts the panel
       * in the same layer as the hotbar, so everything the HUD pass draws afterwards - including the
       * tab list - covers it. ({@code renderPlayerList} does not exist on 1.8.9: the tab list lives
       * on {@code GuiPlayerTabOverlay} and is called from there.)
       */
      String tooltipMethod;
      String tooltipDesc;
      String controllerClass;
      String controllerMethod;
      String controllerDesc;
      String fontClass;
      String fontMethod;
      String fontDesc;
      /** {@code ItemBlock}, needed to tell a placed block from a swing or a bite. */
      String itemBlockClass;
      /** {@code ItemStack.getItem()}: {@code getItem} on MCP, {@code b} on Badlion. */
      String itemGetterName;
      /** The player's own chat send: the {@code /bt} interception point. */
      String chatClass;
      String chatMethod;
      String chatDesc;
      String describe;

      boolean usable() {
         return this.mcClass != null && this.updateDisplay != null && this.instanceField != null;
      }

      /** The three classes that must be retransformed. */
      String[] classes() {
         List<String> out = new ArrayList<String>(4);
         add(out, this.mcClass);
         add(out, this.controllerClass);
         add(out, this.jumpClass);
         return out.toArray(new String[0]);
      }

      private static void add(List<String> out, String name) {
         if (name != null && !out.contains(name)) {
            out.add(name);
         }
      }
   }

   /**
    * Badlion: plain vanilla 1.8.9 obfuscation. Every entry below was read out of
    * {@code .minecraft/labymod-neo/mappings/1.8.9-mcp.tsrg2} and then confirmed against
    * {@code BLClient.jar} with {@code javap -p -c}.
    *
    * <pre>
    *   ave                Minecraft            S = instance, h = updateDisplay, s = runTick
    *   ave h  thePlayer (bew)      ave c  playerController (bda)     ave k  fontRenderer (avn)
    *   ave f  theWorld  (bdb WorldClient)                            ave q  guiIngame (avo)
    *   bew                EntityPlayerSP     e(String)V = sendChatMessage, a(IChatComponent)V
    *   pr                 EntityLivingBase   bF()V = jump  - body starts `this.motionY = getJumpUpwardsMotion`
    *   avo                GuiIngame          a(F)V = renderGameOverlay, a(Lavr;F)V = renderTooltip
    *   bda                PlayerControllerMP a(Lbew;Lbdb;Lzx;Lcj;Lcq;Laui;)Z = onPlayerRightClick
    *   avn                FontRenderer       a(String,III)I = drawString
    *   adm                World              p(BlockPos) = getBlockState, K()J = getTotalWorldTime
    *   afh                Block              a(Block)I = getIdFromBlock
    *   pk                 Entity             s,t,u = posX,posY,posZ, C = onGround
    *   bfl                GlStateManager     c(FFFF)V = color, l() = enableBlend
    * </pre>
    */
   private static final String[] BLC = {
      "ave", "S", "h", "s", "h", "f",
      "s", "t", "u", "C",
      "pr", "bF",
      "avo", "a", "(F)V",
      "bda", "a",
      "avn", "a", "(Ljava/lang/String;III)I",
      "bew", "e", "(Ljava/lang/String;)V",
      "yo", "b",
      "bfl"
   };

   /** MCP names, for any client that keeps them. */
   private static Result fromKnown(Class<?>[] loaded) {
      Class<?> mc = null;
      for (Class<?> type : loaded) {
         if (type != null && "net.minecraft.client.Minecraft".equals(type.getName())) {
            mc = type;
            break;
         }
      }
      if (mc != null && hasMethod(mc, "updateDisplay") && hasMethod(mc, "runTick")
            && hasField(mc, "theMinecraft") && hasField(mc, "thePlayer")) {
         Result r = new Result();
         r.mcClass = "net/minecraft/client/Minecraft";
         r.instanceField = "theMinecraft";
         r.updateDisplay = "updateDisplay";
         r.runTick = "runTick";
         r.playerField = "thePlayer";
         r.worldField = "theWorld";
         r.glStateClass = "net.minecraft.client.renderer.GlStateManager";
         r.settingsField = "gameSettings";
         r.tabKeyField = "keyBindPlayerList";
         r.keyDownMethod = "isKeyDown";
         r.guiScaleField = "guiScale";
         r.framebufferField = "framebufferMc";
         r.framebufferClass = "net/minecraft/client/shader/Framebuffer";
         r.framebufferRenderMethod = "framebufferRender";
         r.obf = false;
         r.groundTrusted = true;
         r.posX = "posX";
         r.posY = "posY";
         r.posZ = "posZ";
         r.onGroundField = "onGround";
         for (Class<?> type : loaded) {
            if (type != null && "net.minecraft.client.multiplayer.PlayerControllerMP".equals(type.getName())) {
               r.controllerClass = "net/minecraft/client/multiplayer/PlayerControllerMP";
               r.controllerMethod = "onPlayerRightClick";
               // "*" = "returns Z with six arguments", matched by shape at transform time. A null
               // here used to reach descMatches and blow up with an NPE, which silently skipped
               // the block-placed hook - the reason bridging never started on Badlion.
               r.controllerDesc = "*";
               break;
            }
         }
         for (Class<?> type : loaded) {
            if (type != null && "net.minecraft.client.gui.FontRenderer".equals(type.getName())) {
               r.fontClass = "net.minecraft/client/gui/FontRenderer".replace('/', '.');
               r.fontMethod = "drawString";
               r.fontDesc = "(Ljava/lang/String;FFIZ)I";
               break;
            }
         }
         // The take-off. On 1.8.9 EntityPlayerSP does not override jump() - the method lives on
         // EntityLivingBase - so the check has to look at declared methods only. Walking the
         // superclass chain here reported "EntityPlayerSP has jump()", pointed the hook at a method
         // that class never declares, and the injection silently matched nothing.
         Class<?> sp = find(loaded, "net/minecraft/client/entity/EntityPlayerSP");
         Class<?> living = find(loaded, "net/minecraft/entity/EntityLivingBase");
         if (sp != null && declaresNoArgVoid(sp, "jump")) {
            r.jumpClass = "net/minecraft/client/entity/EntityPlayerSP";
            r.jumpMethod = "jump";
            r.jumpDesc = "()V";
         } else if (living != null && declaresNoArgVoid(living, "jump")) {
            r.jumpClass = "net/minecraft/entity/EntityLivingBase";
            r.jumpMethod = "jump";
            r.jumpDesc = "()V";
         }
         r.guiClass = "net/minecraft/client/gui/GuiIngame";
         r.guiMethod = "renderGameOverlay";
         r.guiDesc = "(F)V";
         Class<?> gui = find(loaded, "net/minecraft/client/gui/GuiIngame");
         if (gui != null) {
            java.lang.reflect.Method tooltip = null;
            for (Class<?> current = gui; current != null && current != Object.class;
                  current = current.getSuperclass()) {
               try {
                  for (java.lang.reflect.Method m : current.getDeclaredMethods()) {
                     if (!"renderTooltip".equals(m.getName()) || m.getReturnType() != void.class
                           || m.getParameterTypes().length != 2) {
                        continue;
                     }
                     tooltip = m;
                     break;
                  }
               } catch (Throwable ignored) {
                  // keep walking
               }
               if (tooltip != null) {
                  break;
               }
            }
            if (tooltip != null) {
               r.tooltipMethod = "renderTooltip";
               StringBuilder desc = new StringBuilder("(");
               for (Class<?> parameter : tooltip.getParameterTypes()) {
                  desc.append(paramDesc(parameter));
               }
               r.tooltipDesc = desc.append(")V").toString();
            }
         }
         r.itemBlockClass = "net.minecraft.item.ItemBlock";
         r.itemGetterName = "getItem";
         r.describe = describe(r) + " [MCP map]";
         return r.usable() ? r : null;
      }

      Class<?> blc = find(loaded, BLC[0]);
      if (blc != null && hasMethod(blc, "h") && hasField(blc, "S") && hasField(blc, "f")
            && fieldTypeName(blc, "h").endsWith("bew")) {
         Result r = new Result();
         r.mcClass = BLC[0];
         r.instanceField = BLC[1];
         r.updateDisplay = BLC[2];
         r.runTick = BLC[3];
         r.playerField = BLC[4];
         r.worldField = BLC[5];
         r.posX = BLC[6];
         r.posY = BLC[7];
         r.posZ = BLC[8];
         r.onGroundField = BLC[9];
         r.obf = true;
         r.groundTrusted = true;
         r.glStateClass = BLC[25];
         r.settingsField = "t";
         r.tabKeyField = "al";
         r.keyDownMethod = "d";
         r.guiScaleField = "aL";
         r.framebufferField = "aF";
         r.framebufferClass = "bfw";
         r.framebufferRenderMethod = "c";

         // jump() is declared on EntityLivingBase (pr) and never overridden by EntityPlayerSP on
         // 1.8.9. pr.bF() is `this.motionY = (double)this.getJumpUpwardsMotion()` - the take-off.
         Class<?> living = find(loaded, BLC[10]);
         if (living != null && declaresNoArgVoid(living, BLC[11])) {
            r.jumpClass = BLC[10];
            r.jumpMethod = BLC[11];
            r.jumpDesc = "()V";
         }

         if (find(loaded, BLC[12]) != null) {
            r.guiClass = BLC[12];
            r.guiMethod = BLC[13];
            r.guiDesc = BLC[14];
            r.tooltipMethod = BLC[13];
            r.tooltipDesc = "(Lavr;F)V";
         }

         Class<?> controller = find(loaded, BLC[15]);
         if (controller != null && hasMethod(controller, BLC[16])) {
            r.controllerClass = BLC[15];
            r.controllerMethod = BLC[16];
            r.controllerDesc = "*";
         }

         if (find(loaded, BLC[17]) != null) {
            r.fontClass = BLC[17];
            r.fontMethod = BLC[18];
            r.fontDesc = BLC[19];
         }

         Class<?> chat = find(loaded, BLC[20]);
         if (chat != null && declaresVoidString(chat, BLC[21])) {
            r.chatClass = BLC[20];
            r.chatMethod = BLC[21];
            r.chatDesc = BLC[22];
         }

         r.itemBlockClass = BLC[23];
         r.itemGetterName = BLC[24];
         r.describe = describe(r) + " [Badlion map, BLClient PRODUCTION4]";
         return r.usable() ? r : null;
      }
      return null;
   }

   private static boolean hasMethod(Class<?> type, String name) {
      try {
         for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
               return true;
            }
         }
      } catch (Throwable ignored) {
         // treated as absent
      }
      return false;
   }

   private static boolean hasField(Class<?> type, String name) {
      try {
         type.getDeclaredField(name);
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /** Binary name of a declared field's type, or an empty string when there is no such field. */
   private static String fieldTypeName(Class<?> type, String name) {
      try {
         return type.getDeclaredField(name).getType().getName();
      } catch (Throwable t) {
         return "";
      }
   }

   /** True when the class itself declares {@code name(String)V} - the chat-send signature. */
   private static boolean declaresVoidString(Class<?> type, String name) {
      try {
         for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (m.getName().equals(name) && m.getReturnType() == void.class && params.length == 1
                  && params[0] == String.class) {
               return true;
            }
         }
      } catch (Throwable ignored) {
         // treated as absent
      }
      return false;
   }

   private ClassScan() {
   }

   static Result discover(Class<?>[] loaded) {
      ClassLoader loader = loaderOf(loaded);
      if (loader == null) {
         return null;
      }

      // Fast path: both clients have been mapped by hand (see BLCLIENT / MCP below). Validating the
      // names costs a few field lookups; scanning 14k class files costs seconds of stalled render
      // thread, so the scan is only the fallback for an unknown build.
      Result known = fromKnown(loaded);
      if (known != null) {
         resolveChat(known, loaded);
         return known;
      }

      // Step 1: the one class that calls Display.update().
      Frame frame = findPresenter(loader, loaded);
      if (frame == null) {
         Result fallback = fromFallback(loaded);
         if (fallback != null) {
            return fallback;
         }
         return null;
      }

      Result r = new Result();
      r.mcClass = frame.className;
      r.updateDisplay = frame.methodName;

      ClassNode mc = node(loader, frame.className);
      if (mc == null) {
         return null;
      }

      // Step 2: the static self-reference is the singleton.
      r.instanceField = selfField(mc);

      // Step 3: runGameLoop is updateDisplay's heaviest caller inside the same class.
      r.runTick = findRunTick(loader, loaded, mc, frame.methodName);

      // Step 4: player / controller / font by type shape.
      for (FieldNode f : mc.fields) {
         if ((f.access & Opcodes.ACC_STATIC) != 0 || !f.desc.startsWith("L")) {
            continue;
         }
         String type = f.desc.substring(1, f.desc.length() - 1);
         ClassNode target = node(loader, type);
         if (target == null) {
            continue;
         }

         if (r.controllerClass == null) {
            for (MethodNode m : target.methods) {
               if (m.desc.endsWith(")Z") && Type.getArgumentTypes(m.desc).length == 6) {
                  r.controllerClass = type;
                  r.controllerMethod = m.name;
                  r.controllerDesc = m.desc;
                  break;
               }
            }
         }

         if (r.fontClass == null) {
            for (MethodNode m : target.methods) {
               if ("(Ljava/lang/String;FFIZ)I".equals(m.desc)) {
                  r.fontClass = type;
                  r.fontMethod = m.name;
                  r.fontDesc = m.desc;
                  break;
               }
            }
         }

         if (r.playerField == null && looksLikePlayer(target, loader)) {
            r.playerField = f.name;
            readPosition(target, loader, r);
         }
      }

      r.jumpClass = null;
      r.jumpMethod = null;
      r.describe = describe(r);

      if (!r.usable()) {
         Result fallback = fromFallback(loaded);
         if (fallback != null) {
            resolveChat(fallback, loaded);
            return fallback;
         }
         return null;
      }
      resolveChat(r, loaded);
      return r;
   }

   /**
    * Finds the player's chat-send method.
    *
    * <p>MCP clients keep {@code sendChatMessage(String)}. An Odin-flattened client has the same
    * method under a one-letter name, so the fallback is structural: among the methods on the player
    * class that take a single {@code String} and return {@code void}, exactly one is the chat sink —
    * the others are setters for the username/skin fields, which take different arities.
    *
    * <p>Failing to find it costs the {@code /bt} commands, nothing else, so this never throws and
    * never blocks the rest of the map.
    */
   private static void resolveChat(Result r, Class<?>[] loaded) {
      if (r == null || r.chatClass != null) {
         return;
      }
      String mcp = "net.minecraft.client.entity.EntityPlayerSP";
      Class<?> player = null;
      for (Class<?> type : loaded) {
         if (type != null && mcp.equals(type.getName())) {
            player = type;
            break;
         }
      }
      if (player != null && hasStringVoid(player, "sendChatMessage")) {
         r.chatClass = mcp.replace('.', '/');
         r.chatMethod = "sendChatMessage";
         r.chatDesc = "(Ljava/lang/String;)V";
         return;
      }

      // Player class from the mc field the earlier steps already resolved.
      if (player == null && r.mcClass != null && r.playerField != null) {
         Class<?> mc = find(loaded, r.mcClass);
         if (mc != null) {
            try {
               player = mc.getDeclaredField(r.playerField).getType();
            } catch (Throwable ignored) {
               player = null;
            }
         }
      }
      if (player == null) {
         return;
      }
      String only = singleStringVoid(player);
      if (only != null) {
         r.chatClass = player.getName().replace('.', '/');
         r.chatMethod = only;
         r.chatDesc = "(Ljava/lang/String;)V";
      }
   }

   private static boolean hasStringVoid(Class<?> type, String name) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         try {
            for (java.lang.reflect.Method m : current.getDeclaredMethods()) {
               if (m.getName().equals(name) && m.getReturnType() == void.class
                     && m.getParameterTypes().length == 1
                     && m.getParameterTypes()[0] == String.class) {
                  return true;
               }
            }
         } catch (Throwable ignored) {
            // treated as absent
         }
      }
      return false;
   }

   /** JVM descriptor for one parameter type. */
   private static String paramDesc(Class<?> type) {
      if (type == int.class) {
         return "I";
      }
      if (type == float.class) {
         return "F";
      }
      if (type == boolean.class) {
         return "Z";
      }
      if (type == long.class) {
         return "J";
      }
      if (type == double.class) {
         return "D";
      }
      if (type == byte.class) {
         return "B";
      }
      if (type == short.class) {
         return "S";
      }
      if (type == char.class) {
         return "C";
      }
      return "L" + type.getName().replace('.', '/') + ";";
   }

   /** True when this class itself declares a no-argument void method of that name. */
   private static boolean declaresNoArgVoid(Class<?> type, String name) {
      try {
         for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getReturnType() == void.class
                  && m.getParameterTypes().length == 0) {
               return true;
            }
         }
      } catch (Throwable ignored) {
         // treated as absent
      }
      return false;
   }

   /** True when the class chain declares a no-argument void method of that name. */
   private static boolean hasNoArgVoid(Class<?> type, String name) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         try {
            for (java.lang.reflect.Method m : current.getDeclaredMethods()) {
               if (m.getName().equals(name) && m.getReturnType() == void.class
                     && m.getParameterTypes().length == 0) {
                  return true;
               }
            }
         } catch (Throwable ignored) {
            // treated as absent
         }
      }
      return false;
   }

   /** The one {@code (String)V} method on the class chain, or null when it is ambiguous. */
   private static String singleStringVoid(Class<?> type) {
      String found = null;
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         try {
            for (java.lang.reflect.Method m : current.getDeclaredMethods()) {
               if (m.getReturnType() != void.class || m.getParameterTypes().length != 1
                     || m.getParameterTypes()[0] != String.class) {
                  continue;
               }
               if (found != null && !found.equals(m.getName())) {
                  return null;
               }
               found = m.getName();
            }
         } catch (Throwable ignored) {
            // keep walking
         }
      }
      return found;
   }

   private static Class<?> find(Class<?>[] loaded, String internalName) {
      String dotted = internalName.replace('/', '.');
      for (Class<?> type : loaded) {
         if (type != null && dotted.equals(type.getName())) {
            return type;
         }
      }
      return null;
   }

   // ---------------------------------------------------------------- steps

   /** Walks every loaded class looking for the single call site of {@code Display.update()}. */
   private static Frame findPresenter(ClassLoader loader, Class<?>[] loaded) {
      Frame best = null;
      for (Class<?> type : loaded) {
         if (type == null || type.isArray() || type.isPrimitive() || type.isInterface()) {
            continue;
         }
         String name = type.getName().replace('.', '/');
         if (name.startsWith("org/lwjgl/") || name.startsWith("java/") || name.startsWith("sun/")
               || name.startsWith("jdk/") || name.startsWith("com/bridger/")
               || name.startsWith("org/objectweb/")) {
            continue;
         }
         ClassNode cn = node(loader, name);
         if (cn == null) {
            continue;
         }
         for (MethodNode m : cn.methods) {
            int count = 0;
            for (AbstractInsnNode insn : m.instructions) {
               if (insn instanceof MethodInsnNode) {
                  MethodInsnNode mi = (MethodInsnNode) insn;
                  if ("org/lwjgl/opengl/Display".equals(mi.owner) && "update".equals(mi.name)
                        && "()V".equals(mi.desc)) {
                     count++;
                  }
               }
            }
            if (count > 0) {
               // The presenter is short (profiler sections around Display.update). A long method
               // that happens to contain it is not a frame boundary.
               int size = m.instructions.size();
               if (size <= 60 && (best == null || size < best.size)) {
                  best = new Frame(name, m.name, size);
               }
            }
         }
      }
      return best;
   }

   /** runTick is the heaviest no-arg void method the frame loop calls on itself. */
   private static String findRunTick(ClassLoader loader, Class<?>[] loaded, ClassNode mc,
         String updateDisplay) {
      // runGameLoop: the top-level caller of updateDisplay inside the same class.
      String loop = null;
      int loopSize = -1;
      for (Class<?> type : loaded) {
         if (type == null || type.isArray() || type.isPrimitive()) {
            continue;
         }
         if (!type.getName().replace('.', '/').equals(mc.name)) {
            continue;
         }
         for (MethodNode m : mc.methods) {
            for (AbstractInsnNode insn : m.instructions) {
               if (insn instanceof MethodInsnNode) {
                  MethodInsnNode mi = (MethodInsnNode) insn;
                  if (mc.name.equals(mi.owner) && updateDisplay.equals(mi.name)
                        && "()V".equals(mi.desc)) {
                     if (m.instructions.size() > loopSize) {
                        loopSize = m.instructions.size();
                        loop = m.name;
                     }
                  }
               }
            }
         }
         break;
      }
      if (loop == null) {
         return null;
      }

      // Inside runGameLoop, the largest self-call with a no-arg void signature is runTick.
      String tick = null;
      int tickSize = -1;
      for (MethodNode m : mc.methods) {
         if (!m.name.equals(loop)) {
            continue;
         }
         for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode) {
               MethodInsnNode mi = (MethodInsnNode) insn;
               if (!mc.name.equals(mi.owner) || !"()V".equals(mi.desc)) {
                  continue;
               }
               for (MethodNode candidate : mc.methods) {
                  if (candidate.name.equals(mi.name) && "()V".equals(candidate.desc)) {
                     if (candidate.instructions.size() > tickSize) {
                        tickSize = candidate.instructions.size();
                        tick = candidate.name;
                     }
                  }
               }
            }
         }
         break;
      }
      return tick;
   }

   private static final class Frame {
      final String className;
      final String methodName;
      final int size;

      Frame(String className, String methodName, int size) {
         this.className = className;
         this.methodName = methodName;
         this.size = size;
      }
   }

   // ---------------------------------------------------------------- shape tests

   private static boolean looksLikePlayer(ClassNode playerType, ClassLoader loader) {
      ClassNode current = playerType;
      int guard = 0;
      while (current != null && guard++ < 6) {
         if (hasPositionShape(current)) {
            return true;
         }
         current = current.superName == null ? null : node(loader, current.superName);
      }
      return false;
   }

   private static boolean hasPositionShape(ClassNode cn) {
      int doubles = 0;
      boolean bool = false;
      boolean setter = false;
      for (FieldNode f : cn.fields) {
         if ((f.access & Opcodes.ACC_STATIC) != 0) {
            continue;
         }
         if ("D".equals(f.desc)) {
            doubles++;
         } else if ("Z".equals(f.desc)) {
            bool = true;
         }
      }
      for (MethodNode m : cn.methods) {
         if (!"(DDD)V".equals(m.desc)) {
            continue;
         }
         setter = true;
         // The setter must actually assign, otherwise this is a ray trace helper.
         for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD
                  && "D".equals(((FieldInsnNode) insn).desc)) {
               return doubles >= 3 && bool;
            }
         }
      }
      return false;
   }

   /**
    * Reads the three doubles the position setter assigns, in parameter order.
    *
    * <p>When the setter just forwards to a superclass version (1.8.9 does this through
    * {@code setPositionAndRotation}), the chain is followed once. Declaration order is never used as
    * a fallback: {@code renderDistanceWeight} and {@code prevPos*} come first, and picking those
    * silently tracks the wrong numbers.
    */
   private static void readPosition(ClassNode playerType, ClassLoader loader, Result r) {
      ClassNode current = playerType;
      int guard = 0;
      while (current != null && guard++ < 6) {
         for (MethodNode m : current.methods) {
            if (!"(DDD)V".equals(m.desc)) {
               continue;
            }
            String[] fields = positionFromSetter(m);
            if (fields != null) {
               r.posX = fields[0];
               r.posY = fields[1];
               r.posZ = fields[2];
               r.onGroundField = booleanField(current);
               return;
            }
         }
         current = current.superName == null ? null : node(loader, current.superName);
      }
   }

   private static String[] positionFromSetter(MethodNode m) {
      String[] out = new String[3];
      int lastDload = -1;
      for (AbstractInsnNode insn : m.instructions) {
         if (insn instanceof VarInsnNode && insn.getOpcode() == Opcodes.DLOAD) {
            lastDload = ((VarInsnNode) insn).var;
         }
         if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD
               && "D".equals(((FieldInsnNode) insn).desc)) {
            String field = ((FieldInsnNode) insn).name;
            if (lastDload == 1 && out[0] == null) {
               out[0] = field;
            } else if (lastDload == 3 && out[1] == null) {
               out[1] = field;
            } else if (lastDload == 5 && out[2] == null) {
               out[2] = field;
            }
            lastDload = -1;
         }
      }
      return out[0] != null && out[1] != null && out[2] != null ? out : null;
   }

   private static String booleanField(ClassNode cn) {
      for (ClassNode current = cn; current != null; current = null) {
         for (FieldNode f : current.fields) {
            if ((f.access & Opcodes.ACC_STATIC) == 0 && "Z".equals(f.desc)
                  && "onGround".equals(f.name)) {
               return f.name;
            }
         }
      }
      // Obfuscated: take the first boolean declared next to the position doubles.
      for (FieldNode f : cn.fields) {
         if ((f.access & Opcodes.ACC_STATIC) == 0 && "Z".equals(f.desc)) {
            return f.name;
         }
      }
      return null;
   }

   private static String selfField(ClassNode mc) {
      for (FieldNode f : mc.fields) {
         if ((f.access & Opcodes.ACC_STATIC) != 0 && ("L" + mc.name + ";").equals(f.desc)) {
            return f.name;
         }
      }
      return null;
   }

   // ---------------------------------------------------------------- plumbing

   private static ClassLoader loaderOf(Class<?>[] loaded) {
      for (Class<?> type : loaded) {
         if (type != null && "org.lwjgl.opengl.Display".equals(type.getName())) {
            return type.getClassLoader();
         }
      }
      return null;
   }

   private static ClassNode node(ClassLoader loader, String internalName) {
      if (loader == null || internalName == null) {
         return null;
      }
      String resource = internalName + ".class";
      try (InputStream in = loader.getResourceAsStream(resource)) {
         if (in == null) {
            return null;
         }
         byte[] bytes = readAll(in);
         ClassNode cn = new ClassNode();
         new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
         return cn;
      } catch (Throwable t) {
         return null;
      }
   }

   private static byte[] readAll(InputStream in) throws java.io.IOException {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
         out.write(buffer, 0, n);
      }
      return out.toByteArray();
   }

   /**
    * Last resort: the measured Badlion map with no structural validation.
    *
    * <p>Reached only when {@link #findPresenter} found no {@code Display.update()} caller, which on
    * BLClient means the scan itself failed rather than the names being wrong.
    */
   private static Result fromFallback(Class<?>[] loaded) {
      if (find(loaded, BLC[0]) == null) {
         return null;
      }
      Result r = new Result();
      r.mcClass = BLC[0];
      r.instanceField = BLC[1];
      r.updateDisplay = BLC[2];
      r.runTick = BLC[3];
      r.playerField = BLC[4];
      r.worldField = BLC[5];
      r.posX = BLC[6];
      r.posY = BLC[7];
      r.posZ = BLC[8];
      r.onGroundField = BLC[9];
      r.jumpClass = BLC[10];
      r.jumpMethod = BLC[11];
      r.jumpDesc = "()V";
      r.guiClass = BLC[12];
      r.guiMethod = BLC[13];
      r.guiDesc = BLC[14];
      r.controllerClass = BLC[15];
      r.controllerMethod = BLC[16];
      r.controllerDesc = "*";
      r.fontClass = BLC[17];
      r.fontMethod = BLC[18];
      r.fontDesc = BLC[19];
      r.chatClass = BLC[20];
      r.chatMethod = BLC[21];
      r.chatDesc = BLC[22];
      r.itemBlockClass = BLC[23];
      r.itemGetterName = BLC[24];
      r.glStateClass = BLC[25];
      r.framebufferClass = "bfw";
      r.framebufferRenderMethod = "c";
      r.settingsField = "t";
      r.tabKeyField = "al";
      r.keyDownMethod = "d";
      r.guiScaleField = "aL";
      r.framebufferField = "aF";
      r.obf = true;
      r.groundTrusted = true;
      r.describe = describe(r) + " [hardcoded BLClient map]";
      return r;
   }

   private static String describe(Result r) {
      return "mc=" + r.mcClass + " instance=" + r.instanceField + " draw=" + r.updateDisplay
         + " tick=" + r.runTick
         + " player=" + r.playerField + " pos=(" + r.posX + "," + r.posY + "," + r.posZ + ")"
         + " ground=" + r.onGroundField
         + " controller=" + r.controllerClass + "." + r.controllerMethod
         + " font=" + r.fontClass + "." + r.fontMethod;
   }
}
