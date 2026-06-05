package com.virin.visionquiz.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionValues
import androidx.transition.Visibility

class IosFragmentPushExitTransition : Transition() {

    private val transitionParents = linkedMapOf<View, ViewGroup>()
    private var cleanupListenerAdded = false

    init {
        duration = PUSH_DURATION_MS
        interpolator = SETTLE_INTERPOLATOR
    }

    override fun isSeekingSupported(): Boolean = true

    override fun captureStartValues(transitionValues: TransitionValues) {
        ensureCleanupListener()
        transitionValues.capturePushExitValues()
        val parent = transitionValues.view.parent as? ViewGroup ?: return
        if (!transitionParents.containsKey(transitionValues.view)) {
            transitionParents[transitionValues.view] = parent
            parent.startViewTransition(transitionValues.view)
        }
    }

    override fun captureEndValues(transitionValues: TransitionValues) {
        ensureCleanupListener()
        transitionValues.capturePushExitValues()
    }

    override fun isTransitionRequired(
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Boolean = startValues != null && endValues == null

    override fun createAnimator(
        sceneRoot: ViewGroup,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? {
        val values = startValues ?: return null
        if (endValues != null) return null

        val view = values.view
        val parallaxDistance = view.navigationWidth(sceneRoot).toFloat() * PARALLAX_FACTOR
        view.clearAnimation()
        view.animate().cancel()
        view.translationX = 0f
        view.translationZ = BOTTOM_LAYER_Z
        view.alpha = 1f
        view.visibility = View.VISIBLE
        view.enableNavigationCornerClip(sceneRoot)

        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(TRANSLATION_X_PROPERTY, 0f, -parallaxDistance),
            PropertyValuesHolder.ofFloat(ALPHA_PROPERTY, 1f, PARALLAX_ALPHA)
        ).apply {
            addListener(object : AnimatorListenerAdapter() {
                private var finished = false

                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }

                override fun onAnimationCancel(animation: Animator) {
                    finish()
                }

                private fun finish() {
                    if (finished) return
                    finished = true
                    finishViewTransition(view)
                }
            })
        }
    }

    private fun ensureCleanupListener() {
        if (cleanupListenerAdded) return
        cleanupListenerAdded = true
        addListener(object : TransitionListenerAdapter() {
            override fun onTransitionCancel(transition: Transition) {
                finishAllViewTransitions()
                cleanupListenerAdded = false
                removeListener(this)
            }

            override fun onTransitionEnd(transition: Transition) {
                finishAllViewTransitions()
                cleanupListenerAdded = false
                removeListener(this)
            }
        })
    }

    private fun finishViewTransition(view: View) {
        val parent = transitionParents.remove(view)
        parent?.endViewTransition(view)
        view.resetNavigationState()
    }

    private fun finishAllViewTransitions() {
        transitionParents.keys.toList().forEach(::finishViewTransition)
    }

    private fun TransitionValues.capturePushExitValues() {
        values[PUSH_EXIT_PARENT] = view.parent
    }

    companion object {
        private const val PUSH_EXIT_PARENT = "visionquiz:iosPushExit:parent"
    }
}

