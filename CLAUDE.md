# Kubernetes Tools (PyCharm plugin)

## Versioning

The single source of truth is `version` in `build.gradle.kts` (it is patched into plugin.xml and the zip name).

- **New significant feature** -> bump **major** (e.g. 1.4.0 -> 2.0.0), reset minor/patch to 0.
- **Improvement to an existing feature** -> bump **minor** (e.g. 1.0.0 -> 1.1.0), reset patch to 0.
- Bug fix only (not specified by the owner; assumed) -> bump patch.

Bump the version in the same change that adds the feature/improvement, and rebuild the zip
(`./gradlew buildPlugin` -> `build/distributions/k8s-pycharm-plugin-<version>.zip`).

## Publishing the zip

Every time the plugin zip is built (`./gradlew buildPlugin`), it is also copied to `dist/` by the build.
Then **commit `dist/<zip>` and push to `main`** in the same go (owner's standing instruction). Releases additionally
get a `v<version>` tag whose tree contains `dist/<zip>` plus the zip attached as a GitHub release asset.

## Build / run

- Needs JDK 21: use the JBR bundled with PyCharm (`<PyCharm>/jbr`) as `JAVA_HOME`.
- `gradle.properties` -> `ideDir` points at the local PyCharm used to compile against.
- `./gradlew test` runs the parsing unit tests; `./gradlew runIde -PopenProject=<path>` starts a sandbox IDE.
  Close the sandbox before `buildPlugin` (Windows locks the plugin jar).
