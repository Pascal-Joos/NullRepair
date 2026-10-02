# Separate NullRepair from NullAwayAnnotator

> Status: planned, not started. Pick up later. (Mirror of the plan-mode file.)

## Context

NullRepair is currently a **hard fork** of `ucr-riple/NullAwayAnnotator`: it copied the entire
`annotator-core` and edited core classes in place, so the "NullRepair approach" (LLM-based null-fix)
and the underlying annotator engine are tangled in one codebase. The upstream upgrade to annotator
1.3.20 / stock NullAway 0.14.2 (errors.xml v4) is landed on `main` and validated end-to-end on all
12 benchmarks. Now we want a clean boundary.

**Goal / end-state (decided):** NullRepair becomes its **own repo** that *depends on* a thin, generic
**"annotator platform"** — a seam-only fork of NullAwayAnnotator the team publishes as artifacts
(`edu.ucr.cs.riple.annotator:*:1.3.20-SNAPSHOT`), containing **zero NullRepair/LLM code**. NullRepair
contains **zero annotator-core source**. Seams are written to be upstreamable, but PRs to ucr-riple
are **deferred** (self-publish the platform now; upstream later, optionally).

**Why feasible:** the key hook `Checker.resolveRemainingErrors()` already exists as a clean interface
seam. The coupling that forces the fork is a small, well-localized set of in-place edits to generic
classes (base `Error`, `Config`, `Annotator`, `Utility`, `Context`) plus a hardcoded checker `switch`.
Everything genuinely NullRepair-specific (the `codefix/` package, prompts, git/driver orchestration)
is additive and cleanly liftable.

## Target architecture (two repos)

**Repo 1 — `annotator-platform`** (fork of ucr-riple/NullAwayAnnotator; generic, no NullRepair code).
Modules `injector`, `annotator-scanner`, `annotator-util`, `annotation-util`, `library-model-loader`,
`annotator-core`. Publishes library artifacts. Carries only **generic, upstreamable seams**:
- A `CheckerFactory` **ServiceLoader SPI** replacing the hardcoded `switch` in
  `CheckerBaseClass.getCheckerByName`; ships a built-in `NullAwayCheckerFactory` (priority 0) so the
  platform runs standalone.
- `Checker.resolveRemainingErrors()` kept (default no-op in platform).
- Additive `Utility.buildTarget(Context, boolean captureOutput)` overload (keep the void one).
- Base `Error` reverted to upstream (`int offset`). `NullAwayError` keeps the generic error enrichment
  (`DiagnosticPosition` position/path, `ErrorType`, `NullableExpressionInfo`, `infos` parsed from
  NullAway's own errors.xml) and feeds `position.offset` to `super(offset)` — generic NullAway-output
  parsing, not NullRepair logic, so it stays platform-side.
- `Config` keeps a generic suppress/resolve flag; `Context` reverts to upstream fields.
- `annotator-core` publishes a **plain library jar with a real POM** (drop the stripped-POM /
  shadow-only publication for library consumption). Gradle Module Metadata stays **disabled** (so
  Java 8/11 benchmarks resolve via POM — the constraint from the upgrade).

**Repo 2 — `nullrepair`** (the current repo keeps its identity/history; the `annotator-*` modules are
removed and consumed as artifacts). Depends on the platform artifacts. Contains:
- `NullAwayRepairChecker extends NullAway` — implements `resolveRemainingErrors()` with the ~700 lines
  of codefix orchestration currently inlined in `NullAway.java`; owns `responsePromptCache`. Registered
  via `META-INF/services/...CheckerFactory` → `NullRepairCheckerFactory` (priority 100, overrides the
  built-in for name `NULLAWAY`).
- The `codefix/` package verbatim (`NullAwayCodeFix`, `Basic/Advanced/AgentBaselineNullAwayCodeFix`,
  `ChatGPT`, `ChatGPTTokenUsage`, `Response`, `ResponseCache`) + the 15 prompt resources.
- `util/GitUtility`, `util/ASTParser`, `registries/method/invocation/*`, `CodeFixTest`.
- `NullRepairConfig` (runner-only fields: `ResolveRemainingErrorMode`, testCommand, annotatedPackages,
  selectedErrorIds, pushCommits, benchmarkPath/Name, modelName, agentCostLimit, agentCycleLimit,
  branchName(), log/metrics paths) built on top of the platform `Config.Builder`.
- `NullRepairRunner` replacing `Main` (parameterized `ROOT_PATH`; same CLI + benchmark registry +
  xvfb/android/JAVA_HOME wrapping + git branch flow; emits `"Finished annotating"`). Its shadowJar is
  the CLI artifact the eval scripts run.
- All eval assets: `evaluation_scripts/`, `evaluation_data/`, `*.py`, `pyproject.toml`,
  `checkout_benchmarks.sh`, `utility_scripts/`, the `mini-swe-agent-for-nullaway-codefix` submodule,
  `.devcontainer/`, `benchmarks/`, `README.md`.

### CheckerFactory SPI sketch (platform)
```java
public interface CheckerFactory {            // generic
  boolean supports(String name);
  Checker<?> create(Context context);
  int priority();                            // higher wins; lets nullrepair override NULLAWAY
}
// CheckerBaseClass.getCheckerByName: ServiceLoader.load(CheckerFactory.class), filter supports(),
// max by priority, else built-in NullAwayCheckerFactory.
```

## Execution — incremental, eval green after every step

Validation (after each step): build the runnable jar, then `java -jar <jar> gson disabled` and
`java -jar <jar> eureka --mode disabled --selectedErrorIds 2 --depth 1`. Success = exit 0, `errors.xml`
under the benchmark's `annotator-out/0`, and `"Finished annotating"` on stdout (what `smoke_test.py`
asserts; disabled mode, no API key).

