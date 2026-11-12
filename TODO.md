# TODO

## Add `:theme-profile` to `profile` result (PARKED)

Copy `fireworks.macros/theme-profile` into the lib, add a `:theme-profile` entry
to `profile`'s result, and augment the SupportsColor schema.

Full plan (decisions made + open questions):
`~/.claude/plans/i-want-to-add-shiny-treasure.md`

Open when resuming:
- Name the new env var feeding the theme string (not the Ghostty `COLOR_THEME`).
- Decide: compute `:theme-profile` inside `profile` or a separate fn.
- Two latent fireworks bugs (`prefers` undefined; `synced-theme-light/-dark`
  destructuring never matches `:light`/`:dark`) — fix or copy verbatim.


