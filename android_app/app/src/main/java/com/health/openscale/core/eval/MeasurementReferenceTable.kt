/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.eval

import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.GenderType

/**
 * Reference tables and strategies used to evaluate measurements.
 * Uses existing MeasurementEvaluationResult (in this package) and EvaluationState (in core.data).
 */
object MeasurementReferenceTable {

    // ----- Public strategy types -----

    /** Contract for evaluating a value against reference bounds. */
    interface EvaluationStrategy {
        fun evaluate(value: Float, age: Int): MeasurementEvaluationResult
    }

    /** Age-specific reference range. */
    data class AgeBand(
        val ageMin: Int,
        val ageMax: Int,
        val low: Float,
        val high: Float
    )

    /** Strategy selecting bounds by age band. */
    class AgeBandStrategy(private val bands: List<AgeBand>) : EvaluationStrategy {
        override fun evaluate(value: Float, age: Int): MeasurementEvaluationResult {
            val band = bands.firstOrNull { age in it.ageMin..it.ageMax }
                ?: return MeasurementEvaluationResult(value, -1f, -1f, EvaluationState.UNDEFINED)

            val state = when {
                value < band.low  -> EvaluationState.LOW
                value > band.high -> EvaluationState.HIGH
                else              -> EvaluationState.NORMAL
            }
            return MeasurementEvaluationResult(value, band.low, band.high, state)
        }
    }

    /** Strategy computing bounds via formulas. */
    class FormulaStrategy(
        private val low: () -> Float,
        private val high: () -> Float
    ) : EvaluationStrategy {
        override fun evaluate(value: Float, age: Int): MeasurementEvaluationResult {
            val lo = low()
            val hi = high()
            val state = when {
                value < lo -> EvaluationState.LOW
                value > hi -> EvaluationState.HIGH
                else       -> EvaluationState.NORMAL
            }
            return MeasurementEvaluationResult(value, lo, hi, state)
        }
    }

    // ----- Reference tables (static) -----

    // Body Fat %
    val fatMale = AgeBandStrategy(
        listOf(
            AgeBand(10, 14, 11f, 16f),
            AgeBand(15, 19, 12f, 17f),
            AgeBand(20, 29, 13f, 18f),
            AgeBand(30, 39, 14f, 19f),
            AgeBand(40, 49, 15f, 20f),
            AgeBand(50, 59, 16f, 21f),
            AgeBand(60, 69, 17f, 22f),
            AgeBand(70, 1000, 18f, 23f),
        )
    )
    val fatFemale = AgeBandStrategy(
        listOf(
            AgeBand(10, 14, 16f, 21f),
            AgeBand(15, 19, 17f, 22f),
            AgeBand(20, 29, 18f, 23f),
            AgeBand(30, 39, 19f, 24f),
            AgeBand(40, 49, 20f, 25f),
            AgeBand(50, 59, 21f, 26f),
            AgeBand(60, 69, 22f, 27f),
            AgeBand(70, 1000, 23f, 28f),
        )
    )

    // Body Water %
    val waterMale   = AgeBandStrategy(listOf(AgeBand(10, 1000, 50f, 65f)))
    val waterFemale = AgeBandStrategy(listOf(AgeBand(10, 1000, 45f, 60f)))

    // Muscle Mass %
    val muscleMale = AgeBandStrategy(
        listOf(
            AgeBand(18, 29, 37.9f, 46.7f),
            AgeBand(30, 39, 34.1f, 44.1f),
            AgeBand(40, 49, 33.1f, 41.1f),
            AgeBand(50, 59, 31.7f, 38.5f),
            AgeBand(60, 69, 29.9f, 37.7f),
            AgeBand(70, 1000, 28.7f, 43.3f),
        )
    )
    val muscleFemale = AgeBandStrategy(
        listOf(
            AgeBand(18, 29, 28.4f, 39.8f),
            AgeBand(30, 39, 25.0f, 36.2f),
            AgeBand(40, 49, 24.2f, 34.2f),
            AgeBand(50, 59, 24.7f, 33.5f),
            AgeBand(60, 69, 22.7f, 31.9f),
            AgeBand(70, 1000, 25.5f, 34.9f),
        )
    )

