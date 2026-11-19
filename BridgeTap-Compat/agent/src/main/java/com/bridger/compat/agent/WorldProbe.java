package com.bridger.compat.agent;

import com.bridger.compat.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reflective bridge to the three game subsystems the Fabric build could call directly.
 *
 * <p>The agent jar lives on the bootstrap loader, so nothing here may {@code import} a Minecraft
 * type: every access is a {@link Method} or {@link Field} looked up against the live class graph.
 *
 * <p>Three things the original mod got for free and this layer has to reconstruct:
 * <ul>
 *   <li><b>The run clock.</b> {@code BridgeTracker} read the sidebar scoreboard's {@code Time} line
 *       every tick. Deriving time from the tick counter instead drifts (a client that drops frames
 *       still ticks at 20 Hz, but the server's timer is authoritative), so the scoreboard is read
 *       when it is available and the tick estimate is only a fallback.</li>
 *   <li><b>The finish line.</b> Success is "the player's bounding box overlaps a block with id 147"
 *       (weighted pressure plate, light — the gold plate every bridging map uses as its endpoint).
 *       Without this the tracker can never reach {@code SUCCESS}, which is why Plates / Plates-1t
 *       never appeared in the injected build.</li>
 *   <li><b>Chat.</b> {@code /bt} parsing needs {@code sendChatMessage} intercepted, and the success
 *       line needs {@code addChatMessage}.</li>
 * </ul>
 *
 * <p>Every lookup is name-tolerant. Lunar keeps MCP names ({@code getObjectiveInDisplaySlot});
 * a re-obfuscated client has {@code a}/{@code b}. Each member is resolved by a candidate name list
 * first and by shape second, and a subsystem that cannot be resolved degrades to "unavailable"
 * rather than throwing into the tick path.
 */
final class WorldProbe {
   /** Block id of the light weighted pressure plate — the gold plate. */
   private static final int GOLD_PLATE_ID = 147;
   /**
    * The box the game tests an entity against to decide the plate is pressed:
    *
    * <pre>
    * BlockBasePressurePlate.getSensitiveAABB(BlockPos):
    *    float f = 0.125F;
    *    return new AxisAlignedBB(x + f, y, z + f, x + 1 - f, y + 0.25D, z + 1 - f);
    * </pre>
    *
    * <p>Verified against the 1.8.9 bytecode (MCP jar, {@code client-1.8.9-mcp.jar}): the constants
    * are {@code 0.125f} and {@code 0.25d}. Everything about this check comes from that method —
    * the plate does <em>not</em> use its own 1/16 collision shape for the trigger.
    */
   private static final double SENSITIVE_INSET = 0.125D;
   /** Height of that box above the plate's block base. */
   private static final double SENSITIVE_HEIGHT = 0.25D;
   /**
    * The player's own collision box: 0.6 wide, 1.8 tall ({@code EntityPlayer} constructor).
    *
    * <p>{@code BlockBasePressurePlate.getCollisionBoundingBox} returns {@code null} — a plate is not
    * solid. The player does not stand <em>on</em> it: the feet come to rest on the block beneath, at
    * exactly {@code blockY}, and the plate occupies the 1/16 above that.
    *
    * <p>That is why both earlier attempts were wrong. The first (feet at {@code blockY + 0.0625},
    * half a block of slop) matched too early; the second (a band centred on that face) matched
    * nothing at all, because the feet are never at that height.
    */
   private static final double PLAYER_HALF_WIDTH = 0.3D;
   private static final double PLAYER_HEIGHT = 1.8D;
   /** {@code Scoreboard.getObjectiveInDisplaySlot(1)} — 1 is the sidebar slot. */
   private static final int SIDEBAR_SLOT = 1;
   /** How often the plugin attempts to re-resolve a chain that failed (in ticks). */
   private static final int RETRY_TICKS = 40;
   /** Above this the value is not a latency but some other int that happened to be in range. */
   private static final int MAX_PLAUSIBLE_PING = 20000;
   /** Player-list ping cannot resolve before a connection exists; retry at this interval. */
   private static final long PING_RETRY_MILLIS = 3000L;

   private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+):(\\d+(?:\\.\\d+)?)");
   private static final Pattern SECONDS_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

   private final Object mc;
   private final Field worldField;

   private Method getScoreboard;
   private Method getObjectiveInDisplaySlot;
   private Method getSortedScores;
   private Method scoreGetPlayerName;
   private Method scoreGetScore;
   private Method getTeam;
   private Method formatString;

   private Method getBlockState;
   private Method stateGetBlock;
   private Method blockGetId;
   private Method getTotalWorldTime;
   private Constructor<?> blockPosCtor;

   private Method addChatMessage;
   private Constructor<?> chatComponentCtor;

   private volatile boolean scoreboardReady;
   private volatile boolean plateReady;
   private volatile boolean chatReady;
   private int lastAttemptTick;

   /** True when the client runs the 1.8.9 obfuscation, which changes every member name below. */
   private final boolean obf;

   private WorldProbe(Object mc, Field worldField, boolean obf) {
      this.mc = mc;
      this.worldField = worldField;
      this.obf = obf;
   }

   /**
    * Candidate names for one member, obfuscated first when the client is remapped.
    *
    * <p>Badlion's names come from {@code 1.8.9-mcp.tsrg2}, so they are exact rather than guessed -
    * the MCP spellings stay in the list only so a future build that drops the obfuscation still
    * resolves through the same code path.
    */
   private String[] pick(String obfName, String... mcpNames) {
      if (!this.obf || obfName == null) {
         return mcpNames;
      }
      String[] out = new String[mcpNames.length + 1];
      out[0] = obfName;
      System.arraycopy(mcpNames, 0, out, 1, mcpNames.length);
      return out;
   }

   /** @return null when this game object exposes no world field at all */
   static WorldProbe attach(Object mc, Class<?> mcClass, ClassScan.Result map) {
      Field world = null;
      if (map != null && map.worldField != null) {
         world = fieldByName(mcClass, map.worldField);
      }
      if (world == null) {
         world = fieldByType(mcClass, "World");
      }
      if (world == null) {
         world = fieldByName(mcClass, "theWorld", "world", "field_71441_e");
      }
      if (world == null) {
         return null;
      }
      try {
         world.setAccessible(true);
      } catch (Throwable t) {
         return null;
      }
      WorldProbe probe = new WorldProbe(mc, world, map != null && map.obf);
      probe.resolveScoreboard();
      probe.resolvePlate();
      probe.resolveChat();
      Log.info("world probe: scoreboard=" + probe.scoreboardReady + " plate=" + probe.plateReady
         + " chat=" + probe.chatReady + " (world field " + world.getName() + ")");
      return probe;
   }

   /** Retries whatever failed to resolve, at most once every two seconds. */
   void retryIfNeeded(int tick) {
      if (scoreboardReady && plateReady && chatReady) {
         return;
      }
      if (tick - lastAttemptTick < RETRY_TICKS) {
         return;
      }
      lastAttemptTick = tick;
      if (!scoreboardReady) {
         resolveScoreboard();
      }
      if (!plateReady) {
         resolvePlate();
      }
      if (!chatReady) {
         resolveChat();
      }
   }

   // ------------------------------------------------------------------ world

   private Object world() {
      try {
         return worldField.get(mc);
      } catch (Throwable t) {
         return null;
      }
   }

   // ------------------------------------------------------------- scoreboard

   /**
    * Resolves the sidebar chain: world → scoreboard → slot-1 objective → sorted scores → team text.
    *
    * <p>A world is only present in-game, so this is attempted from the tick path as well; it is a
    * handful of reflective lookups and only re-runs while something is still missing.
    */
   private void resolveScoreboard() {
      Object world = world();
      if (world == null) {
         return;
      }
      Class<?> worldClass = world.getClass();
      // Was declared with a void return type, so the lookup could never match - the scoreboard
      // chain has been dead since this class was written, which is why the run clock always fell
      // back to counting frames.
      getScoreboard = methodByName(worldClass, pick("Z", "getScoreboard", "func_96441_U"));
      if (getScoreboard == null) {
         getScoreboard = findNoArgReturning(worldClass, "Scoreboard");
      }
      if (getScoreboard == null) {
         return;
      }
      Object board = call(getScoreboard, world);
      if (board == null) {
         return;
      }
      Class<?> boardClass = board.getClass();
      getObjectiveInDisplaySlot = method(boardClass, null,
         pick("a", "getObjectiveInDisplaySlot", "func_96539_a"), int.class);
      getSortedScores = methodByName(boardClass, pick("i", "getSortedScores", "func_96534_i"));
      if (getSortedScores == null && getObjectiveInDisplaySlot != null) {
         // 1.8.9 takes the objective as an argument: getSortedScores(ScoreObjective).
         getSortedScores = method(boardClass, null, pick("i", "getSortedScores", "func_96534_i"),
            getObjectiveInDisplaySlot.getReturnType());
      }
      getTeam = method(boardClass, null, pick("d", "getTeam", "func_96508_e"), String.class);
      if (getObjectiveInDisplaySlot == null || getSortedScores == null) {
         return;
      }

      Class<?> scoreClass = null;
      Object objective = call(getObjectiveInDisplaySlot, board, Integer.valueOf(SIDEBAR_SLOT));
      if (objective != null) {
         Collection<?> scores = asCollection(call(getSortedScores, board, objective));
         if (scores != null && !scores.isEmpty()) {
            scoreClass = scores.iterator().next().getClass();
         }
      }
      if (scoreClass == null) {
         scoreClass = referencedClass(boardClass, "ScoreboardPlayerScore");
      }
      if (scoreClass != null) {
         scoreGetPlayerName = methodByName(scoreClass, pick("e", "getPlayerName", "func_96653_e"));
         scoreGetScore = methodByName(scoreClass, pick("c", "getScore", "func_96652_c"));
      }
      if (scoreGetPlayerName == null) {
         return;
      }
      if (getTeam != null) {
         // The team type comes from getTeam's own return type; on Badlion the class is called aul
         // and no name-based search can find it.
         Class<?> teamClass = getTeam.getReturnType();
         if (teamClass != null && teamClass != Object.class) {
            formatString = method(teamClass, String.class,
               pick("d", "formatString", "func_178770_a"), String.class);
         }
      }
      scoreboardReady = true;
   }

   /**
    * The server's run timer, or {@code -1} when the sidebar has no {@code Time} line.
    *
    * <p>Every bridging map prints the elapsed run time on the sidebar; the original mod parsed it
    * instead of counting ticks, because the map's own timer is what the player and the leaderboard
    * agree on. The scan mirrors the original: find a line that starts with {@code Time}, use the
    * number on that same line if present, otherwise the next line.
    */
   double readTime() {
      if (!scoreboardReady) {
         return -1.0D;
      }
      Object world = world();
      if (world == null) {
         return -1.0D;
      }
      try {
         Object board = call(getScoreboard, world);
         if (board == null) {
            return -1.0D;
         }
         Object objective = call(getObjectiveInDisplaySlot, board, Integer.valueOf(SIDEBAR_SLOT));
         if (objective == null) {
            return -1.0D;
         }
         Collection<?> raw = asCollection(call(getSortedScores, board, objective));
         if (raw == null || raw.isEmpty()) {
            return -1.0D;
         }
         List<Object> sorted = new ArrayList<Object>(raw);
         // Original sorted by score descending; getSortedScores already does that on 1.8.9.
         boolean nextLine = false;
         for (Object score : sorted) {
            String text = clean(line(board, score));
            if (nextLine) {
               double parsed = parseTime(text);
               if (parsed >= 0.0D) {
                  return parsed;
               }
               nextLine = false;
            }
            if (text.regionMatches(true, 0, "Time", 0, 4)) {
               double parsed = parseTime(text.substring(4));
               if (parsed >= 0.0D) {
                  return parsed;
               }
               nextLine = true;
            }
         }
         return -1.0D;
      } catch (Throwable t) {
         return -1.0D;
      }
   }

   private String line(Object board, Object score) {
      Object name = call(scoreGetPlayerName, score);
      if (name == null) {
         return "";
      }
      String player = String.valueOf(name);
      if (getTeam != null && formatString != null) {
         Object team = call(getTeam, board, player);
         if (team != null) {
            Object decorated = call(formatString, team, player);
            if (decorated != null) {
               return String.valueOf(decorated);
            }
         }
      }
      return player;
   }

   private static String clean(String raw) {
      return raw == null ? "" : raw.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "").trim();
   }

   private static double parseTime(String raw) {
      if (raw == null || raw.isEmpty()) {
         return -1.0D;
      }
      String text = clean(raw);
      Matcher clock = TIME_PATTERN.matcher(text);
      if (clock.find()) {
         try {
            double minutes = Double.parseDouble(clock.group(1));
            double seconds = Double.parseDouble(clock.group(2));
            return minutes * 60.0D + seconds;
         } catch (NumberFormatException ignored) {
            // fall through to the bare-seconds form
         }
      }
      Matcher seconds = SECONDS_PATTERN.matcher(text);
      if (seconds.find()) {
         try {
            return Double.parseDouble(seconds.group(1));
         } catch (NumberFormatException ignored) {
            return -1.0D;
         }
      }
      return -1.0D;
   }

   // ------------------------------------------------------------- gold plate

   private void resolvePlate() {
      Object world = world();
      if (world == null) {
         return;
      }
      Class<?> worldClass = world.getClass();
      // The world's own tick counter. This is the only 20 Hz clock the client actually maintains:
      // Minecraft.runTick runs once per frame (45 ms apart on this machine, i.e. 22 Hz), so counting
      // calls drifts 10% fast and every tick-quantised verdict - the flat label, the Tap/Hold split,
      // the 9-tick fail tap - lands one tick off.
      getTotalWorldTime = method(worldClass, long.class,
         pick("K", "getTotalWorldTime", "func_82737_E"), (Class<?>[])null);
      if (getTotalWorldTime == null) {
         // Name did not survive this client's remapping. 1.8.9 World has exactly two no-arg long
         // getters - the total world time and the seed - so excluding "seed" is unambiguous.
         getTotalWorldTime = findLongNoArg(worldClass);
      }
      getBlockState = method(worldClass, null, pick("p", "getBlockState", "func_180495_p"),
         (Class<?>[])null);
      if (getBlockState != null && getBlockState.getParameterTypes().length != 1) {
         // `p` is overloaded on Badlion's World; keep only the single-argument pose getter.
         getBlockState = oneParamReturning(worldClass, pick("p", "getBlockState", "func_180495_p"));
      }
      if (getBlockState == null) {
         return;
      }
      Class<?> stateClass = getBlockState.getReturnType();
      stateGetBlock = methodByName(stateClass, pick("c", "getBlock", "func_177230_c"));
      if (stateGetBlock == null) {
         return;
      }
      Class<?> blockClass = stateGetBlock.getReturnType();
      blockGetId = method(blockClass, int.class,
         pick("a", "getIdFromBlock", "func_149682_b"), blockClass);
      if (blockGetId == null) {
         // 1.8.9 also exposes the registry form.
         blockGetId = method(blockClass, int.class, new String[] {"func_149682_b", "getIdForBlock"}, blockClass);
      }
      Class<?> posClass = getBlockState.getParameterTypes()[0];
      try {
         blockPosCtor = posClass.getConstructor(int.class, int.class, int.class);
         blockPosCtor.setAccessible(true);
      } catch (Throwable t) {
         blockPosCtor = null;
      }
      plateReady = blockGetId != null && blockPosCtor != null;
      if (getTotalWorldTime == null || getBlockState == null) {
         dumpWorld(worldClass);
      }
   }

   /** One-shot method inventory, printed only when a chain failed to resolve. */
   private void dumpWorld(Class<?> worldClass) {
      try {
         StringBuilder line = new StringBuilder("world methods with no args on ")
            .append(worldClass.getName()).append(": ");
         int count = 0;
         for (Class<?> current = worldClass; current != null && current != Object.class;
               current = current.getSuperclass()) {
            for (Method candidate : current.getDeclaredMethods()) {
               if (candidate.getParameterTypes().length != 0) {
                  continue;
               }
               line.append(candidate.getName()).append("->").append(candidate.getReturnType().getSimpleName())
                  .append(' ');
               if (++count >= 48) {
                  break;
               }
            }
            if (count >= 48) {
               break;
            }
         }
         Log.info(line.toString());
      } catch (Throwable ignored) {
         // diagnostics only
      }
   }

   /**
    * The single no-arg {@code long} getter on the world, excluding the seed.
    *
    * <p>Used when the client's remapper renamed {@code getTotalWorldTime}. Both candidates live on
    * 1.8.9 {@code World}, so the seed exclusion leaves exactly one.
    */
   private static Method findLongNoArg(Class<?> type) {
      Method fallback = null;
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (candidate.getParameterTypes().length != 0 || candidate.getReturnType() != long.class) {
               continue;
            }
            String name = candidate.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("seed") || name.contains("random")) {
               continue;
            }
            if (name.contains("time")) {
               return accessible(candidate);
            }
            if (fallback == null) {
               fallback = candidate;
            }
         }
      }
      return accessible(fallback);
   }

   private static Method accessible(Method method) {
      if (method == null) {
         return null;
      }
      try {
         method.setAccessible(true);
      } catch (Throwable ignored) {
         // public access is enough for a getter
      }
      return method;
   }

   /** The single no-arg getter whose return type name ends with {@code simpleName}. */
   private static Method findNoArgReturning(Class<?> type, String simpleName) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (candidate.getParameterTypes().length != 0) {
               continue;
            }
            String name;
            try {
               name = candidate.getReturnType().getSimpleName();
            } catch (Throwable ignored) {
               continue;
            }
            if (simpleName.equals(name)) {
               return accessible(candidate);
            }
         }
      }
      return null;
   }

   /**
    * The world's tick counter, or {@code -1} when it cannot be read.
    *
    * <p>Increments exactly once per game tick on both sides. Used as the tracker's clock instead of
    * counting frame-hook calls, which run at frame rate on some clients and at a throttled ~22 Hz on
    * others - neither of which is 20 Hz.
    */
   long worldTime() {
      if (getTotalWorldTime == null) {
         return -1L;
      }
      Object world = world();
      if (world == null) {
         return -1L;
      }
      Object value = call(getTotalWorldTime, world);
      return value instanceof Number ? ((Number)value).longValue() : -1L;
   }

   /** Whether a real tick clock is available, so the caller can stop synthesising one. */
   boolean hasTick() {
      return getTotalWorldTime != null;
   }

   /**
    * Report the first plate hit of a run with the numbers behind it, so a bad verdict is traceable.
    */
   private boolean plateLogged;
   /** Near-misses, capped: a plate in range whose face the feet were not resting on. */
   private int plateRejects;

   /**
    * How far above {@code feetY + 1.8} a ceiling block may start and still be picked.
    *
    * <p>The collision resolver parks the head exactly on the ceiling's bottom face, but the
    * coordinate arrives as a float and can land a few hundredths below it. Flooring without the
    * slack returns the air block the head is standing in, so the bonk would never be confirmed.
    */
   private static final double CEILING_SNAP = 0.05D;
   /** Ceiling hits are logged a few times to prove the world read works, then never again. */
   private static final int CEILING_DIAG_LIMIT = 25;
   private int ceilingLogged;

   /**
    * Whether the game would consider the plate under the player pressed.
    *
    * <p>This is a port of the game's own test rather than an approximation of it. The plate's
    * trigger is {@code BlockBasePressurePlate#onEntityCollidedWithBlock} →
    * {@code updateState} → {@code computeRedstoneStrength}, and the last of those asks
    * {@code World.getEntitiesWithinAABBExcludingEntity(null, getSensitiveAABB(pos))}. So the plate
    * is pressed exactly when the player's bounding box intersects
    * {@code (bx+0.125, by, bz+0.125) … (bx+0.875, by+0.25, bz+0.875)}.
    *
    * <p>Two details that earlier attempts got wrong, both read out of the 1.8.9 bytecode:
    * <ul>
    *   <li>{@code getCollisionBoundingBox} returns {@code null} for a plate, so the player never
    *       stands on its 1/16 face — the feet rest on the block below at exactly {@code blockY}.</li>
    *   <li>The trigger box is inset by 0.125 on each side, so a player merely leaning over the
    *       neighbouring block does not press it, while a player standing on the plate does.</li>
    * </ul>
    */
   boolean isOnGoldPlate(double x, double y, double z) {
      if (!plateReady) {
         return false;
      }
      Object world = world();
      if (world == null) {
         return false;
      }
      final double feetMinY = y;
      final double feetMaxY = y + PLAYER_HEIGHT;
      final double bodyMinX = x - PLAYER_HALF_WIDTH;
      final double bodyMaxX = x + PLAYER_HALF_WIDTH;
      final double bodyMinZ = z - PLAYER_HALF_WIDTH;
      final double bodyMaxZ = z + PLAYER_HALF_WIDTH;

      int minX = (int)Math.floor(bodyMinX);
      int maxX = (int)Math.floor(bodyMaxX);
      int minZ = (int)Math.floor(bodyMinZ);
      int maxZ = (int)Math.floor(bodyMaxZ);
      // Every block the player's box can reach: the trigger box is 0.25 tall, so a plate one layer
      // above the feet still counts while the feet are within a quarter block of its base.
      int minY = (int)Math.floor(feetMinY - SENSITIVE_HEIGHT);
      int maxY = (int)Math.floor(feetMaxY);
      try {
         for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
               for (int bz = minZ; bz <= maxZ; bz++) {
                  Object pos = blockPosCtor.newInstance(
                     Integer.valueOf(bx), Integer.valueOf(by), Integer.valueOf(bz));
                  Object state = call(getBlockState, world, pos);
                  if (state == null) {
                     continue;
                  }
                  Object block = call(stateGetBlock, state);
                  if (block == null) {
                     continue;
                  }
                  Object id = call(blockGetId, null, block);
                  if (!(id instanceof Integer) || ((Integer)id).intValue() != GOLD_PLATE_ID) {
                     continue;
                  }
                  // AxisAlignedBB.intersectsWith, with the plate's sensitive box as `other`.
                  boolean hit = bodyMaxX > bx + SENSITIVE_INSET
                     && bodyMinX < bx + 1.0D - SENSITIVE_INSET
                     && bodyMaxZ > bz + SENSITIVE_INSET
                     && bodyMinZ < bz + 1.0D - SENSITIVE_INSET
                     && feetMaxY > by
                     && feetMinY < by + SENSITIVE_HEIGHT;
                  if (hit) {
                     if (!plateLogged) {
                        plateLogged = true;
                        plateRejects = Integer.MAX_VALUE;
                        Log.info(String.format(Locale.US,
                           "plate pressed: feet=%.4f block=(%d,%d,%d) dx=%+.4f dz=%+.4f dy=%+.4f",
                           y, bx, by, bz, x - (bx + 0.5D), z - (bz + 0.5D), y - by));
                     }
                     return true;
                  }
                  if (plateRejects < 8) {
                     plateRejects++;
                     Log.verbose(String.format(Locale.US,
                        "plate miss: feet=%.4f block=(%d,%d,%d) dx=%+.4f dz=%+.4f dy=%+.4f",
                        y, bx, by, bz, x - (bx + 0.5D), z - (bz + 0.5D), y - by));
                  }
               }
            }
         }
      } catch (Throwable t) {
         // A world swap mid-scan is normal; report "not on plate" and retry next tick.
      }
      return false;
   }

   /**
    * Identity of the world instance the player is in right now, 0 when there is no world.
    *
    * <p>1.8.9 gives every dimension its own {@code World} object, and a server transfer or a
    * multi-world plugin replaces it outright, so an identity change is an exact "different world"
    * signal. Comparing the world's tick counters instead is not enough: they also move when the
    * frame rate drops below 20 fps, which would drop a run mid-bridge.
    */
   int worldIdentity() {
      Object world = world();
      return world == null ? 0 : System.identityHashCode(world);
   }

   // ----------------------------------------------------------------- ceiling

   /**
    * Is something solid occupying the block space right above the player's head?
    *
    * <p>This is the world half of the head-hitter test. Upstream reads it straight off the entity
    * ({@code collidedVertically && motionY > 0}); neither field is in the mapped profile, so the
    * tracker watches the height curve instead — and a stalled curve alone is not proof of a bonk.
    * A teleport-lag jump (telly) hands over the same Y twice, which flattens {@code dy} exactly the
    * way a ceiling does. Asking the world removes that ambiguity.
    *
    * <p>The block is picked with {@code floor(headTop + CEILING_SNAP)}: when the head rests against
    * a ceiling the collision resolver puts {@code feetY + 1.8} on the ceiling's bottom face, so the
    * block to test is the one whose body starts there. The epsilon absorbs the float error that
    * would otherwise floor to the air block the head is standing in.
    *
    * @return {@code TRUE}/{@code FALSE} when the world can be read, {@code null} when it cannot
    *         (no world yet, or the block chain did not resolve) — the caller keeps its old lenient
    *         behaviour in that case rather than silently losing the feature
    */
   Boolean ceilingAbove(double x, double y, double z) {
      Object world = world();
      if (world == null || getBlockState == null || stateGetBlock == null
            || blockGetId == null || blockPosCtor == null) {
         return null;
      }
      // The player box is 0.6 wide, so a head straddling a block edge touches up to two columns.
      int minX = (int)Math.floor(x - PLAYER_HALF_WIDTH);
      int maxX = (int)Math.floor(x + PLAYER_HALF_WIDTH);
      int minZ = (int)Math.floor(z - PLAYER_HALF_WIDTH);
      int maxZ = (int)Math.floor(z + PLAYER_HALF_WIDTH);
      int by = (int)Math.floor(y + PLAYER_HEIGHT + CEILING_SNAP);
      try {
         for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
               Object pos = blockPosCtor.newInstance(
                  Integer.valueOf(bx), Integer.valueOf(by), Integer.valueOf(bz));
               Object state = call(getBlockState, world, pos);
               if (state == null) {
                  continue;
               }
               Object block = call(stateGetBlock, state);
               if (block == null) {
                  continue;
               }
               Object id = call(blockGetId, null, block);
               if (!(id instanceof Integer) || ((Integer)id).intValue() == 0) {
                  continue;
               }
               if (ceilingLogged < CEILING_DIAG_LIMIT) {
                  ceilingLogged++;
                  Log.info(String.format(Locale.US,
                     "ceiling above: head=%.4f block=(%d,%d,%d) id=%d dx=%+.3f dz=%+.3f",
                     y + PLAYER_HEIGHT, bx, by, bz, id, x - (bx + 0.5D), z - (bz + 0.5D)));
               }
               return Boolean.TRUE;
            }
         }
      } catch (Throwable t) {
         // A world swap mid-scan is normal; report "unknown" so the verdict is not thrown away.
         return null;
      }
      return Boolean.FALSE;
   }

   // ------------------------------------------------------------------ ping

   /**
    * Round-trip latency to the server in milliseconds, {@code -1} when it cannot be read.
    *
    * <p>1.8.9 exposes it as {@code NetworkPlayerInfo.getResponseTime()}, i.e. the number the tab
    * list draws next to every name. Reaching it means walking three hops, and every one of them is
    * name-obfuscated on Badlion, so nothing here may look at a member name:
    *
    * <ol>
    *   <li>the network handler: found on the player entity, by looking for a field whose object
    *       owns a {@code Map} of player-list entries;</li>
    *   <li>the player list: the handler's own {@code Map<UUID, NetworkPlayerInfo>};</li>
    *   <li>the entry: identified by holding a {@code GameProfile} (that class comes from authlib and
    *       is never obfuscated) plus an {@code int} in a plausible latency range.</li>
    * </ol>
    *
    * <p>Resolution runs once and is retried every {@value #PING_RETRY_MILLIS} ms until it works,
    * because the tick hook can fire long before a server connection exists. A failure is never
    * fatal: the caller prints the success line without the adjustment.
    */
   int pingMillis() {
      if (this.player == null) {
         return -1;
      }
      if (!pingReady) {
         long now = System.currentTimeMillis();
         if (lastPingAttempt != 0L && now - lastPingAttempt < PING_RETRY_MILLIS) {
            return -1;
         }
         lastPingAttempt = now;
         resolvePing();
         if (!pingReady) {
            return -1;
         }
      }
      try {
         Object map = infoMapField.get(netHandler);
         if (!(map instanceof Map)) {
            return -1;
         }
         Map<?, ?> entries = (Map<?, ?>)map;
         if (entries.isEmpty()) {
            return -1;
         }
         Object info = null;
         UUID self = selfId();
         if (self != null) {
            info = entries.get(self);
         }
         if (info == null) {
            // Offline-mode servers key the map by the profile id, which is what getUniqueID returns,
            // so a miss here means either a mismatch or a single-entry list. Only the latter is safe
            // to guess.
            if (entries.size() != 1) {
               return -1;
            }
            info = entries.values().iterator().next();
         }
         int ms = responseTimeField.getInt(info);
         if (ms < 0 || ms > MAX_PLAUSIBLE_PING) {
            return -1;
         }
         if (!pingLogged) {
            pingLogged = true;
            Log.info("ping source: " + info.getClass().getSimpleName() + "."
               + responseTimeField.getName() + " = " + ms + "ms");
         }
         return ms;
      } catch (Throwable t) {
         // A closed connection leaves a stale handler behind; resolve again on the next attempt.
         pingReady = false;
         return -1;
      }
   }

   private void resolvePing() {
      Object player = this.player;
      if (player == null) {
         return;
      }
      if (bindPing(player)) {
         return;
      }
      // Some clients keep the handler off the entity and hang it on Minecraft itself.
      bindPing(mc);
   }

   /** @return true when a handler with a player list was found under {@code holder} */
   private boolean bindPing(Object holder) {
      for (Field field : allInstanceFields(holder.getClass())) {
         if (field.getType().isPrimitive() || field.getType().isArray()) {
            continue;
         }
         Object value;
         try {
            field.setAccessible(true);
            value = field.get(holder);
         } catch (Throwable t) {
            continue;
         }
         if (value == null || value == holder) {
            continue;
         }
         if (bindPlayerList(value)) {
            netHandler = value;
            resolveSelfId();
            pingReady = true;
            Log.info("ping: handler resolved as " + value.getClass().getSimpleName()
               + " (" + (infoMapField == null ? "?" : infoMapField.getName()) + " -> "
               + (responseTimeField == null ? "?" : responseTimeField.getName()) + ")");
            return true;
         }
      }
      return false;
   }

   /**
    * Looks for the {@code Map<UUID, NetworkPlayerInfo>} inside a candidate handler.
    *
    * <p>The map's values are what identify it: a {@code GameProfile} field plus an {@code int} that
    * currently holds a believable latency. Nothing else in the client looks like that, which is what
    * makes this safe to run against every object field of the player entity.
    */
   private boolean bindPlayerList(Object candidate) {
      for (Field field : allInstanceFields(candidate.getClass())) {
         if (!Map.class.isAssignableFrom(field.getType())) {
            continue;
         }
         Object value;
         try {
            field.setAccessible(true);
            value = field.get(candidate);
         } catch (Throwable t) {
            continue;
         }
         if (!(value instanceof Map)) {
            continue;
         }
         Map<?, ?> map = (Map<?, ?>)value;
         if (map.isEmpty()) {
            continue;
         }
         for (Object entry : map.values()) {
            if (entry == null || !hasProfileField(entry.getClass())) {
               continue;
            }
            Field latency = latencyFieldOf(entry);
            if (latency == null) {
               continue;
            }
            infoMapField = field;
            responseTimeField = latency;
            return true;
         }
      }
      return false;
   }

   /** A {@code GameProfile} field — authlib's class name is never obfuscated by the client. */
   private static boolean hasProfileField(Class<?> type) {
      for (Field field : allInstanceFields(type)) {
         String name = field.getType().getName();
         if (name.equals("com.mojang.authlib.GameProfile") || name.endsWith("GameProfile")) {
            return true;
         }
      }
      return false;
   }

   /** The {@code int} that holds the latency: the first one whose current value is plausible. */
   private static Field latencyFieldOf(Object entry) {
      for (Field field : allInstanceFields(entry.getClass())) {
         if (field.getType() != int.class) {
            continue;
         }
         try {
            field.setAccessible(true);
            int value = field.getInt(entry);
            if (value >= 0 && value <= MAX_PLAUSIBLE_PING) {
               return field;
            }
         } catch (Throwable t) {
            // keep looking
         }
      }
      return null;
   }

   private UUID selfId() {
      if (selfIdGetter == null) {
         return null;
      }
      try {
         Object id = selfIdGetter.invoke(player);
         return id instanceof UUID ? (UUID)id : null;
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * {@code Entity.getUniqueID()}, found by shape: the one no-argument method on the entity that
    * returns a {@code UUID}. Nothing else on an entity returns that type.
    */
   private void resolveSelfId() {
      Object player = this.player;
      if (player == null || selfIdGetter != null) {
         return;
      }
      for (Class<?> current = player.getClass(); current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (candidate.getParameterTypes().length != 0
                  || candidate.getReturnType() != UUID.class) {
               continue;
            }
            try {
               candidate.setAccessible(true);
            } catch (Throwable t) {
               continue;
            }
            selfIdGetter = candidate;
            return;
         }
      }
   }

   // ------------------------------------------------------------------ chat

   private void resolveChat() {
      if (chatComponentCtor != null) {
         chatReady = addChatMessage != null;
         return;
      }
      Object world = world();
      if (world == null) {
         return;
      }
      Class<?> component = referencedClass(world.getClass(), "IChatComponent");
      Class<?> text = referencedClass(world.getClass(), "ChatComponentText");
      if (this.obf) {
         // eu = IChatComponent, fa = ChatComponentText. Their names carry no information at all,
         // so the shape search above can never find them.
         if (component == null) {
            component = namedClass("eu");
         }
         if (text == null) {
            text = namedClass("fa");
         }
      }
      if (text == null) {
         for (Class<?> type : Bootstrap.allLoadedClasses()) {
            if ("net.minecraft.util.ChatComponentText".equals(type.getName())) {
               text = type;
               break;
            }
         }
      }
      if (component == null && text != null) {
         Class<?> current = text.getSuperclass();
         while (current != null && current != Object.class) {
            if (current.getSimpleName().endsWith("ChatComponent")) {
               component = current;
               break;
            }
            current = current.getSuperclass();
         }
      }
      if (component != null) {
         chatComponentCtor = ctor(text == null ? component : text, String.class);
      }
      // The sink itself lives on the player entity, which this layer only sees once the tick hook
      // has handed one over - see setPlayer().
      chatReady = addChatMessage != null && chatComponentCtor != null;
   }

   private Object player;

   /** Where the player-list ping lives: the network handler plus the two fields read off it. */
   private Object netHandler;
   private Field infoMapField;
   private Field responseTimeField;
   private Method selfIdGetter;
   private boolean pingReady;
   private boolean pingLogged;
   private long lastPingAttempt;

   void setPlayer(Object player) {
      if (player == null || player == this.player) {
         return;
      }
      this.player = player;
      // A new player entity means a new connection: the old handler's player list is dead.
      netHandler = null;
      infoMapField = null;
      responseTimeField = null;
      selfIdGetter = null;
      pingReady = false;
      if (addChatMessage != null) {
         return;
      }
      Class<?> type = player.getClass();
      while (type != null && type != Object.class) {
         for (Method candidate : type.getDeclaredMethods()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length != 1) {
               continue;
            }
            String name = params[0].getName();
            if (name.endsWith("IChatComponent") || name.endsWith("ChatComponent")
                  || (obf && "eu".equals(name))) {
               try {
                  candidate.setAccessible(true);
               } catch (Throwable t) {
                  continue;
               }
               addChatMessage = candidate;
               chatReady = chatComponentCtor != null;
               Log.info("chat sink resolved: " + type.getSimpleName() + "." + candidate.getName());
               return;
            }
         }
         type = type.getSuperclass();
      }
   }

   /** Sends a line to the player's own chat, exactly like the original mod's success message. */
   void chat(String message) {
      Object target = player;
      if (target == null || addChatMessage == null || chatComponentCtor == null) {
         return;
      }
      try {
         Object component = chatComponentCtor.newInstance(message);
         addChatMessage.invoke(target, component);
      } catch (Throwable t) {
         Log.verbose("chat send failed: " + t);
      }
   }

   // ------------------------------------------------------------- reflection

   private static Object call(Method method, Object target, Object... args) {
      if (method == null) {
         return null;
      }
      try {
         return method.invoke(target, args);
      } catch (Throwable t) {
         return null;
      }
   }

   private static Collection<?> asCollection(Object value) {
      return value instanceof Collection ? (Collection<?>)value : null;
   }

   /** First method whose name is in {@code names}, optionally filtered on parameter types. */
   private static Method method(Class<?> type, Class<?> returnType, String[] names, Class<?>... params) {
      if (type == null) {
         return null;
      }
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (!matches(candidate.getName(), names)) {
               continue;
            }
            if (returnType != null && candidate.getReturnType() != returnType) {
               continue;
            }
            if (params != null && !sameTypes(candidate.getParameterTypes(), params)) {
               continue;
            }
            try {
               candidate.setAccessible(true);
            } catch (Throwable ignored) {
               // still callable via setAccessible-less invoke on public members
            }
            return candidate;
         }
      }
      return null;
   }

   /** Name-only lookup: used where the signature is discovered rather than asserted. */
   private static Method methodByName(Class<?> type, String[] names) {
      if (type == null) {
         return null;
      }
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (!matches(candidate.getName(), names) || candidate.getParameterTypes().length != 0) {
               continue;
            }
            try {
               candidate.setAccessible(true);
            } catch (Throwable ignored) {
               // as above
            }
            return candidate;
         }
      }
      return null;
   }

   /**
    * First method with exactly one object argument returning an object.
    *
    * <p>Badlion's {@code World.p} is overloaded (a pose getter and a chunk-relative variant), so the
    * plain name lookup can land on the wrong one; this keeps only the {@code p(BlockPos)} shape.
    */
   private static Method oneParamReturning(Class<?> type, String[] names) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Method candidate : current.getDeclaredMethods()) {
            if (!matches(candidate.getName(), names)) {
               continue;
            }
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length != 1 || params[0].isPrimitive()) {
               continue;
            }
            Class<?> returnType = candidate.getReturnType();
            if (returnType.isPrimitive() || returnType == void.class) {
               continue;
            }
            return accessible(candidate);
         }
      }
      return null;
   }

   /** A loaded class by exact binary name - the only handle on an obfuscated class. */
   private static Class<?> namedClass(String binaryName) {
      for (Class<?> type : Bootstrap.allLoadedClasses()) {
         if (type != null && binaryName.equals(type.getName())) {
            return type;
         }
      }
      return null;
   }

   private static Constructor<?> ctor(Class<?> type, Class<?>... params) {
      try {
         Constructor<?> c = type.getConstructor(params);
         c.setAccessible(true);
         return c;
      } catch (Throwable t) {
         return null;
      }
   }

   private static boolean matches(String name, String[] names) {
      for (String candidate : names) {
         if (candidate.equals(name)) {
            return true;
         }
      }
      return false;
   }

   private static boolean sameTypes(Class<?>[] actual, Class<?>[] expected) {
      if (actual.length != expected.length) {
         return false;
      }
      for (int i = 0; i < actual.length; i++) {
         if (expected[i] != null && !expected[i].isAssignableFrom(actual[i])) {
            return false;
         }
      }
      return true;
   }

   private static Field fieldByType(Class<?> type, String typeNameFragment) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Field candidate : current.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())) {
               continue;
            }
            String name;
            try {
               name = candidate.getType().getName();
            } catch (Throwable ignored) {
               continue;
            }
            if (name.contains(typeNameFragment)) {
               return candidate;
            }
         }
      }
      return null;
   }

   // ------------------------------------------------------------------ movement keys

   /** Default 1.8.9 bindings: W = 17, A = 30, S = 31, D = 32. */
   private static final int KEY_W = 17;
   private static final int KEY_A = 30;
   private static final int KEY_S = 31;
   private static final int KEY_D = 32;
   /**
    * Retry interval for finding the key bindings: GameSettings may not be constructed before the
    * world is entered.
    *
    * 找键位的重试间隔：进世界之前 GameSettings 可能还没构造好。
    */
   private static final long KEY_RETRY_MILLIS = 3000L;

   private Object keyForward;
   private Object keyBack;
   private Object keyLeft;
   private Object keyRight;
   /** {@code KeyBinding.pressed}; the only boolean on that class, so it needs no name. */
   private Field keyPressedField;
   private boolean keysLogged;
   private long lastKeyAttempt;
   private Object pendingForward;
   private Object pendingBack;
   private Object pendingLeft;
   private Object pendingRight;
   private Field pendingPressedField;

   /**
    * Reads W/A/S/D from the client's own key bindings.
    *
    * <p>Upstream reads {@code movementInput.moveForward/moveStrafe}; those two fields are not in the
    * mapped profile, and inventing a third mapping just for them would break on the next client
    * build. {@code GameSettings} on the other hand is already mapped, and every key binding is an
    * object of the same class with an int key code — so the four bindings are found by their
    * <em>default key codes</em>, which is client-agnostic. A player who rebinds strafe keys loses
    * the gain tag (it goes back to unknown) instead of getting a wrong one.
    *
    * @return bit set: 1 forward, 2 back, 4 left, 8 right; 0 when the bindings could not be found
    */
   int movementBits() {
      if (keyPressedField == null) {
         retryMovementKeys(false);
      }
      if (keyPressedField == null) {
         return 0;
      }
      int bits = 0;
      if (isKeyDown(keyForward)) {
         bits |= 1;
      }
      if (isKeyDown(keyBack)) {
         bits |= 2;
      }
      if (isKeyDown(keyLeft)) {
         bits |= 4;
      }
      if (isKeyDown(keyRight)) {
         bits |= 8;
      }
      return bits;
   }

   boolean hasMovementKeys() {
      if (keyPressedField == null) {
         retryMovementKeys(true);
      }
      return keyPressedField != null;
   }

   private boolean isKeyDown(Object binding) {
      if (binding == null) {
         return false;
      }
      try {
         return keyPressedField.getBoolean(binding);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * At most one attempt every 3 seconds. The first call may happen before the world is entered
    * (GameSettings is not constructed yet), and giving up for good after one failure would mean no
    * Gain tag for the whole session.
    *
    * 每 3 秒最多试一次。第一次调用可能发生在进世界之前（GameSettings 还没构造好），
    * 一次失败就永久放弃的话，这一整局都不会有 Gain 标签。
    */
   private void retryMovementKeys(boolean log) {
      long now = System.currentTimeMillis();
      if (lastKeyAttempt != 0L && now - lastKeyAttempt < KEY_RETRY_MILLIS) {
         return;
      }
      lastKeyAttempt = now;
      resolveMovementKeys(log);
   }

   /**
    * Pull GameSettings out of Minecraft, then recognise the four W/A/S/D KeyBindings inside it.
    *
    * <p><b>Do not recognise them by type name.</b> The old implementation required the field's type
    * name to end with {@code KeyBinding}, and Badlion obfuscates the class names of GameSettings and
    * KeyBinding together (the log shows environment names such as {@code world field f}), so nothing
    * matched at all and the Gain tag never appeared on BLC (agent.log: "no KeyBinding fields
    * found").
    *
    * <p>It now uses duck typing: if the value of a field has an int field equal to 17/30/31/32 (the
    * default key codes for W/A/S/D) and also a boolean ({@code pressed}), it counts as one direction
    * key binding; all four key codes have to be found on the same class before this succeeds, which
    * keeps other objects from being mistaken for keys.
    *
    * <p>Side benefit: GameSettings itself is no longer looked up by name either - every object field
    * of mc is scanned and whichever one fits is the one.
    *
    * 从 Minecraft 手里摸出 GameSettings，再从中认出 W/A/S/D 四个 KeyBinding。
    *
    * <p><b>不能按类型名认。</b>原来的实现要求字段类型名以 {@code KeyBinding} 结尾，而 Badlion 把
    * GameSettings 和 KeyBinding 的类名一起混淆了（日志里是 {@code world field f} 那种环境），
    * 结果一条都匹配不到，Gain 标签在 BLC 上一直不显示（agent.log: "no KeyBinding fields found"）。
    *
    * <p>现在用「鸭子类型」：某个字段的值，只要它的实例里有 int 字段正好等于 17/30/31/32
    * （W/A/S/D 的默认键码），又有一个 boolean（{@code pressed}），就当成一个方向键绑定；
    * 四个键码必须凑齐在同一个类上才算成功，避免把别的对象误认成按键。
    *
    * <p>连带好处：GameSettings 本身也不再按名字找——直接扫 mc 的所有对象字段，谁满足条件就是谁。
    */
   private void resolveMovementKeys(boolean log) {
      try {
         if (bindKeysFrom(mc)) {
            keyPressedField = pendingPressedField;
            keyForward = pendingForward;
            keyBack = pendingBack;
            keyLeft = pendingLeft;
            keyRight = pendingRight;
         }
      } catch (Throwable t) {
         Log.verbose("movement key binding failed: " + t);
      }
      if (log || !keysLogged) {
         keysLogged = true;
         Log.info("movement keys: " + (keyPressedField != null
            ? "bound to the default W/A/S/D codes"
            : "not found yet (will retry)"));
      }
   }

   /**
    * Scans the object fields of {@code holder} for something "that looks like a key settings object",
    * then takes the four direction keys out of it.
    *
    * 扫描 {@code holder} 的对象字段，找一个"像是按键设置"的对象，再从里面取四个方向键。
    *
    * @return true when all four key codes were found; the result is left in the pending* fields
    * · true 表示四个键码都凑齐了，结果放在 pending* 字段里
    */
   private boolean bindKeysFrom(Object holder) {
      if (holder == null) {
         return false;
      }
      for (Field field : allInstanceFields(holder.getClass())) {
         Class<?> type = field.getType();
         if (type.isPrimitive() || type.isArray()) {
            continue;
         }
         Object value;
         try {
            field.setAccessible(true);
            value = field.get(holder);
         } catch (Throwable t) {
            continue;
         }
         if (value == null || value == holder) {
            continue;
         }
         if (bindKeysFromSettings(value)) {
            return true;
         }
      }
      return false;
   }

   /**
    * @return true when all four W/A/S/D bindings were found in {@code settings}
    * · true 表示从 {@code settings} 里凑齐了 W/A/S/D 四个绑定
    */
   private boolean bindKeysFromSettings(Object settings) {
      Object forward = null;
      Object back = null;
      Object left = null;
      Object right = null;
      Field pressed = null;
      Class<?> bindingClass = null;
      for (Field field : allInstanceFields(settings.getClass())) {
         Class<?> type = field.getType();
         if (type.isPrimitive() || type.isArray()) {
            continue;
         }
         Object value;
         try {
            field.setAccessible(true);
            value = field.get(settings);
         } catch (Throwable t) {
            continue;
         }
         if (value == null) {
            continue;
         }
         int code = keyCodeOf(value);
         if (code < 0) {
            continue;
         }
         Field pressedField = booleanFieldOf(value.getClass());
         if (pressedField == null) {
            continue;
         }
         // All four bindings must be four instances of the same class; otherwise something else was
         // most likely mistaken for a key.
         // 四个绑定必须是同一个类的四个实例，否则大概率是把别的东西认成了按键
         if (bindingClass == null) {
            bindingClass = value.getClass();
         } else if (bindingClass != value.getClass()) {
            continue;
         }
         pressed = pressedField;
         if (code == KEY_W) {
            forward = value;
         } else if (code == KEY_S) {
            back = value;
         } else if (code == KEY_A) {
            left = value;
         } else if (code == KEY_D) {
            right = value;
         }
      }
      if (forward == null || back == null || left == null || right == null || pressed == null) {
         return false;
      }
      pendingForward = forward;
      pendingBack = back;
      pendingLeft = left;
      pendingRight = right;
      pendingPressedField = pressed;
      return true;
   }

   /**
    * Every instance field of the class, superclasses included. Names are unreliable in an obfuscated
    * environment, so only type and value can be trusted.
    *
    * 该类（含父类）的所有实例字段。混淆环境里名字不可靠，只能靠类型和值认。
    */
   private static List<Field> allInstanceFields(Class<?> type) {
      List<Field> out = new ArrayList<Field>();
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Field field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
               continue;
            }
            out.add(field);
         }
      }
      return out;
   }

   /** The key binding's int field that looks like a key code (its value is a known 1.8.9 code). */
   private static int keyCodeOf(Object binding) {
      for (Field candidate : binding.getClass().getDeclaredFields()) {
         if (candidate.getType() != int.class || Modifier.isStatic(candidate.getModifiers())) {
            continue;
         }
         try {
            candidate.setAccessible(true);
            int value = candidate.getInt(binding);
            if (value == KEY_W || value == KEY_A || value == KEY_S || value == KEY_D) {
               return value;
            }
         } catch (Throwable ignored) {
            // keep looking
         }
      }
      return -1;
   }

   private static Field booleanFieldOf(Class<?> type) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Field candidate : current.getDeclaredFields()) {
            if (candidate.getType() == boolean.class && !Modifier.isStatic(candidate.getModifiers())) {
               try {
                  candidate.setAccessible(true);
                  return candidate;
               } catch (Throwable ignored) {
                  // keep looking
               }
            }
         }
      }
      return null;
   }

   private Class<?> mcClassOfMc() {
      return mc.getClass();
   }

   private static Field fieldByName(Class<?> type, String... names) {
      for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
         for (Field candidate : current.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())) {
               continue;
            }
            if (matches(candidate.getName(), names)) {
               return candidate;
            }
         }
      }
      return null;
   }

   /**
    * Finds a class the game already references, without touching the client's loader directly.
    *
    * <p>{@code loadClass} on the game loader is refused by Lunar for some packages, but every class
    * it did define is in {@link Bootstrap#allLoadedClasses()}. Matching on the simple name is enough
    * for the handful of types needed here.
    */
   private static Class<?> referencedClass(Class<?> from, String simpleName) {
      ClassLoader loader = from.getClassLoader();
      Class<?>[] loaded = Bootstrap.allLoadedClasses();
      for (int pass = 0; pass < 2; pass++) {
         for (Class<?> type : loaded) {
            if (type == null) {
               continue;
            }
            // getSimpleName() throws InternalError on a handful of generated classes, and this loop
            // runs over every class in a 26k-class client. An Error here used to escape the probe
            // setup entirely and take the tick path down with it.
            String simple;
            try {
               simple = type.getSimpleName();
            } catch (Throwable ignored) {
               continue;
            }
            if (!simpleName.equals(simple)) {
               continue;
            }
            if (pass == 0) {
               try {
                  if (type.getClassLoader() != loader) {
                     continue;
                  }
               } catch (Throwable ignored) {
                  continue;
               }
            }
            return type;
         }
      }
      return null;
   }
}
