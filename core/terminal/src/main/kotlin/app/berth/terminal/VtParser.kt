package app.berth.terminal

/** Receives the semantic events produced by [VtParser]. */
interface VtHandler {
    fun print(codePoint: Int)
    fun execute(control: Int)
    fun escDispatch(intermediates: String, final: Char)
    fun csiDispatch(params: CsiParams, intermediates: String, final: Char)
    fun oscDispatch(payload: String)
    fun dcsDispatch(params: CsiParams, intermediates: String, final: Char, payload: String)
}

/**
 * Parameter list of a CSI/DCS sequence. Each parameter may carry colon-separated sub-parameters
 * (used by SGR 38:2::r:g:b and 4:3 underline styles). A value of -1 marks an omitted parameter.
 */
class CsiParams {
    private val values = IntArray(MAX_PARAMS) { OMITTED }
    private val subStart = IntArray(MAX_PARAMS)
    private val subCount = IntArray(MAX_PARAMS)
    private val subValues = IntArray(MAX_SUBPARAMS)
    private var subUsed = 0
    private var currentHasDigits = false
    private var inSub = false
    var size = 0
        private set

    fun clear() {
        values.fill(OMITTED, 0, size.coerceAtLeast(1).coerceAtMost(MAX_PARAMS))
        subCount.fill(0, 0, size.coerceAtLeast(1).coerceAtMost(MAX_PARAMS))
        size = 0
        subUsed = 0
        currentHasDigits = false
        inSub = false
    }

    internal fun digit(d: Int) {
        if (size == 0) size = 1
        if (inSub) {
            val idx = subStart[size - 1] + subCount[size - 1] - 1
            if (idx in 0 until MAX_SUBPARAMS) {
                val cur = subValues[idx]
                subValues[idx] = ((if (cur == OMITTED) 0 else cur) * 10 + d).coerceAtMost(65535)
            }
            return
        }
        val cur = values[size - 1]
        values[size - 1] = ((if (cur == OMITTED) 0 else cur) * 10 + d).coerceAtMost(65535)
        currentHasDigits = true
    }

    internal fun separator() {
        if (size == 0) size = 1
        if (size < MAX_PARAMS) {
            size++
            values[size - 1] = OMITTED
            subCount[size - 1] = 0
        }
        inSub = false
        currentHasDigits = false
    }

    internal fun subSeparator() {
        if (size == 0) size = 1
        if (!inSub) {
            inSub = true
            subStart[size - 1] = subUsed
            subCount[size - 1] = 0
        }
        if (subUsed < MAX_SUBPARAMS) {
            subValues[subUsed] = OMITTED
            subUsed++
            subCount[size - 1]++
        }
    }

    /** Raw value at [index]; -1 when omitted or absent. */
    operator fun get(index: Int): Int = if (index < size) values[index] else OMITTED

    /** Value at [index] with [default] substituted for omitted or zero values (cursor-movement semantics). */
    fun oneBased(index: Int, default: Int = 1): Int {
        val v = get(index)
        return if (v <= 0) default else v
    }

    /** Value at [index] with 0 substituted for omitted values (SGR/erase semantics). */
    fun zeroBased(index: Int): Int {
        val v = get(index)
        return if (v < 0) 0 else v
    }

    fun subCount(index: Int): Int = if (index < size) subCount[index] else 0

    fun sub(index: Int, subIndex: Int): Int {
        if (index >= size || subIndex >= subCount[index]) return OMITTED
        return subValues[subStart[index] + subIndex]
    }

    fun copy(): CsiParams {
        val c = CsiParams()
        System.arraycopy(values, 0, c.values, 0, MAX_PARAMS)
        System.arraycopy(subStart, 0, c.subStart, 0, MAX_PARAMS)
        System.arraycopy(subCount, 0, c.subCount, 0, MAX_PARAMS)
        System.arraycopy(subValues, 0, c.subValues, 0, MAX_SUBPARAMS)
        c.subUsed = subUsed
        c.size = size
        return c
    }

