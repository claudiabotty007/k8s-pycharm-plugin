# Kubernetes Tools (PyCharm plugin)

## Versioning

The single source of truth is `version` in `build.gradle.kts` (it is patched into plugin.xml and the zip name).

- **New significant feature** -> bump **major** (e.g. 1.4.0 -> 2.0.0), reset minor/patch to 0.
- **Improvement to an existing feature** -> bump **minor** (e.g. 1.0.0 -> 1.1.0), reset patch to 0.
- Bug fix only (not specified by the owner; assumed) -> bump patch.

Bump the version in the same change that adds the feature/improvement, and rebuild the zip
(`./gradlew buildPlugin` -> `build/distributions/k8s-pycharm-plugin-<version>.zip`).

## Publishing (every new version)

Owner's standing instructions:

1. Build (`./gradlew test buildPlugin`). The build also copies the plugin **jar** (not the zip) to `dist/`.
2. **Commit `dist/<jar>` with the change and push to `main`.** The jar is self-contained and installable via
   Install Plugin from Disk; the zip is not kept on main.
3. **Always create a GitHub release** for every new version, tagged `v<version>` on the pushed commit, with the **jar**
   attached: `gh release create v<version> dist/<jar> --target <sha> --title "Kubernetes Tools <version>" --notes-file <file> --latest`.
   Because `dist/<jar>` is committed before tagging, the tag's tree contains the jar too.
   Notes: what changed since the previous release, install steps, what was tested. No personal data (emails, account ids).

Doc-only commits (no version bump) get no release. Installing on the Debian VM is done only on request.

## Build / run

- Needs JDK 21: use the JBR bundled with PyCharm (`<PyCharm>/jbr`) as `JAVA_HOME`.
- `gradle.properties` -> `ideDir` points at the local PyCharm used to compile against.
- `./gradlew test` runs the parsing unit tests; `./gradlew runIde -PopenProject=<path>` starts a sandbox IDE.
  Close the sandbox before `buildPlugin` (Windows locks the plugin jar).
