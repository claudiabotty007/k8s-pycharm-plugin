# Kubernetes Tools (PyCharm plugin)

## Versioning

The single source of truth is `version` in `build.gradle.kts` (it is patched into plugin.xml and the zip name).

- **New significant feature** -> bump **major** (e.g. 1.4.0 -> 2.0.0), reset minor/patch to 0.
- **Improvement to an existing feature** -> bump **minor** (e.g. 1.0.0 -> 1.1.0), reset patch to 0.
- Bug fix only (not specified by the owner; assumed) -> bump patch.

Bump the version in the same change that adds the feature/improvement, and rebuild the zip
(`./gradlew buildPlugin` -> `build/distributions/k8s-pycharm-plugin-<version>.zip`).

