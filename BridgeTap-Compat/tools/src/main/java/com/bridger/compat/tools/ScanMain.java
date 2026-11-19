package com.bridger.compat.tools;

import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Offline structural scanner.
 *
 * <p>Reads a client jar (vanilla 1.8.9, Badlion, Lunar — the algorithm does not care) and reports
 * the anchors the agent needs at runtime: the singleton game class, the player field, the entity
 * base with its three doubles and on-ground flag. It is the same predicate set used by
 * {@code com.bridger.compat.agent.Discovery}, but running over bytecode instead of live reflection,
 * so it can be used on jars that cannot be loaded (missing LWJGL/Mojang dependencies).
 *
 * <pre>
 *   java -jar bridgetap-tools.jar scan &lt;client.jar&gt; [profile.json]
 * </pre>
 */
public final class ScanMain {
   private static final String DISPLAY = "org/lwjgl/opengl/Display";

   private ScanMain() {
   }

   public static void main(String[] args) throws Exception {
      if (args.length >= 2 && "scan".equals(args[0])) {
         File jar = new File(args[1]);
         if (!jar.isFile()) {
            System.err.println("no such jar: " + jar);
            System.exit(2);
         }
         Report report = scan(jar);
         System.out.println(report.render());
         if (args.length >= 3) {
            report.writeJson(new File(args[2]));
            System.out.println("\nprofile written to " + args[2]);
         }
         System.exit(report.minecraftClass == null ? 1 : 0);
      }

      System.out.println("usage: scan <client.jar> [out-profile.json]");
      System.exit(2);
   }

   static final class ClassInfo {
      String name;
      String superName;
      final List<String> fieldNames = new ArrayList<String>();
      final List<String> fieldDescs = new ArrayList<String>();
      final List<Integer> fieldAccess = new ArrayList<Integer>();
      final List<String> methodNames = new ArrayList<String>();
      final List<String> methodDescs = new ArrayList<String>();
      final Set<String> refs = new LinkedHashSet<String>();
      // Field names assigned by the (DDD)V position setter, in parameter order.
      String setterX;
      String setterY;
      String setterZ;

      boolean hasDoubleFields(int count) {
         int seen = 0;
         for (String desc : this.fieldDescs) {
            if ("D".equals(desc)) {
               seen++;
            }
         }
         return seen >= count;
      }

      boolean hasBooleanField() {
         return this.fieldDescs.contains("Z");
      }

      boolean hasSetterTripleDouble() {
         for (String desc : this.methodDescs) {
            if ("(DDD)V".equals(desc)) {
               return true;
            }
         }
         return false;
      }

      String firstFieldOfType(String internalType) {
         for (int i = 0; i < this.fieldDescs.size(); i++) {
            if (("L" + internalType + ";").equals(this.fieldDescs.get(i))) {
               return this.fieldNames.get(i);
            }
         }
         return null;
      }

      List<String> doubles() {
         List<String> out = new ArrayList<String>();
         for (int i = 0; i < this.fieldDescs.size(); i++) {
            if ("D".equals(this.fieldDescs.get(i)) && out.size() < 3
               && (this.fieldAccess.get(i) & Opcodes.ACC_STATIC) == 0) {
               out.add(this.fieldNames.get(i));
            }
         }
         return out;
      }

      String firstBoolean() {
         for (int i = 0; i < this.fieldDescs.size(); i++) {
            if ("Z".equals(this.fieldDescs.get(i)) && (this.fieldAccess.get(i) & Opcodes.ACC_STATIC) == 0) {
               return this.fieldNames.get(i);
            }
         }
         return null;
      }

      String selfStaticField() {
         for (int i = 0; i < this.fieldDescs.size(); i++) {
            if ((this.fieldAccess.get(i) & Opcodes.ACC_STATIC) != 0
               && ("L" + this.name + ";").equals(this.fieldDescs.get(i))) {
               return this.fieldNames.get(i);
            }
         }
         return null;
      }
   }

   static final class Report {
      String jar;
      int classCount;
      String minecraftClass;
      String minecraftRefDisplay = DISPLAY;
      String playerField;
      String playerType;
      String entityClass;
      String xField;
      String yField;
      String zField;
      String setterMethod;
      String setterDesc = "(DDD)V";

      String render() {
         StringBuilder sb = new StringBuilder();
         sb.append("jar: ").append(this.jar).append('\n');
         sb.append("classes: ").append(this.classCount).append('\n');
         sb.append("minecraft: ").append(this.minecraftClass).append('\n');
         sb.append("player field: ").append(this.playerField).append(" : ").append(this.playerType).append('\n');
         sb.append("entity base: ").append(this.entityClass).append('\n');
         sb.append("position: (").append(this.xField).append(", ").append(this.yField).append(", ")
            .append(this.zField).append(")\n");
         sb.append("setter: ").append(this.setterMethod).append(this.setterDesc);
         return sb.toString();
      }

      void writeJson(File file) throws Exception {
         Map<String, Object> map = new LinkedHashMap<String, Object>();
         map.put("name", file.getName().replace(".json", ""));
         map.put("sourceJar", this.jar);
         map.put("minecraftClass", this.minecraftClass);
         map.put("playerField", this.playerField);
         map.put("playerType", this.playerType);
         map.put("entityClass", this.entityClass);
         map.put("xField", this.xField);
         map.put("yField", this.yField);
         map.put("zField", this.zField);
         map.put("setterMethod", this.setterMethod);
         map.put("setterDesc", this.setterDesc);
         map.put("tickMillis", 50);
         map.put("hookedMethod", DISPLAY + ".update()V");
         String json = new GsonBuilder().setPrettyPrinting().create().toJson(map);
         java.nio.file.Files.write(file.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
   }

   static Report scan(File jarFile) throws Exception {
      Map<String, ClassInfo> classes = new LinkedHashMap<String, ClassInfo>();

      try (JarFile jar = new JarFile(jarFile)) {
         Enumeration<JarEntry> entries = jar.entries();
         while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class") || entry.getName().contains("META-INF")) {
               continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
               ClassInfo info = read(in);
               classes.put(info.name, info);
            } catch (Throwable ignored) {
               // skip unparseable entries
            }
         }
      }

