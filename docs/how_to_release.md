# Release Bracket Pair Guides

Use this procedure for a public GitHub and JetBrains Marketplace release.

## Before the first Marketplace upload

JetBrains requires the first plugin publication to be uploaded manually. Do
not set the repository variable `MARKETPLACE_PUBLISH_ENABLED` to `true` until
the Marketplace listing exists.

1. Make the source URL used by the listing publicly reachable. The MIT license
   choice requires a source-code link.
2. Sign in to JetBrains Marketplace, accept the Developer Agreement, and create
   or select the Vendor profile.
3. Provide a valid vendor website and email address, the MIT license, the public
   source URL, and relevant tags.
4. Create the signing certificate and private key described in JetBrains'
   [plugin-signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
   Supply `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` to the
   local Gradle process without committing them.
5. Run the local verification and signing commands below. Upload the
   `*-signed.zip` produced by `signPlugin` through **Upload plugin**.
6. Keep `MARKETPLACE_PUBLISH_ENABLED` unset or `false` when publishing this
   same version as a GitHub release. Marketplace rejects duplicate versions.
7. Wait for the first version to be approved before enabling automated updates
   for the next, higher version.

These are Marketplace account and listing fields; `pluginRepositoryUrl` in
`gradle.properties` does not populate them. See JetBrains' official
[first-publication procedure](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html),
[new-plugin upload form](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html),
and [approval criteria](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html).

## Verify a release candidate

Run from a clean checkout of the exact commit to release:

```shell
./gradlew :engine:clean :plugin:clean :benchmarks:clean \
  :engine:check :plugin:check :benchmarks:jmhJar
./gradlew :plugin:buildPlugin
./gradlew :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure
./gradlew :plugin:verifyPlugin
./gradlew :plugin:signPlugin :plugin:verifyPluginSignature
```

Confirm the generated descriptor contains the intended version, `since-build`,
and dependencies, and that the worktree is clean. The current verifier matrix
is described in the
[IDE and language support reference](reference_language_support.md).

## Publish the GitHub draft

Every push to `main` runs build, test, Qodana, and Plugin Verifier jobs. After
all four succeed, the Build workflow replaces the private draft release and
targets it at the exact verified commit.

1. Confirm the draft tag equals the version in `gradle.properties`.
2. Review the generated release notes and attached commit.
3. Publish the draft as a stable or prerelease GitHub release.

The Release workflow checks out that tag, rejects a tag/version mismatch,
builds and optionally signs the ZIP, and uploads the preferred signed artifact
to the GitHub release before attempting the optional Marketplace publication.

## Enable Marketplace updates

After the first Marketplace version is approved, prepare a new version that has
never been uploaded:

1. Add `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and
   `PRIVATE_KEY_PASSWORD` to the GitHub Actions secrets.
2. Increment `version` in `gradle.properties`; do not reuse the manually
   uploaded version.
3. Set the repository variable `MARKETPLACE_PUBLISH_ENABLED` to `true`.
4. Publish the GitHub draft only after the Build workflow is fully green.
5. Confirm both the GitHub asset and Marketplace review state after the Release
   workflow finishes.

Leave `MARKETPLACE_PUBLISH_ENABLED` unset or `false` when credentials, signing,
or the Marketplace listing are not ready. GitHub release assets are still built
and uploaded in that mode.
