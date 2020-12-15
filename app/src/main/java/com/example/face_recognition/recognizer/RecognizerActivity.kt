package com.example.face_recognition.recognizer

import android.graphics.Bitmap
import android.util.Log
import com.example.face_recognition.utils.ImageUtils.cropBoundingBox
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import kotlin.math.max
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.sqrt

class RecognizerActivity(modelPath: String) {
    private val model: Module by lazy {
        Module.load(modelPath)
    }
    private val photoAlbum = HashMap<String, FloatArray>()

    private val mean = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val std = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val threshold = 0.8f

    fun addFace(bitmap: Bitmap, name: String) {
        // Preprocess image
        val tensor = preprocess(bitmap, 112)

        // Run inference
        val inputs = IValue.from(tensor)
        val outputs = model.forward(inputs).toTensor()
        val embedding = outputs.dataAsFloatArray

        // l2 Norm
        l2Normalize(embedding, 1e-10)

        // Add embeddings and name to photoAlbum
        photoAlbum[name] = embedding
    }

    fun recognizeFaces(bitmap: Bitmap, faceInfo: IntArray): Vector<String> {
        val faceNum = faceInfo[0]
        val recognition = Vector<String>(faceNum)

        // Crop face
        val faces = bitmap.cropBoundingBox(faceInfo)

        for (idx in 0 until faceNum) {
            // Preprocess
            val face = faces[idx]
            val tensor = preprocess(face, 112)

            // Run inference
            val inputs = IValue.from(tensor)
            val startTime = System.currentTimeMillis()
            val outputs = model.forward(inputs).toTensor()
            val detectionTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Face Recognition Time: $detectionTime ms")
            val embedding = outputs.dataAsFloatArray

            // L2 normalize
            l2Normalize(embedding, 1e-10)

            // Get L2 distance with images from photo album
            var name = ""
            for ((key, value) in photoAlbum) {
                val dist = l2Distance(embedding, value)
                Log.d(TAG, "$dist")
                // If it is smaller than threshold, add its name to recognition array
                if (dist <= threshold) {
                    Log.d(TAG, "Recognized Face $key! Dist: $dist")
                    name = key
                    break
                }
            }

            recognition.add(name)
        }

        return recognition
    }

    private fun preprocess(bitmap: Bitmap, size: Int): Tensor {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, size, size, false)
        return TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, mean, std)
    }

    private fun l2Normalize(embedding: FloatArray, epsilon: Double) {
        var sum = 0f
        for (i in embedding.indices) {
            sum += (embedding[i] * embedding[i])
        }
        val invNorm = sqrt(max(sum.toDouble(), epsilon)).toFloat()

        for (i in embedding.indices) {
            embedding[i] = embedding[i] / invNorm
        }
    }

    private fun l2Distance(e1: FloatArray, e2: FloatArray): Float {
        val length = e1.size
        var sum = 0f
        for (i in 0 until length) {
            val diff = e1[i] - e2[i]
            val square = diff * diff
            sum += square
        }

        return sqrt(sum)
    }

    fun setNumThreads(numThreads: Int) {
        // model.setNumThreads(numThreads)
    }

    companion object {
        private val TAG = RecognizerActivity::class.java.simpleName
    }
}