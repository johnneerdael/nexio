package com.nuvio.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

@OptIn(ExperimentalFoundationApi::class)
object NuvioScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = 0.83f,
            stiffness = 150f
        )

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            val itemCenter = offset + size / 2f
            val viewportCenter = containerSize / 2f
            return itemCenter - viewportCenter
        }
    }
}
