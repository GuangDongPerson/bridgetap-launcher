package com.bridger.compat.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the game's own objects without any name mapping.
 *
 * <p>Every 1.8.9 client keeps the vanilla shape even after re-obfuscation: one class holds a static
 * reference to itself, that object owns a player reference, and the player inherits a class that
 * declares three consecutive doubles plus an on-ground boolean plus a {@code (DDD)V} setter. Those
 * structural facts are what this class keys on, so the same code works on vanilla, Badlion and
 * Lunar without per-build offset tables.
 */
final class Discovery {
   static final class Game {
      Class<?> minecraftClass;
      Object minecraft;
      Field instanceField;
      Field playerField;
      Field xField;
      Field yField;
      Field zField;
      Field onGroundField;
      String describe;

      Object player() {
         try {
            return this.playerField.get(this.minecraft);
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

      boolean onGround(Object player) {
         try {
            return this.onGroundField.getBoolean(player);
         } catch (Throwable t) {
            return false;
         }
      }
   }

   private Discovery() {
   }

   static Game resolve(Class<?>[] loaded) {
      List<Class<?>> candidates = new ArrayList<Class<?>>();
      for (Class<?> type : loaded) {
         if (type == null || type.isInterface() || type.isArray() || type.isPrimitive()) {
            continue;
         }
         try {
            if (selfInstanceField(type) != null) {
               candidates.add(type);
            }
         } catch (Throwable ignored) {
            // classes with unresolvable optional dependencies can throw here
         }
      }

      Game best = null;
      int bestDepth = -1;
      for (Class<?> candidate : candidates) {
         Game game = inspect(candidate);
         if (game == null) {
            continue;
         }
         int depth = superDepth(game.playerField.getType(), game.onGroundField.getDeclaringClass());
         if (depth > bestDepth) {
            bestDepth = depth;
            best = game;
         }
      }
      return best;
   }

   private static Game inspect(Class<?> minecraftClass) {
      Field instanceField = selfInstanceField(minecraftClass);
      if (instanceField == null || !Modifier.isStatic(instanceField.getModifiers())) {
         return null;
      }

      Object minecraft;
      try {
         instanceField.setAccessible(true);
         minecraft = instanceField.get(null);
      } catch (Throwable t) {
         return null;
      }
      if (minecraft == null) {
         return null;
      }

      Field bestPlayer = null;
      Class<?> entityBase = null;
      int bestDepth = -1;

      for (Field field : minecraftClass.getDeclaredFields()) {
         if (Modifier.isStatic(field.getModifiers())) {
            continue;
         }
         Class<?> base = entityBase(field.getType());
         if (base == null) {
            continue;
         }
         int depth = superDepth(field.getType(), base);
         if (depth > bestDepth) {
            bestDepth = depth;
            bestPlayer = field;
            entityBase = base;
         }
      }

      if (bestPlayer == null || entityBase == null) {
         return null;
      }

      List<Field> doubles = new ArrayList<Field>();
      Field onGround = null;
      for (Field field : entityBase.getDeclaredFields()) {
         if (Modifier.isStatic(field.getModifiers())) {
            continue;
         }
         if (field.getType() == double.class && doubles.size() < 3) {
            doubles.add(field);
         }
         if (onGround == null && field.getType() == boolean.class) {
            onGround = field;
         }
      }
      if (doubles.size() < 3 || onGround == null) {
         return null;
      }

      // Declaration order puts renderDistanceWeight and prevPos* before the real coordinates, so
      // the authoritative answer is "what the (DDD)V setter writes".
      String[] named = BytecodeView.positionFields(entityBase);
      Field x;
      Field y;
      Field z;
      try {
         if (named != null) {
            x = entityBase.getDeclaredField(named[0]);
            y = entityBase.getDeclaredField(named[1]);
            z = entityBase.getDeclaredField(named[2]);
         } else {
            x = doubles.get(0);
            y = doubles.get(1);
            z = doubles.get(2);
         }
      } catch (Throwable t) {
         return null;
      }

      Game game = new Game();
      game.minecraftClass = minecraftClass;
      game.minecraft = minecraft;
      game.instanceField = instanceField;
      game.playerField = bestPlayer;
      game.xField = x;
      game.yField = y;
      game.zField = z;
      game.onGroundField = onGround;

      try {
         for (Field field : new Field[] {instanceField, bestPlayer, game.xField, game.yField, game.zField, onGround}) {
            field.setAccessible(true);
         }
      } catch (Throwable t) {
         return null;
      }

      game.describe = minecraftClass.getName() + " | player=" + bestPlayer.getType().getName()
         + " (" + bestPlayer.getName() + ") | pos=(" + game.xField.getName() + "," + game.yField.getName()
         + "," + game.zField.getName() + ") | ground=" + onGround.getName();
      return game;
   }

   private static Field selfInstanceField(Class<?> type) {
      try {
         for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
               return field;
            }
         }
      } catch (Throwable ignored) {
         return null;
      }
      return null;
   }

   /** Deepest ancestor that looks like the entity root. */
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
         for (Method method : type.getDeclaredMethods()) {
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

   private static int superDepth(Class<?> type, Class<?> stopAt) {
      int depth = 0;
      Class<?> current = type;
      while (current != null && current != stopAt) {
         current = current.getSuperclass();
         depth++;
      }
      return depth;
   }
}
