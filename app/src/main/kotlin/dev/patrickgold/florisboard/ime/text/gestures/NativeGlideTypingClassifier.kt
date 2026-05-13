package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import android.util.Log
import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import java.io.File

/**
 * Native Glide Typing Classifier using the bundled Google LatinIME JNI library.
 *
 * Uses ProximityInfo for fast key-distance calculations and BinaryDictionary
 * for word-frequency lookups. If the native library fails to load or initialize,
 * the statistical classifier takes over.
 */
class NativeGlideTypingClassifier(private val context: Context) : GlideTypingClassifier {

    private var isInitialized = false
    private var activeSubtype: Subtype? = null
    private var activeKeys: List<TextKey> = emptyList()
    private var proximityInfo: ProximityInfo? = null
    private var binaryDictionary: BinaryDictionary? = null

    // Gesture path buffer
    private val gesturePoints = mutableListOf<GlideTypingGesture.Detector.Position>()

    override fun isReady(): Boolean = isInitialized && activeKeys.isNotEmpty()

    override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
        gesturePoints.add(position)
    }

    override fun setLayout(keyViews: List<TextKey>, subtype: Subtype) {
        activeKeys = keyViews
        activeSubtype = subtype
        initFromLayout()
    }

    override fun setWordData(subtype: Subtype) {
        activeSubtype = subtype
        loadDictionary(subtype)
    }

    override fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData) {
        gesturePoints.clear()
        for (pos in pointerData.positions) {
            gesturePoints.add(pos)
        }
    }

    override fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<CharSequence> {
        if (!isInitialized || gesturePoints.isEmpty()) return emptyList()

        val proximity = proximityInfo ?: return emptyList()

        // Map each gesture point to the nearest key code using native ProximityInfo
        val keySequence = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        for (point in gesturePoints) {
            val keyCode = proximity.getNearestKey(point.x, point.y)
            if (keyCode != 0 && seen.add(keyCode)) {
                keySequence.add(keyCode)
            }
        }

        if (keySequence.isEmpty()) return emptyList()

        // Use native BinaryDictionary for word lookups based on the key sequence
        val candidates = try {
            val nativeResults = binaryDictionary?.getSuggestions(
                keySequence.toIntArray(),
                maxSuggestionCount,
            ) ?: emptyArray()

            nativeResults.toList()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native dictionary lookup failed", e)
            emptyList()
        }

        return candidates
    }

    override fun clear() {
        gesturePoints.clear()
    }

    override fun close() {
        binaryDictionary?.close()
        binaryDictionary = null
        proximityInfo = null
        isInitialized = false
    }

    private fun initFromLayout() {
        val keys = activeKeys
        val subtype = activeSubtype
        if (keys.isEmpty() || subtype == null) {
            isInitialized = false
            return
        }

        val n = keys.size
        val keyCodes = IntArray(n)
        val keyXs = IntArray(n)
        val keyYs = IntArray(n)
        val keyWidths = IntArray(n)
        val keyHeights = IntArray(n)
        val sweetSpotXs = FloatArray(n)
        val sweetSpotYs = FloatArray(n)
        val sweetSpotRadii = FloatArray(n)

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var commonWidth = 0
        var commonHeight = 0

        for ((i, key) in keys.withIndex()) {
            val vb = key.visibleBounds
            val code = (key.data as? dev.patrickgold.florisboard.ime.keyboard.KeyData)?.code ?: 0
            keyCodes[i] = code
            keyXs[i] = vb.left.toInt()
            keyYs[i] = vb.top.toInt()
            keyWidths[i] = vb.width.toInt()
            keyHeights[i] = vb.height.toInt()

            sweetSpotXs[i] = (vb.left + vb.right) / 2f
            sweetSpotYs[i] = (vb.top + vb.bottom) / 2f
            sweetSpotRadii[i] = vb.width / 4.0f

            if (vb.left < minX) minX = vb.left.toInt()
            if (vb.right > maxX) maxX = vb.right.toInt()
            if (vb.top < minY) minY = vb.top.toInt()
            if (vb.bottom > maxY) maxY = vb.bottom.toInt()
            if (vb.width.toInt() > commonWidth) commonWidth = vb.width.toInt()
            if (vb.height.toInt() > commonHeight) commonHeight = vb.height.toInt()
        }

        // Guard: if no key bounds were populated (e.g. pre-measurement layout), bail.
        if (minX == Int.MAX_VALUE || maxX == Int.MIN_VALUE ||
            minY == Int.MAX_VALUE || maxY == Int.MIN_VALUE) {
            isInitialized = false
            return
        }

        val gridWidth = maxX - minX
        val gridHeight = maxY - minY
        if (gridWidth <= 0 || gridHeight <= 0 || commonWidth <= 0 || commonHeight <= 0) {
            Log.w(TAG, "Skipping native init: invalid grid dimensions (${gridWidth}x${gridHeight})")
            isInitialized = false
            return
        }

        try {
            proximityInfo = ProximityInfo(
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                minX = minX, maxX = maxX, minY = minY, maxY = maxY,
                mostCommonKeyWidth = commonWidth,
                mostCommonKeyHeight = commonHeight,
                keyXCoordinates = keyXs,
                keyYCoordinates = keyYs,
                keyWidths = keyWidths,
                keyHeights = keyHeights,
                keyCharCodes = keyCodes,
                sweetSpotCenterXs = sweetSpotXs,
                sweetSpotCenterYs = sweetSpotYs,
                sweetSpotRadii = sweetSpotRadii,
            )
            isInitialized = true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native proximity init failed", e)
            isInitialized = false
        }
    }

    private fun loadDictionary(subtype: Subtype) {
        try {
            binaryDictionary?.close()
            binaryDictionary = null

            val candidatePaths = listOfNotNull(
                File(context.filesDir, "dicts/${subtype.primaryLocale.languageTag()}.dict"),
                File(context.filesDir, "dicts/main_en.dict"),
                File(context.filesDir, "dicts/main.dict"),
            )

            val dictFile = candidatePaths.firstOrNull { it.exists() && it.length() > 0 }
            if (dictFile != null) {
                binaryDictionary = BinaryDictionary(dictFile.absolutePath)
                Log.i(TAG, "Loaded native dictionary: ${dictFile.name}")
            } else {
                Log.i(TAG, "No compiled .dict found; falling back to statistical classifier")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dictionary load failed, falling back to statistical classifier", e)
            binaryDictionary = null
        }
    }

    companion object {
        private const val TAG = "NativeGlide"
    }
}
