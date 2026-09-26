# Thievery

> Locks, theft, and evidence for TF-Minecraft roleplay.

Thievery gives criminal activity and property protection a shared set of game mechanics. Players can secure possessions with locks and keys, attempt to pick locks, steal from eligible targets, and leave clues behind. Character traits, item value, risk, and cooldowns shape what a thief can attempt and take.

## Features

- **Property locks** — secure supported doors, containers, furniture displays, armour stands, and item frames, with personal, guild, and faction lock rules.
- **Keys and keychains** — carry several keys together and create key copies through molds or paper copies.
- **Lockpicking challenges** — use lockpicks in an interactive timing challenge, with tool strength, lock strength, and Dexterity influencing the attempt.
- **Pickpocketing and robbery** — separate activities provide different targeting, alert, cooldown, and loot-budget rules.
- **Controlled looting** — theft menus account for item categories and value, including equipment quality and magical properties, when determining what can be taken.
- **Evidence and risk** — theft and lockpicking can leave clues, with accumulated risk affecting the chance of more revealing evidence.

Protection and intrusion both have a place in the system: locks govern access, while theft sessions govern the goods taken after an opportunity opens. Grave and display interactions extend the same mechanics to other parts of TF-Minecraft's world.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/Thievery/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

With Java 21 and the pinned plugin dependencies installed (see the build workflow), run:

```sh
mvn -B --no-transfer-progress clean verify
```

JUnit, MockBukkit and Mockito exercise configuration, persistence, inventory transfers,
ownership rules, commands, plugin lifecycle and theft sessions. Fixtures use temporary
directories; legacy relative persistence paths are isolated under `target/test-runtime`.
Tests mock external plugin APIs where a live server is required and assert the observable
result of each scenario. Optional RPCharacters API compatibility uses a test fixture
loaded separately to model servers with and without that API.

JaCoCo includes every production class. Open `target/site/jacoco/index.html` for the
HTML report, or use `target/site/jacoco/jacoco.xml` for tooling. CI uploads the coverage
report and Surefire test results. Use a clean full run for combined coverage; focused
test runs are for development and do not establish the suite's coverage.

`verify` requires 100% instruction, line, branch, method and class coverage,
without excluding production code. CI also rejects skipped tests.
Unused utility constructors, empty hooks and demonstrably unreachable alternatives
have been removed; guards for real configuration, metadata and integration failures
remain and have behavioral tests.

Coverage is a guide to missing behavior checks. Tests should verify a contract or a
regression, not invoke private constructors or invent impossible inventory states
to increase the percentage. MockBukkit's unimplemented operations can appear as skipped
tests; a successful coverage run must also have zero skipped tests.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
