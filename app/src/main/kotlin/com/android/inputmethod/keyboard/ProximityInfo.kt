package com.android.inputmethod.keyboard

/**
 * JNI bridge to LatinIME's native ProximityInfo.
 * Stores keyboard layout geometry for fast key-distance calculations.
 *
 * Constructor parameters match AOSP's LatinIME ProximityInfo JNI signature.
 * The native object is created and managed via the companion's loadLibrary call.
 */
class ProximityInfo(
    gridWidth: Int,
    gridHeight: Int,
    minX: Int,
    maxX: Int,
    minY: Int,
    maxY: Int,
    mostCommonKeyWidth: Int,
    mostCommonKeyHeight: Int,
    keyXCoordinates: IntArray,
    keyYCoordinates: IntArray,
    keyWidths: IntArray,
    keyHeights: IntArray,
    keyCharCodes: IntArray,
    sweetSpotCenterXs: FloatArray,
    sweetSpotCenterYs: FloatArray,
    sweetSpotRadii: FloatArray,
) {
    // Native peer — managed by the C++ constructor/destructor
    private var nativePtr: Long = 0

    init {
        nativeInit(
            gridWidth, gridHeight, minX, maxX, minY, maxY,
            mostCommonKeyWidth, mostCommonKeyHeight,
            keyXCoordinates, keyYCoordinates,
            keyWidths, keyHeights,
            keyCharCodes,
            sweetSpotCenterXs, sweetSpotCenterYs, sweetSpotRadii,
        )
    }

    /**
     * Returns the squared distance from (x, y) to the nearest point on key [code].
     * Used for gesture scoring.
     */
    external fun getSquaredDistance(code: Int, x: Float, y: Float): Float

    /**
     * Returns the key code closest to the given point, or 0 if none within threshold.
     */
    external fun getNearestKey(x: Float, y: Float): Int

    private external fun nativeInit(
        gridWidth: Int, gridHeight: Int, minX: Int, maxX: Int, minY: Int, maxY: Int,
        mostCommonKeyWidth: Int, mostCommonKeyHeight: Int,
        keyXCoordinates: IntArray, keyYCoordinates: IntArray,
        keyWidths: IntArray, keyHeights: IntArray,
        keyCharCodes: IntArray,
        sweetSpotCenterXs: FloatArray, sweetSpotCenterYs: FloatArray, sweetSpotRadii: FloatArray,
    )

    @Suppress("DEPRECATION")
    protected fun finalize() {
        nativeFinalize()
    }

    private external fun nativeFinalize()

    companion object {
        init {
            System.loadLibrary("jni_latinimegoogle")
        }
    }
}
