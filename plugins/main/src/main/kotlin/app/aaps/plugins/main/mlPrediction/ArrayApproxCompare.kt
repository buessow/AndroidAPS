package app.aaps.plugins.main.mlPrediction

import kotlin.math.abs

class ArrayApproxCompare {
    companion object {

        private fun approxEquals(n1:  Number, n2: Number, eps: Double): Boolean {
            return if (n1 is Double && n2 is Double) {
                n1.isNaN() == n2.isNaN() && abs(n1 - n2) < eps
            } else if (n1 is Float && n2 is Float) {
                n1.isNaN() == n2.isNaN() && abs(n1 - n2) < eps
            } else {
                false
            }
        }

        private fun firstMismatch(expected: List<Number>, actual: List<Number>, e: Double): Int? {
            if (expected.size != actual.size) return expected.size.coerceAtMost(actual.size)
            for (i in expected.indices) {
                if (!approxEquals(expected[i], actual[i], e))
                    return i
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