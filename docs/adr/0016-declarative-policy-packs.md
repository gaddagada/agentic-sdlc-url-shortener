# ADR 0016: Declarative policy packs next to the built-in rules

Status: Accepted (2026-10-30). Extends the policy model of
[ADR 0005](0005-risk-based-approvals-and-autonomy-levels.md).

## Context

The eleven built-in rules are Java classes. An organization adopting the platform has rules of its own (applied
migrations are immutable, security code needs a security reviewer, personal data needs the privacy officer) and
must not have to fork the engine to add them, nor to turn off a built-in rule that does not fit (for example the
change-size threshold). At the same time, two built-in rules carry the governance guarantees the documentation
makes (the release always needs a human; credentials never cross a stage boundary), and a configuration file must
not be able to remove them.

## Decision

- **Policy as code, a closed condition set.** A `PolicyPack` (name, version, rules, disabled built-in ids) is JSON;
  the control plane also reads YAML by converting it to JSON first. A rule has an id (`[A-Z]{2,5}-\d{3}`, never a
  built-in id), a phase (`RUN_START`, `ENTRY`, `EXIT`), optional stages, a condition, an effect (`DENY`,
  `REQUIRE_APPROVAL`, `WARN`), a severity and a message. Conditions are a sealed set: `intentMatches`,
  `pathMatches`, `payloadMatches` (exit gates only), `riskAtLeast`, `changeSizeOver`, `releaseDecisionIs`,
  `autonomyIs`, combined with `allOf`, `anyOf` and `not` (at most 16 levels). No scripting language.
- **Strict loading.** `PolicyPackLoader` reports every problem at once with its JSON path: malformed JSON or
  duplicate keys, unknown fields and enum values, missing fields, invalid regexes or globs, duplicate or built-in
  ids, rules that cannot apply as written. The control plane loads `sdlc.policy.pack` (a file or
  `classpath:` resource) at startup and refuses to start with an invalid pack.
- **Protected built-ins.** CHG-002 (release approval), GOV-003 (autonomy floor) and SEC-002 (secret leak) cannot be
  disabled, by a pack or in code.
- **Evaluation and audit.** Pack rules run after the built-in rules in the same `PolicyEngine`, with the same
  combination (maximum verdict, no short-circuit, a throwing rule fails closed as DENY). The pack's name, version
  and SHA-256 over its strict canonical JSON are recorded in every `RUN_CREATED` and `POLICY_DENIED` event;
  `GET /api/v1/policy` and a console chip show the policy in force.
- **ReDoS guard.** Policy regexes run under the run lock against model-written text. Regexes longer than 1000
  characters or with a nested unbounded quantifier (`(a+)+`) are refused at load time, and every search has a 1 s
  budget; a search that exceeds it fails closed as a DENY for that rule.

## Consequences

- An organization adds or relaxes rules by configuration, and the audit trail proves which pack decided a run
  (`PolicyPackEngineTest`, `PolicyPackLoaderTest`, `RegexGuardTest`, `PolicyPackFilesTest`). The bundled sample
  (`policy/sample-policy-pack.json` in the core, `policy/example-policy-pack.yaml` in the control plane) is tested.
- Conditions are what the closed set can express: regexes over text and canonical JSON, globs over paths, levels
  and sizes. A rule that needs real program analysis still needs Java (a `PolicyRule`).
- A time-budget DENY safe-stops the run (`POLICY_DENIED`); the pack author fixes the regex and the run is re-planned.
- Packs are read at startup; changing the policy needs a restart (every node of a cluster should load the same
  pack; the hash in the audit trail shows when they did not).

## Alternatives considered

- **A general policy engine (Rego/OPA, CEL).** More expressive and widely known, but a second language, a runtime
  dependency in the core and harder-to-bound evaluation; the closed set covers the rules seen so far.
- **Scripting (JavaScript, Groovy).** Arbitrary code under the run lock; rejected for the same reasons as unbounded
  regexes.
- **Packs that may disable anything.** Simpler, but it would let a configuration file remove the guarantees the
  platform is built on.
