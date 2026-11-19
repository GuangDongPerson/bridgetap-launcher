package com.bridger.compat.agent;

import com.bridger.compat.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Live binding to the running client's own game object.
 *
 * <p>The field names come from {@link ClassScan}, not from a guess: on Lunar they are
 * {@code thePlayer} / {@code posX,posY,posZ} / {@code onGround}, on Badlion they are
 * {@code h} / {@code bq,br,bs} / {@code bw}. Everything downstream reads through this object, so the
 * only place that has to know about a client's naming scheme is the scan.
 *
 * <p>When the scan produced nothing, the old MCP-name and shape-based fallbacks still apply, which
 * keeps a half-broken scan from turning into a dead HUD.
 *
 * <p>Every binding is validated against world limits before it is used. A wrong guess must show up
 * as "no data", never as a HUD scrolling nonsense coordinates through the middle of a bridge.
 */
final class GameBinding {
   /** Minecraft world limits; anything outside means we latched onto the wrong field. */
   private static final double MAX_HORIZONTAL = 3.0E7D;
   private static final double MIN_Y = -512.0D;
   private static final double MAX_Y = 4096.0D;
   /** Ticks of observation before the onGround field is fixed. */
   private static final int OBSERVATIONS = 40;

   final Object mc;
   final Class<?> mcClass;
   final Field playerField;
   final Field xField;
   final Field yField;
   final Field zField;
   final Field onGroundField;
   private final ClassScan.Result map;
   /**
    * Every boolean on the entity chain. {@code onGround} is picked out of these by observation, not
    * by name: on Badlion the field has no name and the first guess ({@code bw}) is read 8 times in
    * the whole jar, while a real {@code onGround} is read over a hundred. A wrong pick is
    * indistinguishable from "the player never stands on anything", which silently kills every
    * tick-derived decision.
    */
   private final Field[] booleanFields;
   private final int[] booleanScores;
   /** How often each candidate read {@code true}; a constant flag cannot be {@code onGround}. */
   private final int[] booleanTrue;
   /**
    * True when {@code onGround} was found under its real name.
    *
    * <p>An MCP-named client hands us the field outright; there is nothing to guess and guessing made
    * it worse. The observation heuristic below scores every boolean against "did the height stay
    * put", and several unrelated flags track that just as well as the real one — on Lunar,
    * {@code spawnForced} scored 39/40 next to {@code onGround}'s 39/40 and won on order, which
    * pinned the tracker to a flag that is permanently true. Every jump was then invisible
    * ({@code jumpCount=0}) while the void check still fired.
    */
   private final boolean groundNamed;
   private Field resolvedGround;
   private double lastObservedY = Double.NaN;
   private int observations;
   /** The client's own font, so the overlay renders with the active resource pack. */
   final Field fontRendererField;
   /** {@code GameSettings.keyBindPlayerList} - the tab-list key, for yielding to the player list. */
   private final Field tabKeyField;
   private final Object gameSettings;
   private final Method tabKeyDown;
   final String describe;

