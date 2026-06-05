package com.virin.visionquiz.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.virin.visionquiz.R

open class BaseQuizFragment : Fragment() {

    var title: CharSequence
        get() = (requireActivity() as AppCompatActivity).supportActionBar?.title ?: ""
        set(value) {
            (requireActivity() as AppCompatActivity).supportActionBar?.title = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            configureSeekableNavigationTransitions()
        }
    }

    private fun configureSeekableNavigationTransitions() {
        enterTransition = IosFragmentTransition(IosFragmentTransition.Kind.PUSH_ENTER)
        exitTransition = IosFragmentPushExitTransition()
        reenterTransition = IosFragmentTransition(IosFragmentTransition.Kind.POP_ENTER)
        returnTransition = IosFragmentTransition(IosFragmentTransition.Kind.POP_EXIT)
        allowEnterTransitionOverlap = true
        allowReturnTransitionOverlap = true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (view as? ViewGroup)?.isTransitionGroup = true
        }
    }

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return super.onCreateAnimator(transit, enter, nextAnim)
        }
        val root = view ?: return super.onCreateAnimator(transit, enter, nextAnim)
        if (shouldSuppressNativePopAnimator(nextAnim)) {
            root.resetNavigationState()
            return ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 0L
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        root.resetNavigationState()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        root.resetNavigationState()
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        root.resetNavigationState()
                    }
                })
            }
        }

        val fullDistance = root.navigationWidth().toFloat()
        val parallaxDistance = fullDistance * 0.33f

        return when (nextAnim) {
            R.animator.ios_push_enter -> root.navigationAnimator(
                fromX = fullDistance,
                toX = 0f,
                duration = navigationSettleDuration,
                interpolator = navigationSettleInterpolator
            )

            R.animator.ios_push_exit -> root.navigationAnimator(
                fromX = 0f,
                toX = -parallaxDistance,
                fromAlpha = 1f,
                toAlpha = 0.85f,
                duration = navigationSettleDuration,
                interpolator = navigationSettleInterpolator
            )

            R.animator.ios_pop_enter -> {
                val timing = navigationPopTiming()
                root.navigationAnimator(
                    fromX = -parallaxDistance,
                    toX = 0f,
                    fromAlpha = 0.85f,
                    toAlpha = 1f,
                    duration = timing.duration,
                    interpolator = timing.interpolator
                ).clearBackAnimationSourceWhenFinished()
            }

            R.animator.ios_pop_exit -> {
                val timing = navigationPopTiming()
                root.navigationAnimator(
                    fromX = 0f,
                    toX = fullDistance,
                    duration = timing.duration,
                    interpolator = timing.interpolator
                ).clearBackAnimationSourceWhenFinished()
            }

            else -> super.onCreateAnimator(transit, enter, nextAnim)
        }
    }

    private data class NavigationTiming(
        val duration: Long,
        val interpolator: Interpolator
    )

    private fun navigationPopTiming(): NavigationTiming {
        return when (NavigationBackAnimationSource.currentPopSource()) {
            NavigationBackAnimationSource.Source.NAVIGATION_BUTTON -> NavigationTiming(
                duration = navigationButtonPopDuration,
                interpolator = navigationSettleInterpolator
            )
            NavigationBackAnimationSource.Source.SYSTEM -> NavigationTiming(
                duration = navigationScrubDuration,
                interpolator = navigationScrubInterpolator
            )
        }
    }

    private fun Animator.clearBackAnimationSourceWhenFinished(): Animator {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                NavigationBackAnimationSource.clear()
            }

            override fun onAnimationEnd(animation: Animator) {
                NavigationBackAnimationSource.clear()
            }
        })
        return this
    }

    private fun View.navigationWidth(): Int {
        val parentWidth = (parent as? View)?.width ?: 0
        val windowWidth = requireActivity().window.decorView.width
        val displayWidth = resources.displayMetrics.widthPixels
        return maxOf(width, parentWidth, windowWidth, displayWidth, 1)
    }

    private fun View.resetNavigationState() {
        alpha = 1f
        translationX = 0f
        visibility = View.VISIBLE
    }

    private fun View.navigationAnimator(
        fromX: Float,
        toX: Float,
        fromAlpha: Float? = null,
        toAlpha: Float? = null,
        duration: Long,
        interpolator: Interpolator = navigationScrubInterpolator
    ): Animator {
        val animators = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(this, View.TRANSLATION_X, fromX, toX)
        )
        if (fromAlpha != null && toAlpha != null) {
            animators += ObjectAnimator.ofFloat(this, View.ALPHA, fromAlpha, toAlpha)
        }
        return AnimatorSet().apply {
            playTogether(animators)
            this.duration = duration
            this.interpolator = interpolator
        }
    }

    companion object {
        private const val navigationScrubDuration = 280L
        private const val navigationButtonPopDuration = 480L
        private const val navigationSettleDuration = 480L
        private var suppressedNativePopAnimatorCount = 0
        private val navigationScrubInterpolator = LinearInterpolator()
        private val navigationSettleInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)

        fun suppressNextNativePopAnimators() {
            suppressedNativePopAnimatorCount = 2
        }

        fun clearNativePopAnimatorSuppression() {
            suppressedNativePopAnimatorCount = 0
        }

        private fun shouldSuppressNativePopAnimator(nextAnim: Int): Boolean {
            val isPopAnimator = nextAnim == R.animator.ios_pop_enter ||
                nextAnim == R.animator.ios_pop_exit
            if (!isPopAnimator || suppressedNativePopAnimatorCount <= 0) {
                return false
            }
            suppressedNativePopAnimatorCount -= 1
            return true
        }
    }
}
