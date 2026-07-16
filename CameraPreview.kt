package com.example.hi_snr_computational_imaging
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat


//all metadata parameters we want on live readout
data class CameraMetadata(
    val iso: Int?,
    val exposureTime: Long?,
    val sensorTimestamp: Long?,
    val focusDistance: Float?,
    val afState: Int?,
    val aeState: Int?,
    val awbState: Int?,
    val rollingShutterSkew: Long?
)



//used for RAW image capture capabilities later
val rawImageReader = ImageReader.newInstance(
    //TODO fix hard coding of dimensions here! Pull using characteristics
    4032,
    3024,
    ImageFormat.RAW_SENSOR,
    2
)





//draws the actual rectangular in-app surface where the preview will appear. Makes a call to open the camera
//once things are ready to be displayed
@Composable
fun CameraPreview(
    //accept and return metadata
    //function that accepts metadata values and doesn't return anything
    onMetadataChanged: (CameraMetadata) -> Unit,
    onCameraControllerReady: (Camera2Controller) -> Unit
) {

    //create a traditional Android View for the camera preview
    AndroidView(

        //factory creates and returns the View object
        factory = { context ->

            val textureView = TextureView(context)

            textureView.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {

                    // Called when the drawing surface is ready
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        surfaceTexture.setDefaultBufferSize(1920, 1080)

                        //CALL TO OPEN THE CAMERA ONCE THINGS HAVE BEEN SET UP
                        openCamera(
                            context,
                            surfaceTexture,
                            onMetadataChanged,
                            onCameraControllerReady
                        )

                    }

                    // Called if the preview surface changes size
                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                    }

                    // Called when the preview surface is removed
                    override fun onSurfaceTextureDestroyed(
                        surfaceTexture: SurfaceTexture
                    ): Boolean {
                        return true
                    }

                    // Called whenever a new frame is drawn
                    override fun onSurfaceTextureUpdated(
                        surfaceTexture: SurfaceTexture
                    ) {
                    }
                }

            // Return the instantiated View
            textureView
        },

        modifier = Modifier.fillMaxSize()
    )
}



//the function used to open up the camera and stream frames to the previously created preview
fun openCamera(
    context: Context,
    surfaceTexture: SurfaceTexture,
    onMetadataChanged: (CameraMetadata) -> Unit,
    onCameraControllerReady: (Camera2Controller) -> Unit
) {

    //gives access to characteristics later
    val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager


    //check camera permissions
    val permissionGranted =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    //exit if we lack permissions
    if (!permissionGranted) {
        return
    }


    //pulling camera characteristics for use with metadata / live extraction
    val characteristics =

        //NOTE: CAM-ID ZERO IS HARDCODED FOR NOW! TODO MAKE THIS DYNAMIC!
        cameraManager.getCameraCharacteristics("0")

    //actual camera opening method
    cameraManager.openCamera(
        //NOTE: CAM-ID ZERO IS HARDCODED FOR NOW! TODO MAKE THIS DYNAMIC!
        "0",

        object : CameraDevice.StateCallback() {

            override fun onOpened(cameraDevice: CameraDevice) {

                //streams individual camera frames. we pass in all necessary params
                sendCameraFrames(
                    context,
                    cameraDevice,
                    surfaceTexture,
                    onMetadataChanged,
                    onCameraControllerReady,
                    characteristics
                )
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                cameraDevice.close()
            }

            override fun onError(
                cameraDevice: CameraDevice,
                error: Int
            ) {
                cameraDevice.close()
            }
        },

        null
    )
}


//used to stream camera frames to the surface preview we created,
//configures the session to support both preview output and requested RAW captures
fun sendCameraFrames(
    context: Context,
    cameraDevice: CameraDevice,
    surfaceTexture: SurfaceTexture,
    onMetadataChanged: (CameraMetadata) -> Unit,
    onCameraControllerReady: (Camera2Controller) -> Unit,
    cameraCharacteristics: CameraCharacteristics
) {

    // Wrap the TextureView's SurfaceTexture in a camera-compatible Surface
    val previewSurface = Surface(surfaceTexture)

    // Build instructions for a low-latency camera preview
    val previewRequestBuilder =
        cameraDevice.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        )


    // Tell the request where its frames should go
    previewRequestBuilder.addTarget(previewSurface)

    // Create a session that is allowed to output to previewSurface
    cameraDevice.createCaptureSession(
        listOf(previewSurface, rawImageReader.surface),

        object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(
                session: CameraCaptureSession
            ) {

                //1. define what happens after each frame finishes
                val captureCallback =
                    object : CameraCaptureSession.CaptureCallback() {

                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            //read metadata for the captured frame
                            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                            val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                            val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                            val rollingShutter =
                                result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)

                            //bundle metadata together in our metadata struct
                            val metadata = CameraMetadata(
                                iso = iso,
                                exposureTime = exposureTime,
                                sensorTimestamp = sensorTimestamp,
                                focusDistance = focusDistance,
                                afState = afState,
                                aeState = aeState,
                                awbState = awbState,
                                rollingShutterSkew = rollingShutter
                            )

                            //send metadata back to MainScreen for live compose update
                            onMetadataChanged(metadata)
                        }
                    }

                //Camera control object to carry out desired actions. this is passed upwards back to
                //mainscreen to become cameraController used by the UI.
                //this is what is used to give the buttons access to camera functions.

                val controller = Camera2Controller(
                    context = context,
                    cameraDevice = cameraDevice,
                    session = session,
                    previewRequestBuilder = previewRequestBuilder,
                    captureCallback = captureCallback,
                    cameraCharacteristics = cameraCharacteristics,
                    rawImageReader = rawImageReader
                )

                onCameraControllerReady(controller)


                //2. start the repeating preview
                session.setRepeatingRequest(
                    previewRequestBuilder.build(),
                    captureCallback,
                    null
                )


            }

            override fun onConfigureFailed(
                session: CameraCaptureSession
            ) {
                //Error case: Preview session could not be created
                println("Preview session couldn't be created!")
            }
        },

        null

    )
}


