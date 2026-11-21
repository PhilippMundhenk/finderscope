package org.mundhenk.finderscope.core

import kotlin.math.abs

/** How the camera is held, as the camera reports it (D = normal landscape). */
enum class Rotation(val degrees: Int) {
    D(0), R(90), U(180), L(270);

    companion object {
        /** SSDP notice value "ssdp:rotationL" -> L. */
        fun fromNotice(nts: String): Rotation? = entries.firstOrNull { nts.trim().equals("ssdp:rotation${it.name}", ignoreCase = true) }

        /**
         * GetInfomation `ROTATION`: most models count 1..4 = D, L, U, R; the WB850 counts 0..3 = D,
         * R, U, L. Anything else = D.
         */
        fun fromInfo(value: Int, model: String): Rotation =
            if (model.contains("WB850")) when (value) { 1 -> R; 2 -> U; 3 -> L; else -> D }
            else when (value) { 2 -> L; 3 -> U; 4 -> R; else -> D }
    }
}

/** One AF frame the camera reported in focus, as a cell of [AfGrid]. */
data class AfCell(val row: Int, val column: Int)

/**
 * The camera's grid of AF frames: 3×5 on the NX models of 2012, 3×3 on the compacts. The camera
 * answers a focus with tokens "row x camera-column"; on the 3×5 grid the camera's column numbers
 * are not left to right but [columnOrder] (camera column per screen column, unconfirmed).
 */
data class AfGrid(val rows: Int, val columns: Int, val columnOrder: List<Int>) {
    /** "1x3,0x2," -> cells in screen coordinates; unknown or out-of-range tokens are dropped. */
    fun cells(afStatus: String?): List<AfCell> = afStatus.orEmpty().split(',').mapNotNull { token ->
        val parts = token.trim().split('x', 'X')
        val r = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val c = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val screenColumn = columnOrder.indexOf(c)
        if (parts.size != 2 || r !in 0 until rows || screenColumn < 0) null else AfCell(r, screenColumn)
    }

    companion object {
        val COMPACT = AfGrid(3, 3, listOf(0, 1, 2))
        val NX = AfGrid(3, 5, listOf(3, 0, 1, 2, 4))
    }
}

/** What finderscope knows per model beyond the camera's own action list. */
data class CameraModel(val name: String) {
    /** The 2012 interchangeable-lens models: zoom on the lens ring, 3×5 AF, no picture when focus fails. */
    val isNx2012: Boolean get() = NX_2012.any { name.contains(it) }
    val afGrid: AfGrid get() = if (isNx2012) AfGrid.NX else AfGrid.COMPACT
    /** Like on the camera itself: an NX does not take a picture it could not focus. */
    val cancelsShotOnFocusFail: Boolean get() = isNx2012
    val zoomsOverWifi: Boolean get() = !isNx2012 && !name.contains("NX1100")

    companion object {
        val NX_2012 = listOf("NX20", "NX210", "NX1000", "NX1100")

        /** "[Camera]NX1000" / "AP_SSC_NX1000_0-AA:BB:CC" / "NX1000_xx" -> "NX1000". */
        fun parse(text: String): CameraModel {
            val t = text.removePrefix("[Camera]").removePrefix("AP_SSC_").trim()
            return CameraModel(t.substringBefore('_').ifBlank { t })
        }
    }
}

/** GPSINFO for ShotWithGPS: "N<lat·3600>XE<lon·3600>" in whole arcseconds, with the real hemispheres. */
fun gpsInfo(latitude: Double, longitude: Double): String {
    val lat = (abs(latitude) * 3600).toLong()
    val lon = (abs(longitude) * 3600).toLong()
    return "${if (latitude < 0) "S" else "N"}${lat}X${if (longitude < 0) "W" else "E"}$lon"
}

/** Megapixel label for a photo size: 5472×3648 -> "20M", 1920×1080 -> "2.1M". */
fun megapixels(width: Int, height: Int): String {
    val mp = width.toLong() * height / 1_000_000.0
    return if (mp >= 9.5) "${Math.round(mp)}M" else String.format(java.util.Locale.US, "%.1fM", mp)
}