class IosFragmentTransition(
    private val kind: Kind
) : Visibility() {

    enum class Kind {
        PUSH_ENTER,
        PUSH_EXIT,
        POP_ENTER,
        POP_EXIT
    }

    init {
        setMode(MODE_IN or MODE_OUT)
        applyNavigationTiming()
        if (kind.isPop) {
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionCancel(transition: Transition) {
                    NavigationBackAnimationSource.clear()
                }

                override fun onTransitionEnd(transition: Transition) {
                    NavigationBackAnimationSource.clear()
                }
            })
        }
    }

    override fun isSeekingSupported(): Boolean = true

    override fun onAppear(
        sceneRoot: ViewGroup,
        view: View,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? {
        applyNavigationTiming()
        val fullDistance = view.navigationWidth(sceneRoot).toFloat()
        val parallaxDistance = fullDistance * PARALLAX_FACTOR
        return when (kind) {
            Kind.PUSH_ENTER -> view.navigationAnimator(
                sceneRoot = sceneRoot,
                fromX = fullDistance,
                toX = 0f,
                layerZ = TOP_LAYER_Z
            )
            Kind.POP_ENTER -> view.navigationAnimator(
                sceneRoot = sceneRoot,
                fromX = -parallaxDistance,
                toX = 0f,
                fromAlpha = PARALLAX_ALPHA,
                toAlpha = 1f,
                layerZ = BOTTOM_LAYER_Z
            )
            else -> null
        }
    }

    override fun onDisappear(
        sceneRoot: ViewGroup,
        view: View,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? {
        applyNavigationTiming()
        val fullDistance = view.navigationWidth(sceneRoot).toFloat()
        val parallaxDistance = fullDistance * PARALLAX_FACTOR
        return when (kind) {
            Kind.PUSH_EXIT -> view.navigationAnimator(
                sceneRoot = sceneRoot,
                fromX = 0f,
                toX = -parallaxDistance,
                fromAlpha = 1f,
                toAlpha = PARALLAX_ALPHA,
                layerZ = BOTTOM_LAYER_Z
            )
            Kind.POP_EXIT -> view.navigationAnimator(
                sceneRoot = sceneRoot,
                fromX = 0f,
                toX = fullDistance,
                layerZ = TOP_LAYER_Z
            )
            else -> null
        }
    }

    private fun View.navigationAnimator(
        sceneRoot: ViewGroup,
        fromX: Float,
        toX: Float,
        fromAlpha: Float = 1f,
        toAlpha: Float = 1f,
        layerZ: Float
    ): Animator {
        clearAnimation()
        animate().cancel()
        translationX = fromX
        translationZ = layerZ
        alpha = fromAlpha
        visibility = View.VISIBLE
        enableNavigationCornerClip(sceneRoot)

        return ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofFloat(TRANSLATION_X_PROPERTY, fromX, toX),
            PropertyValuesHolder.ofFloat(ALPHA_PROPERTY, fromAlpha, toAlpha)
        ).apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (layerZ == TOP_LAYER_Z) {
                        bringToFront()
                        parent?.requestLayout()
                    }
                    visibility = View.VISIBLE
                }

                override fun onAnimationEnd(animation: Animator) {
                    resetNavigationState()
                }

                override fun onAnimationCancel(animation: Animator) {
                    resetNavigationState()
                }
            })
        }
    }

    private val Kind.isPop: Boolean
        get() = this == Kind.POP_ENTER || this == Kind.POP_EXIT

    private fun applyNavigationTiming() {
        val timing = when (kind) {
            Kind.PUSH_ENTER,
            Kind.PUSH_EXIT -> NavigationTiming(PUSH_DURATION_MS, SETTLE_INTERPOLATOR)
            Kind.POP_ENTER,
            Kind.POP_EXIT -> navigationPopTiming()
        }
        duration = timing.duration
        interpolator = timing.interpolator
    }

    private fun navigationPopTiming(): NavigationTiming {
        return when (NavigationBackAnimationSource.currentPopSource()) {
            NavigationBackAnimationSource.Source.NAVIGATION_BUTTON -> NavigationTiming(
                BUTTON_POP_DURATION_MS,
                SETTLE_INTERPOLATOR
            )
            NavigationBackAnimationSource.Source.SYSTEM -> NavigationTiming(
                GESTURE_POP_DURATION_MS,
                SCRUB_INTERPOLATOR
            )
        }
    }

    private data class NavigationTiming(
        val duration: Long,
        val interpolator: android.animation.TimeInterpolator
    )

    companion object {
        private const val GESTURE_POP_DURATION_MS = 280L
        private const val BUTTON_POP_DURATION_MS = 480L
    }
}

private const val PARALLAX_FACTOR = 0.33f
private const val PARALLAX_ALPHA = 0.85f
private const val BOTTOM_LAYER_Z = 0f
private const val TOP_LAYER_Z = 16f
private const val PUSH_DURATION_MS = 480L
private const val TRANSLATION_X_PROPERTY = "translationX"
private const val ALPHA_PROPERTY = "alpha"
private val SETTLE_INTERPOLATOR = PathInterpolator(0.23f, 1f, 0.32f, 1f)
private val SCRUB_INTERPOLATOR = LinearInterpolator()

private fun View.navigationWidth(sceneRoot: ViewGroup): Int {
    val rootWidth = rootView?.width ?: 0
    val displayWidth = resources.displayMetrics.widthPixels
    return maxOf(width, sceneRoot.width, rootWidth, displayWidth, 1)
}

private fun View.resetNavigationState() {
    clearAnimation()
    animate().cancel()
    clearNavigationCornerClip()
    alpha = 1f
    translationX = 0f
    translationZ = 0f
    visibility = View.VISIBLE
}
