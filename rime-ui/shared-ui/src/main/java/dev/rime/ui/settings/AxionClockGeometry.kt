/*
 * Copyright (C) 2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.rime.ui.settings



private const val CLOCK_EDIT_MIN_FRAME_WIDTH_DP = 160f
private const val CLOCK_EDIT_RESIZE_DP_PER_SCALE = 96f

data class ClockSizeScaleRange(
    val min: Float,
    val standard: Float,
    val legacyLarge: Float,
    val max: Float,
) {
    fun clamp(value: Float): Float = value.coerceIn(min, max)

    fun resolve(rawScale: Float?, legacyLargeSelected: Boolean): Float {
        return rawScale?.let(::clamp) ?: if (legacyLargeSelected) legacyLarge else standard
    }
}

data class ClockEditScaleGeometry(
    val scaleRange: ClockSizeScaleRange,
    val requestedScale: Float,
    val frameWidthDp: Float,
    val minFrameWidthDp: Float = CLOCK_EDIT_MIN_FRAME_WIDTH_DP,
    val resizeDpPerScale: Float = CLOCK_EDIT_RESIZE_DP_PER_SCALE,
    val frameHeightDp: Float? = null,
    val resizeHeightDpPerScale: Float? = null,
    val frameCenterYFraction: Float = 0.5f,
) {
    fun clampScale(value: Float): Float = scaleRange.clamp(value)

    fun frameWidthIn(availableWidthDp: Float): Float {
        if (availableWidthDp <= 0f) return frameWidthDp.coerceAtLeast(minFrameWidthDp)
        val minWidth = minOf(minFrameWidthDp, availableWidthDp)
        return frameWidthDp.coerceIn(minWidth, availableWidthDp)
    }

    companion object {
        fun forContentWidth(
            availableWidthDp: Float,
            requestedScale: Float,
            scaleRange: ClockSizeScaleRange,
            contentWidthDp: Float,
            horizontalPaddingDp: Float = 0f,
            frameHeightDp: Float? = null,
            minFrameWidthDp: Float = CLOCK_EDIT_MIN_FRAME_WIDTH_DP,
            resizeDpPerScale: Float = contentWidthDp.coerceAtLeast(1f),
        ): ClockEditScaleGeometry {
            val maxScale = if (availableWidthDp > 0f && contentWidthDp > 0f) {
                ((availableWidthDp - horizontalPaddingDp).coerceAtLeast(1f) / contentWidthDp)
                    .coerceIn(scaleRange.min, scaleRange.max)
            } else {
                scaleRange.max
            }
            val scale = requestedScale.coerceIn(scaleRange.min, maxScale)
            return ClockEditScaleGeometry(
                scaleRange = scaleRange.copy(max = maxScale),
                requestedScale = scale,
                frameWidthDp = contentWidthDp * scale + horizontalPaddingDp,
                minFrameWidthDp = minFrameWidthDp,
                resizeDpPerScale = resizeDpPerScale,
                frameHeightDp = frameHeightDp,
            )
        }

        fun default(
            availableWidthDp: Float,
            requestedScale: Float,
            scaleRange: ClockSizeScaleRange,
        ): ClockEditScaleGeometry {
            val scale = scaleRange.clamp(requestedScale)
            return ClockEditScaleGeometry(
                scaleRange = scaleRange,
                requestedScale = scale,
                frameWidthDp = availableWidthDp * scale,
                resizeDpPerScale = availableWidthDp.coerceAtLeast(1f),
            )
        }
    }
}

data class ClockTopPaddingRange(
    val min: Float,
    val baseMax: Float,
    val reservedHeightDp: Float = 0f,
) {
    val max: Float
        get() = (baseMax - reservedHeightDp).coerceAtLeast(min)

    fun clamp(value: Float): Float = value.coerceIn(min, max)
}

