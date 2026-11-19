package ca.mslab.forge.data

/** Bounded raw PTY scrollback; append does not copy the existing buffer. */
internal class ByteRing(private val capacity: Int) {
    init { require(capacity > 0) }
    private val buffer = ByteArray(capacity)
    private var start = 0
    private var length = 0
    fun clear() { start = 0; length = 0 }
    fun append(bytes: ByteArray) {
        if (bytes.size >= capacity) {
            bytes.copyInto(buffer, 0, bytes.size - capacity); start = 0; length = capacity; return
        }
        val overflow = (length + bytes.size - capacity).coerceAtLeast(0)
        start = (start + overflow) % capacity
        length -= overflow
        val end = (start + length) % capacity
        val first = minOf(bytes.size, capacity - end)
        bytes.copyInto(buffer, end, 0, first)
        bytes.copyInto(buffer, 0, first, bytes.size)
        length += bytes.size
    }
    fun snapshot(): ByteArray {
        val result = ByteArray(length)
        val first = minOf(length, capacity - start)
        buffer.copyInto(result, 0, start, start + first)
        buffer.copyInto(result, first, 0, length - first)
        return result
    }
}
