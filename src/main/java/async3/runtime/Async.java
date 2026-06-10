package async3.runtime;

import async3.transform.AsyncTransformer;

import java.io.InputStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lambda-based front end: the transform is triggered at runtime by <em>cracking</em> the lambda.
 *
 * <pre>{@code
 * CompletableFuture<String> r = Async.async(() -> {
 *     int x = Async.await(fa);
 *     String s = Async.await(fb);
 *     return s + ":" + (x * 2);
 * });
 * }</pre>
 *
 * <p>How it works: {@link AsyncBody} extends {@link Serializable}, so javac emits a
 * {@code writeReplace} on the lambda proxy. Invoking it yields a {@link SerializedLambda}, which
 * names the synthetic {@code lambda$...} impl method (where the body's bytecode actually lives,
 * in the capturing class) and carries the captured arguments. The impl method is run through
 * {@link AsyncTransformer#transformMethod} and the state machine is defined as a
 * <em>hidden class with the NESTMATE option</em>, so the transformed body keeps access to
 * private members of the capturing class — exactly what lambda bodies are allowed to touch.
 * Captured locals (including a captured {@code this}) are the impl method's leading
 * parameters, which the state machine constructor spills like any other entry locals.
 * Compilation happens once per impl method; subsequent calls reuse the cached constructor.
 *
 * <p>This is a faithful stand-in for the eventual compiler integration: an {@code async { ... }}
 * compiled to {@code invokedynamic} hands its bootstrap the caller's {@link MethodHandles.Lookup}
 * with no cracking or reflection needed — everything downstream of the lookup is identical.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>agent-prepared (preferred when available)</b>: if the class was loaded under
 *       {@code -javaagent:async3.jar}, the resumable body already exists as a sibling method of
 *       the capturing class and {@code async}/{@code lift} simply bind its {@code m$async}
 *       entry — no cracking-time transformation, full private access, and IDE breakpoints in
 *       the body bind naturally;</li>
 *   <li>default: hidden class + NESTMATE (no class name leaks, full private access to the
 *       capturing class). IDE line breakpoints inside the lambda body will NOT bind: IntelliJ
 *       resolves such lines only against classes named like the enclosing source class;</li>
 *   <li>{@code -Dasync3.lambda.debuggable=true}: defines the state machine as an ordinary class
 *       <em>named exactly like the capturing class</em>, in a throwaway loader (JDI matches
 *       classes by name across loaders, so the IDE finds it and line breakpoints in the lambda
 *       body bind and fire). The trade-off: from inside the shadow class, the host's name
 *       resolves to the shadow itself, so lambdas that reference the host class — captured
 *       {@code this}, same-class helper calls, nested lambdas — are rejected with an
 *       explanatory error in this mode;</li>
 *   <li>{@code -Dasync3.lambda.dump=<dir>}: also writes the generated class for javap.</li>
 * </ul>
 *
 * <p>An eventual compiler/agent integration sidesteps the trade-off entirely by emitting the
 * resumable body as a sibling method <em>of the capturing class itself</em> (the
 * `externalFsmMethod` shape of the annotation-driven frontend) — then breakpoints, nest access,
 * and stepping all work with no shadowing.
 */
public final class Async {
    private Async() {}

    /** Serializable so the lambda can be cracked via {@code writeReplace}. */
    @FunctionalInterface
    public interface AsyncBody<T> extends Serializable {
        T call();
    }

    /** The await marker; identical semantics to {@link AsyncRT#await} (tier 0 blocks). */
    public static <T> T await(CompletableFuture<T> future) {
        return AsyncRT.await(future);
    }

    /**
     * Runs {@code body} as a state machine. Resolves private access via
     * {@code privateLookupIn(capturingClass)}, which works whenever the capturing class is
     * accessible to this class's module (always true on the plain classpath).
     */
    public static <T> CompletableFuture<T> async(AsyncBody<T> body) {
        return asyncImpl(null, body);
    }

    /** Variant for named-module callers: pass {@code MethodHandles.lookup()} from the call site. */
    public static <T> CompletableFuture<T> async(MethodHandles.Lookup lookup, AsyncBody<T> body) {
        return asyncImpl(lookup, body);
    }

    // ------------------------------------------------------------------ lift: method references

    // Serializable SAM types so a method reference can be cracked. Arity disambiguates overloads.
    // Fn0..Fn22, Lifted0..Lifted22 and the lift overloads below are generated, up to arity 22
    // (the traditional Scala ceiling). Regenerate with `jshell -q gen.jsh` where gen.jsh is:
    //
    //   int N = 22;
    //   StringBuilder fn = new StringBuilder(), li = new StringBuilder(), ov = new StringBuilder();
    //   for (int n = 0; n <= N; n++) {
    //       StringJoiner tp = new StringJoiner(", "), ps = new StringJoiner(", "), as = new StringJoiner(", ");
    //       for (int i = 1; i <= n; i++) { tp.add("T" + i); ps.add("T" + i + " t" + i); as.add("t" + i); }
    //       String tpr = (n == 0 ? "" : tp + ", ") + "R";
    //       fn.append("    @FunctionalInterface public interface Fn" + n + "<" + tpr
    //               + "> extends Serializable { R apply(" + ps + "); }\n");
    //       li.append("    @FunctionalInterface public interface Lifted" + n + "<" + tpr
    //               + "> { CompletableFuture<R> apply(" + ps + "); }\n");
    //       String lam = n == 1 ? "t1" : "(" + as + ")";
    //       ov.append("    public static <" + tpr + "> Lifted" + n + "<" + tpr + "> lift(Fn" + n + "<" + tpr
    //               + "> ref) {\n        Lifted l = liftImpl(ref);\n        return " + lam
    //               + " -> cast(l.invoke(" + as + "));\n    }\n\n");
    //   }
    //   System.out.println(fn + "\n" + li + "\n" + ov);
    //   /exit

    @FunctionalInterface public interface Fn0<R> extends Serializable { R apply(); }
    @FunctionalInterface public interface Fn1<T1, R> extends Serializable { R apply(T1 t1); }
    @FunctionalInterface public interface Fn2<T1, T2, R> extends Serializable { R apply(T1 t1, T2 t2); }
    @FunctionalInterface public interface Fn3<T1, T2, T3, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3); }
    @FunctionalInterface public interface Fn4<T1, T2, T3, T4, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4); }
    @FunctionalInterface public interface Fn5<T1, T2, T3, T4, T5, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5); }
    @FunctionalInterface public interface Fn6<T1, T2, T3, T4, T5, T6, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6); }
    @FunctionalInterface public interface Fn7<T1, T2, T3, T4, T5, T6, T7, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7); }
    @FunctionalInterface public interface Fn8<T1, T2, T3, T4, T5, T6, T7, T8, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8); }
    @FunctionalInterface public interface Fn9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9); }
    @FunctionalInterface public interface Fn10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10); }
    @FunctionalInterface public interface Fn11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11); }
    @FunctionalInterface public interface Fn12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12); }
    @FunctionalInterface public interface Fn13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13); }
    @FunctionalInterface public interface Fn14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14); }
    @FunctionalInterface public interface Fn15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15); }
    @FunctionalInterface public interface Fn16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16); }
    @FunctionalInterface public interface Fn17<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17); }
    @FunctionalInterface public interface Fn18<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18); }
    @FunctionalInterface public interface Fn19<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19); }
    @FunctionalInterface public interface Fn20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19, T20 t20); }
    @FunctionalInterface public interface Fn21<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19, T20 t20, T21 t21); }
    @FunctionalInterface public interface Fn22<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, R> extends Serializable { R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19, T20 t20, T21 t21, T22 t22); }

    @FunctionalInterface public interface Lifted0<R> { CompletableFuture<R> apply(); }
    @FunctionalInterface public interface Lifted1<T1, R> { CompletableFuture<R> apply(T1 t1); }
    @FunctionalInterface public interface Lifted2<T1, T2, R> { CompletableFuture<R> apply(T1 t1, T2 t2); }
    @FunctionalInterface public interface Lifted3<T1, T2, T3, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3); }
    @FunctionalInterface public interface Lifted4<T1, T2, T3, T4, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4); }
    @FunctionalInterface public interface Lifted5<T1, T2, T3, T4, T5, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5); }
    @FunctionalInterface public interface Lifted6<T1, T2, T3, T4, T5, T6, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6); }
    @FunctionalInterface public interface Lifted7<T1, T2, T3, T4, T5, T6, T7, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7); }
    @FunctionalInterface public interface Lifted8<T1, T2, T3, T4, T5, T6, T7, T8, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8); }
    @FunctionalInterface public interface Lifted9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9); }
    @FunctionalInterface public interface Lifted10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10); }
    @FunctionalInterface public interface Lifted11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11); }
    @FunctionalInterface public interface Lifted12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12); }
    @FunctionalInterface public interface Lifted13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13); }
    @FunctionalInterface public interface Lifted14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14); }
    @FunctionalInterface public interface Lifted15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15); }
    @FunctionalInterface public interface Lifted16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16); }
    @FunctionalInterface public interface Lifted17<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17); }
    @FunctionalInterface public interface Lifted18<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18); }
    @FunctionalInterface public interface Lifted19<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19); }
    @FunctionalInterface public interface Lifted20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19, T20 t20); }
    @FunctionalInterface public interface Lifted21<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19, T20 t20, T21 t21); }
    @FunctionalInterface public interface Lifted22<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, R> { CompletableFuture<R> apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15, T16 t16, T17 t17, T18 t18, T19 t19, T20 t20, T21 t21, T22 t22); }

    /**
     * Lifts the referenced method into its suspending variant: the runtime analogue of the
     * annotation-driven frontend's direct/{@code $queued} method pair. The target — possibly in
     * another class — is any method whose bytecode calls the await markers; called directly it
     * blocks (tier 0), called through the lifted handle it suspends. Works with static,
     * unbound-instance ({@code C::m} — the receiver becomes the first parameter), and
     * bound-instance ({@code obj::m} — the receiver is a captured argument) references, and with
     * lambdas. A target with no awaits lifts to a trivially-completed future. Compiled once per
     * target method, cached.
     */
    public static <R> Lifted0<R> lift(Fn0<R> ref) {
        Lifted l = liftImpl(ref);
        return () -> cast(l.invoke());
    }

    public static <T1, R> Lifted1<T1, R> lift(Fn1<T1, R> ref) {
        Lifted l = liftImpl(ref);
        return t1 -> cast(l.invoke(t1));
    }

    public static <T1, T2, R> Lifted2<T1, T2, R> lift(Fn2<T1, T2, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2) -> cast(l.invoke(t1, t2));
    }

    public static <T1, T2, T3, R> Lifted3<T1, T2, T3, R> lift(Fn3<T1, T2, T3, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3) -> cast(l.invoke(t1, t2, t3));
    }

    public static <T1, T2, T3, T4, R> Lifted4<T1, T2, T3, T4, R> lift(Fn4<T1, T2, T3, T4, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4) -> cast(l.invoke(t1, t2, t3, t4));
    }

    public static <T1, T2, T3, T4, T5, R> Lifted5<T1, T2, T3, T4, T5, R> lift(Fn5<T1, T2, T3, T4, T5, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5) -> cast(l.invoke(t1, t2, t3, t4, t5));
    }

    public static <T1, T2, T3, T4, T5, T6, R> Lifted6<T1, T2, T3, T4, T5, T6, R> lift(Fn6<T1, T2, T3, T4, T5, T6, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6) -> cast(l.invoke(t1, t2, t3, t4, t5, t6));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, R> Lifted7<T1, T2, T3, T4, T5, T6, T7, R> lift(Fn7<T1, T2, T3, T4, T5, T6, T7, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, R> Lifted8<T1, T2, T3, T4, T5, T6, T7, T8, R> lift(Fn8<T1, T2, T3, T4, T5, T6, T7, T8, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> Lifted9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> lift(Fn9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> Lifted10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> lift(Fn10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> Lifted11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> lift(Fn11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> Lifted12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> lift(Fn12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> Lifted13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> lift(Fn13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> Lifted14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> lift(Fn14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> Lifted15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> lift(Fn15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> Lifted16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> lift(Fn16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, R> Lifted17<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, R> lift(Fn17<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R> Lifted18<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R> lift(Fn18<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, R> Lifted19<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, R> lift(Fn19<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> Lifted20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> lift(Fn20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, R> Lifted21<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, R> lift(Fn21<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21));
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, R> Lifted22<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, R> lift(Fn22<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, R> ref) {
        Lifted l = liftImpl(ref);
        return (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22) -> cast(l.invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22));
    }

    private static Lifted liftImpl(Serializable ref) {
        SerializedLambda sl = crack(ref);
        if (sl.getImplMethodKind() == java.lang.invoke.MethodHandleInfo.REF_newInvokeSpecial)
            throw new UnsupportedOperationException(
                    "constructor references cannot be lifted (await is not supported in constructors)");
        MethodHandle invoker = invokerFor(null, ref, sl);
        return new Lifted(invoker, capturedArgs(sl));
    }

    /**
     * Invokes the cached invoker — either the agent-prepared {@code m$async} entry point, or a
     * state machine constructor filtered through {@code start()} — with captured arguments
     * (bound receiver, for {@code obj::m}) followed by per-call arguments. Together they form
     * the target method's entry locals in order, for every reference shape.
     */
    private record Lifted(MethodHandle invoker, Object[] captured) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> invoke(Object... args) {
            Object[] all = new Object[captured.length + args.length];
            System.arraycopy(captured, 0, all, 0, captured.length);
            System.arraycopy(args, 0, all, captured.length, args.length);
            try {
                return (CompletableFuture<Object>) invoker.invokeWithArguments(all);
            } catch (Throwable t) {
                throw AsyncRT.sneakyThrow(t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <R> CompletableFuture<R> cast(CompletableFuture<Object> f) {
        return (CompletableFuture<R>) (CompletableFuture<?>) f;
    }

    private static final ConcurrentHashMap<String, MethodHandle> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    private static <T> CompletableFuture<T> asyncImpl(MethodHandles.Lookup lookup, AsyncBody<T> body) {
        SerializedLambda sl = crack(body);
        MethodHandle invoker = invokerFor(lookup, body, sl);
        return cast(new Lifted(invoker, capturedArgs(sl)).invoke());
    }

    private static MethodHandle invokerFor(MethodHandles.Lookup lookup, Serializable ref, SerializedLambda sl) {
        String key = (debuggable() ? "shadow:" : "hidden:")
                + sl.getImplClass() + "." + sl.getImplMethodName() + sl.getImplMethodSignature();
        return CONSTRUCTOR_CACHE.computeIfAbsent(key, k -> compile(lookup, ref.getClass().getClassLoader(), sl));
    }

    /** {@code FutureStateMachine.start()}, used to adapt constructor handles into invokers. */
    private static final MethodHandle START;
    static {
        try {
            START = MethodHandles.lookup().findVirtual(FutureStateMachine.class, "start",
                    MethodType.methodType(CompletableFuture.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle toInvoker(MethodHandle ctor) {
        MethodHandle asFsm = ctor.asType(ctor.type().changeReturnType(FutureStateMachine.class));
        return MethodHandles.filterReturnValue(asFsm, START);
    }

    /**
     * If the agent prepared this method at class load time, use its {@code m$async} entry point:
     * the resumable body then executes in the host class itself (breakpoints bind, private
     * access is same-class), and no cracking-time transformation happens at all. AoT-transformed
     * classes carrying the same entries are picked up identically.
     */
    private static MethodHandle agentEntry(Class<?> capturingClass, MethodHandles.Lookup callerLookup,
                                           SerializedLambda sl, ClassLoader loader) {
        try {
            MethodType implType = MethodType.fromMethodDescriptorString(sl.getImplMethodSignature(), loader);
            MethodType entryType = implType.changeReturnType(CompletableFuture.class);
            MethodHandles.Lookup l = callerLookup != null
                    ? callerLookup
                    : MethodHandles.privateLookupIn(capturingClass, MethodHandles.lookup());
            String name = sl.getImplMethodName() + "$async";
            return sl.getImplMethodKind() == java.lang.invoke.MethodHandleInfo.REF_invokeStatic
                    ? l.findStatic(capturingClass, name, entryType)
                    : l.findVirtual(capturingClass, name, entryType);
        } catch (ReflectiveOperationException e) {
            return null; // no agent-prepared entry; fall back to cracking-time transformation
        }
    }

    private static Object[] capturedArgs(SerializedLambda sl) {
        Object[] captured = new Object[sl.getCapturedArgCount()];
        for (int i = 0; i < captured.length; i++) captured[i] = sl.getCapturedArg(i);
        return captured;
    }

    private static SerializedLambda crack(Serializable lambdaOrRef) {
        try {
            Method writeReplace = lambdaOrRef.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return (SerializedLambda) writeReplace.invoke(lambdaOrRef);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "expected a lambda literal or method reference, not a class implementing the interface", e);
        }
    }

    private static MethodHandle compile(MethodHandles.Lookup callerLookup, ClassLoader loader, SerializedLambda sl) {
        try {
            Class<?> capturingClass = Class.forName(sl.getImplClass().replace('/', '.'), false, loader);

            MethodHandle viaAgent = agentEntry(capturingClass, callerLookup, sl, loader);
            if (viaAgent != null) return viaAgent;

            byte[] hostBytes;
            try (InputStream in = loader.getResourceAsStream(sl.getImplClass() + ".class")) {
                if (in == null)
                    throw new IllegalStateException("cannot read bytecode of " + sl.getImplClass());
                hostBytes = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }

            boolean shadow = debuggable();
            AsyncTransformer.SingleMethod sm = AsyncTransformer.transformMethod(
                    hostBytes, sl.getImplMethodName(), sl.getImplMethodSignature(), shadow);

            String dumpDir = System.getProperty("async3.lambda.dump");
            if (dumpDir != null) {
                // shadow classes share the host's name; suffix the dump file to avoid clobbering
                Path p = Path.of(dumpDir).resolve(
                        sm.name.replace('.', '/') + (shadow ? "$shadow$" + sl.getImplMethodName() : "") + ".class");
                Files.createDirectories(p.getParent());
                Files.write(p, sm.bytes);
            }

            if (shadow) {
                Class<?> smClass = new OneShotLoader(loader).define(sm.name, sm.bytes);
                return toInvoker(MethodHandles.lookup().unreflectConstructor(smClass.getDeclaredConstructors()[0]));
            }

            MethodHandles.Lookup lookup = callerLookup != null
                    ? callerLookup
                    : MethodHandles.privateLookupIn(capturingClass, MethodHandles.lookup());
            MethodHandles.Lookup smLookup =
                    lookup.defineHiddenClass(sm.bytes, false, MethodHandles.Lookup.ClassOption.NESTMATE);
            MethodType ctorType = MethodType.fromMethodDescriptorString(sm.constructorDescriptor, loader);
            return toInvoker(smLookup.findConstructor(smLookup.lookupClass(), ctorType));
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new IllegalStateException("failed to compile async lambda " + sl.getImplMethodName(), e);
        }
    }

    private static boolean debuggable() {
        return Boolean.getBoolean("async3.lambda.debuggable");
    }

    private static final class OneShotLoader extends ClassLoader {
        OneShotLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