### Phase A — decouple in place (current repo, single module); pure refactors
1. **A1** Make `Utility.buildTarget` an additive overload; revert platform callers to the void form.
2. **A2** Add `CheckerFactory` SPI + `META-INF/services` + built-in `NullAwayCheckerFactory`; replace
   the `switch` (returns today's NullAway). Validate.
3. **A3** Move `reportCache` back to `Annotator.cache`; relocate `responsePromptCache` to the checker;
   strip `Context` to its upstream field set.
4. **A4** Revert base `Error` to `offset`; keep `path/position/infos` on `NullAwayError` (super gets
   `position.offset`); `DiagnosticPosition` stays platform-side. (Refs to `Error.position/path` exist
   only in `Error.java` + the nullaway package — localized.)
5. **A5** Restore a generic `Config` suppress/resolve flag; pull NullRepair runner fields into a
   `NullRepairConfig` (temporarily colocated). Remove `Config`'s dependency on `Main.VERSION`.
6. **A6** Extract `NullAwayRepairChecker` from `NullAway`: move `resolveRemainingErrors()` + the
   codefix-calling methods out; register via `NullRepairCheckerFactory` (priority > built-in).
   **Delicate step:** audit every `private` NullAway/collaborator member the moved code touches and
   widen to `protected` (or add a narrow accessor) — see Risks.
7. **A7** Replace `Main` with `NullRepairRunner` (parameterized `ROOT_PATH`); set
   `application{ mainClass }`. Jar name unchanged, so eval scripts still work. Validate.

### Phase B — in-repo module split `:nullrepair` depending on `:annotator-core`
- Add `include 'nullrepair'`; `nullrepair/build.gradle` with `implementation project(':annotator-core')`
  + `com.gradleup.shadow` + `application{ mainClass = NullRepairRunner }` (project dependency → no
  publishing/POM work yet).
- Physically move the isolated classes/resources/tests/SPI-service-file + eval assets into `:nullrepair`.
- Point eval scripts at `nullrepair/build/libs/nullrepair-*.jar` (one-line glob change in
  `run_nullrepair_and_baselines.py` and `smoke_test.py`).
- Confirm `./gradlew :annotator-core:build` passes with **no** NullRepair sources present. Validate eval.

### Phase C — physical two-repo separation + publish
- Make platform `annotator-core` publish a real-POM library jar; `nullrepair` becomes the shadow/CLI
  artifact. Keep Module Metadata disabled.
- Create the `annotator-platform` repo (fork of ucr-riple/NullAwayAnnotator) carrying the generic seam
  commits from Phase A; publish `edu.ucr.cs.riple.annotator:*:1.3.20-SNAPSHOT` to Maven Local / the
  chosen repo.
- In the current repo (now `nullrepair`): delete the `annotator-*`/`injector`/`scanner`/
  `library-model-loader` modules; depend on the published platform artifacts; keep `:nullrepair` + eval
  + benchmarks + submodule + devcontainer.
- Validate the full eval end-to-end from the separated repo (gson/eureka disabled run, plus a Java
  8/11 benchmark to confirm transitive-dep resolution against the real POM).
- Upstreaming PRs to ucr-riple: **deferred** (seams already written generically for later).

## Conventions
- Keep code comments concise — short, only where intent isn't obvious; no multi-line explanations of
  what the code plainly does.

## Critical files
- `annotator-core/src/main/java/edu/ucr/cs/riple/core/checkers/CheckerBaseClass.java` — SPI replaces the `switch`.
- `.../checkers/Checker.java` — `resolveRemainingErrors()` seam (keep; default no-op platform-side).
- `.../checkers/nullaway/NullAway.java` — split: generic deserialize/suppress/buildInfos stays; resolve+codefix moves out.
- `.../checkers/nullaway/NullAwayError.java` + `.../checkers/DiagnosticPosition.java` — error enrichment stays platform-side; base `Error` reverts.
- `.../registries/index/Error.java` — revert to upstream `offset`.
- `.../Config.java`, `.../Annotator.java`, `.../Context.java`, `.../util/Utility.java` — revert in-place edits to generic shape.
- `.../Main.java` → new `NullRepairRunner` (nullrepair); `.../checkers/nullaway/codefix/**` + `resources/.../prompts/**` → nullrepair.
- `settings.gradle`, `annotator-core/build.gradle` (publishing), `run_nullrepair_and_baselines.py`, `smoke_test.py`.

## Risks
- **NullAway internals visibility (highest):** moving codefix into a cross-module subclass forces
  `private`→`protected` widenings; some logic may touch package-private `evaluators`/`registries`
  collaborators not reachable from a subclass in another module — may need narrow accessors or small
  generic platform additions. Resolve during A6 with a member-by-member audit.
- **Suppress/resolve dispatch parity:** collapsing the `ResolveRemainingErrorMode` dispatch into a
  generic flag + checker override must keep **disabled-mode behavior identical** (the validation path).
  Other modes may shift results (acceptable).
- **Publishing / transitive deps (Phase C):** the real-POM library publication must carry
  guava/gson/javaparser/jgit/sqlite/slf4j/commons-text + the sibling modules; validate a Java 8/11
  benchmark resolves against it with Module Metadata disabled.
- **`responsePromptCache` lifecycle:** must remain a single instance across the run when moved to the
  checker, or caching/cost behavior changes.
- **Environment coupling:** `ROOT_PATH` gets parameterized; JAVA_HOME/android/xvfb assumptions and the
  `nimak/auto-code-fix` benchmark-branch flow travel with the nullrepair repo. Benchmark branches pin
  platform artifact versions — keep them in lockstep with the published platform.
