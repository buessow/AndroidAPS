package app.aaps.plugins.main.mlPrediction

import kotlin.math.abs

class ArrayApproxCompare {
    companion object {
        private fun firstMismatch(expected: List<Number>, actual: List<Number>, e: Double): Int? {
            if (expected.size != actual.size) return expected.size.coerceAtMost(actual.size)
            for (i in expected.indices) {
                if (abs(expected[i].toDouble() - actual[i].toDouble()) > e) return i
            }
            return null
        }

        fun getMismatch(actual: Collection<Number>, expected: Collection<Number>, eps: Double): String? {
            val mismatch = firstMismatch(expected.toList(), actual.toList(), eps) ?: return null
            val a = actual.mapIndexed { i, f ->  if  (i == mismatch) "**${f}F" else "${f}F" }.joinToString(", ")
            val e = expected.mapIndexed { i, f ->  if  (i == mismatch) "**${f}F" else "${f}F" }.joinToString(", ")
            return "expected [$e] but was [$a]"
        }
    }
}