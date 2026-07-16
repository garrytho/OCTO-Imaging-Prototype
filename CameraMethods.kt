package com.example.hi_snr_computational_imaging

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.io.FileOutputStream
import android.media.ExifInterface
import java.io.File
import kotlin.math.log
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.SystemClock



//THE CONTROLLABLE CAMERA OBJECT. These are the levers we can pull, and where the bulk of our
//image algorithim development can be done

class Camera2Controller(
    private val context: Context,
    private val cameraDevice: CameraDevice,
    private val session: CameraCaptureSession,
    private val previewRequestBuilder: CaptureRequest.Builder,
    private val captureCallback: CameraCaptureSession.CaptureCallback,
    private val cameraCharacteristics: CameraCharacteristics,
    private val rawImageReader: ImageReader,
    private var latestRawCaptureResult: TotalCaptureResult? = null,
    private var pendingRawFile: File? = null,
    private var pendingRawImage: android.media.Image? = null,
    private var focalSweepJob: Job? = null
){


    //initialize image reader with image save-to-device capabilities
    init {
        rawImageReader.setOnImageAvailableListener({ reader ->
            pendingRawImage = reader.acquireNextImage()

            Log.d("RAW_SAVE", "RAW image available")
            Log.d("RAW_SAVE", "Result null? ${latestRawCaptureResult == null}")
            Log.d("RAW_SAVE", "File null? ${pendingRawFile == null}")

            trySaveRaw()
        }, null)
    }


////////Private helpers ////////////////////////////////////////////////////////////////////////////
    private fun disableAutoExposure(){
        //ensure that auto exposure is disabled
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_OFF
        )
    }

    private fun enableAutoExposure(){
        //ensure that auto exposure is enabled
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON
        )
    }

    private fun disableAutoFocus(){
        //ensure that auto exposure is disabled
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF
        )
    }


    private fun enableAutoFocus(){
        //ensure that auto exposure is enabled
        previewRequestBuilder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_AUTO
        )
    }

    private fun updatePreview() {
        session.setRepeatingRequest(
            previewRequestBuilder.build(),
            captureCallback,
            null
        )
    }



    //a function to save an image as RAW. this requires both the image and the result to be present
    //image - the actual pixel data
    //result - the metadata about how the image was taken
    private fun trySaveRaw() {
        val image = pendingRawImage
        val result = latestRawCaptureResult
        val file = pendingRawFile

        if (image == null || result == null || file == null) {
            return
        }

        try {
            val dngCreator = DngCreator(cameraCharacteristics, result)
                .setOrientation(android.media.ExifInterface.ORIENTATION_ROTATE_90)

            FileOutputStream(file).use { output ->
                dngCreator.writeImage(output, image)
            }

            Log.d("RAW_SAVE", "DNG saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e("RAW_SAVE", "Failed to save DNG", e)

        } finally {
            image.close()
            pendingRawImage = null
            latestRawCaptureResult = null
            pendingRawFile = null
        }
    }


////////////////////////////////////////////////////////////////////////////////////////////////////
    //Public functions


    fun setISO(iso: Int){
       disableAutoExposure()

        //set the actual iso
        previewRequestBuilder.set(
            CaptureRequest.SENSOR_SENSITIVITY,
            iso
        )

        //call camera update
        updatePreview()
    }



    fun setExposureTime(exposureTimeNS: Long){
        disableAutoExposure()

        //set the actual exposureTime
        previewRequestBuilder.set(
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            exposureTimeNS
        )

        //call camera update
        updatePreview()
    }


    fun setFocusDistance(focalDistanceDiopters: Float){
        disableAutoFocus()

        //set the actual focus distance
        previewRequestBuilder.set(
            CaptureRequest.LENS_FOCUS_DISTANCE,
            focalDistanceDiopters
        )

        //call camera update
        updatePreview()
    }

    //identify the focal range in diopters. 0.0f is infinite distance
    fun getFocalRange(): ClosedFloatingPointRange<Float>? {
        val minFocusDistance = cameraCharacteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )

        if (minFocusDistance == null || minFocusDistance == 0.0f) {
            Log.w("FOCUS_CONTROL", "This camera may not support manual focus.")
            return null
        }

        return 0.0f..minFocusDistance
    }

    //focal sweep function to repeatedly capture RAW at different steps.
    //suspend allows the thread to use timing without blocking the thread
    fun startFocalSweep(stepDiopters: Float, delayMS: Long) {
        if (focalSweepJob?.isActive == true) {
            Log.w("FOCUS_SWEEP", "Focal sweep already running.")
            return
        }

        focalSweepJob = CoroutineScope(Dispatchers.Main).launch {
            focalSweep(stepDiopters, delayMS)
        }
    }


    //actual focal sweep that gets called after the coroutine starts
    suspend fun focalSweep(stepDiopters: Float, delayMS: Long){
        val maxFocusDiopters = cameraCharacteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )

        var currentFocusDiopters = 0.0f
        val sweepStartMs = SystemClock.elapsedRealtime()

        //validate that distances are ok for sweep
        if (maxFocusDiopters == null || maxFocusDiopters == 0.0f) {
            Log.w("FOCUS_SWEEP", "Manual focus not supported on this camera.")
            return
        }

        if (stepDiopters <= 0.0f) {
            Log.w("FOCUS_SWEEP", "Step size must be greater than 0.")
            return
        }

        //perform sweep
        while(currentFocusDiopters < maxFocusDiopters){
            setFocusDistance(currentFocusDiopters)

            //specified delay - NOTE: THIS ALLOWS THE LENS TO SETTLE
            delay(delayMS)


            //save image with current timestamp
            val elapsedMs = SystemClock.elapsedRealtime() - sweepStartMs
            val focusLabel = "%.2f".format(currentFocusDiopters)
            saveRaw("${elapsedMs}ms_focus_${focusLabel}D")


            Log.d("RAW_Requested", "RAW requested at ${currentFocusDiopters} diopters")
            currentFocusDiopters += stepDiopters }
    }

