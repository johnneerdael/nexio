# Design System Specification: The Obsidian Lens

## 1. Overview & Creative North Star
**Creative North Star: "The Obsidian Lens"**
This design system is a masterclass in cinematic depth and high-performance precision. Moving beyond the "flat" dark mode trends of previous years, this system treats the interface as a physical environment made of dark-tinted glass, ink-black obsidian, and focused light. 

To break the "template" look, we utilize **intentional asymmetry**. Layouts should not always be centered or perfectly balanced; instead, use a heavy-weighted headline on the left (`display-lg`) balanced by a wide, low-opacity `secondary` glow on the opposite corner. Elements should overlap, with glassmorphism providing the necessary legibility to create a sense of three-dimensional space. We are not just building an app; we are directing a high-end digital film.

---

## 2. Colors & Surface Logic
The palette is rooted in the "Void"—a series of deep, near-black neutrals that provide the canvas for high-energy accents.

### The Palette
- **The Foundation:** `surface` (#0e0e0e) and `surface-container-lowest` (#000000) create the "ink-black" base.
- **The Accents:** `primary` (#ba9eff) provides the Electric Violet glow, while `secondary` (#53ddfc) acts as the Cyber Cyan counterpoint. Use `tertiary` (#ff97b5) sparingly for moments of high-tension or luxury.

### The "No-Line" Rule
**Strict Mandate:** Prohibit the use of 1px solid borders for sectioning or layout containment. 
Boundaries must be defined by:
1.  **Background Shifts:** Place a `surface-container-low` (#131313) component on a `surface` (#0e0e0e) background.
2.  **Tonal Transitions:** Use soft, linear gradients from `surface-container` to `surface-container-high` to imply an edge without drawing a line.

### The Glass & Gradient Rule
For primary CTAs and hero elements, use **Signature Textures**. Instead of a flat `primary` fill, apply a linear gradient from `primary` (#ba9eff) to `primary-dim` (#8455ef) at a 135-degree angle. Floating panels must use `surface-variant` (#262626) at 60% opacity with a `backdrop-blur` of 20px to achieve the 2026 glassmorphism aesthetic.

---

## 3. Typography
The system uses a dual-typeface strategy to balance editorial flair with high-performance readability.

- **Display & Headlines (Manrope):** Chosen for its geometric precision and modern soul. Use `display-lg` (3.5rem) with tight letter-spacing (-0.02em) to create an authoritative, "big screen" feel.
- **Body & Labels (Inter):** The industry standard for legibility. Use `body-md` (0.875rem) for all functional text to ensure the "cinematic" look doesn't sacrifice usability.

**Hierarchy as Identity:** 
Create contrast by pairing an oversized `headline-lg` in `on-surface` (#ffffff) with a `label-md` in `on-surface-variant` (#adaaaa) set in all caps with 0.1em tracking. This "High-Low" pairing is the hallmark of premium editorial design.

---

## 4. Elevation & Depth
Depth is achieved through **Tonal Layering**, not structure.

- **The Layering Principle:** 
    - **Base Layer:** `surface-container-lowest` (Deep Background).
    - **Mid Layer:** `surface-container` (Standard UI Cards).
    - **Top Layer:** `surface-bright` (Active/Hovered states).
- **Ambient Shadows:** When an element must float, use a shadow with a 40px blur, 0px offset, and 6% opacity. The shadow color should be tinted with `primary` (#ba9eff) to simulate the way a neon light casts a glow on dark obsidian.
- **The "Ghost Border" Fallback:** If accessibility requirements demand a border, use `outline-variant` (#494847) at **15% opacity**. It should feel felt, not seen.

---

## 5. Components

### Buttons
- **Primary:** Gradient fill (`primary` to `primary-dim`). `xl` (0.75rem) roundedness. No border. Add a subtle outer glow using the `primary` color at 20% opacity on hover.
- **Secondary (Glass):** `surface-container-highest` at 40% opacity with a `backdrop-blur`. A "Ghost Border" of 10% `on-surface` is permitted here.

### Cards & Lists
- **The "No-Divider" Mandate:** Never use horizontal lines to separate list items. Use a `1.4rem` (Spacing 4) vertical gap or a subtle shift to `surface-container-low` on hover.
- **Layout:** Cards should use `surface-container` with `xl` (0.75rem) corner radius.

### Input Fields
- **Styling:** Use `surface-container-lowest` as the fill. The bottom-edge should have a 2px "glow-line" using `outline-variant` that transitions to `primary` when focused.
- **Interaction:** Labels (`label-md`) should float and shift to `primary` color on focus.

### Additional Signature Component: The "Luminescent Scrim"
A full-width container used for section headers. It features a deep `surface` background with a feathered, radial gradient of `secondary` (#53ddfc) at 5% opacity in the top-right corner, creating a cinematic "lighting rig" effect.

---

## 6. Do's and Don'ts

### Do:
- **Use "Breathing Room":** Rely on the spacing scale (specifically `8` and `12`) to let elements exist without crowding. High-end design feels effortless, not cramped.
- **Embrace Asymmetry:** Align text to the left but place supporting imagery or data visualizations slightly off-center to the right.
- **Color Logic:** Use `primary` for "Actions" (Violet) and `secondary` for "Information/Status" (Cyan).

### Don't:
- **Don't use 100% White:** Avoid `#ffffff` for large blocks of text; use `on-surface-variant` (#adaaaa) for long-form body copy to reduce eye strain in dark mode.
- **Don't use solid borders:** Standard 1px grey lines kill the cinematic depth. If you feel you need a line, use a spacing increment instead.
- **Don't over-glow:** Neon glows should be a "whisper," not a "scream." Keep glow opacities below 30%.

---
*This design system is a living framework. When in doubt, ask: "Does this feel like a tool, or does it feel like an experience?" Aim for the latter.*