   private GameBinding(Object mc, Class<?> mcClass, Field playerField, Field xField, Field yField,
         Field zField, Field onGroundField, Field fontRendererField, String describe,
         boolean trustedGround, ClassScan.Result map) {
      this.map = map;
      this.mc = mc;
      this.mcClass = mcClass;
      this.playerField = playerField;
      this.xField = xField;
      this.yField = yField;
      this.zField = zField;
      this.onGroundField = onGroundField;
      this.booleanFields = collectBooleans(mcClass, playerField.getType());
      this.booleanScores = new int[this.booleanFields.length];
      this.booleanTrue = new int[this.booleanFields.length];
      this.groundNamed = onGroundField != null
         && ("onGround".equals(onGroundField.getName()) || trustedGround);
      this.fontRendererField = fontRendererField;
      this.describe = describe;

      // The tab list is drawn by the client's own HUD pass, before the overlay's hook point, so the
      // panel would cover it. There is no portable injection point ahead of it (Lunar replaces the
      // whole tab-list renderer with a private class), but the key state is readable: while the
      // player list key is held, the overlay stands down.
      Field settings = field(mcClass,
         this.map != null && this.map.settingsField != null ? this.map.settingsField : "gameSettings");
      Object settingsValue = null;
      Field tabKey = null;
      Method keyDown = null;
      try {
         if (settings != null) {
            settings.setAccessible(true);
            settingsValue = settings.get(mc);
         }
         if (settingsValue != null) {
            tabKey = field(settingsValue.getClass(),
               this.map != null && this.map.tabKeyField != null
                  ? this.map.tabKeyField : "keyBindPlayerList");
            if (tabKey != null) {
               tabKey.setAccessible(true);
               keyDown = methodByName(tabKey.getType(),
                  this.map != null && this.map.keyDownMethod != null
                     ? new String[] {this.map.keyDownMethod, "isKeyDown", "func_151468_f"}
                     : new String[] {"isKeyDown", "func_151468_f"});
            }
         }
      } catch (Throwable ignored) {
         // no player-list suppression; the overlay simply stays on top as before
      }
      this.gameSettings = settingsValue;
      this.tabKeyField = tabKey;
      this.tabKeyDown = keyDown;
      Log.info("player-list key: " + (settingsValue == null ? "no gameSettings field"
         : tabKey == null ? "no keyBindPlayerList field"
         : keyDown == null ? "no isKeyDown method on " + tabKey.getType().getName()
         : tabKey.getName() + "." + keyDown.getName() + " (overlay yields while held)"));
   }

   /** True while the player-list key is held, i.e. while the tab list is on screen. */
   /**
    * The client's GUI scale factor, or 0 when it is on "auto".
    *
    * <p>The overlay draws in GUI-scaled pixels - the same space {@code hudX/hudY} were tuned in -
    * and it has to set up that projection itself, because on Badlion the hook point sits after every
    * renderer, where the projection is the 3D one.
    */
   /**
    * The language the client itself selected ({@code GameSettings.language} in 1.8.9), or null when
    * it cannot be read.
    *
    * <p>The field name differs between the two clients (MCP calls it {@code language}, Badlion
    * obfuscates it into something else), so it is found by "the shape of the value": the String field
    * that looks like {@code zh_CN} is the one.
    *
    * 客户端自己选的语言（1.8.9 的 {@code GameSettings.language}），读不到返回 null。
    *
    * <p>字段名在两个客户端上不一致（MCP 是 {@code language}，Badlion 混淆成别的），
    * 所以按「值的形状」找：形如 {@code zh_CN} 的那个 String 字段就是它。
    */
   String gameLanguage() {
      Object settings = this.gameSettings;
      if (settings == null) {
         return null;
      }
      try {
         Field[] fields = settings.getClass().getFields();
         for (int i = 0; i < fields.length; i++) {
            if (fields[i].getType() == String.class) {
               Object value = fields[i].get(settings);
               if (value instanceof String && looksLikeLocale((String) value)) {
                  return (String) value;
               }
            }
         }
      } catch (Throwable t) {
         Log.verbose("gameSettings.language unreadable: " + t);
      }
      return null;
   }

   private static boolean looksLikeLocale(String value) {
      if (value == null || value.length() < 2 || value.length() > 10) {
         return false;
      }
      return value.matches("[a-z]{2,3}(_[A-Z]{2,4})?");
   }

   int guiScale() {
      Object settings = this.gameSettings;
      if (settings == null || this.map == null || this.map.guiScaleField == null) {
         return 0;
      }
      Field field = field(settings.getClass(), this.map.guiScaleField);
      if (field == null || field.getType() != int.class) {
         return 0;
      }
      try {
         field.setAccessible(true);
         return field.getInt(settings);
      } catch (Throwable t) {
         return 0;
      }
   }

   /** The offscreen framebuffer this frame is rendered into, or null when it cannot be read. */
   Object framebuffer() {
      if (this.map == null || this.map.framebufferField == null) {
         return null;
      }
      Field field = field(this.mcClass, this.map.framebufferField);
      if (field == null) {
         return null;
      }
      try {
         field.setAccessible(true);
         return field.get(this.mc);
      } catch (Throwable t) {
         return null;
      }
   }

   boolean isTabListKeyDown() {
      if (this.gameSettings == null || this.tabKeyField == null || this.tabKeyDown == null) {
         return false;
      }
      try {
         Object key = this.tabKeyField.get(this.gameSettings);
         if (key == null) {
            return false;
         }
         return Boolean.TRUE.equals(this.tabKeyDown.invoke(key));
      } catch (Throwable t) {
         return false;
      }
   }

