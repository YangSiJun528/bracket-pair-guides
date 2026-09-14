# Check Native Highlighting and Conflict Notifications

Use this sandbox to check settings application and notifications in IntelliJ
IDEA Community 2024.2.6, New UI, with the current plugin build.

## Open the sandbox

Keep the desktop unlocked while initialization and screen capture run. The
launcher requires the QA editor to have focus before reporting readiness.

From the repository root, run:

```shell
./gradlew :plugin:runManualQa
```

The task calls the same Starter/Driver initialization as `visualTest`, including
its Java project import, New UI, Darcula, JetBrains Mono 14, and pinned IDE.
It verifies that a guide and colored brackets are present, saves `editor.png`
and `window.png`, then leaves the IDE running. It does not run the scenario
suite or compare baselines.

Each command starts a clean session under `outputs/manual-qa-starter/`.
The printed session directory contains `sandbox.txt` with the project and
Starter config/log paths, and `ready.txt` after rendering is verified. Normal
IntelliJ settings and the previous `outputs/manual-qa/` sandbox are not used.
Close the IDE when finished. To check persistence within this session, use
IntelliJ's restart action; running the Gradle command again creates a new one.

On first launch, **Matched brace**, **Current scope**, and **Show indent guides**
are enabled. Bracket Pair Guides is enabled, but **Adjust IntelliJ guide
rendering while Bracket Pair Guides is enabled** is off. The selected mode is
**Hide matched-brace and Current scope highlighting**. Unlike the screenshot
suite, the manual launcher does not mute conflict notifications and leaves the
caret visible at `7:20`.

## Check the minimum cases

Use **Settings | Editor | Bracket Pair Guides** for the plugin controls.
IntelliJ's **Matched brace** and **Current scope** are under
**Editor | General | Highlight on Caret Movement**; **Show indent guides** is
under **Editor | General | Appearance**. Click **Apply** after each change.

1. **Suppress and restore highlighting.** Place the caret at line 6, column 62
   of `Sample.java` (Go to Line/Column: `6:62`). Enable the plugin's integration
   parent checkbox in its default mode. Native emphasis should disappear
   without moving the caret; the dim regular indent guide and plugin guide
   should remain. IntelliJ's Matched brace becomes off; Current scope can stay
   checked because matched-brace processing gates both rendered effects.
   Clear the parent checkbox: the original highlighting must return. Repeat
   restoration by disabling the plugin's **Enabled** checkbox while integration
   is on, then enable the plugin again.
2. **Current scope only.** With the plugin and integration enabled, choose
   **Hide Current scope highlighting only**. Matched brace should be on and
   Current scope off. At `6:62`, matched-brace emphasis can still appear and
   trigger the advisory. Choose **Leave IntelliJ highlighting unchanged**:
   both original native values must be restored. Show indent guides stays on
   throughout.
3. **Warning and its settings action.** With integration off and both native
   highlighting options on, move to `6:62`. The informational balloon
   **IntelliJ may emphasize an adjacent guide** should appear. **Review
   settings** must open the plugin page without changing any options. Moving
   the caret or reopening the file must not repeatedly show the same warning.
4. **Suppression and a new conflict.** Turn the plugin's **Vertical** segment
   off, apply, then on and apply to start a new conflict. On the next balloon,
   click **Don't warn again for this conflict**. Restart this sandbox: the
   same conflict should remain quiet. Turn Vertical off and on again: a new
   warning should be allowed. Finally enable the default integration mode:
   the native guide stays dim and no conflict warning should appear.

The balloon is informational, not an error. A dim regular indent line alone
does not trigger it. A warning that appeared during an earlier checklist step
counts as that conflict's one warning; toggling Vertical off and on starts a
fresh conflict when needed.
