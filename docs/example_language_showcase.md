# Language Showcase Examples

These examples show representative matcher behavior; they are not a runtime
test matrix. `<caret>` marks the caret position and is not part of the source.

- A pair on one line produces a horizontal guide when that segment is enabled.
- A pair spanning lines can produce vertical and opening/closing segments.
- The literal bracket decoys shown inside strings and comments are ignored by
  the language lexer and matcher. JSON has strings but no comments.
- Angle brackets in generic syntax are contextual examples. The installed
  language matcher decides whether a particular `<` and `>` form a pair.

## Java

```java
class Demo<T> {
    List<Map<String, T>> build(T value) {
        String ignored = ")] }";
        // Ignored: ([{ < > }])
        return List.of(
            Map.of("value", (<caret>value))
        );
    }
}
```

## Kotlin

```kotlin
class Demo<T> {
    fun build(value: T): List<Map<String, T>> {
        val ignored = ")] }"
        // Ignored: ([{ < > }])
        return listOf(
            mapOf("value" to (<caret>value)),
        )
    }
}
```

## JSON

```json
{
  "ignored": ")] } [ {",
  "items": [
    {"value": <caret>true}
  ]
}
```

## JavaScript and TypeScript

```typescript
function build<T>(value: T): Array<{ value: T }> {
    const ignored = ")] }";
    // Ignored: ([{ < > }])
    return [
        { value: (<caret>value) },
    ];
}
```

This is TypeScript. Remove the type annotations and generic syntax for a plain
JavaScript variant. TypeScript, JSX, and TSX support is conditional on their
installed plugin resolving the JavaScript base-language matcher.

## Python

```python
def build(value):
    ignored = ")] }"
    # Ignored: ([{ < > }])
    return [
        {"value": (<caret>value)},
    ]
```

Python indentation alone is not a bracket pair; the delimiters above are.

## Go

```go
func build(value string) []map[string]string {
    ignored := ")] }"
    _ = ignored
    // Ignored: ([{ < > }])
    return []map[string]string{
        {"value": (<caret>value)},
    }
}
```

## Rust

```rust
fn build<T>(value: T) -> Vec<Option<T>> {
    let ignored = ")] }";
    // Ignored: ([{ < > }])
    vec![
        Some((<caret>value)),
    ]
}
```

The Rust matcher handles `{}`, `()`, `[]`, and angle brackets contextually.

## YAML

```yaml
ignored: ")] } [ {"
# Ignored: ([{ < > }])
items: [
  {name: demo, values: [<caret>one, two]},
]
```

Only YAML flow collections use brace pairs here. Block indentation does not
create a bracket guide.

## Shell Script

```bash
build() {
    ignored=')]} [ {'
    # Ignored: ([{ < > }])
    result=$(
        printf '%s\n' "${value:-<caret>default}"
    )
}
```

This combines a multiline function and command substitution with a
single-line parameter expansion.

## TOML

```toml
[tool.bracket-pair-guides]
enabled = true
palette = [
  "cyan",
  "<caret>violet",
  "orange",
]
```

TOML arrays and table headers both use `[]` pairs, while inline tables use `{}`.
Brackets inside quoted strings remain string content.

## CSS

```css
.card {
    content: "ignored: } ] )";
    color: rgb(20, 40, <caret>60);
}
```

CSS-family support is available when the installed web-language plugin exposes
its official matcher. LESS, SASS, and SCSS have separate matcher registrations.

## SQL

```sql
SELECT json_object(
    'value', (<caret>value)
)
FROM (
    SELECT 42 AS value
) AS source;
```

SQL support is available when Database Tools supplies its registered
`SqlPairedBraceMatcher`; the installed matcher decides the exact token set.