   private static Method methodByName(Class<?> type, String[] names) {
      for (String name : names) {
         try {
            Method m = type.getMethod(name);
            m.setAccessible(true);
            return m;
         } catch (Throwable ignored) {
            // try the next candidate
         }
      }
      return null;
   }

   /** @return null when this object does not look like a game root we can read */
   static GameBinding of(Object mc, ClassScan.Result map) {
      if (mc == null) {
         return null;
      }
      Class<?> mcClass = mc.getClass();

      Field playerField = map != null && map.playerField != null
         ? field(mcClass, map.playerField) : field(mcClass, "thePlayer");
      // A measured map names the player field directly; the name check below only exists for the
      // scan path, where "is this a player" has to be inferred from the name.
      boolean playerTrusted = map != null && map.obf && playerField != null;
      if (playerField == null
            || (!playerTrusted && !playerField.getType().getName().contains("EntityPlayer"))) {
         Field byShape = playerLike(mcClass);
         if (byShape != null) {
            playerField = byShape;
         }
      }
      if (playerField == null) {
         return null;
      }

      Class<?> playerType = playerField.getType();
      Class<?> entityBase = entityBase(playerType);

      Field x = map != null && map.posX != null ? field(playerType, map.posX) : field(playerType, "posX");
      Field y = map != null && map.posY != null ? field(playerType, map.posY) : field(playerType, "posY");
      Field z = map != null && map.posZ != null ? field(playerType, map.posZ) : field(playerType, "posZ");
      String positionSource = "scan";
      if (x == null || y == null || z == null) {
         positionSource = "setter";
         String[] fromSetter = entityBase == null ? null : BytecodeView.positionFields(entityBase);
         if (fromSetter == null) {
            return null;
         }
         x = field(playerType, fromSetter[0]);
         y = field(playerType, fromSetter[1]);
         z = field(playerType, fromSetter[2]);
      }
      if (x == null || y == null || z == null) {
         return null;
      }

      Field ground = map != null && map.onGroundField != null
         ? field(playerType, map.onGroundField) : field(playerType, "onGround");
      if (ground == null || ground.getType() != boolean.class) {
         ground = firstBoolean(entityBase != null ? entityBase : playerType);
      }


      Field font = fontOfType(mcClass, map == null ? null : map.fontClass);

      try {
         playerField.setAccessible(true);
         if (font != null) {
            font.setAccessible(true);
         }
         x.setAccessible(true);
         y.setAccessible(true);
         z.setAccessible(true);
         if (ground != null) {
            ground.setAccessible(true);
         }
      } catch (Throwable t) {
         return null;
      }

      String describe = mcClass.getName() + " | player=" + playerType.getName()
         + " (" + playerField.getName() + ") | pos=(" + x.getName() + "," + y.getName() + ","
         + z.getName() + ") via " + positionSource + " | ground="
         + (ground == null ? "none" : ground.getName())
         + " | font=" + (font == null ? "none" : font.getName());

      // The field object is looked up twice, so compare by name rather than identity.
      boolean groundTrusted = map != null && map.groundTrusted && ground != null
         && map.onGroundField != null && map.onGroundField.equals(ground.getName());
      GameBinding binding = new GameBinding(mc, mcClass, playerField, x, y, z, ground, font, describe,
         groundTrusted, map);

      Object player = binding.player();
      dumpBooleans(playerType, entityBase, player, ground);
      if (player != null) {
         double[] probe = binding.position(player);
         if (probe == null || !plausible(probe)) {
            return null;
         }
      }
      return binding;
   }

   Object player() {
      try {
         return this.playerField.get(this.mc);
      } catch (Throwable t) {
         return null;
      }
   }

   double[] position(Object player) {
      try {
         return new double[] {
            this.xField.getDouble(player),
            this.yField.getDouble(player),
            this.zField.getDouble(player)
         };
      } catch (Throwable t) {
         return null;
      }
   }

