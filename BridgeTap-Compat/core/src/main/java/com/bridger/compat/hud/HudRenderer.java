package com.bridger.compat.hud;

import java.util.List;

/**
 * Contract between the bootstrap-loaded agent and the GL renderer.
 *
 * <p>This interface lives on the bootstrap loader, together with everything else in the agent jar.
 * The implementation ({@code GlHud}) deliberately does not: it references {@code org.lwjgl.opengl.*},
 * and LWJGL is only visible from the client's own classloader. So GlHud is packaged in a separate
 * {@code hud.jar} and loaded at runtime through a {@link java.net.URLClassLoader} whose parent is the
 * loader that defined {@code org.lwjgl.opengl.Display}.
 *
 * <p>Because the interface comes from the parent and the implementation from the child, the cast in
 * {@code Bridge} is type-safe: same interface class, one identity.
 */
public interface HudRenderer {
   void render(List<String> lines);
}
