package com.example.face_recognition.utils

import android.content.Context
import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

object ImageUtils {
    private val TAG = ImageUtils::class.java.simpleName

    fun Bitmap.rotateBitmap(angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        matrix.postScale(-1f, 1f, width / 2f, width / 2f)
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    fun Bitmap.drawBoundingBox(faceInfo: IntArray, recognition: Vector<String>): Bitmap {
        val overlay = this.copy(Bitmap.Config.ARGB_8888, true)
        if (faceInfo.size > 1) {
            val faceNum = faceInfo[0]
            Log.d(TAG, "Detected $faceNum faces!")

            for (i in 0 until faceNum) {
                val name = recognition[i]
                val paintColor = if (name == "") {
                    Color.RED
                }
                else {
                    Color.GREEN
                }

                val left = faceInfo[1 + 4 * i].toFloat()
                val top = faceInfo[2 + 4 * i].toFloat()
                val right = faceInfo[3 + 4 * i].toFloat()
                val bottom = faceInfo[4 + 4 * i].toFloat()

                val paint = Paint().apply {
                    color = paintColor
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }

                val textPaint = Paint().apply {
                    textSize = 20f
                    color = paintColor
                }

                val canvas = Canvas(overlay)
                canvas.drawRect(left, top, right, bottom, paint)
                canvas.drawText(name, left, top, textPaint)
            }
        }

        return overlay
    }

    fun Bitmap.cropBoundingBox(faceInfo: IntArray): Vector<Bitmap> {
        val croppedFaces = Vector<Bitmap>()

        if (faceInfo.size > 1) {
            val faceNum = faceInfo[0]

            for (i in 0 until faceNum) {
                val left = faceInfo[1 + 4 * i]
                val top = faceInfo[2 + 4 * i]
                val right = faceInfo[3 + 4 * i]
                val bottom = faceInfo[4 + 4 * i]

                val width = right - left
                val height = bottom - top

                croppedFaces.add(Bitmap.createBitmap(this, left, top, width, height))
            }
        }

        return croppedFaces
    }

    @Throws(IOException::class)
    fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        val input = context.assets.open(assetName )
        val output = FileOutputStream(file)
        val buffer = ByteArray(4 * 1024)
        var read = input.read(buffer)

        while (read != -1) {
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
        output.flush()

        return file.absolutePath
    }
}