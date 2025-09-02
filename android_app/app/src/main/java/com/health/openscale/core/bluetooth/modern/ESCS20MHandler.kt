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
package com.health.openscale.core.bluetooth.modern

import androidx.compose.ui.text.toLowerCase
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.YunmaiLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * Handler for ES-CS20M scales (Yunmai lineage).
 *
 * Device uses a vendor service (0x1A10) and two characteristics:
 *  - 0x2A11: bidirectional control (also used to send "start measurement" & "delete history" magic)
 *  - 0x2A10: notifications with result frames
 *
 * The device streams multiple frames during a session. We buffer all frames and
 * only parse/publish when a STOP message arrives, mirroring the legacy behaviour:
 *  - Message IDs:
 *      0x11 -> start/stop response (contains measurement-type)
 *      0x14 -> weight response (may embed resistance if present)
 *      0x15 -> extended response (resistance)
 */
class ESCS20mHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "ESCS20mHandler"

        // Message IDs
        private const val MSG_START_STOP_RESP: Int = 0x11
        private const val MSG_WEIGHT_RESP:     Int = 0x14
        private const val MSG_EXTENDED_RESP:   Int = 0x15

        // Measurement types appearing at byte[10] in MSG_START_STOP_RESP
        private const val TYPE_START_WEIGHT_ONLY: Int = 0x18
        private const val TYPE_STOP_WEIGHT_ONLY:  Int = 0x17
        private const val TYPE_START_ALL:         Int = 0x19
        private const val TYPE_STOP_ALL:          Int = 0x18
    }

    // Vendor service / characteristics (16-bit base UUIDs)
    private val SVC_MAIN      = uuid16(0x1A10)
    private val CHR_CUR_TIME  = uuid16(0x2A11) // control / command mailbox
    private val CHR_RESULTS   = uuid16(0x2A10) // notifications with results

    // "Magic" commands from the legacy driver
    private val MAGIC_START_MEAS = byteArrayOf(
        0x55, 0xAA.toByte(), 0x90.toByte(), 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x94.toByte()
    )
    private val MAGIC_DELETE_HISTORY = byteArrayOf(
        0x55, 0xAA.toByte(), 0x95.toByte(), 0x00, 0x01, 0x01, 0x96.toByte()
    )

    // Session buffer and accumulator (mirrors legacy approach)
    private val rawFrames = mutableListOf<ByteArray>()
    private val acc = ScaleMeasurement()

    /**
     * Identify the device by advertised service 0x1A10 (and optionally by name).
     */
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        val hasSvc = device.serviceUuids.any { it == SVC_MAIN }
        val looksEscs20m = hasSvc || name.contains("ES-CS20M".lowercase())

        if (!looksEscs20m) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,   // we compute composition from resistance
            DeviceCapability.LIVE_WEIGHT_STREAM  // device streams multiple frames during session
        )
        return DeviceSupport(
            displayName = "ES-CS20M",
            capabilities = caps,
            implemented  = caps,
            linkMode     = LinkMode.CONNECT_GATT
        )
    }

    /**
     * Enable notifications and send the vendor commands to start measuring
     * and clear history (like the legacy Java flow).
     */
    override fun onConnected(user: ScaleUser) {
        rawFrames.clear()
        resetAccumulator()

        // Subscribe first
        setNotifyOn(SVC_MAIN, CHR_CUR_TIME)
        setNotifyOn(SVC_MAIN, CHR_RESULTS)

        // Kick off a session
        writeTo(SVC_MAIN, CHR_CUR_TIME, MAGIC_START_MEAS)
        writeTo(SVC_MAIN, CHR_CUR_TIME, MAGIC_DELETE_HISTORY)

        LogManager.i(TAG, "Session started; waiting for frames…")
    }

    /**
     * Buffer all frames; only when we see a START/STOP response do we act.
     * We then parse and publish when STOP is detected.
     */
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_RESULTS && characteristic != CHR_CUR_TIME) {
            LogManager.d(TAG, "Notify from unrelated chr=$characteristic len=${data.size}")
            return
        }

        // Buffer every frame
        rawFrames += data.copyOf()

        // Guard: need at least 3 bytes for msgId at [2]
        if (data.size < 3) return
        val msgId = data[2].toInt() and 0xFF

        // We only take action on 0x11 frames (start/stop) to keep legacy sequencing
        if (msgId != MSG_START_STOP_RESP) return

        // Guard: need at least 11 bytes for measurement type at [10]
        if (data.size < 11) return
        val measurementType = data[10].toInt() and 0xFF

        when (measurementType) {
            TYPE_START_WEIGHT_ONLY, TYPE_START_ALL -> {
                // Nothing to do; legacy resumed its SM here. We already stream.
                LogManager.d(TAG, "Measurement started (type=$measurementType)")
            }

            TYPE_STOP_WEIGHT_ONLY, TYPE_STOP_ALL -> {
                LogManager.d(TAG, "Measurement stopped (type=$measurementType) → parse & publish")
                parseAllFramesAndPublish(user)
            }
        }
    }

    override fun onDisconnected() {
        // No queued publish on disconnect for this device; we only publish once on STOP.
        rawFrames.clear()
        resetAccumulator()
    }

    // -------------------------------------------------------------------------
    // Parsing (mirrors legacy Java: parse on STOP, iterate buffered frames)
    // -------------------------------------------------------------------------

    private fun parseAllFramesAndPublish(user: ScaleUser) {
        if (rawFrames.isEmpty()) {
            LogManager.w(TAG, "No frames buffered; nothing to publish.")
            return
        }

        // Create Yunmai calculator with user info
        val sex = if (user.gender.isMale()) 1 else 0
        val yunmai = YunmaiLib(sex, user.bodyHeight, user.activityLevel)

        // Sort frames by msgId (legacy sorted by msg[2]); keeps behaviour consistent
        val frames = rawFrames.sortedBy { (it.getOrNull(2)?.toInt() ?: 0) and 0xFF }

        LogManager.d(TAG, "Parsing ${frames.size} frames…")

        // Run through all frames; weight and resistance may arrive in any order
        frames.forEach { parseFrame(it, yunmai, user) }

        // Only publish meaningful data
        if (acc.weight > 0f) {
            acc.userId = user.id
            if (acc.dateTime == null) acc.dateTime = Date()
            publish(snapshot(acc))
        } else {
            LogManager.w(TAG, "No stable weight decoded; skip publishing.")
        }

        // Prepare for a fresh session
        rawFrames.clear()
        resetAccumulator()
    }

    private fun snapshot(m: ScaleMeasurement) = ScaleMeasurement().apply {
        userId      = m.userId
        dateTime    = m.dateTime
        weight      = m.weight
        fat         = m.fat
        muscle      = m.muscle
        water       = m.water
        bone        = m.bone
        lbm         = m.lbm
        visceralFat = m.visceralFat
    }

    private fun parseFrame(frame: ByteArray, calc: YunmaiLib, user: ScaleUser) {
        if (frame.size < 3) return
        when ((frame[2].toInt() and 0xFF)) {
            MSG_WEIGHT_RESP   -> parseWeightFrame(frame, calc, user)
            MSG_EXTENDED_RESP -> parseExtendedFrame(frame, calc, user)
        }
    }

    /**
     * Weight frame (0x14).
     * - Stable flag at [5] != 0
     * - Weight at [8..9] (big-endian, 0.01 kg units)
     * - Optional embedded resistance at [10..11] if separate 0x15 not present
     */
    private fun parseWeightFrame(msg: ByteArray, calc: YunmaiLib, user: ScaleUser) {
        if (msg.size < 12) return

        val stable = (msg[5].toInt() and 0xFF) != 0
        if (!stable) return

        acc.weight = u16be(msg, 8) / 100.0f

        // Embedded extended data?
        val hasEmbedded = (msg[10].toInt() and 0xFF) != 0 || (msg[11].toInt() and 0xFF) != 0
        val hasSeparateExt = rawFrames.any { it.size >= 3 && ((it[2].toInt() and 0xFF) == MSG_EXTENDED_RESP) }

        if (hasEmbedded && !hasSeparateExt) {
            val resistance = u16be(msg, 10)
            applyExtended(resistance, calc, user)
        }
    }

    /**
     * Extended frame (0x15): resistance at [9..10] (big-endian).
     */
    private fun parseExtendedFrame(msg: ByteArray, calc: YunmaiLib, user: ScaleUser) {
        if (msg.size < 11) return
        val resistance = u16be(msg, 9)
        applyExtended(resistance, calc, user)
    }

    /**
     * Compute body composition using YunmaiLib and write into accumulator.
     * Requires a valid weight to be already present.
     */
    private fun applyExtended(resistance: Int, calc: YunmaiLib, user: ScaleUser) {
        val w = acc.weight
        if (w <= 0f) {
            LogManager.d(TAG, "Weight not set yet; skip extended calculation.")
            return
        }

        val fat = calc.getFat(user.age, w, resistance)
        val musclePct = calc.getMuscle(fat) / w * 100.0f
        val waterPct = calc.getWater(fat)
        val bone = calc.getBoneMass(musclePct, w)
        val lbm = calc.getLeanBodyMass(w, fat)
        val visceral = calc.getVisceralFat(fat, user.age)

        acc.fat = fat
        acc.muscle = musclePct
        acc.water = waterPct
        acc.bone = bone
        acc.lbm = lbm
        acc.visceralFat = visceral
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private fun u16be(b: ByteArray, off: Int): Int {
        if (off + 1 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun resetAccumulator() {
        acc.userId = -1
        acc.dateTime = null
        acc.weight = 0f
        acc.fat = 0f
        acc.muscle = 0f
        acc.water = 0f
        acc.bone = 0f
        acc.lbm = 0f
        acc.visceralFat = 0f
    }
}
