# Clue Helper

## Branches

- Work on `dev`. `main` only receives `dev` through a pull request at release time; a GitHub branch rule
  blocks pushing to `main` directly.
- Pull `dev` before starting work. The daily bake workflow (`.github/workflows/bake.yml`) pushes a
  "Re-bake data" commit to `dev` on days the baked files change.

## Releasing

- Just before tagging a release, run `./gradlew bakeData` and then `./gradlew test`, and commit any
  change. The daily bake can be up to a day behind the RuneLite release the plugin is built against.
