package com.bridger.compat.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads the entity class bytes back from its classloader and extracts the fields the
 * {@code (DDD)V} position setter assigns, in parameter order.
 *
 * <p>This exists because declaration order is a trap: 1.8.9 {@code Entity} declares
 * {@code renderDistanceWeight}, {@code prevPosX} and {@code prevPosY} before the real
 * {@code posX/posY/posZ}, so "first three doubles" silently tracks the wrong values. Reading the
 * setter is exact and survives re-obfuscation.
 */
final class BytecodeView {
   private BytecodeView() {
   }

   static String[] positionFields(Class<?> entityBase) {
      byte[] bytes = read(entityBase);
      if (bytes == null) {
         return null;
      }

      final String[] out = new String[3];
      try {
         new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                  String[] exceptions) {
               if (!"(DDD)V".equals(descriptor)) {
                  return null;
               }
               return new MethodVisitor(Opcodes.ASM9) {
                  private int lastDload = -1;

                  @Override
                  public void visitVarInsn(int opcode, int var) {
                     if (opcode == Opcodes.DLOAD) {
                        this.lastDload = var;
                     }
                  }

                  @Override
                  public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                     if (opcode == Opcodes.PUTFIELD && "D".equals(fieldDescriptor)) {
                        if (this.lastDload == 1 && out[0] == null) {
                           out[0] = fieldName;
                        } else if (this.lastDload == 3 && out[1] == null) {
                           out[1] = fieldName;
                        } else if (this.lastDload == 5 && out[2] == null) {
                           out[2] = fieldName;
                        }
                        this.lastDload = -1;
                     }
                  }
               };
            }
         }, ClassReader.SKIP_FRAMES);
      } catch (Throwable t) {
         return null;
      }

      if (out[0] == null || out[1] == null || out[2] == null) {
         return null;
      }
      return out;
   }

   private static byte[] read(Class<?> type) {
      String resource = type.getName().replace('.', '/') + ".class";
      ClassLoader loader = type.getClassLoader();
      try (InputStream in = loader != null
            ? loader.getResourceAsStream(resource)
            : ClassLoader.getSystemResourceAsStream(resource)) {
         return in == null ? null : readFully(in);
      } catch (Throwable t) {
         return null;
      }
   }

   /** {@code InputStream.readAllBytes()} is Java 9+; the agent must still load on Java 8. */
   private static byte[] readFully(InputStream in) throws java.io.IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) > 0) {
         buffer.write(chunk, 0, read);
      }
      return buffer.toByteArray();
   }
}