    override fun toString(): String = buildString {
        for (i in 0 until size) {
            if (i > 0) append(';')
            append(if (values[i] == OMITTED) "" else values[i].toString())
            for (s in 0 until subCount[i]) {
                append(':')
                val v = sub(i, s)
                if (v != OMITTED) append(v)
            }
        }
    }

    companion object {
        const val OMITTED = -1
        const val MAX_PARAMS = 32
        const val MAX_SUBPARAMS = 32
    }
}

/**
 * State machine for ECMA-48 / DEC control sequences, following the structure of Paul Williams'
 * VT500 parser. Operates on Unicode code points, so callers decode UTF-8 first.
 */
class VtParser(private val handler: VtHandler) {
    private enum class State {
        GROUND, ESCAPE, ESCAPE_INTERMEDIATE,
        CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, CSI_IGNORE,
        DCS_ENTRY, DCS_PARAM, DCS_INTERMEDIATE, DCS_PASSTHROUGH, DCS_IGNORE,
        OSC_STRING, SOS_PM_APC_STRING,
    }

    private var state = State.GROUND
    private val params = CsiParams()
    private val intermediates = StringBuilder()
    private val stringPayload = StringBuilder()

    fun reset() {
        state = State.GROUND
        params.clear()
        intermediates.setLength(0)
        stringPayload.setLength(0)
    }

    fun feed(cp: Int) {
        // Transitions that apply from any state.
        when (cp) {
            0x18, 0x1A -> { // CAN, SUB
                if (state == State.OSC_STRING) finishOsc()
                handler.execute(cp)
                toGround()
                return
            }
            0x1B -> {
                if (state == State.OSC_STRING) finishOsc()
                if (state == State.DCS_PASSTHROUGH) finishDcs()
                enterEscape()
                return
            }
            0x9C -> { // ST as a C1 code point
                if (state == State.OSC_STRING) finishOsc()
                if (state == State.DCS_PASSTHROUGH) finishDcs()
                toGround()
                return
            }
        }

        when (state) {
            State.GROUND -> when {
                cp < 0x20 -> handler.execute(cp)
                cp == 0x7F -> Unit
                cp in 0x80..0x9F -> Unit
                else -> handler.print(cp)
            }

            State.ESCAPE -> when {
                cp < 0x20 -> handler.execute(cp)
                cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.ESCAPE_INTERMEDIATE }
                cp == 0x5B -> { clearCollect(); state = State.CSI_ENTRY } // [
                cp == 0x5D -> { stringPayload.setLength(0); state = State.OSC_STRING } // ]
                cp == 0x50 -> { clearCollect(); state = State.DCS_ENTRY } // P
                cp == 0x58 || cp == 0x5E || cp == 0x5F -> state = State.SOS_PM_APC_STRING // X ^ _
                cp in 0x30..0x7E -> { handler.escDispatch(intermediates.toString(), cp.toChar()); toGround() }
                cp == 0x7F -> Unit
                else -> toGround()
            }

            State.ESCAPE_INTERMEDIATE -> when {
                cp < 0x20 -> handler.execute(cp)
                cp in 0x20..0x2F -> intermediates.append(cp.toChar())
                cp in 0x30..0x7E -> { handler.escDispatch(intermediates.toString(), cp.toChar()); toGround() }
                cp == 0x7F -> Unit
                else -> toGround()
            }

            State.CSI_ENTRY -> when {
                cp < 0x20 -> handler.execute(cp)
                cp in 0x30..0x39 -> { params.digit(cp - 0x30); state = State.CSI_PARAM }
                cp == 0x3B -> { params.separator(); state = State.CSI_PARAM }
                cp == 0x3A -> { params.subSeparator(); state = State.CSI_PARAM }
                cp in 0x3C..0x3F -> { intermediates.append(cp.toChar()); state = State.CSI_PARAM } // private prefix < = > ?
                cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
                cp in 0x40..0x7E -> { dispatchCsi(cp); toGround() }
                cp == 0x7F -> Unit
                else -> toGround()
            }

            State.CSI_PARAM -> when {
                cp < 0x20 -> handler.execute(cp)
                cp in 0x30..0x39 -> params.digit(cp - 0x30)
                cp == 0x3B -> params.separator()
                cp == 0x3A -> params.subSeparator()
                cp in 0x3C..0x3F -> state = State.CSI_IGNORE
                cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
                cp in 0x40..0x7E -> { dispatchCsi(cp); toGround() }
                cp == 0x7F -> Unit
                else -> toGround()
            }

            State.CSI_INTERMEDIATE -> when {
                cp < 0x20 -> handler.execute(cp)
                cp in 0x20..0x2F -> intermediates.append(cp.toChar())
                cp in 0x30..0x3F -> state = State.CSI_IGNORE
                cp in 0x40..0x7E -> { dispatchCsi(cp); toGround() }
                cp == 0x7F -> Unit
                else -> toGround()
            }

            State.CSI_IGNORE -> when {
                cp < 0x20 -> handler.execute(cp)
                cp in 0x40..0x7E -> toGround()
                else -> Unit
            }

            State.DCS_ENTRY -> when {
                cp in 0x30..0x39 -> { params.digit(cp - 0x30); state = State.DCS_PARAM }
                cp == 0x3B -> { params.separator(); state = State.DCS_PARAM }
                cp == 0x3A -> { params.subSeparator(); state = State.DCS_PARAM }
                cp in 0x3C..0x3F -> { intermediates.append(cp.toChar()); state = State.DCS_PARAM }
                cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.DCS_INTERMEDIATE }
                cp in 0x40..0x7E -> { dcsFinal = cp.toChar(); stringPayload.setLength(0); state = State.DCS_PASSTHROUGH }
                cp < 0x20 || cp == 0x7F -> Unit
                else -> state = State.DCS_IGNORE
            }

