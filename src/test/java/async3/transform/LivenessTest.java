package async3.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Unit tests for {@link Liveness#liveOut}: the backward dataflow analysis that drives both dead-ref
 * nulling at spill sites and the minimal-restore filter at resume sites.
 *
 * <p>Each test builds a small {@link MethodNode} programmatically — slots are explicit, so
 * assertions are exact without relying on javac's allocation choices.
 */
class LivenessTest {

    private static final String CF = "Ljava/util/concurrent/CompletableFuture;";

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Fresh INVOKESTATIC node for AsyncRT.await (must be a new instance per use in a list). */
    private static MethodInsnNode await() {
        return new MethodInsnNode(INVOKESTATIC,
                AsyncTransformer.AWAIT_OWNER, AsyncTransformer.AWAIT_NAME,
                AsyncTransformer.AWAIT_DESC, false);
    }

    /** Index of the nth await call (0-based) in {@code mn.instructions}. */
    private static int awaitIdx(MethodNode mn, int which) {
        int count = 0;
        for (int i = 0; i < mn.instructions.size(); i++) {
            AbstractInsnNode n = mn.instructions.get(i);
            if (n.getOpcode() == INVOKESTATIC && n instanceof MethodInsnNode m
                    && m.name.equals("await"))
                if (count++ == which) return i;
        }
        throw new AssertionError("await #" + which + " not found");
    }

    // ── tests ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Basic case: one param used only before the await is dead after it; one used only after is live.
     *
     * <pre>
     *   static (CF f/0, int a/1, int b/2) → int
     *   ILOAD 2          b used before await  → dead after
     *   POP
     *   ALOAD 0
     *   await            ← analysis point
     *   POP
     *   ILOAD 1          a used after await   → live
     *   IRETURN
     * </pre>
     */
    @Test void deadParamAfterAwait() {
        MethodNode mn = method("(" + CF + "II)I", 3);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(ILOAD, 2));
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(IRETURN));

        BitSet at = Liveness.liveOut(mn)[awaitIdx(mn, 0)];

        assertTrue(at.get(1),  "a (slot 1) used after await — live");
        assertFalse(at.get(0), "f (slot 0) consumed by await — dead");
        assertFalse(at.get(2), "b (slot 2) used only before await — dead");
    }

    /**
     * Loop-bound pattern: the array is used after the await (in the loop body), the future is not.
     *
     * <pre>
     *   static (CF fhi/0, int[] arr/1) → int
     *   ALOAD 0
     *   await            ← fhi consumed; arr used below → live
     *   POP
     *   ALOAD 1          arr — live after await
     *   ARRAYLENGTH
     *   IRETURN
     * </pre>
     */
    @Test void loopBoundIsLive() {
        MethodNode mn = method("(" + CF + "[I)I", 2);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ALOAD, 1));
        il.add(new InsnNode(ARRAYLENGTH));
        il.add(new InsnNode(IRETURN));

        BitSet at = Liveness.liveOut(mn)[awaitIdx(mn, 0)];

        assertFalse(at.get(0), "fhi (slot 0) consumed by await — dead");
        assertTrue(at.get(1),  "arr (slot 1) used after await — live");
    }

    /**
     * Three sequential awaits (the chain3 shape): each future is dead from its own await onward;
     * futures not yet consumed remain live.
     *
     * <pre>
     *   static (CF f1/0, CF f2/1, CF f3/2) → int
     *   ALOAD 0; await   ← #0: f1 dead, f2/f3 live
     *   POP
     *   ALOAD 1; await   ← #1: f2 dead, f3 live
     *   POP
     *   ALOAD 2; await   ← #2: all dead
     *   POP; ICONST_0; IRETURN
     * </pre>
     */
    @Test void chainAwaitsDeadFuturesExcluded() {
        MethodNode mn = method("(" + CF + CF + CF + ")I", 3);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(ALOAD, 0)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ALOAD, 1)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ALOAD, 2)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(IRETURN));

        BitSet[] lo = Liveness.liveOut(mn);
        BitSet at0 = lo[awaitIdx(mn, 0)];
        BitSet at1 = lo[awaitIdx(mn, 1)];
        BitSet at2 = lo[awaitIdx(mn, 2)];

        assertFalse(at0.get(0), "f1 consumed at await #0");
        assertTrue(at0.get(1),  "f2 still needed after await #0");
        assertTrue(at0.get(2),  "f3 still needed after await #0");

        assertFalse(at1.get(0), "f1 dead since await #0");
        assertFalse(at1.get(1), "f2 consumed at await #1");
        assertTrue(at1.get(2),  "f3 still needed after await #1");

        assertTrue(at2.isEmpty(), "nothing live after last await");
    }

    /**
     * Wide type (long) is tracked at its base slot; the high half never appears in liveOut.
     *
     * <pre>
     *   static (CF f/0, long x/1+2) → long
     *   ALOAD 0
     *   await            ← f dead; x (base slot 1) live
     *   POP
     *   LLOAD 1
     *   LRETURN
     * </pre>
     */
    @Test void wideLocalTrackedAtBaseSlot() {
        MethodNode mn = method("(" + CF + "J)J", 3);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(LLOAD, 1));
        il.add(new InsnNode(LRETURN));

        BitSet at = Liveness.liveOut(mn)[awaitIdx(mn, 0)];

        assertFalse(at.get(0), "f (slot 0) dead after await");
        assertTrue(at.get(1),  "x base slot (1) live after await");
        assertFalse(at.get(2), "x high half (slot 2) never independently tracked");
    }

    /**
     * A local used on only one branch after the await still counts as live (union of successors).
     *
     * <pre>
     *   static (CF f/0, int a/1, int cond/2) → int
     *   ALOAD 0
     *   await            ← cond live (branch), a live (then-path), f dead
     *   POP
     *   ILOAD 2; IFEQ else
     *   ILOAD 1; IRETURN      a used here
     * else:
     *   ICONST_0; IRETURN
     * </pre>
     */
    @Test void localLiveOnOneBranchCountsAsLive() {
        MethodNode mn = method("(" + CF + "II)I", 3);
        InsnList il = mn.instructions;
        LabelNode elseLabel = new LabelNode();

        il.add(new VarInsnNode(ALOAD, 0));
        il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ILOAD, 2));
        il.add(new JumpInsnNode(IFEQ, elseLabel));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(IRETURN));
        il.add(elseLabel);
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(IRETURN));

        BitSet at = Liveness.liveOut(mn)[awaitIdx(mn, 0)];

        assertFalse(at.get(0), "f (slot 0) consumed by await — dead");
        assertTrue(at.get(1),  "a (slot 1) live — used on then-branch");
        assertTrue(at.get(2),  "cond (slot 2) live — needed for branch");
    }

    /**
     * Ref local that is dead after one await stays dead at the next — the restore-elision is safe
     * because the spill for the second await nulls dead refs rather than loading from the JVM slot.
     * This is the {@code chain3} pattern: {@code f1} is consumed at the first await and never used
     * again; it must NOT be restored at resume[1] or resume[2].
     *
     * <pre>
     *   static (CF f1/0, CF f2/1) → int
     *   ALOAD 0; await   ← #0: f1 dead, f2 live
     *   POP
     *   ALOAD 1; await   ← #1: f1 still dead, f2 dead
     *   POP; ICONST_0; IRETURN
     * </pre>
     */
    @Test void deadRefStaysDeadAcrossAwaits() {
        MethodNode mn = method("(" + CF + CF + ")I", 2);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(ALOAD, 0)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ALOAD, 1)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(IRETURN));

        BitSet[] lo = Liveness.liveOut(mn);
        assertFalse(lo[awaitIdx(mn, 0)].get(0), "f1 dead after await #0");
        assertTrue(lo[awaitIdx(mn, 0)].get(1),  "f2 live after await #0");
        assertFalse(lo[awaitIdx(mn, 1)].get(0), "f1 still dead at await #1");
        assertFalse(lo[awaitIdx(mn, 1)].get(1), "f2 dead after await #1");
    }

    /**
     * Dead prim at one await is dead at all subsequent awaits too (liveness is transitive): a use
     * of the prim after any later await would propagate liveness backward through that await and all
     * earlier ones. So if it is dead at await[i], skipping its spill at await[i] is safe — no later
     * spill site reads it. This is the {@code mixedPrims} pattern: {@code scale} is used to compute
     * {@code l = scale * 3} before the first await, then never again.
     *
     * <pre>
     *   static (CF f1/0, CF f2/1, long scale/2+3) → long
     *   LLOAD 2; POP2      scale used before first await
     *   ALOAD 0; await     ← #0: scale dead, f2 live
     *   POP
     *   ALOAD 1; await     ← #1: all dead
     *   POP; LCONST_0; LRETURN
     * </pre>
     */
    @Test void deadPrimStaysDeadAcrossAwaits() {
        // Slot layout: 0=f1(CF), 1=f2(CF), 2+3=scale(long)
        MethodNode mn = method("(" + CF + CF + "J)J", 4);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(LLOAD, 2));   // use scale before first await
        il.add(new InsnNode(POP2));
        il.add(new VarInsnNode(ALOAD, 0)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new VarInsnNode(ALOAD, 1)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new InsnNode(LCONST_0));
        il.add(new InsnNode(LRETURN));

        BitSet[] lo = Liveness.liveOut(mn);
        // scale is dead after await #0 — liveness is correct
        assertFalse(lo[awaitIdx(mn, 0)].get(2), "scale base slot dead after await #0");
        // scale is also dead at await #1 — transitivity guarantees this
        assertFalse(lo[awaitIdx(mn, 1)].get(2), "scale dead at await #1 too (liveness is transitive)");
        // f2 is live after await #0, dead after await #1
        assertFalse(lo[awaitIdx(mn, 0)].get(0), "f1 dead after await #0");
        assertTrue(lo[awaitIdx(mn, 0)].get(1),  "f2 live after await #0");
        assertTrue(lo[awaitIdx(mn, 1)].isEmpty(), "nothing live after last await");
    }

    /**
     * Local written (not read) between two awaits is dead at the second await site if not used
     * afterwards.
     *
     * <pre>
     *   static (CF f1/0, CF f2/1) → int
     *   ALOAD 0; await   ← #0: f2 live
     *   POP
     *   ISTORE 2         write tmp (slot 2)
     *   ALOAD 1; await   ← #1: tmp dead (never read), f1/f2 dead
     *   POP; ICONST_0; IRETURN
     * </pre>
     */
    @Test void writtenButUnreadLocalIsDead() {
        MethodNode mn = method("(" + CF + CF + ")I", 3);
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(ALOAD, 0)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ISTORE, 2));   // write tmp — never read again
        il.add(new VarInsnNode(ALOAD, 1)); il.add(await());
        il.add(new InsnNode(POP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(IRETURN));

        BitSet[] lo = Liveness.liveOut(mn);
        BitSet at0 = lo[awaitIdx(mn, 0)];
        BitSet at1 = lo[awaitIdx(mn, 1)];

        assertFalse(at0.get(0), "f1 consumed at await #0");
        assertTrue(at0.get(1),  "f2 needed after await #0");
        assertFalse(at0.get(2), "tmp not yet written at await #0 — dead");

        assertTrue(at1.isEmpty(), "nothing live after last await");
    }

    // ── factory ───────────────────────────────────────────────────────────────────────────────────

    private static MethodNode method(String desc, int maxLocals) {
        MethodNode mn = new MethodNode(ACC_STATIC, "test", desc, null, null);
        mn.maxLocals = maxLocals;
        return mn;
    }
}
