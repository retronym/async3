# async3: a bytecode-level async transform (design notes & prototype plan)

Status: exploratory prototype. The repository root is the standalone Maven
project; the [README](../README.md) is the guided tour (usage, transcripts,
javap of the generated shapes, debugging instructions, comparison table).
This document is the rationale and the plan.

## 1. Motivation

The current async support is split between:

- a user-writable frontend (macro or compiler plugin) that marks methods for
  transformation and lifts them into a state-machine wrapper class
  (`src/testkit/scala/tools/testkit/async/Async.scala`,
  `test/junit/scala/tools/nsc/async/AnnotationDrivenAsyncTest.scala`), and
- the built-in `async` compiler phase
  (`src/compiler/scala/tools/nsc/transform/async/AsyncPhase.scala`) that turns
  the marked method body into a resumable state machine, working on typed trees.

The tree-level transform is extremely non-trivial (ANF transform, local
lifting, symbol re-ownership, live-variable analysis, patmat interaction,
value-class boxing workarounds). We already run it late in the pipeline to see
erased types and expanded pattern matches, but it remains the most complex
transform in the compiler.

**Idea (inspired by Kotlin coroutines):** perform the state-machine
transformation *after* lowering to bytecode. A stack machine makes the problem
dramatically simpler. Taken further, the transform can be deferred to **class
load time or even runtime**, driven by profiling: run the synchronous
(blocking) version until it proves to be a bottleneck, then swap in the
suspendable version.

## 2. Prior art

| System | Where the transform runs | Notes |
|---|---|---|
| Kotlin coroutines | compiler backend, on bytecode-level IR (`CoroutineTransformerMethodVisitor`) | spills to fields of the continuation object; `@DebugMetadata` annotation maps state → spilled variable names + line numbers |
| Quasar | java agent / AoT ASM instrumentation | generic `Stack` object: `long[]` for primitives + `Object[]` for refs |
| Kilim, Apache Javaflow | AoT ASM instrumentation | same family; Javaflow documents the uninitialized-object problem |
| Project Loom | inside the VM (native stack copying) | the "do nothing, block on a virtual thread" baseline |

