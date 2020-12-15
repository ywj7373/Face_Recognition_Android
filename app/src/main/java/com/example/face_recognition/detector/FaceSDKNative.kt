package com.example.face_recognition.detector

class FaceSDKNative {
    external fun faceDetectionModelInit(faceDetectionModelPath: String?): Boolean
    external fun faceDetect(
        imageDate: ByteArray?,
        imageWidth: Int,
        imageHeight: Int,
        imageChannel: Int
    ): IntArray

    external fun faceDetectionModelUnInit(): Boolean

    companion object {
        init {
            System.loadLibrary("facedetect")
        }
    }
}