   Object fontRenderer() {
      if (this.fontRendererField == null) {
         return null;
      }
      try {
         return this.fontRendererField.get(this.mc);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Whether the player is standing on something.
    *
    * <p>The field is chosen by comparing every boolean on the entity against a fact we can measure
    * directly: the height does not change between ticks while standing, and changes on every tick
    * while airborne. After a few dozen ticks the field that agrees with that most often is the
    * real one, whatever the client called it.
    */
   boolean onGround(Object player, double y) {
      if (!this.groundNamed && this.resolvedGround == null && this.booleanFields.length > 1) {
         observe(player, y);
      }
      Field field = this.resolvedGround != null ? this.resolvedGround : this.onGroundField;
      if (field == null) {
         return false;
      }
      try {
         return field.getBoolean(player);
      } catch (Throwable t) {
         return false;
      }
   }

   private void observe(Object player, double y) {
      if (!Double.isNaN(this.lastObservedY) && this.observations < OBSERVATIONS) {
         boolean steady = Math.abs(y - this.lastObservedY) < 0.005D;
         for (int i = 0; i < this.booleanFields.length; i++) {
            try {
               boolean value = this.booleanFields[i].getBoolean(player);
               if (value) {
                  this.booleanTrue[i]++;
               }
               if (value == steady) {
                  this.booleanScores[i]++;
               }
            } catch (Throwable ignored) {
               // a field we cannot read simply scores nothing
            }
         }
         this.observations++;
         if (this.observations >= OBSERVATIONS) {
            int best = -1;
            int bestScore = -1;
            StringBuilder report = new StringBuilder();
            for (int i = 0; i < this.booleanFields.length; i++) {
               report.append(this.booleanFields[i].getName()).append(':')
                  .append(this.booleanScores[i]).append('/').append(this.booleanTrue[i]).append(' ');
               // A flag that never changed during observation is not a ground state: it scored well
               // only because the player stood still for most of the window.
               boolean varies = this.booleanTrue[i] > 0 && this.booleanTrue[i] < this.observations;
               if (!varies) {
                  continue;
               }
               if (this.booleanScores[i] > bestScore) {
                  bestScore = this.booleanScores[i];
                  best = i;
               }
            }
            if (best >= 0) {
               this.resolvedGround = this.booleanFields[best];
               Log.info("onGround resolved to " + this.resolvedGround.getDeclaringClass().getSimpleName()
                  + "." + this.resolvedGround.getName() + " after " + OBSERVATIONS
                  + " ticks (" + bestScore + "/" + OBSERVATIONS + " agree); scores: " + report);
            } else {
               Log.warn("no varying boolean found; keeping " + this.onGroundField.getName()
                  + " (scores: " + report + ")");
            }
         }
      }
      this.lastObservedY = y;
   }

   /** Every non-static boolean visible on the game object and the player, superclasses included. */
   private static Field[] collectBooleans(Class<?> mcClass, Class<?> playerType) {
      java.util.List<Field> out = new java.util.ArrayList<Field>();
      for (Class<?> type : new Class<?>[] {playerType, mcClass}) {
         for (Class<?> current = type; current != null && current != Object.class;
               current = current.getSuperclass()) {
            try {
               for (Field candidate : current.getDeclaredFields()) {
                  if (Modifier.isStatic(candidate.getModifiers())
                        || candidate.getType() != boolean.class) {
                     continue;
                  }
                  try {
                     candidate.setAccessible(true);
                  } catch (Throwable t) {
                     continue;
                  }
                  out.add(candidate);
               }
            } catch (Throwable ignored) {
               // skip this level
            }
         }
      }
      return out.toArray(new Field[0]);
   }

   /**
    * Logs every boolean on the player's entity classes, once, with the one currently in use marked.
    *
    * <p>{@code onGround} has no name to look up on an obfuscated client, so it is picked
    * structurally - and a wrong pick is invisible in the HUD: {@code wasInAir} stays true forever,
    * the "landed for more than 4 ticks" rule fires on every landing, and the run restarts with every
    * placed block. Printing the whole set against a known standing position turns that guess into a
    * check: the right field reads {@code true} while the feet are on a block.
    */
   private static void dumpBooleans(Class<?> playerType, Class<?> entityBase, Object player,
         Field chosen) {
      try {
         StringBuilder line = new StringBuilder("boolean fields on ")
            .append(entityBase == null ? playerType.getName() : entityBase.getName()).append(": ");
         Class<?> type = entityBase == null ? playerType : entityBase;
         for (Class<?> current = type; current != null && current != Object.class;
               current = current.getSuperclass()) {
            for (Field candidate : current.getDeclaredFields()) {
               if (Modifier.isStatic(candidate.getModifiers()) || candidate.getType() != boolean.class) {
                  continue;
               }
               boolean accessible = true;
               try {
                  candidate.setAccessible(true);
               } catch (Throwable t) {
                  accessible = false;
               }
               Object value = "?";
               if (accessible) {
                  try {
                     value = Boolean.valueOf(candidate.getBoolean(player));
                  } catch (Throwable ignored) {
                     // stays "?"
                  }
               }
               line.append(current.getSimpleName()).append('.').append(candidate.getName())
                  .append('=').append(value);
               if (candidate.equals(chosen)) {
                  line.append(" (in use)");
               }
               line.append("  ");
            }
         }
         Log.info(line.toString());
      } catch (Throwable t) {
         Log.verbose("boolean dump failed: " + t);
      }
   }

   private static boolean plausible(double[] pos) {
      for (double value : pos) {
         if (Double.isNaN(value) || Double.isInfinite(value)) {
            return false;
         }
      }
      return Math.abs(pos[0]) <= MAX_HORIZONTAL
         && Math.abs(pos[2]) <= MAX_HORIZONTAL
         && pos[1] >= MIN_Y && pos[1] <= MAX_Y;
   }

   /** Field by name on the class or any superclass (1.8.9 puts posX on Entity, not on the player). */
   private static Field field(Class<?> type, String name) {
      if (name == null) {
         return null;
      }
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         try {
            return current.getDeclaredField(name);
         } catch (Throwable ignored) {
            // keep walking up
         }
      }
      return null;
   }

   /** The font field, matched by type rather than name: Badlion calls it {@code k}. */
   private static Field fontOfType(Class<?> mcClass, String typeName) {
      try {
         for (Field candidate : mcClass.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())) {
               continue;
            }
            String name = candidate.getType().getName();
            if (typeName != null && name.equals(typeName)) {
               return candidate;
            }
            if (typeName == null && name.contains("FontRenderer")) {
               return candidate;
            }
         }
      } catch (Throwable ignored) {
         // reported as font=none
      }
      return field(mcClass, "fontRendererObj");
   }

   /** The instance field whose type most deeply resembles a player entity. */
   private static Field playerLike(Class<?> mcClass) {
      Field best = null;
      int bestDepth = -1;
      try {
         for (Field candidate : mcClass.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())) {
               continue;
            }
            Class<?> base = entityBase(candidate.getType());
            if (base == null) {
               continue;
            }
            int depth = depth(candidate.getType(), base);
            if (depth > bestDepth) {
               bestDepth = depth;
               best = candidate;
            }
         }
      } catch (Throwable t) {
         return null;
      }
      return best;
   }

   private static Class<?> entityBase(Class<?> type) {
      Class<?> current = type;
      Class<?> found = null;
      while (current != null && current != Object.class) {
         if (looksLikeEntity(current)) {
            found = current;
         }
         current = current.getSuperclass();
      }
      return found;
   }

   private static boolean looksLikeEntity(Class<?> type) {
      int doubles = 0;
      boolean onGround = false;
      boolean setter = false;
      try {
         for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
               continue;
            }
            if (field.getType() == double.class) {
               doubles++;
            } else if (field.getType() == boolean.class) {
               onGround = true;
            }
         }
         for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3 && params[0] == double.class && params[1] == double.class
               && params[2] == double.class) {
               setter = true;
               break;
            }
         }
      } catch (Throwable t) {
         return false;
      }
      return doubles >= 3 && onGround && setter;
   }

   private static Field firstBoolean(Class<?> type) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         try {
            for (Field field : current.getDeclaredFields()) {
               if (!Modifier.isStatic(field.getModifiers()) && field.getType() == boolean.class) {
                  return field;
               }
            }
         } catch (Throwable ignored) {
            // keep walking up
         }
      }
      return null;
   }

   private static int depth(Class<?> type, Class<?> stopAt) {
      int depth = 0;
      Class<?> current = type;
      while (current != null && current != stopAt) {
         current = current.getSuperclass();
         depth++;
      }
      return depth;
   }
}
