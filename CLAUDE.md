# Clue Helper

## Branches

- Work on `dev`. `main` only receives `dev` through a pull request at release time; a GitHub branch rule
  blocks pushing to `main` directly.
- Pull `dev` before starting work. The daily bake workflow (`.github/workflows/bake.yml`) pushes a
  "Regenerate the bundled data" commit to `dev` on days the generated or baked files change. It commits even
  when the tests then fail (the run ends red): replay the changed campaign steps before releasing.
- The same workflow opens an issue labelled `missing-teleport` for every teleport on the wiki's Transportation page
  that the catalog has no row for, and closes it once the catalog has one. The row is added in Clue-Helper-Tools, from
  the draft in the issue. An issue closed as not planned is never raised again.

## Releasing

- Just before tagging a release, run `./gradlew bakeData` and then `./gradlew test`, and commit any
  change. The daily bake can be up to a day behind the RuneLite release the plugin is built against.
