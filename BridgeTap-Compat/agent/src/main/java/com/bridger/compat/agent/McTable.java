package com.bridger.compat.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflects over {@code net.minecraft.client.Minecraft} and writes what it finds.
 *
 * <p>The class name survives remapping, but method names may not: a client can keep MCP names, use
 * Searge names ({@code s()}), or something of its own. Guessing wrong means an injection that
 * silently matches nothing, which is exactly the failure being debugged here - so read the real
 * method table instead of assuming {@code runGameLoop} exists.
 *
 * <p>Carries a name no earlier build contained, so a repeat injection into a live JVM still runs it.
 */
public final class McTable {
   private McTable() {
   }

   /**
    * @param type the game's main class, handed over by the native payload
    *
    * <p>Takes a {@code Class} rather than looking one up: the client's loader refuses to resolve
    * {@code net.minecraft.client.Minecraft} for outside callers (it answers
    * {@code ClassNotFoundException} even though the class is loaded), but a {@code Class} object
    * crosses loader boundaries freely because {@code java.lang.Class} is bootstrap-visible.
    */
   public static String run(Class<?> type) {
      try {
         if (type == null) {
            return "null class";
         }
         StringBuilder summary = new StringBuilder();

         File dir = new File(logBase(), "BridgeTap/dump");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return "cannot create " + dir;
         }
         File out = new File(dir, "minecraft-class.txt");
         try (PrintWriter writer = new PrintWriter(
               new OutputStreamWriter(new FileOutputStream(out), "UTF-8"))) {
            writer.println("# " + type.getName() + " loader=" + describeLoader(type));
            writer.println("# super=" + type.getSuperclass());
            writer.println();
            writer.println("## static fields (singleton candidate has the same type as the class)");
            for (Field field : type.getDeclaredFields()) {
               if (!Modifier.isStatic(field.getModifiers())) {
                  continue;
               }
               String mark = field.getType() == type ? "  <== SELF" : "";
               writer.println(Modifier.toString(field.getModifiers()) + " " + field.getType().getName()
                  + " " + field.getName() + mark);
            }
            writer.println();
            writer.println("## instance fields of reference type");
            int refFields = 0;
            for (Field field : type.getDeclaredFields()) {
               if (Modifier.isStatic(field.getModifiers())) {
                  continue;
               }
               Class<?> t = field.getType();
               if (t.isPrimitive() || t.isArray()) {
                  continue;
               }
               writer.println("  " + t.getName() + " " + field.getName());
               refFields++;
               if (refFields > 120) {
                  writer.println("  ...");
                  break;
               }
            }
            writer.println();
            writer.println("## methods ()V - frame loop candidates live here");
            int noArgVoid = 0;
            for (Method method : type.getDeclaredMethods()) {
               if (method.getParameterTypes().length == 0 && method.getReturnType() == void.class) {
                  writer.println("  " + Modifier.toString(method.getModifiers()) + " "
                     + method.getName() + "()");
                  noArgVoid++;
               }
            }
            writer.println();
            writer.println("## all declared methods");
            for (Method method : type.getDeclaredMethods()) {
               writer.println("  " + method.getName() + describe(method.getParameterTypes())
                  + " -> " + method.getReturnType().getName());
            }
            writer.println();
            writer.println("## constructors");
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
               writer.println("  " + describe(ctor.getParameterTypes()));
            }

            summary.append(type.getDeclaredMethods().length).append(" methods, ")
               .append(noArgVoid).append(" no-arg void, ")
               .append(type.getDeclaredFields().length).append(" fields");
         }
         String text = summary + " -> " + out.getAbsolutePath();
         note("mc table: " + text);
         return text;
      } catch (Throwable t) {
         note("mc table failed: " + t);
         return "failed: " + t;
      }
   }

   private static String describe(Class<?>[] params) {
      StringBuilder sb = new StringBuilder("(");
      for (int i = 0; i < params.length; i++) {
         if (i > 0) {
            sb.append(", ");
         }
         sb.append(params[i].getName());
      }
      return sb.append(")").toString();
   }

   private static String describeLoader(Class<?> type) {
      ClassLoader loader = type.getClassLoader();
      return loader == null ? "bootstrap"
         : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
   }

   /** File-based, because this class may be loaded by the client's own loader, which cannot see
    * the agent's logger. */
   private static void note(String line) {
      try {
         File dir = new File(logBase(), "BridgeTap");
         if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
         }
         try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
               new FileOutputStream(new File(dir, "agent.log"), true), "UTF-8"))) {
            writer.println("[BridgeTap] " + line);
         }
      } catch (Throwable ignored) {
         // diagnostics only
      }
   }

   private static String logBase() {
      String base = System.getenv("LOCALAPPDATA");
      if (base == null || base.isEmpty()) {
         base = System.getProperty("java.io.tmpdir");
      }
      return base;
   }
}