            State.DCS_PARAM -> when {
                cp in 0x30..0x39 -> params.digit(cp - 0x30)
                cp == 0x3B -> params.separator()
                cp == 0x3A -> params.subSeparator()
                cp in 0x3C..0x3F -> state = State.DCS_IGNORE
                cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.DCS_INTERMEDIATE }
                cp in 0x40..0x7E -> { dcsFinal = cp.toChar(); stringPayload.setLength(0); state = State.DCS_PASSTHROUGH }
                cp < 0x20 || cp == 0x7F -> Unit
                else -> state = State.DCS_IGNORE
            }

            State.DCS_INTERMEDIATE -> when {
                cp in 0x20..0x2F -> intermediates.append(cp.toChar())
                cp in 0x30..0x3F -> state = State.DCS_IGNORE
                cp in 0x40..0x7E -> { dcsFinal = cp.toChar(); stringPayload.setLength(0); state = State.DCS_PASSTHROUGH }
                cp < 0x20 || cp == 0x7F -> Unit
                else -> state = State.DCS_IGNORE
            }

            State.DCS_PASSTHROUGH -> {
                if (cp != 0x7F && stringPayload.length < MAX_STRING) stringPayload.appendCodePoint(cp)
            }

            State.DCS_IGNORE -> Unit

            State.OSC_STRING -> when {
                cp == 0x07 -> { finishOsc(); toGround() }
                cp < 0x20 -> Unit
                else -> if (stringPayload.length < MAX_STRING) stringPayload.appendCodePoint(cp)
            }

            State.SOS_PM_APC_STRING -> Unit
        }
    }

    private var dcsFinal: Char = ' '

    private fun enterEscape() {
        intermediates.setLength(0)
        state = State.ESCAPE
    }

    private fun clearCollect() {
        params.clear()
        intermediates.setLength(0)
    }

    private fun toGround() {
        state = State.GROUND
    }

    private fun dispatchCsi(finalCp: Int) {
        handler.csiDispatch(params, intermediates.toString(), finalCp.toChar())
    }

    private fun finishOsc() {
        handler.oscDispatch(stringPayload.toString())
        stringPayload.setLength(0)
    }

    private fun finishDcs() {
        handler.dcsDispatch(params, intermediates.toString(), dcsFinal, stringPayload.toString())
        stringPayload.setLength(0)
    }

    private companion object {
        const val MAX_STRING = 4096
    }
}
