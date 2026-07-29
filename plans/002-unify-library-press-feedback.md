# 002 — Unify library press feedback

- **Status**: DONE
- **Commit**: a901991
- **Severity**: MEDIUM
- **Category**: Cohesion & accessibility
- **Estimated scope**: 1 file, medium

## Problem

Library cards and inline action surfaces relied only on ripple feedback, while
motion timings were repeated as uncoordinated `tween(220)` values. The screen
had no explicit gentler behavior for users who disable system animations.

```kotlin
// app/src/main/java/com/virin/visionquiz/quizlibrarylist/QuizLibraryListCompose.kt:110 — previous
animationSpec = tween(durationMillis = 220)
```

## Target

Use one strong ease-out curve, 120ms physical press feedback, and 160ms state
color feedback:

```kotlin
private val StrongEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val PressDurationMillis = 120
private const val StateDurationMillis = 160

val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
val scale by animateFloatAsState(
    targetValue = if (animationsEnabled && isPressed) 0.97f else 1f,
    animationSpec = tween(PressDurationMillis, easing = StrongEaseOut)
)
```

Use `graphicsLayer` for scale. When system animations are disabled, retain
ripple and color feedback without transform movement.

## Repo conventions to follow

- Use Compose `MutableInteractionSource` and `collectIsPressedAsState`.
- Keep Android Material ripple through `LocalIndication.current`.
- Use `graphicsLayer` for compositor-friendly scale.
- Large list cards may use `0.98f`; smaller action surfaces use `0.97f`.

## Steps

1. Add shared 120ms and 160ms motion constants.
2. Add a reusable pressable surface using an interaction source and ripple.
3. Apply 0.97 scale to quick actions and the review CTA.
4. Apply a subtler 0.98 scale to large library cards and dialog rows.
5. Gate transform movement with `ValueAnimator.areAnimatorsEnabled()`.

## Boundaries

- Do not add bounce or spring overshoot.
- Do not animate high-frequency list entrances.
- Do not replace Material ripple.
- Do not add a motion dependency.

## Verification

- **Mechanical**:
  `./gradlew :app:compileDebugKotlin` and
  `./gradlew :app:testDebugUnitTest` must pass.
- **Feel check**:
  - Press each top quick action and confirm feedback begins on touch-down.
  - Rapidly alternate between cards; scale must retarget without jumping.
  - Disable Android animator duration scale and confirm transform motion is
    removed while ripple/color feedback remains.
- **Done when**: all pressable surfaces respond consistently without visible
  overshoot, delayed feedback, or movement under reduced motion.