///////RAW Image capture builder////////////////////////////////////////////////////////////////////
        fun saveRaw(customName: String){


        //protect against multiple calls to save a RAW while one is still finishing
        if (pendingRawFile != null || pendingRawImage != null || latestRawCaptureResult != null) {
            Log.w("RAW_SAVE", "RAW capture already pending; ignoring new request")
            return
        }

        //set filename. use current time if no custom name provided
        val fileName =
            if (customName.isBlank()) {
                "${System.currentTimeMillis()}.dng"
            } else {
                "${customName}.dng"
            }


        //create a spot for the RAW file to ultimately go.
        //TODO MAKE THE FILEPATH SPECIFIABLE BY PARAMETER. RIGHT NOW IT DEFAULTS.
        pendingRawFile = File(
            context.getExternalFilesDir(null),
            fileName
        )

        //create capture builder and set target to the image reader
        val rawCaptureBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

        rawCaptureBuilder.addTarget(rawImageReader.surface)

        //make the capture builder respect our settings//////////////////
        rawCaptureBuilder.set(
            CaptureRequest.CONTROL_AE_MODE,
            previewRequestBuilder.get(CaptureRequest.CONTROL_AE_MODE)
        )

        rawCaptureBuilder.set(
            CaptureRequest.SENSOR_SENSITIVITY,
            previewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY)
        )

        rawCaptureBuilder.set(
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            previewRequestBuilder.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
        )

        rawCaptureBuilder.set(
            CaptureRequest.CONTROL_AF_MODE,
            previewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE)
        )

        rawCaptureBuilder.set(
            CaptureRequest.LENS_FOCUS_DISTANCE,
            previewRequestBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE)
        )
        ///////////////////////////////////////////////////////////////////


        //perform the actual capture
        session.capture(
            rawCaptureBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    latestRawCaptureResult = result
                    Log.d("RAW_SAVE", "RAW capture result received")
                    trySaveRaw()
                }
            },
            null
        )
    }



}



