package com.example.face_recognition.detector

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.jvm.Throws

class DetectorActivity(private val assetManager: AssetManager) {
    private val faceSDKNative = FaceSDKNative()

    init {
        copyBigDataToSD("RFB-320.mnn")
        copyBigDataToSD("RFB-320-quant-ADMM-32.mnn")
        copyBigDataToSD("RFB-320-quant-KL-5792.mnn")
        copyBigDataToSD("slim-320.mnn")
        copyBigDataToSD("slim-320-quant-ADMM-50.mnn")

        val path = Environment.getExternalStorageDirectory().toString() + "/facesdk/"
        faceSDKNative.faceDetectionModelInit(path)
    }

    // Detect faces
    fun detectFaces(image: Bitmap): IntArray {
        // Convert image to byte array
        val width = image.width
        val height = image.height
        val imageData = getPixelsRGBA(image)

        // Detect
        val startTime = System.currentTimeMillis()
        val faceInfo = faceSDKNative.faceDetect(imageData, width, height, 4) ?: intArrayOf(0)
        val detectionTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Face Detection Time: $detectionTime ms")

        return faceInfo
    }

    // Convert bitmap to byteArray
    private fun getPixelsRGBA(image: Bitmap): ByteArray {
        // calculate how many bytes our image consists of
        val bytes = image.byteCount
        val buffer = ByteBuffer.allocate(bytes) // Create a new buffer
        image.copyPixelsToBuffer(buffer) // Move the byte data to the buffer
        return buffer.array()
    }

    // Copy assets data to external directory
    @Throws(IOException::class)
    private fun copyBigDataToSD(strOutFileName: String) {
        // Create File
        val sdDir = Environment.getExternalStorageDirectory()
        val file = File("$sdDir/facesdk/")
        if (!file.exists()) {
            file.mkdir()
        }
        val tmpFile = "$sdDir/facesdk/$strOutFileName"
        val f = File(tmpFile)
        if (f.exists()) {
            Log.d(TAG, "file exists $strOutFileName")
            return
        }

        // Copy
        Log.d(TAG, "start copy file $strOutFileName")
        val myOutput = FileOutputStream("$sdDir/facesdk/$strOutFileName")
        val myInput = assetManager.open(strOutFileName)
        val buffer = ByteArray(1024)
        var length = myInput.read(buffer)
        while (length > 0) {
            myOutput.write(buffer, 0, length)
            length = myInput.read(buffer)
        }

        // Close
        myOutput.flush()
        myInput.close()
        myOutput.close()
        Log.d(TAG, "end copy file $strOutFileName")
    }

    companion object {
        private val TAG = DetectorActivity::class.java.simpleName
    }
}