    // BMI
    val bmiMale = AgeBandStrategy(
        listOf(
            AgeBand(16, 24, 20f, 25f),
            AgeBand(25, 34, 21f, 26f),
            AgeBand(35, 44, 22f, 27f),
            AgeBand(45, 54, 23f, 28f),
            AgeBand(55, 64, 24f, 29f),
            AgeBand(65, 90, 25f, 30f),
        )
    )
    val bmiFemale = AgeBandStrategy(
        listOf(
            AgeBand(16, 24, 19f, 24f),
            AgeBand(25, 34, 20f, 25f),
            AgeBand(35, 44, 21f, 26f),
            AgeBand(45, 54, 22f, 27f),
            AgeBand(55, 64, 23f, 28f),
            AgeBand(65, 90, 24f, 29f),
        )
    )

    // WHtR
    val whtr = AgeBandStrategy(
        listOf(
            AgeBand(15, 40, 0.4f, 0.5f),
            AgeBand(41, 42, 0.4f, 0.51f),
            AgeBand(43, 44, 0.4f, 0.53f),
            AgeBand(45, 46, 0.4f, 0.55f),
            AgeBand(47, 48, 0.4f, 0.57f),
            AgeBand(49, 50, 0.4f, 0.59f),
            AgeBand(51, 90, 0.4f, 0.6f),
        )
    )

    // WHR
    val whrMale   = AgeBandStrategy(listOf(AgeBand(18, 90, 0.8f, 0.9f)))
    val whrFemale = AgeBandStrategy(listOf(AgeBand(18, 90, 0.7f, 0.8f)))

    // Visceral Fat (index)
    val visceralFat = AgeBandStrategy(listOf(AgeBand(18, 90, -1f, 12f)))

    // Lean Body Mass (kg) – Italian reference P25–P75 (DOI: 10.26355/eurrev_201811_16415)
    val lbmMale = AgeBandStrategy(
        listOf(
            AgeBand(18, 24, 52.90f, 62.70f),
            AgeBand(25, 34, 53.10f, 64.80f),
            AgeBand(35, 44, 53.83f, 65.60f),
            AgeBand(45, 54, 53.60f, 65.20f),
            AgeBand(55, 64, 51.63f, 61.10f),
            AgeBand(65, 74, 48.48f, 58.20f),
            AgeBand(75, 88, 43.35f, 60.23f),
        )
    )
    val lbmFemale = AgeBandStrategy(
        listOf(
            AgeBand(18, 24, 34.30f, 41.90f),
            AgeBand(25, 34, 35.20f, 43.70f),
            AgeBand(35, 44, 35.60f, 47.10f),
            AgeBand(45, 54, 36.10f, 44.90f),
            AgeBand(55, 64, 35.15f, 43.95f),
            AgeBand(65, 74, 34.10f, 42.05f),
            AgeBand(75, 88, 33.80f, 40.40f),
        )
    )

    // ----- Dynamic strategies -----

    /** Waist circumference (cm) thresholds by gender. */
    fun waistStrategyCm(gender: GenderType): EvaluationStrategy = when (gender) {
        GenderType.MALE   -> AgeBandStrategy(listOf(AgeBand(18, 90, -1f, 94f)))
        GenderType.FEMALE -> AgeBandStrategy(listOf(AgeBand(18, 90, -1f, 80f)))
    }

    /** Target weight bounds computed from height (cm) and BMI ranges by gender. */
    fun targetWeightStrategy(heightCm: Int, gender: GenderType): EvaluationStrategy {
        val h2 = (heightCm / 100f) * (heightCm / 100f)
        val (bmiLo, bmiHi) = if (gender == GenderType.MALE) 20f to 25f else 19f to 24f
        return FormulaStrategy(
            low  = { h2 * bmiLo },
            high = { h2 * bmiHi }
        )
    }
}
