package async3.transform;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.BitSet;

import static org.objectweb.asm.Opcodes.*;

/**
 * Backward liveness of local-variable slots: the textbook bitvector dataflow,
 * {@code liveIn = use ∪ (liveOut \ def)}, iterated to a fixpoint over the CFG. Exception edges
 * are conservative: every instruction inside a protected range may transfer to its handler, so
 * locals read by a catch handler stay live throughout the corresponding try region.
 *
 * <p>Wide values are tracked at their base slot only, matching how the spill code addresses
 * them ({@code LLOAD v} is a use of slot {@code v}; the high half is never accessed on its own
 * in verified code).
 */
final class Liveness {
    private Liveness() {}

    /** Live-out slot sets, indexed like {@code mn.instructions}. */
    static BitSet[] liveOut(MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        int n = insns.length;

        int[][] succs = new int[n][];
        for (int i = 0; i < n; i++) succs[i] = successors(mn, insns[i], i, n);

        // handlerOf[i] = handler indices reachable from instruction i (usually none or one)
        int[][] handlers = new int[n][];
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                int s = mn.instructions.indexOf(tcb.start);
                int e = mn.instructions.indexOf(tcb.end);
                int h = mn.instructions.indexOf(tcb.handler);
                for (int i = s; i < e; i++) handlers[i] = append(handlers[i], h);
            }

        BitSet[] in = new BitSet[n], out = new BitSet[n];
        for (int i = 0; i < n; i++) { in[i] = new BitSet(); out[i] = new BitSet(); }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                BitSet o = new BitSet();
                for (int s : succs[i]) o.or(in[s]);
                if (handlers[i] != null) for (int h : handlers[i]) o.or(in[h]);

                BitSet ni = (BitSet) o.clone();
                AbstractInsnNode insn = insns[i];
                int op = insn.getOpcode();
                if (insn instanceof VarInsnNode v) {
                    if (op >= ISTORE && op <= ASTORE) ni.clear(v.var);
                    else ni.set(v.var); // xLOAD (RET is rejected upstream)
                } else if (insn instanceof IincInsnNode ii) {
                    ni.set(ii.var); // use dominates the def of the same slot
                }

                if (!o.equals(out[i]) || !ni.equals(in[i])) {
                    out[i] = o;
                    in[i] = ni;
                    changed = true;
                }
            }
        }
        return out;
    }

    private static int[] successors(MethodNode mn, AbstractInsnNode insn, int i, int n) {
        int op = insn.getOpcode();
        if (insn instanceof JumpInsnNode j) {
            int t = mn.instructions.indexOf(j.label);
            return op == GOTO ? new int[]{t} : new int[]{t, i + 1};
        }
        if (insn instanceof TableSwitchInsnNode ts) {
            int[] s = new int[ts.labels.size() + 1];
            s[0] = mn.instructions.indexOf(ts.dflt);
            for (int k = 0; k < ts.labels.size(); k++) s[k + 1] = mn.instructions.indexOf(ts.labels.get(k));
            return s;
        }
        if (insn instanceof LookupSwitchInsnNode ls) {
            int[] s = new int[ls.labels.size() + 1];
            s[0] = mn.instructions.indexOf(ls.dflt);
            for (int k = 0; k < ls.labels.size(); k++) s[k + 1] = mn.instructions.indexOf(ls.labels.get(k));
            return s;
        }
        if ((op >= IRETURN && op <= RETURN) || op == ATHROW) return new int[0];
        return i + 1 < n ? new int[]{i + 1} : new int[0];
    }

    private static int[] append(int[] a, int v) {
        if (a == null) return new int[]{v};
        int[] b = new int[a.length + 1];
        System.arraycopy(a, 0, b, 0, a.length);
        b[a.length] = v;
        return b;
    }
}
