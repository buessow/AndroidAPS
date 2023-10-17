package app.aaps.plugins.sync.garmin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Base64

class DeltaVarEncodedList {
    private var lastValues: IntArray
    private var data: ByteArray
    private val start: Int = 0
    private var end: Int = 0

    val byteSize: Int get() =  end - start
    var size: Int = 0
        private set

    constructor(byteSize: Int, entrySize: Int) {
        data = ByteArray(toLongBoundary(byteSize))
        lastValues = IntArray(entrySize)
    }

    constructor(lastValues: IntArray, byteBuffer: ByteBuffer) {
        this.lastValues = lastValues
        data = ByteArray(byteBuffer.limit())
        byteBuffer.position(0)
        byteBuffer.get(data)
        end = data.size
        val it = DeltaIterator()
        while (it.next()) {
            size++
        }
    }

    fun encodedData(): List<Long> {
        val byteBuffer: ByteBuffer = ByteBuffer.wrap(data)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.limit(toLongBoundary(end))
        val buffer: LongBuffer = byteBuffer.asLongBuffer()
        val encodedData: MutableList<Long> = ArrayList(buffer.limit())
        while (buffer.position() < buffer.limit()) {
            encodedData.add(buffer.get())
        }
        return encodedData
    }

    fun encodedBase64(): String {
        val byteBuffer: ByteBuffer = ByteBuffer.wrap(data, start, end)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        return String(Base64.getEncoder().encode(byteBuffer).array())
    }

    private fun addVarEncoded(value: Int) {
        var remaining: Int = value
        do {
            // Grow data if needed (double size).
            if (end == data.size) {
                val newData = ByteArray(2 * end)
                System.arraycopy(data, 0, newData, 0, end)
                data = newData
            }
            if ((remaining and 0x7f.inv()) != 0) {
                data[end++] = ((remaining and 0x7f) or 0x80).toByte()
            } else {
                data[end++] = remaining.toByte()
            }
            remaining = remaining ushr 7
        } while (remaining != 0)
    }

    private fun addI(value: Int, idx: Int) {
        val delta: Int = value - lastValues[idx]
        addVarEncoded(zigzagEncode(delta))
        lastValues[idx] = value
    }

    fun add(vararg values: Int) {
        if (values.size != lastValues.size) {
            throw IllegalArgumentException()
        }
        for (idx in values.indices) {
            addI(values[idx], idx)
        }
        size++
    }

    fun toArray(): IntArray {
        val values: IntBuffer = IntBuffer.allocate(lastValues.size * size)
        val it = DeltaIterator()
        while (it.next()) {
            values.put(it.current())
        }
        val next: IntArray = lastValues.copyOf(lastValues.size)
        var nextIdx: Int = next.size - 1
        for (valueIdx in values.position() - 1 downTo 0) {
            val value: Int = values.get(valueIdx)
            values.put(valueIdx, next.get(nextIdx))
            next[nextIdx] -= value
            nextIdx = (nextIdx + 1) % next.size
        }
        return values.array()
    }

    private inner class DeltaIterator {

        private val buffer: ByteBuffer = ByteBuffer.wrap(data)
        private val currentValues: IntArray = IntArray(lastValues.size)
        private var more: Boolean = false
        fun current(): IntArray {
            return currentValues
        }

        private fun readNext(): Int {
            var v = 0
            var offset = 0
            var b: Int
            do {
                if (!buffer.hasRemaining()) {
                    more = false
                    return 0
                }
                b = buffer.get().toInt()
                v = v or ((b and 0x7f) shl offset)
                offset += 7
            } while ((b and 0x80) != 0)
            return zigzagDecode(v)
        }

        operator fun next(): Boolean {
            if (!buffer.hasRemaining()) return false
            more = true
            var i = 0
            while (i < currentValues.size && more) {
                currentValues[i] = readNext()
                i++
            }
            return more
        }

        init {
            buffer.position(start)
            buffer.limit(end)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    companion object {

        private fun toLongBoundary(i: Int): Int {
            return 8 * ((i + 7) / 8)
        }

        private fun zigzagEncode(i: Int): Int {
            return (i shr 31) xor (i shl 1)
        }

        private fun zigzagDecode(i: Int): Int {
            return (i ushr 1) xor -(i and 1)
        }
    }
}