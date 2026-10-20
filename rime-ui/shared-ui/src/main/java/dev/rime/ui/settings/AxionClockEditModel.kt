/*
 * Copyright (C) 2026 AxionOS
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

import kotlin.math.abs

internal const val CLOCK_EDIT_HANDLE_SIZE_DP = 56
internal const val CLOCK_EDIT_GUIDE_CORNER_RADIUS_DP = 16
internal const val CLOCK_EDIT_GUIDE_STROKE_DP = 2
internal const val CLOCK_EDIT_HANDLE_STROKE_DP = 4
internal const val CLOCK_EDIT_HANDLE_ARC_SIZE_DP = CLOCK_EDIT_GUIDE_CORNER_RADIUS_DP * 2
internal const val CLOCK_EDIT_HANDLE_INSET_DP = CLOCK_EDIT_HANDLE_STROKE_DP / 2
internal const val CLOCK_EDIT_HANDLE_OVERFLOW_DP =
    (CLOCK_EDIT_HANDLE_STROKE_DP - CLOCK_EDIT_GUIDE_STROKE_DP) / 2
internal const val CLOCK_EDIT_TOP_PADDING_MIN_DP = AxionClockLimits.TOP_PADDING_MIN_DP

internal data class ClockEditModel(
    val storedSizeScale: Float,
    val editedSizeScale: Float,
    val storedHorizontalOffsetDp: Float,
    val editedHorizontalOffsetDp: Float,
    val storedTopPaddingDp: Float,
    val editedTopPaddingDp: Float,
    val scaleGeometry: ClockEditScaleGeometry,
    val horizontalOffsetRange: ClockHorizontalOffsetRange,
    val topPaddingRange: ClockTopPaddingRange,
    val dragTarget: ClockEditDragTarget = ClockEditDragTarget.None,
    val pendingSizeScale: Float? = null,
    val pendingHorizontalOffsetDp: Float? = null,
    val pendingTopPaddingDp: Float? = null,
) {
    val sizeScaleOverride: Float
        get() = scaleGeometry.clampScale(editedSizeScale)

    val topPaddingDp: Float
        get() = topPaddingRange.clamp(editedTopPaddingDp)

    val horizontalOffsetDp: Float
        get() = horizontalOffsetRange.clamp(editedHorizontalOffsetDp)

    fun sync(
        storedSizeScale: Float,
        storedHorizontalOffsetDp: Float,
        storedTopPaddingDp: Float,
        scaleGeometry: ClockEditScaleGeometry,
        horizontalOffsetRange: ClockHorizontalOffsetRange,
        topPaddingRange: ClockTopPaddingRange,
    ): ClockEditModel {
        val nextPendingSizeScale =
            pendingSizeScale?.takeUnless { storedSizeScale.approximatelyEquals(it) }
        val nextPendingHorizontalOffset =
            pendingHorizontalOffsetDp?.takeUnless {
                storedHorizontalOffsetDp.approximatelyEquals(it)
            }
        val nextPendingTopPadding =
            pendingTopPaddingDp?.takeUnless { storedTopPaddingDp.approximatelyEquals(it) }
        val nextStoredSizeScale = nextPendingSizeScale ?: storedSizeScale
        val nextStoredHorizontalOffset =
            nextPendingHorizontalOffset ?: storedHorizontalOffsetDp
        val nextStoredTopPadding = nextPendingTopPadding ?: storedTopPaddingDp
        val nextScaleGeometry =
            if (
                dragTarget == ClockEditDragTarget.Size &&
                    !scaleGeometry.requestedScale.approximatelyEquals(
                        scaleGeometry.clampScale(editedSizeScale),
                    )
            ) {
                this.scaleGeometry
            } else {
                scaleGeometry
            }
        if (dragTarget != ClockEditDragTarget.None) {
            return copy(
                storedSizeScale = nextStoredSizeScale,
                editedSizeScale = nextScaleGeometry.clampScale(editedSizeScale),
                storedHorizontalOffsetDp = nextStoredHorizontalOffset,
                editedHorizontalOffsetDp = horizontalOffsetRange.clamp(editedHorizontalOffsetDp),
                storedTopPaddingDp = nextStoredTopPadding,
                editedTopPaddingDp = topPaddingRange.clamp(editedTopPaddingDp),
                scaleGeometry = nextScaleGeometry,
                horizontalOffsetRange = horizontalOffsetRange,
                topPaddingRange = topPaddingRange,
                pendingSizeScale = nextPendingSizeScale,
                pendingHorizontalOffsetDp = nextPendingHorizontalOffset,
                pendingTopPaddingDp = nextPendingTopPadding,
            )
        }
        return copy(
            storedSizeScale = nextStoredSizeScale,
            editedSizeScale = scaleGeometry.clampScale(nextStoredSizeScale),
            storedHorizontalOffsetDp = nextStoredHorizontalOffset,
            editedHorizontalOffsetDp = horizontalOffsetRange.clamp(nextStoredHorizontalOffset),
            storedTopPaddingDp = nextStoredTopPadding,
            editedTopPaddingDp = topPaddingRange.clamp(nextStoredTopPadding),
            scaleGeometry = scaleGeometry,
            horizontalOffsetRange = horizontalOffsetRange,
            topPaddingRange = topPaddingRange,
            pendingSizeScale = nextPendingSizeScale,
            pendingHorizontalOffsetDp = nextPendingHorizontalOffset,
            pendingTopPaddingDp = nextPendingTopPadding,
        )
    }

    fun beginSizeDrag(): ClockEditModel = copy(dragTarget = ClockEditDragTarget.Size)

    fun beginPositionDrag(): ClockEditModel = copy(dragTarget = ClockEditDragTarget.Position)

    fun cancelDrag(): ClockEditModel {
        return when (dragTarget) {
            ClockEditDragTarget.Size -> restoreSizeDrag()
            ClockEditDragTarget.Position -> copy(
                editedHorizontalOffsetDp = horizontalOffsetRange.clamp(storedHorizontalOffsetDp),
                editedTopPaddingDp = topPaddingRange.clamp(storedTopPaddingDp),
                dragTarget = ClockEditDragTarget.None,
            )
            ClockEditDragTarget.None -> this
        }
    }

    fun commitDrag(): ClockEditModel {
        return when (dragTarget) {
            ClockEditDragTarget.Size -> copy(
                storedSizeScale = sizeScaleOverride,
                editedSizeScale = sizeScaleOverride,
                storedHorizontalOffsetDp = horizontalOffsetDp,
                editedHorizontalOffsetDp = horizontalOffsetDp,
                storedTopPaddingDp = topPaddingDp,
                editedTopPaddingDp = topPaddingDp,
                dragTarget = ClockEditDragTarget.None,
                pendingSizeScale = sizeScaleOverride,
                pendingHorizontalOffsetDp = horizontalOffsetDp,
                pendingTopPaddingDp = topPaddingDp,
            )
            ClockEditDragTarget.Position -> copy(
                storedHorizontalOffsetDp = horizontalOffsetDp,
                editedHorizontalOffsetDp = horizontalOffsetDp,
                storedTopPaddingDp = topPaddingDp,
                editedTopPaddingDp = topPaddingDp,
                dragTarget = ClockEditDragTarget.None,
                pendingHorizontalOffsetDp = horizontalOffsetDp,
                pendingTopPaddingDp = topPaddingDp,
            )
            ClockEditDragTarget.None -> this
        }
    }

    fun resizeBy(
        deltaX: Float,
        deltaY: Float,
        density: Float,
        previewScale: Float,
        corner: ClockResizeCorner,
    ): ClockEditModel {
        val currentScale = scaleGeometry.clampScale(editedSizeScale)
        val widthRate = scaleGeometry.resizeDpPerScale.coerceAtLeast(1f)
        val heightRate = scaleGeometry.resizeHeightDpPerScale
        val horizontalScaleDelta =
            corner.horizontalDirection * deltaX / density / previewScale / widthRate
        val verticalScaleDelta =
            heightRate?.let {
                corner.verticalDirection * deltaY / density / previewScale / it.coerceAtLeast(1f)
            }
        val requestedScaleDelta =
            if (verticalScaleDelta != null && abs(verticalScaleDelta) > abs(horizontalScaleDelta)) {
                verticalScaleDelta
            } else {
                horizontalScaleDelta
            }
        val nextScale = scaleGeometry.clampScale(currentScale + requestedScaleDelta)
        val scaleDelta = nextScale - currentScale
        val frameWidthDelta = scaleDelta * scaleGeometry.resizeDpPerScale
        val nextFrameWidth = scaleGeometry.frameWidthDp + frameWidthDelta
        val nextFrameHeight =
            scaleGeometry.frameHeightDp?.let { frameHeight ->
                if (heightRate != null) {
                    frameHeight + scaleDelta * heightRate
                } else {
                    frameHeight *
                        (nextFrameWidth / scaleGeometry.frameWidthDp.coerceAtLeast(1f))
                }
            }
        val frameHeightDelta =
            scaleGeometry.frameHeightDp?.let { (nextFrameHeight ?: it) - it } ?: 0f
        val nextHorizontalOffsetRange =
            horizontalOffsetRange.resizedBy(frameWidthDelta)
        val draggedStartEdge = if (corner.isLeft) 1f else 0f
        val horizontalOffsetDelta = (0.5f - draggedStartEdge) * frameWidthDelta
        val topPaddingDelta = (if (corner.isTop) -0.5f else 0.5f) * frameHeightDelta
        return copy(
            editedSizeScale = nextScale,
            editedHorizontalOffsetDp = nextHorizontalOffsetRange.clamp(
                editedHorizontalOffsetDp + horizontalOffsetDelta,
            ),
            editedTopPaddingDp = topPaddingRange.clamp(
                editedTopPaddingDp + topPaddingDelta,
            ),
            scaleGeometry = scaleGeometry.copy(
                requestedScale = nextScale,
                frameWidthDp = nextFrameWidth,
                frameHeightDp = nextFrameHeight,
            ),
            horizontalOffsetRange = nextHorizontalOffsetRange,
        )
    }

    fun movePositionBy(
        deltaX: Float,
        deltaY: Float,
        density: Float,
        previewScale: Float,
    ): ClockEditModel {
        return copy(
            editedHorizontalOffsetDp = horizontalOffsetRange.clamp(
                editedHorizontalOffsetDp + deltaX / density / previewScale,
            ),
            editedTopPaddingDp = topPaddingRange.clamp(
                editedTopPaddingDp + deltaY / density / previewScale,
            ),
        )
    }

    fun snapHorizontalPosition(): ClockEditModel {
        val offset = horizontalOffsetDp
        if (abs(offset) > AxionClockLimits.HORIZONTAL_CENTER_SNAP_THRESHOLD_DP) return this
        return copy(editedHorizontalOffsetDp = horizontalOffsetRange.clamp(0f))
    }

    fun resetSize(): ClockEditModel = copy(
        storedSizeScale = scaleGeometry.clampScale(1f),
        editedSizeScale = scaleGeometry.clampScale(1f),
        dragTarget = ClockEditDragTarget.None,
        pendingSizeScale = scaleGeometry.clampScale(1f),
    )

    fun resetPosition(): ClockEditModel = copy(
        storedHorizontalOffsetDp = horizontalOffsetRange.clamp(0f),
        editedHorizontalOffsetDp = horizontalOffsetRange.clamp(0f),
        storedTopPaddingDp = topPaddingRange.clamp(0f),
        editedTopPaddingDp = topPaddingRange.clamp(0f),
        dragTarget = ClockEditDragTarget.None,
        pendingHorizontalOffsetDp = horizontalOffsetRange.clamp(0f),
        pendingTopPaddingDp = topPaddingRange.clamp(0f),
    )

    private fun restoreSizeDrag(): ClockEditModel {
        val currentScale = scaleGeometry.clampScale(editedSizeScale)
        val restoredScale = scaleGeometry.clampScale(storedSizeScale)
        val scaleDelta = restoredScale - currentScale
        val frameWidthDelta = scaleDelta * scaleGeometry.resizeDpPerScale
        val restoredFrameHeight =
            scaleGeometry.frameHeightDp?.let { frameHeight ->
                scaleGeometry.resizeHeightDpPerScale?.let { frameHeight + scaleDelta * it }
                    ?: frameHeight *
                        ((scaleGeometry.frameWidthDp + frameWidthDelta) /
                            scaleGeometry.frameWidthDp.coerceAtLeast(1f))
            }
        val restoredOffsetRange = horizontalOffsetRange.resizedBy(frameWidthDelta)
        return copy(
            editedSizeScale = restoredScale,
            editedHorizontalOffsetDp = restoredOffsetRange.clamp(storedHorizontalOffsetDp),
            editedTopPaddingDp = topPaddingRange.clamp(storedTopPaddingDp),
            scaleGeometry = scaleGeometry.copy(
                requestedScale = restoredScale,
                frameWidthDp = scaleGeometry.frameWidthDp + frameWidthDelta,
                frameHeightDp = restoredFrameHeight,
            ),
            horizontalOffsetRange = restoredOffsetRange,
            dragTarget = ClockEditDragTarget.None,
        )
    }

    companion object {
        fun from(
            storedSizeScale: Float,
            storedHorizontalOffsetDp: Float,
            storedTopPaddingDp: Float,
            scaleGeometry: ClockEditScaleGeometry =
                ClockEditScaleGeometry.default(
                    availableWidthDp = 0f,
                    requestedScale = storedSizeScale,
                    scaleRange = AxionClockLimits.sizeScaleRange,
                ),
            horizontalOffsetRange: ClockHorizontalOffsetRange = ClockHorizontalOffsetRange.Zero,
            topPaddingRange: ClockTopPaddingRange =
                ClockTopPaddingRange(
                    CLOCK_EDIT_TOP_PADDING_MIN_DP,
                    AxionClockLimits.TOP_PADDING_MAX_DP,
                ),
        ): ClockEditModel {
            return ClockEditModel(
                storedSizeScale = storedSizeScale,
                editedSizeScale = scaleGeometry.clampScale(storedSizeScale),
                storedHorizontalOffsetDp = storedHorizontalOffsetDp,
                editedHorizontalOffsetDp = horizontalOffsetRange.clamp(storedHorizontalOffsetDp),
                storedTopPaddingDp = storedTopPaddingDp,
                editedTopPaddingDp = topPaddingRange.clamp(storedTopPaddingDp),
                scaleGeometry = scaleGeometry,
                horizontalOffsetRange = horizontalOffsetRange,
                topPaddingRange = topPaddingRange,
            )
        }
    }
}

internal data class ClockEditFrameBounds(
    val widthDp: Float,
    val heightDp: Float?,
    val centerYFraction: Float,
) {
    fun widthIn(availableWidthDp: Float): Float {
        if (availableWidthDp <= 0f) return widthDp
        return widthDp.coerceIn(0f, availableWidthDp)
    }

    fun heightIn(availableHeightDp: Float): Float {
        if (availableHeightDp <= 0f) return heightDp?.coerceAtLeast(0f) ?: 0f
        return heightDp?.coerceIn(0f, availableHeightDp) ?: availableHeightDp
    }

    fun startOffsetDp(
        availableWidthDp: Float,
        horizontalOffsetDp: Float,
    ): Float {
        val width = widthIn(availableWidthDp)
        val centeredStart = (availableWidthDp - width) / 2f
        return (centeredStart + horizontalOffsetDp)
            .coerceIn(0f, (availableWidthDp - width).coerceAtLeast(0f))
    }

    fun endOffsetDp(
        availableWidthDp: Float,
        horizontalOffsetDp: Float,
    ): Float =
        startOffsetDp(availableWidthDp, horizontalOffsetDp) +
            widthIn(availableWidthDp)

    fun topOffsetDp(availableHeightDp: Float): Float {
        val height = heightIn(availableHeightDp)
        if (availableHeightDp <= 0f) return 0f
        return (availableHeightDp * centerYFraction - height / 2f)
            .coerceIn(0f, (availableHeightDp - height).coerceAtLeast(0f))
    }

    fun bottomOffsetDp(availableHeightDp: Float): Float =
        topOffsetDp(availableHeightDp) + heightIn(availableHeightDp)

    companion object {
        fun from(geometry: ClockEditScaleGeometry, availableWidthDp: Float): ClockEditFrameBounds =
            ClockEditFrameBounds(
                widthDp = geometry.frameWidthIn(availableWidthDp),
                heightDp = geometry.frameHeightDp,
                centerYFraction = geometry.frameCenterYFraction,
            )
    }
}

internal data class ClockHorizontalOffsetRange(
    val min: Float,
    val max: Float,
) {
    fun clamp(value: Float): Float = value.coerceIn(min, max)

    fun resizedBy(widthDeltaDp: Float): ClockHorizontalOffsetRange {
        val limit = (max - widthDeltaDp / 2f).coerceAtLeast(0f)
        return ClockHorizontalOffsetRange(-limit, limit)
    }

    companion object {
        val Zero = ClockHorizontalOffsetRange(0f, 0f)

        fun from(
            geometry: ClockEditScaleGeometry,
            availableWidthDp: Float,
        ): ClockHorizontalOffsetRange {
            val frameWidth = geometry.frameWidthIn(availableWidthDp)
            val freeWidth = (availableWidthDp - frameWidth).coerceAtLeast(0f)
            val centeredStart = freeWidth / 2f
            return ClockHorizontalOffsetRange(
                -centeredStart,
                centeredStart,
            )
        }
    }
}

internal enum class ClockResizeCorner(val arcStartAngle: Float) {
    TopLeft(180f),
    TopRight(270f),
    BottomLeft(90f),
    BottomRight(0f);

    val isLeft: Boolean
        get() = this == TopLeft || this == BottomLeft

    val isTop: Boolean
        get() = this == TopLeft || this == TopRight

    val horizontalDirection: Float
        get() = if (isLeft) -1f else 1f

    val verticalDirection: Float
        get() = if (isTop) -1f else 1f

    fun handleX(
        frameStart: Float,
        frameEnd: Float,
        handleSize: Float,
        handleOverflow: Float,
    ): Float =
        if (isLeft) {
            frameStart - handleOverflow
        } else {
            frameEnd - handleSize + handleOverflow
        }

    fun handleY(
        frameTop: Float,
        frameBottom: Float,
        handleSize: Float,
        handleOverflow: Float,
    ): Float =
        if (isTop) {
            frameTop - handleOverflow
        } else {
            frameBottom - handleSize + handleOverflow
        }
}

internal enum class ClockEditDragTarget {
    None,
    Size,
    Position,
}

private fun Float.approximatelyEquals(other: Float): Boolean = abs(this - other) < 0.001f

internal object AxionClockLimits {
    const val TOP_PADDING_MIN_DP = -24f
    const val TOP_PADDING_MAX_DP = 240f
    const val HORIZONTAL_CENTER_SNAP_THRESHOLD_DP = 12f
    val sizeScaleRange = ClockSizeScaleRange(0.5f, 1f, 1.35f, 1.35f)
}
