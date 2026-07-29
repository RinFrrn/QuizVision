# 001 — Stabilize library selection motion

- **Status**: DONE
- **Commit**: a901991
- **Severity**: HIGH
- **Category**: Performance
- **Estimated scope**: 1 file, medium

## Problem

The library list animated geometry and shadow values on every visible card when
selection mode changed. That forced repeated layout and drawing work across the
whole list:

```kotlin
// app/src/main/java/com/virin/visionquiz/quizlibrarylist/QuizLibraryListCompose.kt:134 — previous
val borderWidth by animateDpAsState(
    targetValue = if (isSelected) 2.dp else 1.dp,
    animationSpec = tween(durationMillis = 220)
)
val elevation by animateDpAsState(
    targetValue = if (isSelected) 2.dp else 0.dp,
    animationSpec = tween(durationMillis = 220)
)
val contentEndPadding by animateDpAsState(
    targetValue = if (isSelectionMode) 16.dp else 48.dp,
    animationSpec = tween(durationMillis = 220)
)
```

The checkbox, action area, and chevron also expanded or shrank horizontally and
vertically on every card.

## Target

Keep card geometry fixed. Represent selection through the fixed-size leading
visual, container color, title color, and a constant 1dp border:

```kotlin
private val StrongEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val StateDurationMillis = 160

val containerColor by animateColorAsState(
    targetValue = if (isSelected) colors.primaryContainer
        else colors.surfaceContainerLowest,
    animationSpec = tween(StateDurationMillis, easing = StrongEaseOut)
)
```

Selection must not animate padding, border width, elevation, height, or width.

## Repo conventions to follow

- Compose UI for the library screen lives in
  `app/src/main/java/com/virin/visionquiz/quizlibrarylist/QuizLibraryListCompose.kt`.
- Colors come from `MaterialTheme.colorScheme`, which is bridged from the
  existing XML theme by `MdcThemeBridge`.
- The shared strong ease-out curve is
  `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`.

## Steps

1. Remove all `animateDpAsState` calls from library-card selection.
2. Keep a 44dp leading slot in both normal and selection modes.
3. Replace the library icon with a checkbox inside that slot during selection.
4. Keep card border width and elevation constant.
5. Animate only selection-related colors for 160ms with `StrongEaseOut`.

## Boundaries

- Do not change database or selection-state behavior.
- Do not change navigation behavior.
- Do not add dependencies.
- Do not animate layout properties.

## Verification

- **Mechanical**:
  `./gradlew :app:compileDebugKotlin` must finish with `BUILD SUCCESSFUL`.
- **Feel check**:
  - Long-press a card and confirm every card keeps its original height.
  - Select and deselect several cards quickly; titles and neighboring cards
    must not jump horizontally.
  - Inspect at slow speed and confirm only color/checkbox state changes.
- **Done when**: entering and leaving selection mode causes no list reflow and
  all selection controls remain functional.
