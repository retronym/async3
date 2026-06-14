package async3.bench;

import async3.runtime.FutureStateMachine;
import async3.transform.AsyncTransformer;
import org.objectweb.asm.Type;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.InputStream;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the three frame stores ({@code array-spill} / {@code array-live} /
 * {@code typed-fields}) across method shapes that expose the axes described in docs/DESIGN.md §9.
 *
 * <h2>Two path variants per shape</h2>
 * <ul>
 *   <li><b>fast path</b> ({@code _fast}): all futures are pre-completed. The state machine runs
 *       synchronously top-to-bottom — no callback, no thread switch. Measures state-machine
 *       dispatch and per-access / spill overhead without suspension noise.
 *       <em>Note:</em> the JIT may scalar-replace the SM object on this path (no escape through
 *       {@code whenComplete}), which can eliminate live-store per-access overhead. That IS the
 *       real behaviour; the real path forces escape.</li>
 *   <li><b>real path</b> ({@code _real}): fresh futures are created, the SM suspends at each
 *       await, and futures are completed one by one on the same thread. Because
 *       {@link CompletableFuture#whenComplete} fires synchronously in the completing thread when
 *       the future is already registered, the whole chain runs on one thread with no scheduling
 *       — but the SM object escapes through the callback, defeating scalar replacement and showing
 *       raw memory-access costs.</li>
 * </ul>
 *
 * <h2>Key hypotheses (docs/DESIGN.md §9)</h2>
 * <ol>
 *   <li>{@code array-spill} pays O(N) spill cost at every suspension, even on the fast path;
 *       live stores pay nothing for locals at suspension.</li>
 *   <li>{@code array-live} / {@code typed-fields} pay per-access overhead on every body
 *       reference — most visible in {@code hotInner} where loop variables are read/written 300×
 *       between the single suspension.</li>
 *   <li>{@code typed-fields} has no bounds check and no long-normalization vs. {@code array-live};
 *       the difference is clearest for wide primitive live-sets and many accesses.</li>
 * </ol>
 *
 * <h2>Running</h2>
 * <pre>
 *   mvn clean package -DskipTests
 *   java -jar target/benchmarks.jar                                          # all benchmarks
 *   java -jar target/benchmarks.jar FrameStoreBenchmark.hotInner -f 2 -wi 5 -i 10
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class FrameStoreBenchmark {

    @Param({"array-spill", "array-live", "typed-fields"})
    public String store;

    // One handle per sample method, pre-built under the chosen store in @Setup.
    // Handle type: (entry params...) -> CompletableFuture<Object>
    private MethodHandle single;
    private MethodHandle wide;
    private MethodHandle chain3;
    private MethodHandle hotInner;
    private MethodHandle loopAwait8;
    private MethodHandle awaitThenLoop;

    // ---- pre-completed futures for the fast path ----

    private static final CompletableFuture<Integer> F1 = CompletableFuture.completedFuture(1);
    private static final CompletableFuture<Integer> F100 = CompletableFuture.completedFuture(100);

    // array for awaitThenLoop: 100 elements, values 0..99
    private static final int[] ARR;
    static {
        ARR = new int[100];
        for (int i = 0; i < ARR.length; i++) ARR[i] = i;
    }
    private static final CompletableFuture<Integer> F2 = CompletableFuture.completedFuture(2);
    private static final CompletableFuture<Integer> F3 = CompletableFuture.completedFuture(3);

    @SuppressWarnings("unchecked")
    private static final CompletableFuture<Integer>[] DONE8 = new CompletableFuture[]{
        CompletableFuture.completedFuture(1), CompletableFuture.completedFuture(2),
        CompletableFuture.completedFuture(3), CompletableFuture.completedFuture(4),
        CompletableFuture.completedFuture(5), CompletableFuture.completedFuture(6),
        CompletableFuture.completedFuture(7), CompletableFuture.completedFuture(8),
    };

    // ---- FutureStateMachine.start(), used to turn constructors into invokers ----

    private static final MethodHandle START;
    static {
        try {
            START = MethodHandles.lookup().findVirtual(FutureStateMachine.class, "start",
                    MethodType.methodType(CompletableFuture.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ---- setup: transform BenchmarkSamples under the chosen store ----

    @Setup(Level.Trial)
    public void setup() throws Throwable {
        byte[] hostBytes = classBytes(BenchmarkSamples.class);
        ClassLoader loader  = BenchmarkSamples.class.getClassLoader();
        MethodHandles.Lookup lookup = BenchmarkSamples.lookup();

        single       = buildHandle(lookup, loader, hostBytes, "single");
        wide         = buildHandle(lookup, loader, hostBytes, "wide");
        chain3       = buildHandle(lookup, loader, hostBytes, "chain3");
        hotInner     = buildHandle(lookup, loader, hostBytes, "hotInner");
        loopAwait8   = buildHandle(lookup, loader, hostBytes, "loopAwait8");
        awaitThenLoop = buildHandle(lookup, loader, hostBytes, "awaitThenLoop");
    }

    /**
     * Transforms one method of {@link BenchmarkSamples} under {@link #store}, defines the
     * resulting state machine as a NESTMATE hidden class of {@code BenchmarkSamples}, and returns
     * an invoker: {@code (entry-params...) -> CompletableFuture<Object>}.
     */
    private MethodHandle buildHandle(MethodHandles.Lookup lookup, ClassLoader loader,
                                     byte[] hostBytes, String name) throws Throwable {
        String desc = descriptor(name);
        AsyncTransformer.SingleMethod sm =
                AsyncTransformer.transformMethodElevated(hostBytes, name, desc, store);
        MethodHandles.Lookup smLookup = lookup.defineHiddenClass(
                sm.bytes, false, MethodHandles.Lookup.ClassOption.NESTMATE);
        MethodType ctorType = MethodType.fromMethodDescriptorString(sm.constructorDescriptor, loader);
        MethodHandle ctor   = smLookup.findConstructor(smLookup.lookupClass(), ctorType);
        MethodHandle asFsm  = ctor.asType(ctor.type().changeReturnType(FutureStateMachine.class));
        return MethodHandles.filterReturnValue(asFsm, START);
    }

    // ---- 1. single: one await, no extra locals ----
    // Hypothesis: all stores should be close here; serves as a baseline.

    /** Fast path: future already complete; SM runs synchronously. */
    @Benchmark
    public Object single_fast() throws Throwable {
        return join(single.invokeWithArguments(F1));
    }

    /** Real path: SM suspends once, resumed synchronously on the completing thread. */
    @Benchmark
    public Object single_real() throws Throwable {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> r = (CompletableFuture<Object>) single.invokeWithArguments(f);
        f.complete(1);
        return r.join();
    }

    // ---- 2. wide: five int locals across one suspension ----
    // Hypothesis: array-spill pays 5× spill + 5× restore even on fast path;
    //             array-live and typed-fields pay nothing for locals at suspension.

    @Benchmark
    public Object wide_fast() throws Throwable {
        return join(wide.invokeWithArguments(F1, 1, 2, 3, 4, 5));
    }

    @Benchmark
    public Object wide_real() throws Throwable {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> r = (CompletableFuture<Object>) wide.invokeWithArguments(f, 1, 2, 3, 4, 5);
        f.complete(1);
        return r.join();
    }

    // ---- 3. chain3: three sequential awaits ----
    // Multiplies the per-suspension cost by 3.

    @Benchmark
    public Object chain3_fast() throws Throwable {
        return join(chain3.invokeWithArguments(F1, F2, F3));
    }

    @Benchmark
    public Object chain3_real() throws Throwable {
        CompletableFuture<Integer> f1 = new CompletableFuture<>(),
                                   f2 = new CompletableFuture<>(),
                                   f3 = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> r = (CompletableFuture<Object>) chain3.invokeWithArguments(f1, f2, f3);
        f1.complete(1);
        f2.complete(2);
        f3.complete(3);
        return r.join();
    }

    // ---- 4. hotInner: 100-iteration inner loop + one suspension (Kotlin hot-loop case) ----
    // Hypothesis: array-live / typed-fields pay a per-access overhead (~300 Frames/field ops)
    //             that array-spill avoids (loop vars live in free JVM slots).
    //             The real path forces SM escape, showing raw memory-access costs.

    @Benchmark
    public Object hotInner_fast() throws Throwable {
        return join(hotInner.invokeWithArguments(F1));
    }

    @Benchmark
    public Object hotInner_real() throws Throwable {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> r = (CompletableFuture<Object>) hotInner.invokeWithArguments(f);
        f.complete(1);
        return r.join();
    }

    // ---- 5. loopAwait8: eight sequential awaits inside a loop ----
    // Hypothesis: per-suspension costs accumulate × 8; typed-fields promotes sum/i to typed
    //             fields and writes them back at every one of the 8 suspensions.

    @Benchmark
    public Object loopAwait_fast() throws Throwable {
        return join(loopAwait8.invokeWithArguments((Object) DONE8));
    }

    @Benchmark
    public Object loopAwait_real() throws Throwable {
        @SuppressWarnings("unchecked")
        CompletableFuture<Integer>[] fs = new CompletableFuture[8];
        for (int i = 0; i < 8; i++) fs[i] = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> r = (CompletableFuture<Object>) loopAwait8.invokeWithArguments((Object) fs);
        // Each complete fires the SM synchronously: the chain runs on this thread.
        for (int i = 0; i < 8; i++) fs[i].complete(i + 1);
        return r.join();
    }

    // ---- 6. awaitThenLoop: await a bound, then loop up to it ----
    // Hypothesis: hi is live across the suspension and therefore lives in a SM field for
    // array-live / typed-fields, but is restored to a free JVM local for array-spill.
    // The JIT hoists a local trivially; hoisting a field load from the loop condition
    // requires alias analysis. array-live is expected worst (indexed array load);
    // typed-fields may or may not hoist; array-spill is the baseline (always a local).

    @Benchmark
    public Object awaitThenLoop_fast() throws Throwable {
        return join(awaitThenLoop.invokeWithArguments(F100, ARR));
    }

    @Benchmark
    public Object awaitThenLoop_real() throws Throwable {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> r = (CompletableFuture<Object>) awaitThenLoop.invokeWithArguments(f, ARR);
        f.complete(100);
        return r.join();
    }

    // ---- helpers ----

    private static Object join(Object cf) {
        return ((CompletableFuture<?>) cf).join();
    }

    private static byte[] classBytes(Class<?> c) throws Exception {
        String resource = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("not found: " + resource);
            return in.readAllBytes();
        }
    }

    /** ASM-derived descriptor for the named method in {@link BenchmarkSamples}. */
    private static String descriptor(String name) throws NoSuchMethodException {
        for (Method m : BenchmarkSamples.class.getDeclaredMethods())
            if (m.getName().equals(name))
                return Type.getMethodDescriptor(m);
        throw new NoSuchMethodException("BenchmarkSamples." + name);
    }

    // ---- entry point ----

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(FrameStoreBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}