      Report report = new Report();
      report.jar = jarFile.getName();
      report.classCount = classes.size();

      ClassInfo bestMinecraft = null;
      String bestPlayerField = null;
      String bestPlayerType = null;
      String bestEntity = null;
      int bestDepth = -1;

      for (ClassInfo info : classes.values()) {
         if (info.selfStaticField() == null || !info.refs.contains(DISPLAY)) {
            continue;
         }

         for (int i = 0; i < info.fieldNames.size(); i++) {
            String desc = info.fieldDescs.get(i);
            if ((info.fieldAccess.get(i) & Opcodes.ACC_STATIC) != 0 || !desc.startsWith("L") || !desc.endsWith(";")) {
               continue;
            }
            String type = desc.substring(1, desc.length() - 1);
            String entity = entityBase(type, classes);
            if (entity == null) {
               continue;
            }
            int depth = superDepth(type, entity, classes);
            if (depth > bestDepth) {
               bestDepth = depth;
               bestMinecraft = info;
               bestPlayerField = info.fieldNames.get(i);
               bestPlayerType = type;
               bestEntity = entity;
            }
         }
      }

      if (bestMinecraft == null) {
         return report;
      }

      report.minecraftClass = bestMinecraft.name;
      report.playerField = bestPlayerField;
      report.playerType = bestPlayerType;
      report.entityClass = bestEntity;

      ClassInfo entity = classes.get(report.entityClass);
      if (entity != null) {
         List<String> doubles = entity.doubles();
         // Prefer the fields the (DDD)V setter actually writes; declaration order alone is wrong
         // because renderDistanceWeight / prevPos* are also doubles and come first.
         report.xField = entity.setterX != null ? entity.setterX : (doubles.size() > 0 ? doubles.get(0) : null);
         report.yField = entity.setterY != null ? entity.setterY : (doubles.size() > 1 ? doubles.get(1) : null);
         report.zField = entity.setterZ != null ? entity.setterZ : (doubles.size() > 2 ? doubles.get(2) : null);
         for (int i = 0; i < entity.methodDescs.size(); i++) {
            if ("(DDD)V".equals(entity.methodDescs.get(i))) {
               report.setterMethod = entity.methodNames.get(i);
               break;
            }
         }
      }

      return report;
   }


   private static ClassInfo read(InputStream in) throws Exception {
      ClassInfo info = new ClassInfo();
      ClassReader reader = new ClassReader(in);
      reader.accept(new ClassVisitor(Opcodes.ASM9) {
         @Override
         public void visit(int version, int access, String name, String signature, String superName,
               String[] interfaces) {
            info.name = name;
            info.superName = superName;
         }

         @Override
         public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            info.fieldNames.add(name);
            info.fieldDescs.add(descriptor);
            info.fieldAccess.add(access);
            return null;
         }

         @Override
         public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
               String[] exceptions) {
            info.methodNames.add(name);
            info.methodDescs.add(descriptor);
            final boolean positionSetter = "(DDD)V".equals(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
               private int lastDload = -1;

               @Override
               public void visitVarInsn(int opcode, int var) {
                  if (positionSetter && opcode == Opcodes.DLOAD) {
                     this.lastDload = var;
                  }
               }

               @Override
               public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                  info.refs.add(owner);
                  info.refs.add(Type.getType(fieldDescriptor).getInternalName());
                  if (positionSetter && opcode == Opcodes.PUTFIELD && "D".equals(fieldDescriptor)) {
                     if (this.lastDload == 1 && info.setterX == null) {
                        info.setterX = fieldName;
                     } else if (this.lastDload == 3 && info.setterY == null) {
                        info.setterY = fieldName;
                     } else if (this.lastDload == 5 && info.setterZ == null) {
                        info.setterZ = fieldName;
                     }
                     this.lastDload = -1;
                  }
               }

               @Override
               public void visitTypeInsn(int opcode, String type) {
                  info.refs.add(type);
               }

               @Override
               public void visitMethodInsn(int opcode, String owner, String name2, String descriptor2,
                     boolean isInterface) {
                  info.refs.add(owner);
               }
            };
         }
      }, 0);
      return info;
   }

   private static String entityBase(String type, Map<String, ClassInfo> classes) {
      String current = type;
      String found = null;
      int guard = 0;
      while (current != null && guard++ < 32) {
         ClassInfo info = classes.get(current);
         if (info == null) {
            break;
         }
         if (info.hasDoubleFields(3) && info.hasBooleanField() && info.hasSetterTripleDouble()) {
            found = current;
         }
         current = info.superName;
      }
      return found;
   }

   private static int superDepth(String type, String stopAt, Map<String, ClassInfo> classes) {
      int depth = 0;
      String current = type;
      int guard = 0;
      while (current != null && guard++ < 32) {
         if (current.equals(stopAt)) {
            return depth;
         }
         ClassInfo info = classes.get(current);
         current = info == null ? null : info.superName;
         depth++;
      }
      return depth;
   }
}
