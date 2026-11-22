# Clue Helper

## Code

- Instance fields in `src/main/java` are private by default through `lombok.config`: leave out `private` on them, and
  keep it on static fields, which the setting does not cover. A field another class reads takes `@PackagePrivate`, or
  `@FieldDefaults(level = AccessLevel.PACKAGE)` on its class. Tests keep explicit modifiers (`src/test/lombok.config`).

## Branches

- Work on `dev`. `main` only receives `dev` through a pull request at release time; a GitHub branch rule
  blocks pushing to `main` directly.
- Pull `dev` before starting work. The daily bake workflow (`.github/workflows/bake.yml`) never pushes to `dev`: on
  days the generated or baked files change it force-pushes them to the `bake` branch and opens or refreshes one
  "Regenerate the bundled data" pull request into `dev`. It does so even when the tests fail (the run ends red and the
  pull request says so): replay the changed campaign steps before merging it.
- The same workflow opens an issue labelled `missing-teleport` for every teleport on the wiki's Transportation page
  that the catalog has no row for, and closes it once the catalog has one. The row is added in Clue-Helper-Tools, from
  the draft in the issue. An issue closed as not planned is never raised again.

## Releasing

- Just before tagging a release, run `./gradlew bakeData` and then `./gradlew test`, and commit any
  change. The daily bake can be up to a day behind the RuneLite release the plugin is built against.
