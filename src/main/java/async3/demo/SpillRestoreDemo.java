package async3.demo;

import async3.bench.BenchmarkSamples;
import async3.samples.Samples;
import async3.transform.AsyncTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.BitSet;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Shows the reduced spill/restore traffic produced by the minimal-restore optimization.
 *
 * <p>For each suspension point in a method the demo prints: which locals are in scope (were
 * present in the frame at the await call), which are live (actually needed after resume), and
 * which are dead (elided from both spill and restore). For live locals it also shows the pair
 * of instructions the generated state machine emits — one to read from the frame store on the
 * slow path, one to write into the JVM local slot before jumping to the resume label.
 *
 * <p>Two contrasting shapes are shown:
 * <ul>
 *   <li>{@code chain3} — three {@code CompletableFuture} params consumed one-per-await;
 *       each successive resume restores fewer refs, and the dead ones are also absent from
 *       the preceding spill (so the frame shrinks as the chain progresses).</li>
 *   <li>{@code mixedPrims} — a {@code long} param ({@code scale}) whose only use is computing
 *       {@code l = scale * 3} before the first await; dead at every suspension, so it is
 *       elided from all spills and restores — the aligned prim+ref optimization.</li>
 * </ul>
 *
 * Run: {@code mvn -q compile exec:java -Dexec.mainClass=async3.demo.SpillRestoreDemo}
 */
public class SpillRestoreDemo {

    public static void main(String[] args) throws Exception {
        report(BenchmarkSamples.class, "chain3");
        report(Samples.class, "mixedPrims");
    }

    // ── core ─────────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static void report(Class<?> host, String methodName) throws Exception {
        byte[] bytes = classBytes(host);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(methodName));

        Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter()).analyze(cn.name, mn);
        BitSet[] liveOut = AsyncTransformer.liveOut(mn);
        AbstractInsnNode[] insns = mn.instructions.toArray();

        System.out.printf("%n══════════════════════════════════════════════════════════════%n");
        System.out.printf("  %s.%s%n", host.getSimpleName(), methodName);
        System.out.printf("══════════════════════════════════════════════════════════════%n%n");

        int state = 0, totalScope = 0, totalLive = 0;

        for (int i = 0; i < insns.length; i++) {
            if (!isAwait(insns[i])) continue;
            state++;

            Frame<BasicValue> f = frames[i];
            BitSet live = liveOut[i];

            // count before printing so the header can include the summary
            int inScope = 0, restored = 0;
            for (int v = 0; v < mn.maxLocals; v++) {
                if (!isSpillable(f.getLocal(v))) continue;
                inScope++;
                if (live.get(v)) restored++;
            }
            totalScope += inScope;
            totalLive += restored;

            System.out.printf("  Suspension %d  —  %d of %d locals restored, %d elided%n",
                    state, restored, inScope, inScope - restored);

            for (int v = 0; v < mn.maxLocals; v++) {
                BasicValue val = f.getLocal(v);
                if (!isSpillable(val)) continue;
                boolean isLive = live.get(v);
                String name = localName(mn, v, i);
                String type = shortType(val.getType());

                if (isLive) {
                    // The generated restore section: load from frame store, write to JVM slot
                    String load  = frameLoad(val.getType(), v);
                    String store = storeInsn(val.getType(), v + 2); // OFF = 2
                    System.out.printf("    slot %-2d  %-10s %-8s  restored   %s  →  %s%n",
                            v, name, type, load, store);
                } else {
                    System.out.printf("    slot %-2d  %-10s %-8s  ELIDED%n", v, name, type);
                }
            }
            System.out.println();
        }

        int elided = totalScope - totalLive;
        double pct = totalScope == 0 ? 0 : 100.0 * elided / totalScope;
        System.out.printf("  Summary: %d restores of %d possible  —  %d elided (%.0f%%)%n%n",
                totalLive, totalScope, elided, pct);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    static boolean isAwait(AbstractInsnNode n) {
        return n instanceof MethodInsnNode m
                && m.name.equals(AsyncTransformer.AWAIT_NAME)
                && (m.owner.equals(AsyncTransformer.AWAIT_OWNER)
                    || m.owner.equals(AsyncTransformer.AWAIT_OWNER_LAMBDA));
    }

    static boolean isSpillable(BasicValue v) {
        return v != null && v != BasicValue.UNINITIALIZED_VALUE
                && v.getType() != null && v.getType().getSort() != Type.VOID;
    }

    /** Name of the local variable at {@code slot} at instruction {@code instrIdx}, from the LVT. */
    static String localName(MethodNode mn, int slot, int instrIdx) {
        List<LocalVariableNode> lvt = mn.localVariables;
        if (lvt == null) return "?" + slot;
        for (LocalVariableNode lv : lvt) {
            if (lv.index != slot) continue;
            int start = mn.instructions.indexOf(lv.start);
            int end   = mn.instructions.indexOf(lv.end);
            if (instrIdx >= start && instrIdx < end) return lv.name;
        }
        return "?" + slot;
    }

    /** Human-readable short type name. */
    static String shortType(Type t) {
        return switch (t.getSort()) {
            case Type.INT     -> "int";
            case Type.LONG    -> "long";
            case Type.FLOAT   -> "float";
            case Type.DOUBLE  -> "double";
            case Type.BOOLEAN -> "boolean";
            case Type.BYTE    -> "byte";
            case Type.SHORT   -> "short";
            case Type.CHAR    -> "char";
            case Type.ARRAY   -> shortType(t.getElementType()) + "[]";
            case Type.OBJECT  -> {
                String n = t.getInternalName();
                int s = n.lastIndexOf('/');
                yield s >= 0 ? n.substring(s + 1) : n;
            }
            default -> t.getClassName();
        };
    }

    /** The instruction that reads local {@code slot} back from the frame store on resume. */
    static String frameLoad(Type t, int slot) {
        return switch (t.getSort()) {
            case Type.OBJECT, Type.ARRAY -> "refs[" + slot + "]";
            case Type.LONG               -> "lgetP(prims," + slot + ")";
            case Type.DOUBLE             -> "dgetP(prims," + slot + ")";
            case Type.FLOAT              -> "fgetP(prims," + slot + ")";
            default                      -> "igetP(prims," + slot + ")";
        };
    }

    /** The JVM store instruction that writes the value into its (shifted) local slot. */
    static String storeInsn(Type t, int generatedSlot) {
        String op = switch (t.getSort()) {
            case Type.OBJECT, Type.ARRAY -> "ASTORE";
            case Type.LONG               -> "LSTORE";
            case Type.DOUBLE             -> "DSTORE";
            case Type.FLOAT              -> "FSTORE";
            default                      -> "ISTORE";
        };
        return op + " " + generatedSlot;
    }

    static byte[] classBytes(Class<?> c) throws Exception {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("not found: " + res);
            return in.readAllBytes();
        }
    }
}
