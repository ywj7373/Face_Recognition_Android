package com.example.face_recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleOwner
import com.example.face_recognition.detector.DetectorActivity
import com.example.face_recognition.recognizer.RecognizerActivity
import com.example.face_recognition.utils.ImageUtils.assetFilePath
import com.example.face_recognition.utils.ImageUtils.cropBoundingBox
import com.example.face_recognition.utils.ImageUtils.drawBoundingBox
import com.example.face_recognition.utils.ImageUtils.rotateBitmap
import com.example.face_recognition.utils.YuvToRgbConverter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.android.synthetic.main.activity_main.*
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var bitmapBuffer: Bitmap
    private lateinit var plusImageView: ImageView
    private lateinit var minusImageView: ImageView
    private lateinit var threadsTextView: TextView
    private lateinit var fpsTextView: TextView
    private lateinit var overlayImageView: ImageView
    private lateinit var fabAddFloatingButton: FloatingActionButton
    private lateinit var fabSwitchCamButton: FloatingActionButton
    private lateinit var imageImageView: ImageView

    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private val executor = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        DetectorActivity(assets)
    }
    private val recognizer by lazy {
        RecognizerActivity(assetFilePath(this, "mobilenet-v2.pt"))
    }

    private val SELECT_IMAGE = 1
    private val permissionsRequestCode = Random.nextInt(0, 10000)
    private val permissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        plusImageView = findViewById(R.id.plus)
        minusImageView = findViewById(R.id.minus)
        threadsTextView = findViewById(R.id.threads)
        fpsTextView = findViewById(R.id.fps_info)
        overlayImageView = findViewById(R.id.overlayImageView)
        fabAddFloatingButton = findViewById(R.id.fab_add)
        fabSwitchCamButton = findViewById(R.id.fab_switchcam)

        plusImageView.setOnClickListener(this)
        minusImageView.setOnClickListener(this)
        fabAddFloatingButton.setOnClickListener(this)
        fabSwitchCamButton.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()

        // Request permissions each time the app resumes, since they can be revoked at any time
        if (!hasPermissions(this)) {
            ActivityCompat.requestPermissions(
                this, permissions.toTypedArray(), permissionsRequestCode
            )
        } else {
            bindCamera()
        }
    }

    override fun onClick(view: View?) {
        view?.let { v ->
            when (v.id) {
                R.id.plus -> {
                    var threads = Integer.parseInt(threadsTextView.text.toString().trim())
                    if (threads > 9) return
                    threads++
                    threadsTextView.text = threads.toString()
                    setThreads(threads)
                }
                R.id.minus -> {
                    var threads = Integer.parseInt(threadsTextView.text.toString().trim())
                    if (threads == 1) return
                    threads--
                    threadsTextView.text = threads.toString()
                    setThreads(threads)
                }
                R.id.fab_switchcam -> {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    }
                    else CameraSelector.LENS_FACING_FRONT

                    bindCamera()
                }
                R.id.fab_add -> showAddFaceDialog()
                else -> return
            }
        }
    }

    /** Bind preview camera image to the view */
    private fun bindCamera() = view_finder.post {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {

            // Camera provider is now guaranteed to be available
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder().build()

        // Create a new camera selector each time, enforcing lens facing
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Set up the image analysis use case which will process frames in real time
        val imageAnalysisBuilder = ImageAnalysis.Builder()

        // Configure Camera parameter: Set FPS to 30
        val camera2InterOp = Camera2Interop.Extender(imageAnalysisBuilder)
        camera2InterOp.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_OFF
        )
        camera2InterOp.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range<Int>(30, 30)
        )

        val imageAnalysis = imageAnalysisBuilder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(view_finder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // Start Face Recognition
        startAnalysis(imageAnalysis)

        // Bind the preview to the view
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            this as LifecycleOwner, cameraSelector, preview, imageAnalysis
        )

        // Use the camera object to link our preview use case with the view
        preview.setSurfaceProvider(view_finder.createSurfaceProvider())
    }

    /** Analyze Image */
    @SuppressLint("UnsafeExperimentalUsageError", "SetTextI18n")
    private fun startAnalysis(imageAnalysis: ImageAnalysis) {
        var frameCounter = 0
        var lastFpsTimestamp = System.currentTimeMillis()

        lateinit var previousBitmap: Bitmap
        val converter = YuvToRgbConverter(this)
        var skip = false

        imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { image ->
            if (!::bitmapBuffer.isInitialized) {
                // The analyzer has started running
                bitmapBuffer = Bitmap.createBitmap(
                    image.width, image.height, Bitmap.Config.ARGB_8888
                )
            }
            // Convert the image to RGB and place it in our shared buffer
            image.run { converter.yuvToRgb(image.image!!, bitmapBuffer) }
            val imageRotationDegrees = image.imageInfo.rotationDegrees.toFloat()
            val currentBitmap = bitmapBuffer.rotateBitmap(imageRotationDegrees)

            // Detection
            val faceInfo = detector.detectFaces(currentBitmap)

            // Recognition
            val recognition = recognizer.recognizeFaces(currentBitmap, faceInfo)

            // Draw Bounding Boxes every 2 frames
            val overlay = if (skip) {
                skip = false
                previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
            else {
                skip = true
                previousBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, true)
                previousBitmap = previousBitmap.drawBoundingBox(faceInfo, recognition)
                previousBitmap
            }
            runOnUiThread {
                overlayImageView.setImageBitmap(overlay)
            }

            // Compute the FPS of the entire pipeline
            val frameCount = 10
            if (++frameCounter % frameCount == 0) {
                frameCounter = 0
                val now = System.currentTimeMillis()
                val delta = now - lastFpsTimestamp
                val fps = 1000 * frameCount.toFloat() / delta
                fpsTextView.text = "%.02f".format(fps)
                lastFpsTimestamp = now
            }

            image.close()
        })
    }

    /** Set interpreter options */
    private fun setThreads(numThreads: Int) {
        recognizer.setNumThreads(numThreads)
    }

    /** Add Face to photo album */
    private fun showAddFaceDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogLayout = layoutInflater.inflate(R.layout.add_image_dialog, null)
        val nameEditText = dialogLayout.findViewById<EditText>(R.id.name)
        imageImageView = dialogLayout.findViewById(R.id.image)

        nameEditText.hint = "Name"

        imageImageView.setOnClickListener {
            imageImageView.contentDescription = "Not a Face"
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, SELECT_IMAGE)
        }

        builder.setView(dialogLayout)
            .setTitle("Add Image")
            .setPositiveButton("OK", object: DialogInterface.OnClickListener {
                override fun onClick(p0: DialogInterface?, p1: Int) {
                    val name = nameEditText.text.toString()
                    val bitmap = imageImageView.drawable.toBitmap()
                    val imageContent = imageImageView.contentDescription

                    if (imageContent == "Face" && name != "") {
                        recognizer.addFace(bitmap, name)
                        Toast.makeText(this@MainActivity, "Added a face!", Toast.LENGTH_LONG).show()
                    }
                    else if (name != "") {
                        Toast.makeText(this@MainActivity, "Not a face image!", Toast.LENGTH_LONG).show()
                    }
                    else {
                        Toast.makeText(this@MainActivity, "Please name the image", Toast.LENGTH_LONG).show()
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Throws(FileNotFoundException::class)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && data != null) {
            val selectedImage = data.data ?: return
            if (requestCode == SELECT_IMAGE) {
                val bitmap = decodeUri(selectedImage)

                bitmap?.let {
                    val faceInfo = detector.detectFaces(it)
                    val croppedBitmap = it.cropBoundingBox(faceInfo)

                    if (croppedBitmap.isNotEmpty()) {
                        imageImageView.setImageBitmap(croppedBitmap[0])
                        imageImageView.contentDescription = "Face"
                    }
                    else {
                        Toast.makeText(this@MainActivity, "Face not detected!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @Throws(FileNotFoundException::class)
    private fun decodeUri(selectedImage: Uri): Bitmap? {
        // Decode image size
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeStream(contentResolver.openInputStream(selectedImage), null, options)

        // The new size we want to scale to
        val size = 400

        // Find the correct scale value. It should be the power of 2.
        var width = options.outWidth
        var height = options.outHeight
        var scale = 1
        while (true) {
            if (width / 2 < size || height / 2 < size) {
                break
            }
            width /= 2
            height /= 2
            scale *= 2
        }

        // Decode with inSampleSize
        val options2 = BitmapFactory.Options()
        options2.inSampleSize = scale
        return BitmapFactory.decodeStream(
            contentResolver.openInputStream(selectedImage),
            null,
            options2
        )
    }

    /** Permissions */
    private fun hasPermissions(context: Context) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionsRequestCode && hasPermissions(this)) {
            bindCamera()
        } else {
            finish() // If we don't have the required permissions, we can't run
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}