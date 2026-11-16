package de.tontaube.core

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Vorpuffer: Wie viele Sekunden Audio vorliegen müssen, bevor die Wiedergabe startet bzw. nach einem
 * Stocken weiterläuft. Ist der Server langsamer als Echtzeit, lässt sich Stocken nicht ganz vermeiden –
 * aber ein größerer Puffer macht aus vielen kurzen Aussetzern wenige seltene Pausen.
 */
object BufferPolicy {
    /** Einstellung „automatisch“ (sonst: feste Sekunden). */
    const val AUTO = 0

    /** Auswahl für die manuelle Einstellung (Sekunden). */
    val manualOptions = listOf(30, 60, 120, 300, 600)

    /** Automatik: so lange (Sekunden) soll nach dem Puffern ohne Unterbrechung vorgelesen werden. */
    const val LISTEN_TARGET_S = 300

    const val MIN_AUTO_S = 15
    const val MAX_S = 600

    /** Grobe Sprechgeschwindigkeit (Zeichen je Sekunde bei Tempo 1), um Abschnitte in Sekunden umzurechnen. */
    const val CHARS_PER_SECOND = 14.0

    /**
     * Ziel-Puffer in Sekunden. [realtimeFactor] = erzeugte Audiosekunden je Sekunde Wartezeit, bezogen auf das
     * eingestellte Tempo (1,0 = genau Echtzeit; null = noch nicht gemessen). [start] = erster Start (sonst nach Stocken).
     */
    fun targetSeconds(mode: Int, realtimeFactor: Double?, start: Boolean): Int {
        if (mode > 0) return mode.coerceAtMost(MAX_S)
        val r = realtimeFactor ?: return if (start) 5 else 30
        if (r >= 1.15) return if (start) 3 else 8
        // Netto leert sich der Puffer mit (1 − r) Audiosekunden je Sekunde; er soll LISTEN_TARGET_S reichen.
        val needed = LISTEN_TARGET_S * (1.0 - r).coerceAtLeast(0.0)
        return needed.roundToInt().coerceIn(MIN_AUTO_S, MAX_S)
    }

    /** Wie viele Abschnitte der Player vorhalten muss, damit [targetSeconds] überhaupt gepuffert werden können. */
    fun windowSegments(targetSeconds: Int, averageChars: Int, speed: Float = 1f): Int {
        val secondsPerSegment = averageChars.coerceAtLeast(40) / CHARS_PER_SECOND / speed.coerceIn(0.5f, 3f)
        return ceil((targetSeconds + 90) / secondsPerSegment).toInt().coerceIn(3, 120)
    }

    fun label(mode: Int): String = when {
        mode == AUTO -> "Automatisch"
        mode % 60 == 0 -> "${mode / 60} min"
        else -> "$mode s"
    }

    /** Lesbare Beschreibung eines gemessenen Echtzeitfaktors, z. B. „0,85× Echtzeit“. */
    fun describeFactor(r: Double): String = String.format(java.util.Locale.GERMAN, "%.2f× Echtzeit", r)
}

/**
 * Misst, wie schnell der Server Audio erzeugt (Audiosekunden je Sekunde), als gleitender Mittelwert über die
 * Wartezeit. Werte gibt es erst, wenn genug Zeit beobachtet wurde.
 */
class RateEstimator(private val halfLifeSeconds: Double = 60.0, private val minObservedSeconds: Double = 20.0) {
    private var weightedRate = 0.0
    private var weight = 0.0
    private var observed = 0.0

    /** [audioSeconds] in [wallSeconds] erzeugt. */
    fun add(audioSeconds: Double, wallSeconds: Double) {
        if (wallSeconds <= 0.0 || audioSeconds < 0.0) return
        val decay = Math.pow(0.5, wallSeconds / halfLifeSeconds)
        weightedRate = weightedRate * decay + audioSeconds
        weight = weight * decay + wallSeconds
        observed += wallSeconds
    }

    /** Startwert aus einer früheren Messung (zählt wie [minObservedSeconds] Beobachtung). */
    fun seed(rate: Double) {
        weightedRate = rate * minObservedSeconds
        weight = minObservedSeconds
        observed = minObservedSeconds
    }

    val rate: Double? get() = if (observed >= minObservedSeconds && weight > 0) weightedRate / weight else null
}
