# Plan for Fabric 1.21.1

This compatibility port is based on upstream `plan-player-analytics/Plan`
commit `c047e36fd5fea6b704c0871ab54d20b03da03868` (5.9 build 3705), the
default-branch HEAD when the work began. It retains the latest shared Plan
source and dashboard rather than reverting to an older Plan release.

## Installation

1. Use Minecraft 1.21.1 with Java 21 or newer and Fabric Loader 0.16.10 or newer.
2. Install Fabric API for Minecraft 1.21.1 (tested with 0.116.7+1.21.1).
3. Place `PlanFabric-5.9-build-3705.jar` in the dedicated server's `mods` folder.
4. Restart the server. Plan stores its configuration in `config/plan/`.
5. Use `/plan info` and `/plan server`. Plan's default web port is 8804.

Fabric Permissions API 0.3.1 is optional. Without a permissions provider,
Plan uses Minecraft's operator permission level and its existing self-service
permission defaults. Clients do not need this server-only mod.

The packaged configuration and messages remain generic. Test-server changes
are not included in the delivered JAR.

## Building

The current upstream build tooling requires JDK 25 to run Gradle. The Fabric
code is compiled for Java 21; shared modules retain their lower upstream
bytecode targets. The resulting mod runs on Java 21.

From the repository's `Plan` directory, with `JAVA_HOME` pointing to JDK 25:

```text
./gradlew :fabric:build -PisRelease
```

The installable, remapped JAR is written to `Plan/builds/` with the original
artifact naming convention. Development JARs remain under `fabric/build/`.
`planBuild=3705` pins the upstream build number for reproducible compatibility
builds without increasing Plan's version when local commits are added.

## Compatibility changes

- Restored Mojang mappings and intermediary remapping for obfuscated Minecraft
  1.21.1, including Mixin injection targets.
- Adapted permissions, player profiles, world-change and combat events,
  dimension identifiers, server properties, and chat click/hover components.
- Used the actual fatal damage source for combat attribution. Minecraft 1.21.1
  updates its cached last-damage source after the death callback; reading that
  cache can assign a player kill to an earlier mob hit. Custom item names are
  retained in recorded weapons, and deaths are counted once.
- Kept player analytics, SQLite storage, the current dashboard, and shared
  extensions from the latest source.
- Made configuration reads non-writing and default persistence conditional,
  validated, atomic where supported, and guarded against concurrent edits.
- Preserved explicit nulls, existing values, unknown fields, comments, selected
  lists, and removed example records. Existing legacy migrations retain missing
  operator fields when both old and current sections are present; current keys
  retain precedence.
- Updated existing locale files on startup and supported reloads without
  overwriting customized messages or creating removed example locale files.
- Allowed dashboard button labels to wrap instead of truncating them.

## Verification

Regression tests cover missing nested defaults, nulls, unknown keys, comments,
list preservation, removed example records, legacy aliases, repeat loads,
malformed input, concurrent edits, and failed writes retaining decoded settings.
Legacy migration tests and existing locale/configuration tests are also run.

A local dedicated-server check uses the packaged JAR on Minecraft 1.21.1 and
Java 21, checks the web server and SQLite database, and connects a protocol
client to exercise login, movement, commands, dimension and game-mode changes,
death/respawn, and logout. This is a server/protocol verification, not a claim
that a graphical Minecraft client was inspected.

Upstream licensing and copyright notices remain in the source.

Verified on October 13, 2026: 4,104 tests across the available platform suites,
with zero failures and 236 environment-dependent skips. This includes 4,026
shared tests and three Fabric tests: 3,868 passed and 236 skipped.
Live protocol checks passed on Fabric Loader 0.16.10 and 0.19.5, including the
optional Fabric Permissions API 0.3.1. The final JAR also passed a complete class
bytecode audit for Java 21 compatibility. No graphical Minecraft client was
used for these checks.
