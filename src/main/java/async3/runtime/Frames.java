package async3.runtime;

/**
 * Static accessors for the generic two-array frame ({@link FutureStateMachine#refs} /
 * {@link FutureStateMachine#prims}), bundling the array access with the primitive↔long
 * normalization so the transform emits one {@code INVOKESTATIC} per slot instead of an inline
 * {@code getfield + index + convert + array-op} sequence (docs/DESIGN.md §9).
 *
 * <p>Every method is tiny and side-effect-free, so the JIT inlines it back to a bounds-checked
 * array op — the abstraction costs nothing after compilation. The {@code array-spill} store uses
 * these to shrink its float/double/long spill code; the {@code array-live} store uses them as its
 * per-access read/write layer. (The {@code typed-fields} store needs none of this — it accesses a
 * field of the value's real type directly.)
 *
 * <p>All of {@code boolean/byte/char/short/int} normalize to an {@code int} view ({@link #igetP}/
 * {@link #isetP}); the JVM treats those as ints on the stack anyway.
 */
public final class Frames {
    private Frames() {}

    public static int igetP(long[] p, int i) { return (int) p[i]; }
    public static void isetP(long[] p, int i, int v) { p[i] = v; }

    public static long lgetP(long[] p, int i) { return p[i]; }
    public static void lsetP(long[] p, int i, long v) { p[i] = v; }

    public static float fgetP(long[] p, int i) { return Float.intBitsToFloat((int) p[i]); }
    public static void fsetP(long[] p, int i, float v) { p[i] = Float.floatToRawIntBits(v); }

    public static double dgetP(long[] p, int i) { return Double.longBitsToDouble(p[i]); }
    public static void dsetP(long[] p, int i, double v) { p[i] = Double.doubleToRawLongBits(v); }

    public static Object getR(Object[] r, int i) { return r[i]; }
    public static void setR(Object[] r, int i, Object v) { r[i] = v; }
}
