package async3.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 1 of the async3 prototype: rewrites methods containing calls to the
 * {@code AsyncRT.await} marker into resumable state machines, at the bytecode level.
 *
 * <p>For each such method {@code m} this produces:
 * <ul>
 *   <li>a state machine class {@code Owner$async$m$i extends FutureStateMachine} whose
 *       {@code apply(Object tr)} is the transformed copy of {@code m}'s body;</li>
 *   <li>a sibling entry point {@code m$async(args): CompletableFuture} on the original class;</li>
 *   <li>the original (blocking) {@code m} untouched — the synchronous tier.</li>
 * </ul>
 *
 * <p>The rewrite at each await call site i (operand stack: {@code [below..., future]}):
 * <pre>
 *   state = i
 *   tr = getCompleted(future)            // fast path for already-completed futures
 *   if (tr != null) goto resume_i        // locals and stack still intact
 *   spill locals and below-stack into the refs/prims frame
 *   onComplete(future); return           // park
 * resume_i:                              // also targeted by the dispatch switch, after restore
 *   push tryGet(tr)                      // value, or rethrows the failure into original handlers
 * </pre>
 * The method prologue dispatches on {@code state}: case 0 restores the constructor-spilled
 * parameters; case i restores the locals and operand stack recorded for await site i and jumps
 * to {@code resume_i}.
 *
 * <p>{@code new Foo(await(f))} — an uninitialized object on the stack at the suspension point,
 * which cannot be spilled to the heap — is handled by a pre-pass ({@link #sinkUninitializedNews})
 * that deletes the {@code NEW}(+{@code DUP}) and re-materializes it just below the constructor
 * arguments at the {@code INVOKESPECIAL <init>} site, the same strategy as Kotlin coroutines.
 * (Observable difference: the class-initialization side effect of {@code NEW} moves after the
 * evaluation of the constructor arguments; Kotlin accepts the same.)
 *
 * <p>Spilling is liveness-aware for references: dead ref locals are not spilled and every ref
 * frame slot not written at a suspension point is nulled ({@link Liveness}, the bytecode
 * analogue of the tree transform's fieldsToNullOut), so a suspended frame never pins values
 * the resumed code cannot read. Primitives are spilled regardless (no pinning concern).
 *
 * <p>Current limitations (deliberate, see docs/DESIGN.md): rejects suspension while a monitor
 * is held; await in constructors is rejected.
 */
public final class AsyncTransformer {

    public static final String AWAIT_OWNER = "async3/runtime/AsyncRT";
    public static final String AWAIT_OWNER_LAMBDA = "async3/runtime/Async";
    public static final String AWAIT_NAME = "await";
    public static final String AWAIT_DESC = "(Ljava/util/concurrent/CompletableFuture;)Ljava/lang/Object;";

    static final String FSM = "async3/runtime/FutureStateMachine";
    static final String FSM_DESC = "L" + FSM + ";";
    static final String DSM = "async3/runtime/DelegatingStateMachine";
    static final String FRAMES = "async3/runtime/Frames";
    static final String REFS_DESC = "[Ljava/lang/Object;";
    static final String PRIMS_DESC = "[J";
    static final String CF = "java/util/concurrent/CompletableFuture";
    static final String CF_DESC = "L" + CF + ";";
    /** Descriptor of the externalized resumable body: {@code (stateMachine, tr) -> void}. */
    static final String BODY_DESC = "(" + FSM_DESC + "Ljava/lang/Object;)V";

    /** Strategy-B bootstrap for elevating a virtually dispatched call at the call site. */
    static final Handle ELEVATE_BSM = new Handle(H_INVOKESTATIC, "async3/runtime/Elevation", "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);

    /**
     * Where captured state lives (docs/DESIGN.md §9), chosen by {@code -Dasync3.frame}. Read at
     * transform time so it shapes the generated code. {@code array-spill} keeps locals in their
     * JVM slots and copies them to the two-array frame only across a suspension (today's default,
     * best debuggability); {@code array-live} makes the array slot the local's only home, accessed
     * in place via {@link async3.runtime.Frames} (no local spill/restore — only the operand stack
     * is spilled).
     */
    enum FrameMode {
        ARRAY_SPILL, ARRAY_LIVE, TYPED_FIELDS;
        /** The global default, from {@code -Dasync3.frame}; a per-method override flows in as a parameter. */
        static FrameMode current() { return from(System.getProperty("async3.frame", "array-spill")); }
        static FrameMode from(String v) {
            return switch (v) {
                case "array-spill" -> ARRAY_SPILL;
                case "array-live" -> ARRAY_LIVE;
                case "typed-fields" -> TYPED_FIELDS;
                default -> throw new IllegalArgumentException(
                        "unknown async3.frame: " + v + " (expected array-spill | array-live | typed-fields)");
            };
        }
        /** Honored only on a generated class; the agent's shared shell falls back to an array store. */
        FrameMode withoutFields() { return this == TYPED_FIELDS ? ARRAY_SPILL : this; }
    }

    public static final class Result {
        /** Binary name of the (patched) host class. */
        public String hostName;
        public byte[] hostClass;
        /** Generated state machine classes, by binary name. */
        public final Map<String, byte[]> stateMachines = new LinkedHashMap<>();
        /** Human-readable frame-layout metadata, by "binaryName.method". */
        public final Map<String, String> debugMetadata = new LinkedHashMap<>();

        public Map<String, byte[]> allClasses() {
            Map<String, byte[]> all = new LinkedHashMap<>(stateMachines);
            all.put(hostName, hostClass);
            return all;
        }
    }

    public static Result transform(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_FRAMES);

        Result result = new Result();
        result.hostName = cn.name.replace('/', '.');
        Set<String> generatedInternalNames = new HashSet<>();
        List<MethodNode> entryPoints = new ArrayList<>();
        int index = 0;
        for (MethodNode mn : cn.methods) {
            if (!hasAwait(mn)) continue;
            checkSupported(cn, mn);
            sinkUninitializedNews(cn, mn);
            String smName = cn.name + "$async$" + mn.name + "$" + index++;
            generatedInternalNames.add(smName);
            // Join the host's nest so the transformed body keeps access to private members
            // (fields, methods) of the host, exactly as the original body had. Only possible
            // when the host is its own nest host: joining a foreign nest would require
            // patching that class too. (At runtime-deferral time, defineHiddenClass with
            // NESTMATE achieves the same.)
            boolean nestmate = cn.nestHostClass == null;
            if (nestmate) {
                if (cn.nestMembers == null) cn.nestMembers = new ArrayList<>();
                cn.nestMembers.add(smName);
            }
            StringBuilder debug = new StringBuilder();
            byte[] smBytes = generateStateMachine(cn, mn, smName, nestmate, debug, FrameMode.current());
            result.stateMachines.put(smName.replace('/', '.'), smBytes);
            result.debugMetadata.put(result.hostName + "." + mn.name, debug.toString());
            entryPoints.add(entryPoint(cn, mn, smName));
        }
        cn.methods.addAll(entryPoints);
        ClassWriter cw = new SmAwareClassWriter(generatedInternalNames);
        cn.accept(cw);
        result.hostClass = cw.toByteArray();
        return result;
    }

    // ------------------------------------------------------------------ in-place ("agent") shape

    /**
     * The agent shape: for every method containing await markers, adds to the host class itself
     * <ul>
     *   <li>a private static sibling {@code m$asyncBody(FutureStateMachine, Object)} holding the
     *       resumable body — so it executes <em>in the host class</em>: full private access with
     *       no nest tricks, and IDE line breakpoints bind naturally because the code for those
     *       source lines lives in the class the IDE expects;</li>
     *   <li>a {@code m$async} entry point that allocates a {@link async3.runtime.DelegatingStateMachine}
     *       bound to the body via an LDC MethodHandle constant — no per-method class generation
     *       at all.</li>
     * </ul>
     * The original method is untouched (the blocking tier). Must run at class <em>load</em> time
     * (e.g. from a ClassFileTransformer): retransformation cannot add methods. Returns null if
     * there is nothing to do (no markers, or already processed — idempotent under agent re-entry
     * and compatible with classes already carrying AoT-generated {@code m$async} entries).
     */
    public static byte[] transformInPlace(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_FRAMES);
        // Suspendable = directly awaits, or (transitively) calls a suspendable sibling. Computed
        // once over the original methods, before any generated members exist — so re-entry is
        // idempotent (the transformed bodies no longer carry award markers nor call originals).
        Set<String> suspendable = suspendableMethods(cn);
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode mn : new ArrayList<>(cn.methods)) {
            if (!suspendable.contains(methodKey(mn))) continue;
            String entryDesc = Type.getMethodDescriptor(Type.getObjectType(CF), Type.getArgumentTypes(mn.desc));
            if (hasMethod(cn, mn.name + "$async", entryDesc)) continue;
            checkSupported(cn, mn);
            // Work on a copy: the original method body stays byte-identical as the blocking tier.
            MethodNode work = copyOf(mn);
            // Elevate calls to suspendable siblings (invoke g -> await(g$async)) before any other
            // rewrite, so the injected suspension points flow through new-sinking and analysis.
            elevate(cn, work, suspendable);
            sinkUninitializedNews(cn, work);
            Type[] entry = entryTypes(cn, mn);
            Frame<BasicValue>[] frames = analyze(cn.name, work);
            String bodyName = uniqueMethodName(cn, added, mn.name + "$asyncBody");
            StringBuilder debug = new StringBuilder();
            debug.append("method ").append(cn.name).append('.').append(mn.name).append(mn.desc).append('\n');
            // The shared-shell body has no per-method fields, so typed-fields downgrades to an
            // array store here; the arrays live on the DelegatingStateMachine (local 0).
            FrameStore store = frameStore(FrameMode.current().withoutFields(), null, entry, work, frames,
                    work.maxLocals + work.maxStack);
            added.add(applyMethod(work, entry, frames, debug, bodyName,
                    ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, BODY_DESC, store));
            added.add(inPlaceEntryPoint(cn, mn, entry, work.maxLocals + work.maxStack, bodyName, debug.toString()));
        }
        if (added.isEmpty()) return null;
        cn.methods.addAll(added);
        ClassWriter cw = new SmAwareClassWriter(Set.of());
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static MethodNode inPlaceEntryPoint(ClassNode cn, MethodNode mn, Type[] entry,
                                                int frameSlots, String bodyName, String debug) {
        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        MethodNode ep = new MethodNode(ACC_PUBLIC | ACC_SYNTHETIC | (isStatic ? ACC_STATIC : 0),
                mn.name + "$async",
                Type.getMethodDescriptor(Type.getObjectType(CF), Type.getArgumentTypes(mn.desc)), null, null);
        InsnList il = ep.instructions;
        int slots = 0;
        for (Type t : entry) slots += t.getSize();
        int smLocal = slots;
        il.add(new TypeInsnNode(NEW, DSM));
        il.add(new InsnNode(DUP));
        il.add(pushInt(frameSlots));
        il.add(pushInt(frameSlots));
        il.add(new LdcInsnNode(new Handle(H_INVOKESTATIC, cn.name, bodyName, BODY_DESC,
                (cn.access & ACC_INTERFACE) != 0)));
        il.add(new LdcInsnNode(debug));
        il.add(new MethodInsnNode(INVOKESPECIAL, DSM, "<init>",
                "(IILjava/lang/invoke/MethodHandle;Ljava/lang/String;)V", false));
        il.add(new VarInsnNode(ASTORE, smLocal));
        int slot = 0; // entry locals ([this,] params) spill into their original frame slots
        for (Type t : entry) {
            il.add(spillFromLocalVia(smLocal, t, slot, slot));
            slot += t.getSize();
        }
        il.add(new VarInsnNode(ALOAD, smLocal));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, DSM, "start", "()" + CF_DESC, false));
        il.add(new InsnNode(ARETURN));
        return ep;
    }

    private static MethodNode copyOf(MethodNode mn) {
        MethodNode copy = new MethodNode(mn.access, mn.name, mn.desc, mn.signature,
                mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]));
        mn.accept(copy);
        return copy;
    }

    private static boolean hasMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods)
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        return false;
    }

    /** Overloads of {@code m} would collide on the fixed body descriptor; disambiguate by suffix. */
    private static String uniqueMethodName(ClassNode cn, List<MethodNode> pending, String base) {
        String candidate = base;
        int i = 0;
        outer:
        while (true) {
            for (MethodNode m : cn.methods)
                if (m.name.equals(candidate)) { candidate = base + "$" + ++i; continue outer; }
            for (MethodNode m : pending)
                if (m.name.equals(candidate)) { candidate = base + "$" + ++i; continue outer; }
            return candidate;
        }
    }

    // ------------------------------------------------------------------ single-method API (lambda cracking)

    /** Result of {@link #transformMethod}: one state machine class, host untouched. */
    public static final class SingleMethod {
        /** Binary name of the generated class (hidden-class definition appends its own suffix). */
        public final String name;
        public final byte[] bytes;
        /** Descriptor of the generated constructor: {@code ([this,] params...)V}. */
        public final String constructorDescriptor;
        public final String debugMetadata;

        SingleMethod(String name, byte[] bytes, String constructorDescriptor, String debugMetadata) {
            this.name = name;
            this.bytes = bytes;
            this.constructorDescriptor = constructorDescriptor;
            this.debugMetadata = debugMetadata;
        }
    }

    /**
     * Transforms a single method — typically a cracked lambda's {@code lambda$...} impl method —
     * into a state machine class, without patching the host class. No Nest* attributes are
     * emitted: the intended consumer is {@code Lookup.defineHiddenClass(bytes, false, NESTMATE)},
     * which joins the defining lookup's nest dynamically (this is the runtime-deferred analogue
     * of the AoT path's NestMembers patching).
     */
    public static SingleMethod transformMethod(byte[] hostBytes, String methodName, String methodDesc) {
        return transformMethod(hostBytes, methodName, methodDesc, false, false, FrameMode.current());
    }

    /**
     * With {@code shadowHostName}, the generated class is named <em>exactly like the host
     * class</em>, for definition in a separate class loader. Rationale: IntelliJ resolves a line
     * breakpoint inside a lambda only against classes named as the enclosing source class (the
     * lambda body normally lives there as a {@code lambda$...} method), with no {@code $*}
     * wildcard. JDI class-prepare filters and {@code classesByName} match by name string across
     * loaders, so a same-named shadow class is found and its {@code locationsOfLine} bind the
     * breakpoint. The price: inside the shadow class the host's name resolves to the shadow
     * itself, so bodies that reference the host class (captured {@code this}, same-class helper
     * calls, nested lambdas) are rejected here rather than failing strangely at runtime.
     */
    public static SingleMethod transformMethod(byte[] hostBytes, String methodName, String methodDesc,
                                               boolean shadowHostName) {
        return transformMethod(hostBytes, methodName, methodDesc, shadowHostName, false, FrameMode.current());
    }

    /**
     * Like {@link #transformMethod(byte[], String, String)}, but first elevates this method's own
     * virtually dispatched suspendable calls to per-receiver call sites (§7.8/§7.9). A state machine
     * built this way suspends <em>through</em> its callees instead of blocking on them, so the
     * runtime path goes deeper than one method: each level's call site resolves and transforms the
     * next on demand. Used by {@link async3.runtime.Elevation} when it materializes a suspending
     * entry at runtime.
     */
    public static SingleMethod transformMethodElevated(byte[] hostBytes, String methodName, String methodDesc) {
        return transformMethodElevated(hostBytes, methodName, methodDesc, null);
    }

    /**
     * As above, but with an explicit frame-store name ({@code array-spill}/{@code array-live}/
     * {@code typed-fields}), or null for the {@code -Dasync3.frame} default. This is the seam for
     * profile-driven store selection: the runtime tier (see {@link async3.runtime.Elevation}) can
     * pick a store per hot method rather than taking the global default.
     */
    public static SingleMethod transformMethodElevated(byte[] hostBytes, String methodName, String methodDesc,
                                                       String frameStore) {
        FrameMode mode = frameStore == null ? FrameMode.current() : FrameMode.from(frameStore);
        return transformMethod(hostBytes, methodName, methodDesc, false, true, mode);
    }

    private static SingleMethod transformMethod(byte[] hostBytes, String methodName, String methodDesc,
                                                boolean shadowHostName, boolean elevateVirtualCalls, FrameMode mode) {
        ClassNode cn = new ClassNode();
        new ClassReader(hostBytes).accept(cn, ClassReader.SKIP_FRAMES);
        MethodNode mn = null;
        for (MethodNode m : cn.methods)
            if (m.name.equals(methodName) && m.desc.equals(methodDesc)) { mn = m; break; }
        if (mn == null)
            throw new IllegalArgumentException("method not found: " + cn.name + "." + methodName + methodDesc);
        if (elevateVirtualCalls) elevate(cn, mn, suspendableMethods(cn), /*runtimeMode*/ true);
        checkSupported(cn, mn);
        sinkUninitializedNews(cn, mn);
        if (shadowHostName) checkNoHostReferences(cn, mn);
        String smName = shadowHostName ? cn.name : cn.name + "$async$" + methodName;
        StringBuilder debug = new StringBuilder();
        // a hidden class can host fields; the shadow path (separate loader) keeps it array-based
        byte[] bytes = generateStateMachine(cn, mn, smName, false, debug, shadowHostName ? mode.withoutFields() : mode);
        String ctorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, entryTypes(cn, mn));
        return new SingleMethod(smName.replace('/', '.'), bytes, ctorDesc, debug.toString());
    }

    private static void checkNoHostReferences(ClassNode cn, MethodNode mn) {
        String host = cn.name;
        String hostDesc = "L" + host + ";";
        String why = ": debuggable shadow mode defines the state machine under the host class's own name"
                + " (so that IDE line breakpoints in the lambda body bind), which makes references back to"
                + " the host class unresolvable. Use the default hidden-class mode for this lambda, or move"
                + " the referenced member out of " + host.replace('/', '.');
        if ((mn.access & ACC_STATIC) == 0)
            throw new UnsupportedOperationException("lambda captures `this`" + why);
        if (mn.desc.contains(hostDesc))
            throw new UnsupportedOperationException("lambda captures a value typed as the enclosing class" + why);
        for (AbstractInsnNode insn : mn.instructions) {
            String offender = null;
            if (insn instanceof MethodInsnNode mi && (mi.owner.equals(host) || mi.desc.contains(hostDesc)))
                offender = "call to " + mi.owner.replace('/', '.') + "." + mi.name;
            else if (insn instanceof FieldInsnNode fi && (fi.owner.equals(host) || fi.desc.contains(hostDesc)))
                offender = "access to field " + fi.owner.replace('/', '.') + "." + fi.name;
            else if (insn instanceof TypeInsnNode ti && elementName(Type.getObjectType(ti.desc)).equals(host))
                offender = "use of type " + ti.desc;
            else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t && elementName(t).equals(host))
                offender = "class literal " + t.getClassName();
            else if (insn instanceof MultiANewArrayInsnNode ma && ma.desc.contains(hostDesc))
                offender = "array of " + host.replace('/', '.');
            else if (insn instanceof InvokeDynamicInsnNode indy) {
                if (indy.desc.contains(hostDesc)) offender = "invokedynamic " + indy.name;
                else if (indy.bsmArgs != null)
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h && (h.getOwner().equals(host) || h.getDesc().contains(hostDesc)))
                            offender = "nested lambda or method reference into " + host.replace('/', '.');
                        else if (arg instanceof Type t && elementName(t).equals(host))
                            offender = "invokedynamic type argument " + t.getClassName();
                    }
            }
            if (offender != null) throw new UnsupportedOperationException(offender + why);
        }
    }

    private static String elementName(Type t) {
        Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
        return e.getSort() == Type.OBJECT ? e.getInternalName() : "";
    }

    // ------------------------------------------------------------------ marker detection

    private static boolean isAwait(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode mi
                && mi.getOpcode() == INVOKESTATIC
                && (mi.owner.equals(AWAIT_OWNER) || mi.owner.equals(AWAIT_OWNER_LAMBDA))
                && mi.name.equals(AWAIT_NAME)
                && mi.desc.equals(AWAIT_DESC);
    }

    private static boolean hasAwait(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions) if (isAwait(insn)) return true;
        return false;
    }

    // ------------------------------------------------------------------ transitive suspension

    /** Identifies a method within a class by name+descriptor (descriptor disambiguates overloads). */
    private static String methodKey(MethodNode mn) { return mn.name + mn.desc; }

    private static String methodKey(MethodInsnNode mi) { return mi.name + mi.desc; }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods)
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    /**
     * Whether a call to a same-class sibling is <em>statically bound</em>, and so safe to retarget
     * to {@code g$async}. Elevation rewrites {@code invoke g} into a call to {@code g$async}; that
     * is only correct if the two dispatch to the <em>same</em> receiver method. For a virtually
     * dispatched call ({@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} to an overridable method) it
     * may not: a subclass can override {@code g} without supplying a matching {@code g$async}
     * (because its override is not itself suspendable, or its class was not processed), so the
     * rewritten call would resolve {@code g$async} to <em>this</em> class's body while the blocking
     * call would have dispatched to the override — a miscompilation (verified: an interface default
     * method elevated through {@code INVOKEINTERFACE} runs the wrong body under an override).
     *
     * <p>Statically bound, hence safe: {@code INVOKESTATIC} (no receiver); {@code INVOKESPECIAL}
     * (private or super — never overridden at this site); {@code INVOKEVIRTUAL} only when the
     * target method is {@code final}/{@code private} or its class is {@code final}. Everything else
     * — notably {@code INVOKEINTERFACE} and virtual calls to overridable methods — is left blocking,
     * so suspension simply stops at that dispatch boundary (the same conservative choice as for
     * cross-class calls). Resolving virtual targets soundly is the runtime witness's job: it
     * elevates against the <em>observed</em> receiver (docs/DESIGN.md §7.7).
     */
    private static boolean dispatchSafe(ClassNode cn, MethodInsnNode mi) {
        if (!mi.owner.equals(cn.name)) return false; // cross-class: callee's $async not guaranteed
        int op = mi.getOpcode();
        if (op == INVOKESTATIC || op == INVOKESPECIAL) return true; // no virtual dispatch at this site
        if (op == INVOKEVIRTUAL) {
            if ((cn.access & ACC_FINAL) != 0) return true; // class cannot be subclassed
            MethodNode target = findMethod(cn, mi.name, mi.desc);
            return target != null && (target.access & (ACC_FINAL | ACC_PRIVATE)) != 0;
        }
        return false; // INVOKEINTERFACE and anything else: not statically bound
    }

    /**
     * The set of methods in {@code cn} that are <em>suspendable</em>: they either call the
     * {@code await} marker directly, or (transitively) call another suspendable method of the
     * same class. This is the in-class slice of §7.7's call-graph closure — the static analogue
     * of the runtime stack witness. A suspendable method that this transform cannot rewrite
     * (constructor, {@code synchronized}, monitor) is left out, so suspension simply stops at
     * that boundary rather than aborting the class; a method that <em>directly</em> awaits but is
     * unsupported is still seeded here and rejected loudly by {@link #checkSupported} downstream,
     * preserving the existing diagnostics.
     *
     * <p>Computed as a monotone fixpoint over the same-class call graph, so cycles (recursion,
     * mutual recursion) converge correctly. Both statically bound and virtually dispatched edges
     * are followed: {@link #elevate} rewrites the former directly to {@code g$async} and the latter
     * to a Strategy-B {@code invokedynamic} call site that resolves the suspending entry per actual
     * receiver (see {@link async3.runtime.Elevation}). Only same-class edges are followed, since
     * detecting suspendability of a cross-class target needs the wider closure deferred to §7.7.
     */
    private static Set<String> suspendableMethods(ClassNode cn) {
        Set<String> suspendable = new LinkedHashSet<>();
        for (MethodNode mn : cn.methods)
            if (hasAwait(mn)) suspendable.add(methodKey(mn));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MethodNode mn : cn.methods) {
                if (suspendable.contains(methodKey(mn))) continue;
                if (unsupportedReason(mn) != null) continue; // cannot be elevated: stop suspension here
                for (AbstractInsnNode insn : mn.instructions)
                    if (insn instanceof MethodInsnNode mi
                            && mi.owner.equals(cn.name) && suspendable.contains(methodKey(mi))) {
                        suspendable.add(methodKey(mn));
                        grew = true;
                        break;
                    }
            }
        }
        return suspendable;
    }

    /**
     * Retargets each call to a suspendable sibling so that {@code mn} suspends through it instead
     * of blocking on it: {@code invoke g(args)R} becomes {@code await(<entry>(args))}, coerced
     * back to {@code R}. The injected {@code await} marker is an ordinary suspension point that
     * the downstream state-machine transform turns into a park/resume site like any author-written
     * one — so {@code mn} need not contain a single source-level {@code await} to become a state
     * machine. The operand stack at the call is unchanged: {@code <entry>} consumes the same
     * receiver and arguments and returns a {@code CompletableFuture}.
     *
     * <p>The entry differs by how the call dispatches ({@link #dispatchSafe}):
     * <ul>
     *   <li><b>statically bound</b> (static/special/final): a direct call to the sibling
     *       {@code g$async} generated in this same class — cheap, no call-site machinery;</li>
     *   <li><b>virtually dispatched</b> (interface, or overridable virtual): a Strategy-B
     *       {@code invokedynamic} whose bootstrap ({@link async3.runtime.Elevation}) resolves the
     *       suspending entry against the <em>actual receiver</em> at runtime, so an override runs
     *       its own body — sound where a static {@code g$async} call would not be, and needing no
     *       scaffolding on the callee hierarchy.</li>
     * </ul>
     */
    private static void elevate(ClassNode cn, MethodNode mn, Set<String> suspendable) {
        elevate(cn, mn, suspendable, false);
    }

    /**
     * {@code runtimeMode} is for state machines built lazily at runtime ({@link #transformMethodElevated},
     * used by {@link async3.runtime.Elevation}): there the loaded class may carry no {@code g$async}
     * siblings, so only virtually dispatched calls are elevated (through the per-receiver call site,
     * which resolves and transforms its callee on demand). Statically bound calls are left blocking
     * — suspension stops at that boundary, the same conservative choice as for unprocessed classes.
     */
    private static void elevate(ClassNode cn, MethodNode mn, Set<String> suspendable, boolean runtimeMode) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions)
            if (insn instanceof MethodInsnNode mi
                    && mi.owner.equals(cn.name) && suspendable.contains(methodKey(mi)))
                calls.add(mi);
        for (MethodInsnNode mi : calls) {
            boolean safe = dispatchSafe(cn, mi);
            if (runtimeMode && safe) continue;   // no g$async to call at runtime — leave blocking
            Type ret = Type.getReturnType(mi.desc);
            Type[] args = Type.getArgumentTypes(mi.desc);
            InsnList repl = new InsnList();
            if (safe) {
                String asyncDesc = Type.getMethodDescriptor(Type.getObjectType(CF), args);
                repl.add(new MethodInsnNode(mi.getOpcode(), mi.owner, mi.name + "$async", asyncDesc, mi.itf));
            } else {
                // virtual/interface: resolve per actual receiver at the call site (Strategy B).
                Type[] indyParams = new Type[args.length + 1];
                indyParams[0] = Type.getObjectType(mi.owner);   // the receiver
                System.arraycopy(args, 0, indyParams, 1, args.length);
                String indyDesc = Type.getMethodDescriptor(Type.getObjectType(CF), indyParams);
                repl.add(new InvokeDynamicInsnNode(mi.name, indyDesc, ELEVATE_BSM, mi.desc));
            }
            repl.add(new MethodInsnNode(INVOKESTATIC, AWAIT_OWNER, AWAIT_NAME, AWAIT_DESC, false));
            repl.add(coerceFromObject(ret));
            mn.instructions.insertBefore(mi, repl);
            mn.instructions.remove(mi);
        }
    }

    /**
     * Coerces the {@code Object} left by the {@code await} marker back to {@code ret}, mirroring
     * the checkcast/unbox javac emits after an author-written {@code await} — the same instructions
     * the state-machine transform relies on following a suspension point.
     */
    private static InsnList coerceFromObject(Type ret) {
        InsnList il = new InsnList();
        switch (ret.getSort()) {
            case Type.VOID -> il.add(new InsnNode(POP));
            case Type.OBJECT, Type.ARRAY -> il.add(new TypeInsnNode(CHECKCAST,
                    ret.getSort() == Type.ARRAY ? ret.getDescriptor() : ret.getInternalName()));
            default -> { // primitive: checkcast to the wrapper, then unbox
                String boxed = boxedName(ret);
                il.add(new TypeInsnNode(CHECKCAST, boxed));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, boxed,
                        ret.getClassName() + "Value", "()" + ret.getDescriptor(), false));
            }
        }
        return il;
    }

    private static String boxedName(Type prim) {
        return switch (prim.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> throw new IllegalArgumentException("not a primitive: " + prim);
        };
    }

    /**
     * The entry locals of the original method: {@code [this,] param...}, with slot numbering
     * matching the original frame. `this` needs no special treatment anywhere: it is just the
     * reference in slot 0, captured, spilled and restored like any other local.
     */
    private static Type[] entryTypes(ClassNode cn, MethodNode mn) {
        Type[] args = Type.getArgumentTypes(mn.desc);
        if ((mn.access & ACC_STATIC) != 0) return args;
        Type[] all = new Type[args.length + 1];
        all[0] = Type.getObjectType(cn.name);
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    private static void checkSupported(ClassNode cn, MethodNode mn) {
        String reason = unsupportedReason(mn);
        if (reason != null)
            throw new UnsupportedOperationException(reason + ": " + cn.name + "." + mn.name + mn.desc);
    }

    /**
     * The reason {@code mn} cannot host a suspension point, or null if it can. Shared by the loud
     * {@link #checkSupported} (the direct-await path, where rejection aborts the whole class) and
     * the quiet {@link #suspendableMethods} closure (which simply declines to elevate a method it
     * cannot transform, leaving it on the blocking tier).
     */
    private static String unsupportedReason(MethodNode mn) {
        if (mn.name.equals("<init>") || mn.name.equals("<clinit>"))
            return "await is not supported in constructors/initializers";
        if ((mn.access & ACC_SYNCHRONIZED) != 0)
            return "await is illegal in a synchronized method";
        for (AbstractInsnNode insn : mn.instructions) {
            int op = insn.getOpcode();
            // Coarse: rejects any monitor usage in an awaiting method. Precise monitor-depth
            // dataflow ("is a monitor held *at* the suspension point") is a straightforward
            // refinement.
            if (op == MONITORENTER || op == MONITOREXIT)
                return "await may not be used while a monitor is held";
            if (op == JSR || op == RET)
                return "JSR/RET not supported";
        }
        return null;
    }

    // ------------------------------------------------------------------ entry point on host class

    private static MethodNode entryPoint(ClassNode cn, MethodNode mn, String smName) {
        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        Type[] entry = entryTypes(cn, mn);
        MethodNode ep = new MethodNode(
                ACC_PUBLIC | (isStatic ? ACC_STATIC : 0), mn.name + "$async",
                Type.getMethodDescriptor(Type.getObjectType(CF), Type.getArgumentTypes(mn.desc)), null, null);
        InsnList il = ep.instructions;
        il.add(new TypeInsnNode(NEW, smName));
        il.add(new InsnNode(DUP));
        int slot = 0; // for instance methods, entry[0] is `this` = local 0 of m$async too
        for (Type t : entry) {
            il.add(new VarInsnNode(t.getOpcode(ILOAD), slot));
            slot += t.getSize();
        }
        il.add(new MethodInsnNode(INVOKESPECIAL, smName, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE, entry), false));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, smName, "start", "()" + CF_DESC, false));
        il.add(new InsnNode(ARETURN));
        return ep;
    }

    // ------------------------------------------------------------------ state machine class

    private static byte[] generateStateMachine(ClassNode cn, MethodNode mn, String smName,
                                               boolean nestmate, StringBuilder debug, FrameMode mode) {
        Type[] entry = entryTypes(cn, mn);
        Frame<BasicValue>[] frames = analyze(cn.name, mn);

        ClassNode sm = new ClassNode();
        sm.version = V17;
        sm.access = ACC_PUBLIC | ACC_FINAL | ACC_SUPER;
        sm.name = smName;
        sm.superName = FSM;
        sm.sourceFile = cn.sourceFile;
        if (nestmate) sm.nestHostClass = cn.name;

        debug.append("method ").append(cn.name).append('.').append(mn.name).append(mn.desc).append('\n');

        // Generous positional frame layout: slot v of the original method <-> frame index v;
        // operand stack entry j <-> frame index maxLocals + j. Per-state reuse is automatic.
        int frameSlots = mn.maxLocals + mn.maxStack;

        // The generated class can host typed fields (and arrays); the store decides the layout.
        FrameStore store = frameStore(mode, smName, entry, mn, frames, frameSlots);
        sm.methods.add(constructor(entry, store));
        sm.methods.add(applyMethod(mn, entry, frames, debug, "apply", ACC_PUBLIC, "(Ljava/lang/Object;)V", store));
        store.declare(sm);

        sm.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                "$asyncDebug", "Ljava/lang/String;", null, debug.toString()));

        ClassWriter cw = new SmAwareClassWriter(Set.of(smName));
        sm.accept(cw);
        return cw.toByteArray();
    }

    private static MethodNode constructor(Type[] entry, FrameStore store) {
        MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE, entry), null, null);
        InsnList il = ctor.instructions;
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(pushInt(store.superRefSlots()));
        il.add(pushInt(store.superPrimSlots()));
        il.add(new MethodInsnNode(INVOKESPECIAL, FSM, "<init>", "(II)V", false));
        // Capture constructor arguments ([this,] params) into their entry frame slots; the dispatch
        // switch's case 0 restores them (array-spill), or they simply live there (live stores).
        int ctorLocal = 1, paramSlot = 0;
        for (Type t : entry) {
            il.add(store.captureFromLocal(paramSlot, t, ctorLocal));
            ctorLocal += t.getSize();
            paramSlot += t.getSize();
        }
        il.add(new InsnNode(RETURN));
        return ctor;
    }

    /**
     * Builds the resumable body. The instructions are identical whether it is emitted as
     * {@code apply(Object)} on a state machine subclass (local 0 = {@code this}) or as a static
     * sibling {@code m$asyncBody(FutureStateMachine, Object)} of the host class (local 0 = the
     * state machine parameter): the body only ever touches the public FutureStateMachine ABI
     * through local 0.
     */
    private static MethodNode applyMethod(MethodNode mn, Type[] entry, Frame<BasicValue>[] frames,
                                          StringBuilder debug, String name, int access, String desc,
                                          FrameStore store) {
        MethodNode apply = new MethodNode(access, name, desc, null, null);
        Type returnType = Type.getReturnType(mn.desc);
        // live: the local's home is the frame (no spill/restore at suspensions), so the body's
        // local accesses are rewritten in place; otherwise locals stay in JVM slots (array-spill).
        final boolean live = store.rewritesBody();

        // apply's local layout: 0 = this, 1 = tr, [2, 2+maxLocals) = original locals,
        // then a scratch slot for the awaited future and a 2-wide scratch for spills/boxing.
        final int OFF = 2;
        final int futTmp = OFF + mn.maxLocals;
        final int scratch = futTmp + 1;

        List<AbstractInsnNode> awaits = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) if (isAwait(insn)) awaits.add(insn);
        int n = awaits.size();

        // Liveness drives the nulling of dead ref slots (the bytecode analogue of the tree
        // transform's fieldsToNullOut): a suspended frame must not pin values the resumed code
        // can no longer read.
        BitSet[] liveOut = Liveness.liveOut(mn);
        BitSet refSlots = refFrameSlots(entry, mn, frames, awaits);

        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (AbstractInsnNode insn : mn.instructions)
            if (insn instanceof LabelNode l) labelMap.put(l, new LabelNode());

        LabelNode bodyStart = new LabelNode();
        LabelNode dflt = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode[] cases = new LabelNode[n + 1];
        LabelNode[] resume = new LabelNode[n + 1];
        for (int i = 0; i <= n; i++) cases[i] = new LabelNode();
        for (int i = 1; i <= n; i++) resume[i] = new LabelNode();

        // --- transformed copy of the body
        InsnList body = new InsnList();
        int state = 0;
        int idx = -1;
        for (AbstractInsnNode insn : mn.instructions) {
            idx++;
            if (insn instanceof LabelNode l) {
                body.add(labelMap.get(l));
            } else if (insn instanceof FrameNode) {
                // dropped; COMPUTE_FRAMES recomputes
            } else if (insn instanceof LineNumberNode ln) {
                body.add(new LineNumberNode(ln.line, labelMap.get(ln.start)));
            } else if (isAwait(insn)) {
                state++;
                Frame<BasicValue> f = frames[idx];
                if (f == null) throw new IllegalStateException("unreachable await call");
                body.add(awaitSite(state, f, mn.maxLocals, OFF, futTmp, scratch, resume[state],
                        liveOut[idx], refSlots, store));
                appendDebug(debug, mn, insn, state, f, liveOut[idx]);
            } else if (insn.getOpcode() >= IRETURN && insn.getOpcode() <= RETURN) {
                body.add(returnSite(returnType, scratch));
            } else if (live && insn instanceof VarInsnNode v && v.var < mn.maxLocals) {
                // the local's home is the frame slot — read/write it in place via the store
                int op = v.getOpcode();
                if (op >= ISTORE && op <= ASTORE) {
                    body.add(store.store(v.var, varType(op), scratch));
                } else {
                    Frame<BasicValue> f = frames[idx];
                    Type refType = op == ALOAD && f != null ? f.getLocal(v.var).getType() : null;
                    body.add(store.load(v.var, varType(op), refType));
                }
            } else if (live && insn instanceof IincInsnNode ii && ii.var < mn.maxLocals) {
                body.add(store.load(ii.var, Type.INT_TYPE, null));
                body.add(pushInt(ii.incr));
                body.add(new InsnNode(IADD));
                body.add(store.store(ii.var, Type.INT_TYPE, scratch));
            } else {
                AbstractInsnNode c = insn.clone(labelMap);
                if (c instanceof VarInsnNode v) v.var += OFF;
                else if (c instanceof IincInsnNode ii) ii.var += OFF;
                body.add(c);
            }
        }

        // --- dispatch prologue
        InsnList il = apply.instructions;
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, FSM, "state", "I"));
        il.add(new TableSwitchInsnNode(0, n, dflt, cases));

        il.add(cases[0]);
        if (!live) { // live stores keep entry params in the frame, accessed in place
            int slot = 0;
            for (Type t : entry) {
                il.add(store.load(slot, t, t));
                il.add(new VarInsnNode(isNullType(t) ? ASTORE : t.getOpcode(ISTORE), OFF + slot));
                slot += t.getSize();
            }
        }
        il.add(new JumpInsnNode(GOTO, bodyStart));

        for (int i = 1; i <= n; i++) {
            il.add(cases[i]);
            Frame<BasicValue> f = frames[mn.instructions.indexOf(awaits.get(i - 1))];
            if (!live) { // live stores restore no locals — only the operand stack (below)
                for (int v = 0; v < mn.maxLocals; v++) {
                    BasicValue value = f.getLocal(v);
                    if (!isSpillable(value)) continue;
                    Type t = value.getType();
                    il.add(store.load(v, t, t));
                    il.add(new VarInsnNode(isNullType(t) ? ASTORE : t.getOpcode(ISTORE), OFF + v));
                }
            }
            for (int j = 0; j < f.getStackSize() - 1; j++) {
                Type st = f.getStack(j).getType();
                il.add(store.load(mn.maxLocals + j, st, st)); // store.load handles null → ACONST_NULL
            }
            il.add(new JumpInsnNode(GOTO, resume[i]));
        }

        il.add(dflt);
        il.add(new TypeInsnNode(NEW, "java/lang/IllegalStateException"));
        il.add(new InsnNode(DUP));
        il.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        il.add(new InsnNode(ATHROW));

        il.add(bodyStart);
        il.add(body);

        // --- outermost failure handler: uncaught exceptions fail the result future
        il.add(handler);
        il.add(new VarInsnNode(ASTORE, scratch));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ALOAD, scratch));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, FSM, "completeFailure", "(Ljava/lang/Throwable;)V", false));
        il.add(new InsnNode(RETURN));

        apply.tryCatchBlocks = new ArrayList<>();
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks)
                apply.tryCatchBlocks.add(new TryCatchBlockNode(
                        labelMap.get(tcb.start), labelMap.get(tcb.end), labelMap.get(tcb.handler), tcb.type));
        // appended last: user handlers (cloned above) take precedence
        apply.tryCatchBlocks.add(new TryCatchBlockNode(bodyStart, handler, handler, null));

        // The LocalVariableTable carries over: spilled values are restored into the (shifted)
        // original slots, and the resume code sits inside the original scope labels, so the
        // entries stay truthful in resumed regions — a debugger sees ordinary named locals.
        // (array-live keeps no locals in slots, so the LVT would be untruthful — skip it.)
        if (!live && mn.localVariables != null)
            for (LocalVariableNode lv : mn.localVariables)
                apply.localVariables.add(new LocalVariableNode(lv.name, lv.desc, lv.signature,
                        labelMap.get(lv.start), labelMap.get(lv.end), lv.index + OFF));

        apply.maxLocals = OFF + mn.maxLocals + 3;
        apply.maxStack = mn.maxStack + 8; // recomputed by COMPUTE_FRAMES
        return apply;
    }

    /**
     * The replacement for one await call instruction. On entry the stack is [below..., future].
     *
     * <p>{@code live} is the liveness at the await; {@code refSlots} is every ref frame slot the
     * machine ever writes (entry spill or any await). Dead ref locals are not spilled, and any
     * ref slot not written at this state is nulled, so a suspended frame holds exactly the
     * references the resumed code can still read — stale values from earlier states (or from a
     * frame slot whose variable has since died) don't pin garbage for the suspension's duration.
     * The restore path is unchanged: a dead-but-in-scope ref local restores as null (which any
     * CHECKCAST accepts), the same observable behavior as the tree transform's fieldsToNullOut.
     */
    private static InsnList awaitSite(int state, Frame<BasicValue> f, int maxLocals,
                                      int off, int futTmp, int scratch, LabelNode resumeLabel,
                                      BitSet live, BitSet refSlots, FrameStore store) {
        InsnList il = new InsnList();
        LabelNode fast = new LabelNode();

        il.add(new VarInsnNode(ASTORE, futTmp));
        // state must be written before onComplete: the callback may fire on another thread
        // immediately; whenComplete registration publishes the spilled frame and state.
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(pushInt(state));
        il.add(new FieldInsnNode(PUTFIELD, FSM, "state", "I"));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ALOAD, futTmp));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, FSM, "getCompleted", "(" + CF_DESC + ")Ljava/lang/Object;", false));
        il.add(new InsnNode(DUP));
        il.add(new JumpInsnNode(IFNONNULL, fast));
        il.add(new InsnNode(POP));

        // park: spill assigned locals (live ones, for refs) ...
        // In array-live the locals already live in the frame, so nothing is spilled here; we still
        // walk them to mark which ref slots hold a live value (so the nulling pass below leaves
        // those alone and nulls only the dead ones).
        BitSet written = new BitSet();
        for (int v = 0; v < maxLocals; v++) {
            BasicValue value = f.getLocal(v);
            requireInitialized(value);
            if (!isSpillable(value)) continue;
            Type t = value.getType();
            if (isRef(t) && !isNullType(t)) {
                if (!live.get(v)) continue; // dead ref: slot nulled below instead
                written.set(v);
            }
            if (!store.rewritesBody()) il.add(store.captureFromLocal(v, t, off + v));
        }
        // ... and the operand stack below the future, popped top-down via the scratch local
        // (stack values are live by construction: the original code consumes them after resume)
        for (int j = f.getStackSize() - 2; j >= 0; j--) {
            BasicValue value = f.getStack(j);
            requireInitialized(value);
            Type t = value.getType();
            if (isRef(t) && !isNullType(t)) written.set(maxLocals + j);
            il.add(store.store(maxLocals + j, t, scratch)); // null-typed → POP (restored as ACONST_NULL)
        }
        // null every ref slot not written at this state
        for (int s = refSlots.nextSetBit(0); s >= 0; s = refSlots.nextSetBit(s + 1)) {
            if (!written.get(s)) il.add(store.nullRef(s));
        }
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ALOAD, futTmp));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, FSM, "onComplete", "(" + CF_DESC + ")V", false));
        il.add(new InsnNode(RETURN));

        // fast path: stack is [below..., tr]
        il.add(fast);
        il.add(new VarInsnNode(ASTORE, 1));
        // join point with the slow-path restore (which jumps here with the same shape)
        il.add(resumeLabel);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ALOAD, 1));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, FSM, "tryGet", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        // tryGet leaves the awaited value (as Object) on the stack; the original instructions
        // that followed the await (checkcast / unboxing) consume it unchanged.
        return il;
    }

    /**
     * Every ref frame slot the machine ever writes: the entry locals spilled by the
     * constructor/entry point, plus everything any await site can spill. The complement of a
     * state's own writes within this set is what {@link #awaitSite} nulls.
     */
    private static BitSet refFrameSlots(Type[] entry, MethodNode mn, Frame<BasicValue>[] frames,
                                        List<AbstractInsnNode> awaits) {
        BitSet set = new BitSet();
        int slot = 0;
        for (Type t : entry) {
            if (isRef(t)) set.set(slot);
            slot += t.getSize();
        }
        for (AbstractInsnNode a : awaits) {
            Frame<BasicValue> f = frames[mn.instructions.indexOf(a)];
            if (f == null) continue;
            for (int v = 0; v < mn.maxLocals; v++) {
                BasicValue value = f.getLocal(v);
                if (isSpillable(value) && isRef(value.getType()) && !isNullType(value.getType()))
                    set.set(v);
            }
            for (int j = 0; j < f.getStackSize() - 1; j++) {
                Type t = f.getStack(j).getType();
                if (t != null && isRef(t) && !isNullType(t)) set.set(mn.maxLocals + j);
            }
        }
        return set;
    }

    /** Replaces a return instruction: complete the result future and exit the dispatch. */
    private static InsnList returnSite(Type returnType, int scratch) {
        InsnList il = new InsnList();
        if (returnType.getSort() == Type.VOID) {
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new InsnNode(ACONST_NULL));
        } else {
            il.add(box(returnType));
            il.add(new VarInsnNode(ASTORE, scratch));
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new VarInsnNode(ALOAD, scratch));
        }
        il.add(new MethodInsnNode(INVOKEVIRTUAL, FSM, "completeSuccess", "(Ljava/lang/Object;)V", false));
        il.add(new InsnNode(RETURN));
        return il;
    }

    // ------------------------------------------------------------------ spill/restore helpers

    private static boolean isRef(Type t) {
        return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
    }

    private static boolean isNullType(Type t) {
        return t.getSort() == Type.OBJECT && t.getInternalName().equals("null");
    }

    private static boolean isSpillable(BasicValue v) {
        return v != null
                && v != BasicValue.UNINITIALIZED_VALUE
                && v.getType() != null
                && v.getType().getSort() != Type.VOID; // RETURNADDRESS
    }

    private static void requireInitialized(BasicValue v) {
        if (v instanceof UninitValue)
            throw new UnsupportedOperationException(
                    "uninitialized object on the stack at a suspension point " +
                    "(`new Foo(await(f))`): not yet supported, needs NEW-sinking");
    }

    /** Spills a value held in local {@code localIdx} of the method being built into frame slot {@code frameIdx}. */
    private static InsnList spillFromLocal(Type t, int localIdx, int frameIdx) {
        return spillFromLocalVia(0, t, localIdx, frameIdx);
    }

    /** Variant for code where the state machine reference lives in local {@code smLocal}, not local 0. */
    private static InsnList spillFromLocalVia(int smLocal, Type t, int localIdx, int frameIdx) {
        InsnList il = new InsnList();
        if (isNullType(t)) return il; // restored as ACONST_NULL
        // value-first: load the local, then the array + index, then the setter (matches Frames'
        // setter signatures, which array-live also relies on; see Frames).
        il.add(new VarInsnNode(t.getOpcode(ILOAD), localIdx));
        il.add(new VarInsnNode(ALOAD, smLocal));
        il.add(frameArrayField(t));
        il.add(pushInt(frameIdx));
        il.add(isRef(t) ? refSet() : primSet(t));
        return il;
    }

    // ---- shared Frames accessor nodes (used by ArrayStore and constructor capture) ----

    private static FieldInsnNode frameArrayField(Type t) {
        return isRef(t) ? new FieldInsnNode(GETFIELD, FSM, "refs", REFS_DESC)
                        : new FieldInsnNode(GETFIELD, FSM, "prims", PRIMS_DESC);
    }

    private static MethodInsnNode primSet(Type t) {
        String name = switch (t.getSort()) {
            case Type.FLOAT -> "fsetP"; case Type.LONG -> "lsetP"; case Type.DOUBLE -> "dsetP"; default -> "isetP";
        };
        char c = switch (t.getSort()) {
            case Type.FLOAT -> 'F'; case Type.LONG -> 'J'; case Type.DOUBLE -> 'D'; default -> 'I';
        };
        return new MethodInsnNode(INVOKESTATIC, FRAMES, name, "(" + c + PRIMS_DESC + "I)V", false);
    }

    private static MethodInsnNode primGet(Type t) {
        String name = switch (t.getSort()) {
            case Type.FLOAT -> "fgetP"; case Type.LONG -> "lgetP"; case Type.DOUBLE -> "dgetP"; default -> "igetP";
        };
        char c = switch (t.getSort()) {
            case Type.FLOAT -> 'F'; case Type.LONG -> 'J'; case Type.DOUBLE -> 'D'; default -> 'I';
        };
        return new MethodInsnNode(INVOKESTATIC, FRAMES, name, "(" + PRIMS_DESC + "I)" + c, false);
    }

    private static MethodInsnNode refGet() {
        return new MethodInsnNode(INVOKESTATIC, FRAMES, "getR", "(" + REFS_DESC + "I)Ljava/lang/Object;", false);
    }

    private static MethodInsnNode refSet() {
        return new MethodInsnNode(INVOKESTATIC, FRAMES, "setR", "(Ljava/lang/Object;" + REFS_DESC + "I)V", false);
    }

    // ---- array-live: rewrite a body local access to read/write its frame slot in place ----

    private static final Type OBJECT = Type.getObjectType("java/lang/Object");

    /** The on-stack type for a load/store opcode (boolean/byte/char/short all view as int). */
    private static Type primType(int opcode) {
        return switch (opcode) {
            case LLOAD, LSTORE -> Type.LONG_TYPE;
            case FLOAD, FSTORE -> Type.FLOAT_TYPE;
            case DLOAD, DSTORE -> Type.DOUBLE_TYPE;
            default -> Type.INT_TYPE;
        };
    }

    // ---- typed-fields: a precisely-typed field per (frame slot, kind) the method touches ----

    /** boolean/byte/char/short collapse to 'I'; the rest are J/F/D, references R (an Object field). */
    private static char kindChar(Type t) {
        return switch (t.getSort()) {
            case Type.OBJECT, Type.ARRAY -> 'R';
            case Type.LONG -> 'J';
            case Type.FLOAT -> 'F';
            case Type.DOUBLE -> 'D';
            default -> 'I';
        };
    }

    /** Field descriptor: precise for primitives (no long-normalization); references are Object. */
    private static String fieldDesc(Type t) {
        return switch (t.getSort()) {
            case Type.OBJECT, Type.ARRAY -> "Ljava/lang/Object;";
            case Type.LONG -> "J";
            case Type.FLOAT -> "F";
            case Type.DOUBLE -> "D";
            default -> "I";
        };
    }

    /** The on-stack type a load/store opcode views the slot as (ALOAD/ASTORE → Object). */
    private static Type varType(int opcode) {
        return switch (opcode) {
            case ALOAD, ASTORE -> OBJECT;
            case LLOAD, LSTORE -> Type.LONG_TYPE;
            case FLOAD, FSTORE -> Type.FLOAT_TYPE;
            case DLOAD, DSTORE -> Type.DOUBLE_TYPE;
            default -> Type.INT_TYPE;
        };
    }

    /** The set of typed fields a state machine needs, one per (frame slot, kind) actually touched. */
    static final class FieldPlan {
        private final String owner;
        private final Map<String, String> name = new HashMap<>(); // "slot:kind" -> field name
        private final List<FieldNode> fields = new ArrayList<>();

        private FieldPlan(String owner) { this.owner = owner; }

        static FieldPlan compute(String owner, Type[] entry, MethodNode mn, Frame<BasicValue>[] frames) {
            FieldPlan p = new FieldPlan(owner);
            int slot = 0;
            for (Type t : entry) { p.ensure(slot, t); slot += t.getSize(); } // entry params
            for (AbstractInsnNode insn : mn.instructions) {                   // body local accesses
                if (insn instanceof VarInsnNode v && v.var < mn.maxLocals) p.ensure(v.var, varType(v.getOpcode()));
                else if (insn instanceof IincInsnNode ii && ii.var < mn.maxLocals) p.ensure(ii.var, Type.INT_TYPE);
            }
            for (AbstractInsnNode insn : mn.instructions) {                   // operand-stack spills
                if (!isAwait(insn)) continue;
                Frame<BasicValue> f = frames[mn.instructions.indexOf(insn)];
                if (f == null) continue;
                for (int j = 0; j < f.getStackSize() - 1; j++) {
                    BasicValue bv = f.getStack(j);
                    if (isSpillable(bv) && !isNullType(bv.getType())) p.ensure(mn.maxLocals + j, bv.getType());
                }
            }
            return p;
        }

        private void ensure(int slot, Type t) {
            if (t == null || isNullType(t)) return;
            String key = slot + ":" + kindChar(t);
            if (name.containsKey(key)) return;
            String fn = "s" + slot + kindChar(t);
            name.put(key, fn);
            fields.add(new FieldNode(ACC_PUBLIC, fn, fieldDesc(t), null, null));
        }

        String field(int slot, Type t) { return name.get(slot + ":" + kindChar(t)); }
        String owner() { return owner; }
        List<FieldNode> fields() { return fields; }
    }

    /** typed-fields: push slot {@code slot}'s value from its field ({@code refType} = ALOAD type). */
    private static InsnList fieldGet(FieldPlan plan, int slot, Type t, Type refType) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, plan.owner(), plan.field(slot, t), fieldDesc(t)));
        if (isRef(t) && refType != null && (refType.getSort() == Type.OBJECT || refType.getSort() == Type.ARRAY)
                && !refType.getInternalName().equals("java/lang/Object"))
            il.add(new TypeInsnNode(CHECKCAST, refType.getInternalName()));
        return il;
    }

    /** typed-fields: pop the stack value into slot {@code slot}'s field (stash via scratch for putfield order). */
    private static InsnList fieldPutFromStack(FieldPlan plan, int slot, Type t, int scratch) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(t.getOpcode(ISTORE), scratch));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(t.getOpcode(ILOAD), scratch));
        il.add(new FieldInsnNode(PUTFIELD, plan.owner(), plan.field(slot, t), fieldDesc(t)));
        return il;
    }

    /** typed-fields: set slot {@code slot}'s reference field to null (the fieldsToNullOut analogue). */
    private static InsnList fieldNull(FieldPlan plan, int slot) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new InsnNode(ACONST_NULL));
        il.add(new FieldInsnNode(PUTFIELD, plan.owner(), plan.field(slot, OBJECT), fieldDesc(OBJECT)));
        return il;
    }

    // ---- FrameStore: the one strategy the codegen calls for every frame-slot access ----

    /**
     * Where a captured value lives and how the body reaches it (docs/DESIGN.md §9). The codegen is
     * store-agnostic: it asks the store to {@link #load}/{@link #store}/{@link #nullRef} a frame
     * slot and to {@link #captureFromLocal capture} an entry param, and the store emits the right
     * bytecode (two arrays, or typed fields). {@link #rewritesBody} distinguishes the <em>live</em>
     * stores (the slot is the variable's home, accessed in place) from the spill store (the variable
     * lives in a JVM slot and is copied in/out only at suspensions). All slots are addressed by
     * frame index (local slot v, or operand-stack entry j at {@code maxLocals + j}); the state
     * machine is always local 0.
     */
    abstract static class FrameStore {
        abstract boolean rewritesBody();
        abstract int superRefSlots();
        abstract int superPrimSlots();
        void declare(ClassNode sm) {}
        /** Constructor capture: move entry param in JVM local {@code localIdx} into frame {@code slot}. */
        abstract InsnList captureFromLocal(int slot, Type t, int localIdx);
        /** Push frame slot {@code slot} (typed {@code t}); {@code refType} is the checkcast for references. */
        abstract InsnList load(int slot, Type t, Type refType);
        /** Pop the stack value into frame slot {@code slot}; {@code scratch} is a free 2-wide local. */
        abstract InsnList store(int slot, Type t, int scratch);
        /** Null a dead reference slot (the {@code fieldsToNullOut} analogue). */
        abstract InsnList nullRef(int slot);
    }

    static FrameStore frameStore(FrameMode mode, String smName, Type[] entry, MethodNode mn,
                                 Frame<BasicValue>[] frames, int frameSlots) {
        return switch (mode) {
            case TYPED_FIELDS -> new FieldStore(FieldPlan.compute(smName, entry, mn, frames));
            case ARRAY_LIVE -> new ArrayStore(frameSlots, true);
            case ARRAY_SPILL -> new ArrayStore(frameSlots, false);
        };
    }

    /** Generic two-array frame; {@code live} chooses the array-live vs. array-spill body shape. */
    static final class ArrayStore extends FrameStore {
        private final int frameSlots;
        private final boolean live;
        ArrayStore(int frameSlots, boolean live) { this.frameSlots = frameSlots; this.live = live; }
        boolean rewritesBody() { return live; }
        int superRefSlots() { return frameSlots; }
        int superPrimSlots() { return frameSlots; }
        InsnList captureFromLocal(int slot, Type t, int localIdx) { return spillFromLocal(t, localIdx, slot); }
        InsnList load(int slot, Type t, Type refType) {
            InsnList il = new InsnList();
            if (isNullType(t)) { il.add(new InsnNode(ACONST_NULL)); return il; }
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(frameArrayField(t));
            il.add(pushInt(slot));
            if (isRef(t)) {
                il.add(refGet());
                Type c = refType != null ? refType : t;
                if ((c.getSort() == Type.OBJECT || c.getSort() == Type.ARRAY)
                        && !c.getInternalName().equals("java/lang/Object"))
                    il.add(new TypeInsnNode(CHECKCAST, c.getInternalName()));
            } else {
                il.add(primGet(t));
            }
            return il;
        }
        InsnList store(int slot, Type t, int scratch) {
            InsnList il = new InsnList();
            if (isNullType(t)) { il.add(new InsnNode(POP)); return il; } // restored as ACONST_NULL
            il.add(new VarInsnNode(ALOAD, 0));     // value-first setters: push array + index above the value
            il.add(frameArrayField(t));
            il.add(pushInt(slot));
            il.add(isRef(t) ? refSet() : primSet(t));
            return il;
        }
        InsnList nullRef(int slot) {
            InsnList il = new InsnList();
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new FieldInsnNode(GETFIELD, FSM, "refs", REFS_DESC));
            il.add(pushInt(slot));
            il.add(new InsnNode(ACONST_NULL));
            il.add(new InsnNode(AASTORE));
            return il;
        }
    }

    /** Typed fields on the generated class (always live); the agent's shared shell never uses this. */
    static final class FieldStore extends FrameStore {
        private final FieldPlan plan;
        FieldStore(FieldPlan plan) { this.plan = plan; }
        boolean rewritesBody() { return true; }
        int superRefSlots() { return 0; }   // no backing arrays
        int superPrimSlots() { return 0; }
        void declare(ClassNode sm) { sm.fields.addAll(plan.fields()); }
        InsnList captureFromLocal(int slot, Type t, int localIdx) {
            InsnList il = new InsnList();
            if (isNullType(t)) return il;
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new VarInsnNode(t.getOpcode(ILOAD), localIdx));
            il.add(new FieldInsnNode(PUTFIELD, plan.owner(), plan.field(slot, t), fieldDesc(t)));
            return il;
        }
        InsnList load(int slot, Type t, Type refType) {
            if (isNullType(t)) { InsnList il = new InsnList(); il.add(new InsnNode(ACONST_NULL)); return il; }
            return fieldGet(plan, slot, t, isRef(t) ? (refType != null ? refType : t) : null);
        }
        InsnList store(int slot, Type t, int scratch) {
            if (isNullType(t)) { InsnList il = new InsnList(); il.add(new InsnNode(POP)); return il; }
            return fieldPutFromStack(plan, slot, t, scratch);
        }
        InsnList nullRef(int slot) {
            if (plan.field(slot, OBJECT) == null) return new InsnList(); // slot never holds a ref field
            return fieldNull(plan, slot);
        }
    }

    private static InsnList box(Type t) {
        InsnList il = new InsnList();
        String owner = switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (owner != null)
            il.add(new MethodInsnNode(INVOKESTATIC, owner, "valueOf",
                    "(" + t.getDescriptor() + ")L" + owner + ";", false));
        return il;
    }

    private static AbstractInsnNode pushInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, v);
        return new LdcInsnNode(v);
    }

    // ------------------------------------------------------------------ NEW-sinking pre-pass

    /**
     * Rewrites {@code NEW T; [DUP;] ...await...; INVOKESPECIAL T.<init>} so that no uninitialized
     * reference is on the operand stack at a suspension point: the {@code NEW}(+{@code DUP}) is
     * deleted from its original position and re-materialized just below the constructor arguments
     * at the {@code <init>} site (args are briefly parked in fresh scratch locals to make room).
     * Handles the javac idiom, including nested news, multiple awaits among the arguments, and
     * conditional argument expressions; anything more exotic (extra stack-shuffled copies,
     * an uninitialized reference stored in a local) is rejected by {@link #requireInitialized}.
     */
    private static void sinkUninitializedNews(ClassNode cn, MethodNode mn) {
        Frame<BasicValue>[] frames = analyze(cn.name, mn);
        AbstractInsnNode[] insns = mn.instructions.toArray();

        // uninitialized values in flight at any suspension point, deduped by their NEW insn
        Set<UninitValue> pending = new LinkedHashSet<>();
        for (int i = 0; i < insns.length; i++) {
            if (!isAwait(insns[i]) || frames[i] == null) continue;
            for (int j = 0; j < frames[i].getStackSize(); j++)
                if (frames[i].getStack(j) instanceof UninitValue u) pending.add(u);
        }
        if (pending.isEmpty()) return;

        String where = cn.name + "." + mn.name + mn.desc;
        for (UninitValue u : pending) {
            AbstractInsnNode afterNew = nextSignificant(u.newInsn);
            boolean dupped = afterNew != null && afterNew.getOpcode() == DUP;

            // locate the <init> consuming u as its receiver (frames/indices are pre-rewrite,
            // but the <init> nodes themselves stay in place as insertion anchors)
            MethodInsnNode init = null;
            Frame<BasicValue> initFrame = null;
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof MethodInsnNode mi && mi.getOpcode() == INVOKESPECIAL
                        && mi.name.equals("<init>") && frames[i] != null) {
                    int r = frames[i].getStackSize() - 1 - Type.getArgumentTypes(mi.desc).length;
                    if (r >= 0 && u.equals(frames[i].getStack(r))) { init = mi; initFrame = frames[i]; break; }
                }
            }
            if (init == null)
                throw new UnsupportedOperationException("cannot locate <init> for NEW at a suspension point: " + where);

            int nargs = Type.getArgumentTypes(init.desc).length;
            int receiver = initFrame.getStackSize() - 1 - nargs;
            int copies = 1;
            while (receiver - copies >= 0 && u.equals(initFrame.getStack(receiver - copies))) copies++;
            if (copies > 2 || (copies == 2) != dupped)
                throw new UnsupportedOperationException("unsupported NEW/DUP shape at a suspension point: " + where);

            Type[] argTypes = new Type[nargs];
            int[] argSlots = new int[nargs];
            int slot = mn.maxLocals;
            for (int a = 0; a < nargs; a++) {
                argTypes[a] = initFrame.getStack(receiver + 1 + a).getType();
                argSlots[a] = slot;
                slot += argTypes[a].getSize();
            }
            mn.maxLocals = slot;

            InsnList patch = new InsnList();
            for (int a = nargs - 1; a >= 0; a--)
                patch.add(new VarInsnNode(argTypes[a].getOpcode(ISTORE), argSlots[a]));
            patch.add(new TypeInsnNode(NEW, u.newInsn.desc));
            if (dupped) patch.add(new InsnNode(DUP));
            for (int a = 0; a < nargs; a++)
                patch.add(new VarInsnNode(argTypes[a].getOpcode(ILOAD), argSlots[a]));
            mn.instructions.insertBefore(init, patch);
            if (dupped) mn.instructions.remove(afterNew);
            mn.instructions.remove(u.newInsn);
        }
        mn.maxStack += 2;
    }

    private static AbstractInsnNode nextSignificant(AbstractInsnNode insn) {
        for (AbstractInsnNode p = insn.getNext(); p != null; p = p.getNext())
            if (!(p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode)) return p;
        return null;
    }

    // ------------------------------------------------------------------ analysis

    private static Frame<BasicValue>[] analyze(String owner, MethodNode mn) {
        Analyzer<BasicValue> analyzer = new Analyzer<>(new Interp()) {
            @Override protected Frame<BasicValue> newFrame(int numLocals, int numStack) {
                return new InitTrackingFrame(numLocals, numStack);
            }
            @Override protected Frame<BasicValue> newFrame(Frame<? extends BasicValue> frame) {
                return new InitTrackingFrame(frame);
            }
        };
        try {
            return analyzer.analyze(owner, mn);
        } catch (AnalyzerException e) {
            throw new IllegalStateException("analysis of " + owner + "." + mn.name + " failed", e);
        }
    }

    /**
     * SimpleVerifier (precise reference types, needed for the CHECKCASTs on restore) extended to
     * mark the result of NEW as uninitialized-until-&lt;init&gt;, so suspension points with such a
     * value in flight can be rejected instead of miscompiled.
     */
    static final class Interp extends SimpleVerifier {
        Interp() {
            super(ASM9, null, null, null, false);
            setClassLoader(AsyncTransformer.class.getClassLoader());
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            BasicValue v = super.newOperation(insn);
            return insn.getOpcode() == NEW ? new UninitValue(v.getType(), (TypeInsnNode) insn) : v;
        }
    }

    /**
     * Value marking "allocated by {@code newInsn} but &lt;init&gt; not yet called". Equality is
     * keyed on the NEW instruction (not object identity): the analyzer re-executes blocks while
     * iterating to a fixpoint, creating fresh instances for the same allocation site, and merge
     * must recognize them as the same value or the marking is lost.
     */
    static final class UninitValue extends BasicValue {
        final TypeInsnNode newInsn;

        UninitValue(Type type, TypeInsnNode newInsn) {
            super(type);
            this.newInsn = newInsn;
        }

        @Override public boolean equals(Object o) { return o instanceof UninitValue u && u.newInsn == newInsn; }
        @Override public int hashCode() { return System.identityHashCode(newInsn); }
    }

    /**
     * Frame that, upon INVOKESPECIAL &lt;init&gt;, replaces all copies of the receiver's
     * {@link UninitValue} (e.g. the one left by DUP, or stored in a local) with an initialized
     * value — mirroring what the real verifier does. Without this, any object constructed before
     * an await and live across it would be spuriously rejected.
     */
    static final class InitTrackingFrame extends Frame<BasicValue> {
        InitTrackingFrame(int numLocals, int numStack) { super(numLocals, numStack); }
        InitTrackingFrame(Frame<? extends BasicValue> frame) { super(frame); }

        @Override
        public void execute(AbstractInsnNode insn, Interpreter<BasicValue> interpreter) throws AnalyzerException {
            BasicValue uninit = null;
            if (insn.getOpcode() == INVOKESPECIAL && insn instanceof MethodInsnNode mi && "<init>".equals(mi.name)) {
                int receiver = getStackSize() - 1 - Type.getArgumentTypes(mi.desc).length;
                if (receiver >= 0 && getStack(receiver) instanceof UninitValue u) uninit = u;
            }
            super.execute(insn, interpreter);
            if (uninit != null) {
                BasicValue inited = new BasicValue(uninit.getType());
                for (int i = 0; i < getLocals(); i++)
                    if (uninit.equals(getLocal(i))) setLocal(i, inited);
                int size = getStackSize();
                BasicValue[] stack = new BasicValue[size];
                for (int i = size - 1; i >= 0; i--) stack[i] = pop();
                for (int i = 0; i < size; i++) push(uninit.equals(stack[i]) ? inited : stack[i]);
            }
        }
    }

    // ------------------------------------------------------------------ debug metadata

    private static void appendDebug(StringBuilder sb, MethodNode mn, AbstractInsnNode awaitInsn,
                                    int state, Frame<BasicValue> f, BitSet live) {
        int line = -1;
        for (AbstractInsnNode p = awaitInsn; p != null; p = p.getPrevious())
            if (p instanceof LineNumberNode ln) { line = ln.line; break; }
        sb.append("state ").append(state);
        if (line >= 0) sb.append(" (line ").append(line).append(")");
        sb.append(":");
        int awaitIdx = mn.instructions.indexOf(awaitInsn);
        boolean first = true;
        for (int v = 0; v < mn.maxLocals; v++) {
            BasicValue value = f.getLocal(v);
            if (!isSpillable(value) || isNullType(value.getType())) continue;
            // dead ref slots are nulled at this state, not spilled — omit from the rendering
            if (isRef(value.getType()) && !live.get(v)) continue;
            String name = localName(mn, v, awaitIdx);
            sb.append(first ? " " : " | ").append(name != null ? name : "local" + v)
              .append(" -> ").append(slotName(value.getType(), v));
            first = false;
        }
        for (int j = 0; j < f.getStackSize() - 1; j++) {
            Type t = f.getStack(j).getType();
            if (isNullType(t)) continue;
            sb.append(first ? " " : " | ").append("stack").append(j)
              .append(" -> ").append(slotName(t, mn.maxLocals + j));
            first = false;
        }
        sb.append('\n');
    }

    private static String slotName(Type t, int idx) {
        return (isRef(t) ? "refs[" : "prims[") + idx + "] (" + t.getDescriptor() + ")";
    }

    private static String localName(MethodNode mn, int slot, int insnIdx) {
        if (mn.localVariables == null) return null;
        for (LocalVariableNode lv : mn.localVariables) {
            if (lv.index != slot) continue;
            int s = mn.instructions.indexOf(lv.start), e = mn.instructions.indexOf(lv.end);
            if (insnIdx >= s && insnIdx < e) return lv.name;
        }
        return null;
    }

    // ------------------------------------------------------------------ class writing

    /**
     * COMPUTE_FRAMES needs a common-superclass oracle; generated state machine names are not
     * loadable by the writer's class loader, so resolve them as their superclass.
     */
    static final class SmAwareClassWriter extends ClassWriter {
        private final Set<String> generatedInternalNames;

        SmAwareClassWriter(Set<String> generatedInternalNames) {
            super(COMPUTE_FRAMES | COMPUTE_MAXS);
            this.generatedInternalNames = generatedInternalNames;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            String a = generatedInternalNames.contains(type1) ? FSM : type1;
            String b = generatedInternalNames.contains(type2) ? FSM : type2;
            if (a.equals(b)) return a;
            return super.getCommonSuperClass(a, b);
        }
    }
}
