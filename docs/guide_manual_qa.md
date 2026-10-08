# Check native highlighting and conflict notifications

Run the ordinary development sandbox from the repository root:

```shell
./gradlew :plugin:runIde
```

Open a Java source file with nested, multiline braces. This sandbox is for
manual behavior checks; it does not create or approve Driver baselines. Close
the IDE when finished.

1. In **Settings | Editor | Bracket Pair Guides**, enable all guide segments,
   pair borders and pair backgrounds. Move the caret inside nested pairs and
   check their geometry and depth colors.
2. Enable IntelliJ **Matched brace**, **Current scope**, and **Show indent
   guides**. With plugin native management disabled, check their original
   rendering remains visible. Enable management in the default suppression
   mode: native brace emphasis should disappear and regular indent guides
   should remain. Disable the plugin: the original native settings must return.
3. Select current-scope-only suppression, then leave-highlighting-unchanged.
   Check their advertised native effects after each Apply.
4. With native management disabled, place the caret adjacent to an opening
   brace so native and plugin active visuals overlap. Check the advisory and
   its settings action. Rapidly move A/B/A, change source, hide the editor, and
   close it; obsolete native evidence must not show an advisory for the new
   editor episode.
5. Edit indentation inside the active pair. Affected old guide geometry must
   disappear promptly and repaired geometry must follow the new text. Switch
   focus and hide/show the editor; stale active guides must not remain.

For pinned Linux captures and independent baseline review, follow
[Run visual contracts](guide_visual_testing.md). A manual observation does not
replace automated contracts or constitute deterministic thread/timing evidence.
