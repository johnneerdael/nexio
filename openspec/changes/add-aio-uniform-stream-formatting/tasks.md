## 1. AIO-Compatible Parse Contract
- [x] 1.1 Define Android models for the AIO-compatible parse-value contract used by formatter
      templates.
- [x] 1.2 Port or adapt filename/stream parsing so required AIO-compatible parsed fields are
      produced from Nexio stream data.
- [x] 1.3 Add targeted parser tests for representative movie, episode, debrid, and edge-case
      stream inputs.

## 2. AIO-Compatible Template Engine
- [x] 2.1 Port the formatter template compiler/evaluator grammar used by `formatter/base.ts`.
- [x] 2.2 Support the current string, array, number, conditional, and comparator behaviors required
      for full built-in template compatibility.
- [x] 2.3 Add parity-style tests for nested expansion, line removal, chained modifiers, and boolean
      comparator behavior.

## 3. Uniform Rendering Integration
- [x] 3.1 Add a built-in Android template registry with multiple AIO-compatible template
      definitions, including the new universal template.
- [x] 3.2 Route `uniformStreamFormattingEnabled` rendering through the AIO template output as the
      single source of truth for title and detail lines.
- [x] 3.3 Keep the legacy non-uniform rendering path unchanged.

## 4. Validation
- [x] 4.1 Add stream presentation tests proving uniform rendering output comes from template
      evaluation rather than legacy hardcoded builders.
- [x] 4.2 Verify representative examples from existing and new universal templates render the
      expected title and detail lines.
- [x] 4.3 Confirm grouped stream selection, deduplication, and filtering continue to work with the
      new parsed-field source.