Everything below has been done before in some form; the novelty is wiring it to
Scala's existing frontend-pluggable async ABI and the *lazy, profile-driven*
variant. (A reader-facing trade-off comparison of the same systems is in the
README's "How it compares".)

## 3. Why bytecode level is easier

Almost everything hard in `AsyncPhase` is an artifact of working on trees:

- **ANF transform disappears.** Its entire purpose is to forbid `await` in
  expression position so that evaluation-in-progress never needs to be
  reified. On the JVM, evaluation-in-progress *is* the operand stack, and
  ASM's `Analyzer` reports the exact type of every stack slot and local at
  every instruction.
- **Lifter / `UseFields` disappear.** No symbol re-ownership,
  `makeNotPrivate`, or outer-path reconstruction. Locals are numbered slots to
  spill and restore.
- **Patmat, by-name, named/default args, value classes, closures** are already
  lowered. The `{ expr: Any }` boxing hack and `ForceMatchDesugar` attachment
  go away. `await` in extractor calls and guards — which the annotation-driven
  frontend needs and which requires patmat-desugar trickery today — becomes a
  non-event: by codegen time it is just a call instruction inside ordinary
  branches.
- **`finally` is already duplicated** by codegen; the messiest control-flow
  case in the tree transform needs no special handling.
- **Live-variable analysis** (the `LiveVariables` null-out machinery) becomes
  a textbook bitvector dataflow over instructions. ✅ implemented
  (`async3.transform.Liveness`, with conservative exception edges; drives the
  dead-slot nulling of §5).

### 3.1 The transform, in one paragraph

For each method containing calls to the `await` marker: run ASM's analyzer to
get the frame (types of locals and operand stack) at each await call site
*i* ∈ 1..N. Replace the call with: fast-path check `getCompleted(f)`; if
already complete, proceed; otherwise spill live locals and the operand stack
below the awaited future into a frame object, set `state = i`, register
`onComplete(f, this)`, and return. The method prologue becomes a
`TABLESWITCH` on `state` whose case *i* restores the spilled slots and jumps
to the instruction after the call, pushing the result. Returns become
`completeSuccess`; an outermost handler routes exceptions to
`completeFailure`. The existing `AsyncStateMachine` ABI (`state`,
`onComplete`, `getCompleted`, `tryGet`, `completeSuccess/Failure`) is
preserved, so existing frontends keep working: the frontend's only remaining
body-level job is to leave a real `await` call instruction in the bytecode.

### 3.2 Known hard corners (all solved in prior art)

1. **Uninitialized objects on the stack**: `new Foo(await(f))` has an
   uninitialized reference on the stack at the suspension point, which cannot
   be stored in a field or array. Kotlin sinks the `NEW`/`DUP` pair past the
   suspension and re-materializes it before `INVOKESPECIAL <init>`. The one
   genuinely fiddly piece. ✅ implemented in the prototype
   (`sinkUninitializedNews`): the analyzer marks NEW results
   uninitialized-until-`<init>` (with verifier-style replacement of all copies
   upon initialization, keyed by allocation site so loop-fixpoint re-execution
   doesn't lose the marking); the pre-pass deletes the `NEW`(+`DUP`) and
   re-materializes it below the constructor args at the `<init>` site. Handles
   nested news, multiple awaits among arguments, conditional argument
   expressions, and the statement (`POP`) idiom; exotic stack-shuffled shapes
   are still rejected rather than miscompiled.
2. **`await` under `MONITORENTER`**: detect and reject with a clear error
   (trivially detectable at bytecode level; silently broken today).
3. **Stack map frames**: jumping from the dispatch switch into the middle of
   try regions is verifier-legal as long as frames match. Use
   `COMPUTE_FRAMES` with a custom `getCommonSuperClass`, and validate every
   output with `CheckClassAdapter` in tests.
4. **long/double two-slot handling, 64KB method limit** — mechanical, but
   needs tests.

## 4. The annotation-driven frontend (Optimus-style)

The biggest production use case is annotation-driven: users mark methods
`@async`; a compiler plugin emits a *pair* of methods — the direct
(synchronous) one and a lifted/suspendable one — and retargets calls between
async contexts to the lifted variants. This is approximated by
`AnnotationDrivenAsyncTest` (`@customAsync` / `@autoawait`,
`externalFsmMethod = true`).

This doesn't change the design; it sharpens the division of labour:

- **Stays in the frontend (typed work):** generating the lifted method's
  *signature* (`f` vs. `f$queued: Node[T]`), deciding which callee variant
  each call site targets, inserting the `await` marker around calls to lifted
  methods, type checking the user-facing API.
- **Moves to bytecode (untyped work):** everything `markForAsyncTransform`
  triggers today — the state machine, lifting, liveness. The plugin emits the
  lifted method's body as a *plain synchronous body with marker calls*
  (`await(g$queued(args))`), and the bytecode stage rewrites it. The frontend
  no longer wraps bodies into state-machine classes at all.
- The `externalFsmMethod` shape in the test (FSM body as a sibling method
  taking `(self, tr)` rather than an `apply` override) is the natural shape
  for the bytecode transform too: it transforms an arbitrary method and
  generates the state-machine class separately.
- **Forking:** Optimus also supports forking — queuing a child computation
  (`g$queued(...)` returns a Node immediately) separately from awaiting it, so
  siblings run in parallel under the graph scheduler. This costs the bytecode
  transform nothing: `await` takes any `F[_]` value, wherever it was created
  (a local, a parameter, a Node queued many statements earlier), so fork/join
  shapes transform identically to sequential ones. The prototype's samples
  already await futures received as parameters, which is the same shape.
- **Optional further step:** since the direct and lifted bodies differ only in
  call targets (`g(...)` vs. `await(g$queued(...))`) and that retargeting is
  *descriptor-mechanical* once the frontend has recorded which methods are
  async, the bytecode stage could derive the entire lifted method from the
  direct one. Then the frontend emits one body + metadata, and lifted variants
  can be derived lazily at runtime — which is exactly the
  "run sync with blocking until profiling says otherwise" mode this user has
  asked for. Caveat, because of forking: a purely mechanical per-call-site
  rewrite to `await(g$queued(...))` produces *sequential* semantics — correct,
  but it forfeits fork opportunities. Where forking matters, either the
  frontend keeps emitting the lifted body (placing queue and await points
  deliberately), or the mechanical derivation is followed by an
  await-sinking/batching optimization that separates the queue point from the
  await point when dataflow allows (Optimus has exactly this kind of analysis;
  at bytecode level it is a code-motion pass over the marker calls).
- The ABI stays generic in `F[_]`/`R[_]` (`Future`/`Try`, `CustomFuture`/
  `Either`, `Node`/...): the transform only emits calls to the abstract
  `getCompleted`/`onComplete`/`tryGet` methods and never inspects `F`.

## 5. State capture: the frame representation

| Representation | Pros | Cons |
|---|---|---|
| Generic frame: `long[] prims` + `Object[] refs` (Quasar style) | no classgen → works for the lazy/runtime variant; one allocation per call | bounds checks, card marks on ref stores; names live in side metadata |
| Generated fields on the SM class, named after source locals (Kotlin style, but with real names instead of `L$0`) | fastest spills; debugger sees named fields | class per method (fine AoT; needs `defineHiddenClass` at runtime) |
| Boxed `Object[]` only | simplest | boxes every primitive spill — rejected |

**Prototype choice: the generic two-array frame**, because it is the only one
that works unchanged in both the compile-time and runtime-deferred modes and
keeps the transformer free of field-layout decisions. All primitives normalize
to `long` slots (`floatToRawIntBits` etc.). One mutable frame per
state-machine instance, slots addressed positionally (frame slot *v* = local
slot *v*; stack entry *j* = `maxLocals + j`), reused across states, with
liveness-driven nulling of dead ref slots (the bytecode analogue of
`fieldsToNullOut`). ✅ implemented: at each park, dead ref locals are not
spilled and every ref slot not written at that state is nulled, so a suspended
frame holds exactly the references the resumed code can still read; a dead
ref local in scope restores as null, same as the nulled field in the tree
transform. "Typed fields on the SM class" is the planned optimization
for the AoT path — decide with JMH numbers, not vibes.

Note the layout is per-state: slot *k* can hold different variables in
different states. Debug metadata must be keyed by state.

## 6. Debuggability

Two distinct problems:

1. **While executing** (between suspensions): restore spilled values into
   their *original local slots* on resume. Then the original
   `LocalVariableTable` entries remain truthful; we extend each entry's range
   (or emit a duplicate entry) to cover resumed regions. A debugger stepping
   through resumed code sees ordinary named locals. Line numbers carry over
   untouched because the original instructions are preserved.
2. **While suspended** (the frame is heap data): emit per-method static
   metadata mapping `state → [(name, descriptor, frameKind, frameIndex,
   sourceLine)]`, sourced from the original LVT + line number table. This is
   Kotlin's `@DebugMetadata` design. A class annotation (or constant field)
   travels with the class and is readable by a debugger plugin, a `toString`,
   or an async-stack-trace dumper:
   `suspended at Foo.scala:42: x=1, items=List(...)`.

Bonus: give frames a `parent` pointer to the awaiting frame and logical async
stack traces (Kotlin's `CoroutineStackFrame`) fall out almost automatically. The
consumer side is proven out by Kotlin's tooling: the IntelliJ plugin's
[coroutine debugger](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin/jvm-debugger/coroutines)
(`PositionManager` + `AsyncStackTraceProvider` rendering suspended frames
alongside real ones) and
[kotlinx-coroutines-debug](https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-debug)'s
`DebugProbes`. See the README's "Future work" for the async3 analogue.

## 7. The runtime-deferred ("tiered") variant

The marker design makes this clean: the compiler emits the method in its
**synchronous shape**, where `await(f)` is a real static method whose default
implementation blocks, plus a `@Suspendable` annotation so the transformer
doesn't scan the world. That class runs correctly with zero transformation —
the "interpreter tier".

The dispatch mechanism matters more than the transform:

- `Instrumentation.retransformClasses` can only replace method bodies — it
  cannot add methods or fields, so a state machine cannot be grafted onto the
  original class after the fact. Awkward.
- **Better: `invokedynamic` indirection.** Compile entry to `@Suspendable`
  methods through an indy bootstrap backed by a `MutableCallSite` /
  `SwitchPoint`, initially bound to the blocking version. When profiling (a
  counter in the blocking `await`, or observed contention) says
  hot-and-blocking, generate the state machine via
  `Lookup.defineHiddenClass` and promote the call site. The JIT inlines through
  stable call sites, so the un-promoted path costs ~nothing.
- As with JIT tiering there is no on-stack replacement: threads already parked
  in a blocking await stay parked; only subsequent invocations suspend
  cooperatively. Acceptable; state it.

**The Loom question, answered up front:** this runtime variant is userland
Loom. On JDK 21+, "run the sync version, block on a virtual thread" is already
a fine steady state. The case for the transform is what Loom doesn't cover:
pre-21 JVMs, pinning-sensitive or allocation-sensitive environments, and
runtimes (Optimus-style graph schedulers) that need their own notion of
suspension and scheduling rather than thread semantics. (Non-JVM targets are
*not* a differentiator: a bytecode-level transform is exactly as JVM-bound as
Loom; Scala.js/Native would need their own IR-level equivalent either way.) **Forking is the clearest example**: Optimus separates
*queuing* a computation (`g$queued(...)` returns a Node immediately) from
*awaiting* it, so several child computations can be forked before the first
suspension — which is what makes auto-parallelism, batching, deduplication,
and distribution visible to the scheduler as a dataflow graph. Blocking on a
virtual thread collapses that back to one sequential thread of execution
unless the user manually spawns threads; the queued/await split keeps the
graph explicit at zero syntax cost. The transform is agnostic to this by
construction — `await` accepts any `F[_]` value regardless of where or when it
was created, so fork/join shapes (queue several, await later, awaitables held
in locals or passed as arguments) need nothing beyond what sequential code
needs. The AoT variant additionally needs no runtime classgen at all.

## 7.5 Lambda front end (prototype of the runtime-triggered transform)

`Async.async(() -> { ... Async.await(f) ... })` triggers the transform at
runtime via **lambda cracking**: the body interface extends `Serializable`, so
the proxy's `writeReplace` yields a `SerializedLambda` naming the synthetic
`lambda$...` impl method (where javac placed the body's bytecode, in the
capturing class) plus the captured arguments. The impl method goes through the
single-method transformer entry point and is defined with
`Lookup.defineHiddenClass(bytes, false, NESTMATE)` — the hidden class joins
the capturing class's nest dynamically, so private access works with zero
host patching (the runtime twin of the AoT path's `NestMembers` patching).
Captured locals, including a captured `this`, are simply the impl method's
leading parameters; the constructor handle is cached per impl method. This is
an accurate stand-in for the eventual compiler integration, where
`async { ... }` compiles to `invokedynamic` and the bootstrap receives the
caller's `Lookup` without any cracking.

**Lifting method references.** `Async.lift(C::m)` extends the same machinery
from inline bodies to *existing methods, possibly in other classes*: cracking
a method reference yields the target method itself (not a synthetic
`lambda$`), and the captured arguments (a bound receiver for `obj::m`)
concatenated with per-call arguments form the target's entry locals for every
reference shape — static, unbound instance, bound instance, private (the
hidden class joins the target's nest). This is the **runtime analogue of the
annotation-driven frontend's direct/`$queued` method pair**: the author writes
one method calling the await markers; direct calls block (tier 0), the lifted
handle suspends — no build-time sibling generation. A lifted handle is also
the natural installation point for the phase-4 tier promotion: today it compiles
eagerly on first use; the profiling-driven variant binds a `MutableCallSite`
to the blocking path and swaps in the transformed version when hot.

**Debugger finding (empirical):** IntelliJ resolves a line breakpoint inside a
lambda body only against classes named exactly like the enclosing source
class — javac puts lambda bodies *in* that class, so IJ registers an
exact-name class-prepare filter, with no `$*` wildcard (that is reserved for
anonymous/local classes). Consequently a state machine named
`Owner$async$lambda$m$0` never receives the breakpoint; it silently binds into
the original, now-dead `lambda$` method instead. Workaround implemented
(`-Dasync3.lambda.debuggable=true`): define the state machine *under the
capturing class's own name* in a throwaway loader — JDI matches names across
loaders, so the IDE finds it; verified with a JDI program replaying exactly
IntelliJ's procedure (`classesByName` → `locationsOfLine` → breakpoint → hit
after a real resume with named locals intact). The shadow trade-off: the
host's name resolves to the shadow from inside it, so bodies referencing the
host class (captured `this`, same-class helpers, nested lambdas) are rejected
in this mode. The clean resolution belongs to the compiler/agent integration:
emit the resumable body as a sibling method *of the capturing class itself*
(the `externalFsmMethod` shape §4 already uses) — then breakpoint binding,
nest access, and stepping all work with no shadowing and no mode split.

## 7.6 The Java agent (in-place shape) — implemented

A load-time `ClassFileTransformer` (`async3.agent.AsyncAgent`, `Premain-Class`/
`Agent-Class` in the jar manifest) resolves the three ugliest compromises at
once, because **at class load time adding methods is still legal** (the
limitation driving §7's retransformation discussion is that
`retransformClasses` may only replace bodies). For every method containing
await markers, `AsyncTransformer.transformInPlace` adds to the host class:

- `m$asyncBody(FutureStateMachine, Object)` — the resumable body as a private
  static sibling. The generated instructions are byte-identical to the
  `apply` of the class-per-method shape: the body only touches the public
  `FutureStateMachine` ABI through local 0, so it is indifferent to whether
  local 0 is `this` of a subclass or a parameter.
- `m$async(args)` — an entry point allocating the shared runtime shell
  `DelegatingStateMachine(refSlots, primSlots, bodyHandle, debugMetadata)`,
  where `bodyHandle` is an `LDC MethodHandle` constant referencing the sibling
  (private access is fine: the constant is resolved from within the host).
  **No per-method class generation at all**; debug metadata travels per
  instance instead of as a per-class constant.

The original method is untouched — the blocking tier; the pair exists for
every marked method on the classpath, including code not built with the
project; the transform is idempotent under agent re-entry and skips classes
already carrying AoT-generated entries. `Async.async`/`Async.lift` probe for
the prepared entry first and skip cracking-time transformation entirely.

Consequences, verified end-to-end (`JdiAgentCheck` attaching to `AgentProbe`
under `-javaagent`):

- **Debugging is simply correct.** The executing bytecode for a lambda body
  lives in the class its source lines belong to, so the §7.5 problem
  dissolves: `locationsOfLine` on the host returns the (dead) original and
  the (live) `$asyncBody` locations, the IDE plants breakpoints on all of
  them, and the hit lands in the sibling with named locals intact. No shadow
  classes, no modes.
- **Private access is same-class access** — the nestmate machinery
  (AoT `NestMembers` patching, `defineHiddenClass(NESTMATE)`) becomes
  unnecessary in this shape, including for inner-class hosts.
- **The tier-promotion skeleton exists**: the direct/lifted pair is materialized at load with no extra work; what remains for the lazy tier is only the dispatch policy
  (a `MutableCallSite` promotion or body retransformation — both legal now that
  the members exist).

Not covered by the agent: stepping over a real suspension still steps out
(inherent; a debugger plugin planting the resume breakpoint is the fix), and
`ClassFileTransformer` never sees hidden classes (irrelevant here — lambda
bodies live in ordinary capturing classes).

## 7.7 Elevating the blocking tier (transitive suspension)

§7.6 transforms a method iff its *own* bytecode contains an `await` marker
(`hasAwait`). That is purely local: the agent walks one class at a time, at
load, and never consults a call graph. It catches **direct** blocking. It
cannot catch **indirect** blocking — a method `f` that blocks only because
`f → g → await`. Making `f` *suspend* rather than block is "elevating the
blocking tier", and it is the missing half of the tiering story.

Elevation needs two separable things; keep them apart:

1. **A suspending entry on the callee** (`g$async`) reachable from `f`.
2. **A rewrite of `f`**: the `invoke g` becomes `await(g$async(...))`, then `f`
   runs through the ordinary state-machine transform. At bytecode level this is
   mechanical marker injection around a retargeted invoke — the easy half.

The hard half is **discovery**: *which* methods need elevating? That is the
transitive closure "suspendable if it directly awaits, or calls a suspendable
method," over the override graph — function *coloring*. Every production system
(Kotlin `suspend`, scala-async, Optimus `@async`, Quasar `@Suspendable`) makes
the author or compiler supply this closure rather than infer it, for two
reasons that bite the agent directly:

- **The load-time agent cannot see the call graph.** It transforms `C` before
  `C`'s callees load — they may never load, may come from code you didn't
  build, or be chosen by virtual dispatch. A sound *static* closure needs a
  closed world the agent lacks.
- **`retransformClasses` cannot add members.** So you cannot learn `f` is
  suspendable and *then* graft `f$async`/`f$asyncBody` onto the loaded class.
  Adding members is legal only at first load — before you know you need them.

### Design: discover by runtime profiling, materialize on demand

The chosen resolution sidesteps the static closure entirely and leans on the
fact that **tier 0 already runs correctly**, so the closure can be *observed*
instead of computed.

**Discovery — the blocking `await` is its own profiler.** The slow path of the
blocking `AsyncRT.await` (the branch that is about to park a real thread) bumps
a per-site counter and, when hot, takes one `StackWalker` sample
(`RETAIN_CLASS_REFERENCE`). That sample **is a concrete sample of one real
suspension path** — `f → g → await`, with the *actual dispatched* callees, not
static types. Virtual dispatch thus resolves itself: you elevate against the
target that really ran, never reasoning about the override graph or the open
world. The suspendable set `S` grows only by sampled evidence; nothing in it
is speculative. Counter and sample come from one site.

**Materialization — on-demand hidden-class siblings.** Indirectly-blocking
methods get *nothing* at load (no bloat — the cost model stays "pay only where
profiling says it matters"). At elevation time, per method:

1. Read the method's bytecode back from its defining loader, run the
   single-method transform, and define the state machine as a **hidden class**
   (`defineHiddenClass(bytes, NESTMATE)` into the method's own nest for private
   access) — the §7.5 shape, the same cost model as today's no-agent `lift`
   fallback, now triggered by the profiler instead of by first use.
2. Inside that body, each `invoke g` being suspended through is retargeted to
   `await(g$async(...))` before the transform runs.
3. Cache a `MethodHandle` to the hidden entry, keyed per method (the
   constructor-handle cache `Async` already keeps).

**Dispatch — the edge goes through `invokedynamic`.** Hidden classes are not
nameable in a constant pool, so a caller cannot `invoke f$async` directly. The
caller→callee *edge* is promoted by **retransforming the caller's body** (legal:
body replacement only) so the `invoke f` becomes an indy site whose bootstrap
returns the `MethodHandle` to `f`'s hidden entry, awaited, backed by a
`MutableCallSite`. The call site can therefore also be promoted **back** to
blocking when a path goes cold — de-elevation, which a hard-coded retransform
would forfeit. This is precisely §7's tier-promotion mechanism; the two design
choices converge on it.

Net: the retransform restriction is dodged from both sides — **bodies are
retransformed (callers + boundary), members are never added to loaded classes
(callees become hidden classes instead).** (The agent must declare
`Can-Retransform-Classes` and register with `addTransformer(t, true)`; §7.6's
agent does not today.)

### The chain has a top and a bottom

```
Async.async { ... }          ← async boundary: already transformed; has its in-host pair
        │  calls e()
        ▼
        e   ── elevate ──▶ retarget invoke f → await(f$async); regen
        │  calls f()
        ▼
        f   ── elevate ──▶ retarget invoke g → await(g$async); gen hidden sibling
        │  calls g()
        ▼
        g   (direct awaiter: already has g$async in-host from load time)
        │
        ▼
      await(leaf)            ← blocking-await slow path fires; live stack IS the profiler
```

**Elevation is only useful contiguously from the await up to an async
boundary.** Suspending `f` while `e` still blocks merely moves the park up one
frame. So the unit of elevation is the *run of frames between a boundary and a
hot await*, and the boundary (`Async.async`/`lift`, or an actor/workflow
scheduler entry) is the natural top: it already exists because the user opted
in there, and already carries its in-host pair. The sampled stack always
bottoms at such a boundary, so propagation terminates on its own. **Granularity
decision: promote the whole sampled chain on first hot observation** — the
boundary bounds it, so there is nothing to ratchet toward.

**The entry ABI is a firewall against re-derivation cascades.** Once `f` calls
`g$async`, later elevation *inside* `g` (because `g`'s own callee goes hot) is
invisible to `f` — `f` still calls the stable `g$async` entry. Deepening the
closure never regenerates already-elevated callers. The set `S` grows
monotonically and each method is derived at most once.

### Costs, stated honestly

- **Debugging regresses for elevated indirect methods.** Direct-await methods
  keep in-host breakpoint binding (load-time pair). An *elevated* method's live
  bytecode now lives in a hidden class, so it re-enters the §7.5 problem:
  IntelliJ's exact-name class-prepare filter will not bind a line breakpoint
  inside `Owner$async$f$N`. Fix mirrors the lambda path — a
  `-Dasync3.elevate.debuggable` shadow mode — or accept that hot-elevated
  frames debug like the no-agent path. (The universal-entry alternative —
  give *every* method a shim pair at load, elevate by body-retransform only —
  keeps these in-host and makes elevation unconditionally sound, at the price
  of pervasive class bloat. Rejected here in favour of "pay only where hot.")
- **No on-stack replacement** (§7): threads already parked stay parked; only
  subsequent calls suspend.
- **Per-elevation work:** one bytecode read-back + parse + `defineHiddenClass`
  per method, one caller-body retransform per edge; amortized, cached, once per
  hot path.

**Implemented (the static in-class slice).** `transformInPlace` now computes the
same-class suspendability closure and performs the elevation rewrite for every
suspendable method, not just those that await directly: `invoke g` becomes
`await(g$async(...))`, coerced back to `g`'s return type, and the downstream
state-machine transform treats the injected marker as an ordinary suspension
point. So `int indirect(f) { return leaf(f) * 10; }` — no source `await` — gets a
real `indirect$async` that suspends through `leaf$async`. Closure and rewrite are
restricted to one class per pass (the only scope where suspendability of the
target can be detected statically). The *dispatch* of each elevated call is
handled by one of two mechanisms (`elevate` chooses by `dispatchSafe`):
**statically bound** calls (`INVOKESTATIC`, `INVOKESPECIAL`/private,
`INVOKEVIRTUAL` to a `final` method or in a `final` class) rewrite directly to
the sibling `g$async`; **virtually dispatched** calls (`INVOKEINTERFACE`, or
`INVOKEVIRTUAL` to an overridable method) rewrite to a call-site
`invokedynamic` that resolves the suspending entry against the *actual
receiver* at runtime (§7.8). The latter is what makes elevation through an
overridden interface default sound — the very case the direct rewrite had to
refuse. The blocking original is preserved byte-identical, re-entry is
idempotent, and callers this transform cannot rewrite are quietly left on the
blocking tier. What remains is the *runtime* half — discovering
*which* indirect methods are worth elevating (the `StackWalker` sample), the
cross-class closure with on-demand hidden siblings, and the indy edge promotion.

This is userland Loom's tiering done by observation: tier 0 runs and blocks;
the blocking await profiles itself; hot blocking stacks are elevated frame by
frame up to the boundary, with virtual dispatch and the open world handled for
free because every elevation is backed by a sampled execution.

## 7.8 Elevating through virtual dispatch (Strategy B — implemented)

Rewriting `invoke g` → `await(g$async)` is unsound for a virtually dispatched
`g`: `g$async` is an independent method with its own override tree, so a
subclass can override `g` without a matching `g$async`, and the rewrite runs
the wrong body (demonstrated: an interface default `indirect` calling `leaf`,
with one implementer overriding `leaf` — `indirect$async` ran the interface's
`leaf`, not the override). Two ways to make the two override trees parallel:

- **(A) Scaffold the callee hierarchy** — declare `g$async` as a contract
  member parallel to `g`, with a default body that is the blocking shim
  `completedFuture(this.g(args))` (so any receiver, processed or not, runs its
  *own* `g` via virtual dispatch), overridden by the real state machine where a
  body is suspendable. Sound and open-world-safe, but it requires a
  declaration-site color (`@Suspendable`) on the type at the call site, because
  the call site binds `g$async` against a possibly-abstract supertype where
  suspendability is invisible — and `retransformClasses` cannot add the member
  later. This is the Kotlin `suspend` / Optimus `$queued` shape.

- **(B) Scaffold the call site** — *implemented here* (`async3.runtime.Elevation`).
  The virtual call rewrites to
  `invokedynamic g (receiver, args) -> CompletableFuture [bootstrap, blockingDesc]`
  followed by the usual `await`. The bootstrap's call site, per actual receiver
  class (an inline cache via `ClassValue`), finds that receiver's *real* `g`
  (honouring overrides and inherited interface defaults), transforms just that
  method with `transformMethod`, defines it as a hidden `NESTMATE` of the body's
  declaring class, and invokes it. Soundness comes from resolving against the
  runtime receiver, not a static type: a non-awaiting override transforms to a
  state machine with no suspension points (completes synchronously, like the
  blocking shim) while a suspendable body really suspends — each running its own
  `g`. **No `$async` members or annotations on the callee hierarchy**, and new
  receiver classes (the open world) are handled on first encounter.

The transform keeps the direct `g$async` rewrite for statically bound calls
(cheaper, no call-site machinery) and uses (B) only where dispatch is virtual.
Limitation: the per-receiver transform is single-method (it elevates `g`'s own
awaits, not transitive blocking inside `g`) — the same scope as `Async.lift`.
Verified by `ElevateDispatchTest`: an overridden interface default and an
overridable virtual call both elevate and agree with the blocking tier (`Impl`
→ 60; `OverridingImpl` runs its override → 9990).

## 7.9 The profile-driven tier promotion (StackWalker — implemented)

§7.7 described electing *when* to elevate from a runtime profiler rather than
elevating everything reachable from an `await` ahead of time. That sampling now
exists and drives the Strategy-B call site.

- **The blocking await profiles itself** (`async3.runtime.Profiler`,
  `AsyncRT.await`). When the awaited future is *not* already complete — a real
  carrier block is imminent — `await` calls `Profiler.observeBlock`, which takes
  a `StackWalker.getInstance(RETAIN_CLASS_REFERENCE)` sample and counts the
  **contiguous run of blocking user frames** above the await, up to the
  elevation boundary (the first runtime/reflection/generated frame below them) —
  the direct awaiter *and every blocking caller it reaches through*, each keyed
  `class.name+descriptor`. Counting the whole chain (not just the leaf) is what
  makes a deeper caller go hot and promote its own call site too — **chain
  elevation**. Crossing a threshold marks a method *hot*; the walk happens only
  on the slow path, where it is cheap next to the block it precedes.

- **The call site starts blocking and promotes when hot.** Each per-receiver
  `Site` in `Elevation` begins on the **blocking tier**: it simply invokes the
  real `g` (whose own awaits block), so the state-machine cost is deferred. On
  each call it consults `Profiler.isHot`; once the profiler has seen `g` block
  enough, the `Site` promotes to the suspending transform on its next call. The two
  tiers are result-equivalent — promoting only changes whether a not-yet-complete
  await parks the carrier or releases it.

- **Suspension goes as deep as the chain.** A state machine materialized at
  runtime is built with `transformMethodElevated`, which elevates the method's
  *own* virtually dispatched suspendable calls to per-receiver call sites too. So
  when `mid`'s site promotes, the `mid` state machine doesn't block on `leaf` — it
  suspends through a fresh `leaf` call site, which promotes when `leaf` is hot. Each
  level resolves and transforms the next on demand; the chain comes off the
  thread one site at a time as the profiler marks each level hot.

This is the userland-Loom tiering of §7.7, scoped to the call site rather than a
global `MutableCallSite`. Because the profiler counts the actual blocking chain
and `Elevation` resolves per actual receiver, the two compose: the frames that
really block are the frames that get state machines. No on-stack replacement — a
call already parked stays parked; only subsequent calls suspend (stated, as in
JIT tiering). Verified by `DynamicElevationTest` (one level: `N` blocking calls
sample `leaf` hot, then the next call suspends instead of parking) and
`ChainElevationTest` (three levels `top → mid → leaf`: the profiler makes both
`mid` and `leaf` hot, both sites promote on the next call, and it suspends through
the whole chain to the same answer).

What remains of §7.7's full vision: **de-elevation** when a path cools (the
`Site` promotion is one-way today); applying the same blocking-start promotion to the
**statically bound** (`g$async`) path, which would need an indy there too (it is
still eager); and, for the agent, `Can-Retransform-Classes` to rewrite
*already-loaded* caller bodies rather than relying on the caller having been
elevated at load. None of these change the mechanism — only its reach.

## 8. Prototype plan

Plain Java + upstream ASM (`asm-tree`/`asm-analysis`/`asm-util`); no scalac
involvement until the final phase. The repo's shaded `scala.tools.asm` is
deliberately not used, to keep iteration friction low.

- **Phase 0 — fix the ABI by hand.** `AsyncRT.await` (blocking default),
  `FutureStateMachine` base (mirrors `AsyncStateMachine`:
  `state`/`onComplete`/`getCompleted`/`tryGet`/`completeSuccess`/`completeFailure`),
  the two-array frame. Hand-write one sample twice: the source shape (sync,
  calls `await`) and the expected state-machine output. Nails the calling
  convention, the already-completed fast path, and exception semantics before
  any ASM is written. ✅ scaffolded
- **Phase 1 — the transformer.** Standalone: read the source-shape `.class`,
  find `INVOKESTATIC AsyncRT.await` sites, run `Analyzer` for frame types,
  emit the spill/restore/switch rewrite into a generated SM class; add a
  sibling `m$async` entry point to the original class. Every output goes
  through `CheckClassAdapter`. ✅ scaffolded (static methods, no
  uninitialized-`new` handling yet)
- **Phase 2 — semantic test matrix.** Each case runs blocking and transformed,
  asserting identical results, with both already-completed futures (fast
  path) and futures completed later from another thread (suspension path):
  sequential awaits; deep operand stack `1 + (2 * await(f)) + g(await(h), 3)`;
  await in a loop; try/catch/finally around and containing awaits; long/double
  locals and operands; exception thrown by the future; `new Foo(await(f))`
  (the milestone test); `synchronized` rejection. ✅ green, including
  NEW-sinking (simple/nested/two-arg/statement/conditional shapes)
- **Phase 3 — debuggability.** Emit the state → names metadata; restore into
  original slots + LVT extension; verify with a debugger; write the
  suspended-frame pretty-printer. ✅ mostly done: per-state `$asyncDebug`
  metadata with source names; original `LocalVariableTable` and line numbers
  carried into the generated `apply` (restores target the original slots, so
  entries stay truthful); `AsyncDebug.describe` renders e.g.
  `Samples.sumTwice(...) suspended at state 2 (line 23): fa = ..., x = 5`.
  Verified over JDWP with jdb: a line breakpoint in `Samples.java` defers,
  resolves into the generated `Samples$async$sumTwice$N.apply` at class load,
  hits after a resume, and `locals` shows `x = 5` under its source name.
  `async3.demo.Demo` is a single-threaded debugger walkthrough for IntelliJ
  (see the README's "Debugging in IntelliJ").
- **Phase 3.5 — lambda front end.** ✅ see §7.5: cracking + hidden-nestmate
  definition + constructor caching, and the shadow-named debuggable mode with
  JDI-verified breakpoint binding inside lambda bodies. Plus
  `Async.lift(C::m)` — the runtime method-pair via method references.
- **Phase 3.75 — Java agent.** ✅ see §7.6: load-time in-place shape
  (`m$asyncBody` sibling + `m$async` entry + shared `DelegatingStateMachine`
  shell), preferred automatically by `async`/`lift`, JDI-verified debugging
  with no shadow naming. Retires the shadow/nestmate workarounds wherever the
  agent is present and stands up the member layout the lazy tier promotion needs. Includes
  `Async.lift(C::m)` — runtime derivation of the suspending variant of an
  existing method (the runtime method-pair), covering static/unbound/bound/
  private references.
- **Phase 4 — lazy variant + numbers.** `defineHiddenClass` +
  `MutableCallSite` promotion; JMH: blocking vs. AoT-transformed vs. lazily promoted
  vs. the current compiler phase's output; generic frame vs. typed fields.
- **Phase 4.5 — elevate the blocking tier (§7.7).** ✅ *static in-class slice*:
  `transformInPlace` computes the same-class suspendability closure (a method is
  suspendable if it directly awaits or transitively calls one that does, monotone
  fixpoint, cycle-safe) and elevates each suspendable method — `invoke g`
  rewritten to `await(g$async(...))` with the result coerced back to `g`'s return
  type, so a method with no source `await` still becomes a state machine.
  Statically bound calls rewrite directly to `g$async`; virtual/interface calls
  rewrite to a per-receiver `invokedynamic` call site (§7.8, Strategy B) that is
  sound under overriding with no callee-hierarchy scaffolding. The blocking
  original is untouched, re-entry stays idempotent, and unsupported callers
  (synchronized/monitor/ctor) are quietly left blocking rather than aborting the
  class. Tested in `ElevateTest` (primitive/object/void coercions, multi-level,
  mixed direct+indirect, fast path vs. real suspension) and `ElevateDispatchTest`
  (interface-default and overridable-virtual calls elevate and agree with the
  blocking tier, per actual receiver). ✅ *profile-driven tier promotion* (§7.9): the
  blocking `AsyncRT.await` profiles itself with a `StackWalker` sample
  (`async3.runtime.Profiler`); the Strategy-B call site starts blocking and promotes
  to the suspending transform once the awaiting method is sampled hot, verified
  by `DynamicElevationTest`, and *chain elevation* (§7.9): the profiler counts the
  whole contiguous blocking chain so deeper callers promote too, and runtime state
  machines are built with `transformMethodElevated` so suspension recurses through
  per-receiver call sites — `ChainElevationTest` suspends a three-level
  `top → mid → leaf` chain end to end.
  *Remaining:* de-elevation when a path cools (the promotion is one-way today); the
  same blocking-start promotion on the statically bound `g$async` path (needs an indy
  there too); cross-class static closure; and on the agent,
  `Can-Retransform-Classes` for caller-body rewrites.
- **Phase 5 — integration sketch only.** Post-`jvm` hook in `GenBCode` (where
  shaded ASM lives) vs. shipping as a build/agent step; shrink
  `markForAsyncTransform` to "keep the await call + annotate".

**Risk order:** uninitialized-object sinking ✅, stack-map correctness around
try regions ✅ (JVM verifier accepts all generated classes, exercised on every
test load), generic-frame spill cost vs. Kotlin-style typed fields — phase 4
answers this with data.

## 9. Frame stores (where captured state lives)

A third axis, orthogonal to the transform and to deployment: *where a value that
crosses a suspension is kept, and how the body reaches it*. The transform is
parameterized over a **frame store**; one transform, three stores, chosen by
policy. Only the generated bytecode at and around suspension points differs —
the analysis (frame types, liveness, NEW-sinking) and everything else is shared.

Two sub-questions hide here, and they are more coupled than they first look:

- **Location** — the generic two-array frame (`refs[]`/`prims[]`) vs. typed
  fields on the state-machine class.
- **Access** — *spill/restore* (the variable lives in a JVM local during
  execution and is copied to backing storage only across a suspension) vs.
  *live* (the backing storage is the variable's only home; the body reads and
  writes it in place, the way scala-async 2.0's `UseFields` and Kotlin's
  continuations work).

The live model is the standard coroutine shape: a value that crosses a
suspension is promoted to its storage home and accessed there directly, so the
suspension point spills **nothing for locals** — only the operand stack, which
is never addressable, is ever spilled. async3's current store is the unusual
one: it keeps locals in their *original* slots and copies to the arrays across a
suspension, purely so the original instructions and `LocalVariableTable` survive
and a debugger sees real named locals in the host class.

### The three stores

| store | var home | access | local spill at suspend | per-access cost | debug | needs a generated class? |
|---|---|---|---|---|---|---|
| **`array-spill`** (today, default) | JVM slot | native slot | yes — to `prims[]`/`refs[]`, ~6 instr × live slots, at spill and at restore | free (slot) | best — LVT intact, real named locals | no (runs on the shared shell) |
| **`array-live`** | `prims[]`/`refs[]` slot | `Frames` helper | **none** (operand stack only) | one static call ≈ array load + convert | weak — locals aren't in slots | no |
| **`typed-fields`** | a named typed field | `getfield`/`putfield` | **none** (operand stack only) | one typed field op | good — named fields, debugger reads them | **yes** |

`array-spill` is the balanced, most-debuggable default; `array-live` makes
suspension points tiny with no class generation (works on the agent's shared
`DelegatingStateMachine` shell); `typed-fields` is the `UseFields` shape — tiny
suspensions *and* the cheapest typed access (no bounds check, no
long-normalization, no boxing), at the cost of a per-method class and named-field
debug.

### The one hard constraint (store ⋈ deployment)

`typed-fields` needs a generated class to hang fields on. The load-time agent's
shared shell has no per-method fields — that is the "no class per method" win —
so under the in-place agent a request for `typed-fields` **downgrades to
`array-spill`** (logged). `typed-fields` is honored only where the deployment
already generates a class: AoT class-per-method, `lift`/`async` hidden class, and
`Elevation`'s runtime transform. The two array stores work everywhere.

### Abstraction: a transform-time `FrameStore`, never a runtime frame object

The store is a **transform-time** strategy that emits bytecode; it is *not* a
runtime polymorphic `Frame` interface. A virtual `frame.getInt(i)` per access
would defeat `array-live`'s whole purpose. So the generated bytecode is
monomorphic for the chosen store, and the only shared runtime code is a set of
**static, inlinable** helpers (`async3.runtime.Frames`) that bundle array access
with primitive conversion:

```
int igetP(long[] p,int i) / void isetP(long[] p,int i,int v)     // covers boolean/byte/char/short/int
long lgetP / lsetP   float fgetP / fsetP   double dgetP / dsetP   // hide the float/double bit casts
Object getR(Object[] r,int i) / void setR(Object[] r,int i,Object v)
```

Each access collapses to one `INVOKESTATIC`; the JIT inlines it to a
bounds-checked array op. These serve `array-live`'s hot path *and* shrink
`array-spill`'s float/double/long spill code (the inline `floatToRawIntBits; i2l;
lastore` becomes one `fsetP`). `typed-fields` uses **no** helper — direct typed
`getfield`/`putfield`, exactly where a shim would hurt. So: shims on the array
hot path and all cold paths (entry capture, stack spill, debug), bare bytecode
where typed fields make a shim pointless.

### Policy

- **Now:** `-Dasync3.frame=array-spill|array-live|typed-fields` (default
  `array-spill`), read at transform time so it shapes codegen; honored by every
  transform entry, with `transformInPlace` applying the downgrade above.
- **Later (profile-driven):** the dynamic tier-promotion already re-transforms
  hot methods at runtime (`Elevation.buildSuspending`). Extend `Profiler` to
  record per-method live-set size and suspension frequency, and let the runtime
  transform pick a store per method (large live-set + hot → `typed-fields`;
  await-heavy with many locals → `array-live`; else the property default). The
  property sets the floor; the profile overrides per method — no new mechanism,
  it rides the promotion path that already exists.

### Cross-cutting

- The operand stack is spilled at every suspension in all three stores.
- Liveness nulling (the `fieldsToNullOut` analogue) applies to all: null dead
  ref slots / ref fields at suspend.
- NEW-sinking and two-slot prims are store-independent (they run first).
- Debug: `array-spill` keeps `$asyncDebug` + LVT; `typed-fields` maps
  state→field name and the debugger reads fields directly; `array-live` has no
  source slots — the throughput store, not the debugging one.

A later refinement, not the baseline: Kotlin's emitted bytecode caches a field
in a JVM local inside hot regions and writes the field only around suspensions —
a register-allocation win for tight loops. That is a hybrid on top of the live
model (a bounded local spill for hot locals); decide it with JMH.

### Phases

1. ✅ **`Frames` helpers** — static accessors; the existing conversions route
   through them (smaller bytecode, identical semantics). Setters are
   *value-first* so a live store can write a stack value with no scratch shuffle.
2. ✅ **`array-live`** + the `-Dasync3.frame` policy (`AsyncTransformer.FrameMode`):
   body local accesses become `Frames` calls, the suspension spills only the
   operand stack, and no `LocalVariableTable` is emitted (no source slots). Held
   to the matrix under the property by `ArrayLiveStoreTest`; `sumTwice$asyncBody`
   shrinks 137→107 instructions vs `array-spill`. (First landed as a mode branch;
   folded into the `FrameStore` strategy in phase 4.)
3. ✅ **`typed-fields`** — `FieldPlan` allocates one field per (frame slot, kind)
   the method touches; `generateStateMachine` declares them on the SM class and
   the body reads/writes them in place (`getfield`/`putfield`, value stashed via
   the scratch local for store order). Honored on the class-per-method and
   hidden-class paths; the agent's shared shell passes a null plan and so
   downgrades to an array store. Held to the matrix by `TypedFieldsStoreTest`.
   *Simplifications in this cut:* primitives get precisely-typed fields (no
   long-normalization), but references use `Object` fields with a checkcast on
   load (like `array-live`) rather than precise per-type fields; field names are
   synthetic (`s3I`, `s5R`), not yet sourced from the LVT — so the debugger sees
   typed fields but not source names. Both are refinements, not blockers.
4. ✅ **`FrameStore` extraction.** The three stores are now one strategy
   (`AsyncTransformer.FrameStore`): `ArrayStore` (spill or live, on the two
   arrays) and `FieldStore` (live, on typed fields). The codegen is
   store-agnostic — `applyMethod`/`awaitSite`/the constructor ask the store to
   `load`/`store`/`nullRef` a frame slot and to `captureFromLocal` an entry
   param, and `rewritesBody()` selects the live vs. spill body shape. The
   array-vs-field and live-vs-spill branches that were scattered through the
   codegen collapsed into the store. The chosen `FrameMode` now flows as a
   *parameter* from the public entry points (default `-Dasync3.frame`), not a
   property re-read deep in the transform — so a per-method choice is just a
   different argument.
5. ✅ **Profile seam** (the integration point; policy still trivial).
   `transformMethodElevated(…, String frameStore)` takes an explicit store, and
   `Elevation.buildSuspending` passes `Profiler.preferredStore(methodKey)` (null
   today → the global default) when it materializes a hot method. Teaching the
   profiler to choose — keying on observed live-set size / suspension frequency
   — is now a change to one method, plus the JMH numbers to choose by.

The verification spine: the existing blocking ≡ transformed semantic matrix,
run once per store via the system property, holds every store to identical
observable behavior.
