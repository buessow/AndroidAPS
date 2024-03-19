package app.aaps.plugins.main.mlPrediction

import cc.buessow.glumagic.predictor.ModelInterpreter
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer

class TensorflowInterpreter: ModelInterpreter {
     private val interpreter: Interpreter

     constructor(modelFile: File) {
         interpreter = Interpreter(modelFile)
     }

     constructor(modelBytes: ByteBuffer) {
         interpreter = Interpreter(modelBytes)
     }

     override fun run(input: Array<FloatArray>, output: Array<FloatArray>) {
        interpreter.run(input, output)
    }

    override fun close() { interpreter.close() }
}