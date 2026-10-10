# Thievery

> Locks, theft, and evidence for TF-Minecraft roleplay.

Thievery gives criminal activity and property protection a shared set of game mechanics. Players can secure possessions with locks and keys, attempt to pick locks, steal from eligible targets, and leave clues behind. Character traits, item value, risk, and cooldowns shape what a thief can attempt and take.

## Features

- **Property locks** — secure supported doors, containers, furniture displays, armour stands, and item frames, with personal, guild, and faction lock rules.
- **Keys and keychains** — carry several keys together and create key copies through molds or paper copies.
- **Lockpicking challenges** — use lockpicks in an interactive timing challenge on doors and displays, or, on chests, a floating lockpick ring followed by a seized-pin probe, with tool strength, lock strength, and Dexterity influencing the attempt.
- **Pickpocketing and robbery** — separate activities provide different targeting, alert, cooldown, and loot-budget rules. A pickpocket first fills a floating ring by mashing jump before the pocket opens.
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

JUnit, MockBukkit and Mockito exercise configuration, persistence, inventory
transfers, ownership rules, commands, plugin lifecycle and theft sessions.
External plugin APIs are mocked or represented by fixtures; live Paper gameplay
and integration checks remain separate.

Surefire writes test results to `target/surefire-reports/`; JaCoCo writes HTML
and XML coverage reports to `target/site/jacoco/`. Build CI uploads both.
`verify` requires 100% production instruction, line, branch, method and class
coverage, with no exclusions. CI also rejects skipped tests. Use a clean full
run for combined coverage; focused runs do not establish the suite's coverage.
See the [testing guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/Thievery/docs/testing.md)
for fixture isolation and local report checks.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
