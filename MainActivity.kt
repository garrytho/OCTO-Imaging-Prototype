package com.example.hi_snr_computational_imaging

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp


//create a class called main activity. ComponentActivity() is inherited
class MainActivity : ComponentActivity(){

    //define our own "onCreate" function to be called at runtime
    override fun onCreate(savedInstanceState: Bundle?) {

        //call the parent class (ComponentActivity) onCreate method. Performs important android setup
        super.onCreate(savedInstanceState)

        //composable function that describes what the screen will display
        setContent{
            MainScreen()
        }

    }
}


@Composable

fun MainScreen() {

    //local variables:

    //default message before camera permissions
    var message by remember {
        mutableStateOf("Camera permission not granted.")
    }

    //camera permissions
    var camPermission by remember {
        mutableStateOf(false)
    }

    //Camera ID results
    var ID_result by remember {
        mutableStateOf("")
    }

    //controls when the live camera preview replaces the menu screen
    var showCamera by remember {
        mutableStateOf(false)
    }

    //stores the currentMetadata from live camera feed
    var currentMetadata by remember {
        mutableStateOf<CameraMetadata?>(null)
    }

    val context = LocalContext.current

    //Launcher for requesting camera permission
    val camPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { permissionGranted ->

        if (permissionGranted) {
            camPermission = true
            ID_result = validateForCamera(context)
            message = "Camera permission granted!"
        } else {
            camPermission = false
            message = "Camera permission not granted."
        }
    }

    //camera object that gives our main screen access to more in depth camera functions defined elsewhere
    var cameraController by remember {
        mutableStateOf<Camera2Controller?>(null)
    }

    //show either the full-screen camera or the menu
    if (showCamera) {

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            CameraPreview(
                onMetadataChanged = { newMetadata ->
                    currentMetadata = newMetadata
                },

                onCameraControllerReady = { controller ->
                    Log.d("RAW_SAVE", "MainScreen received Camera2Controller")
                    cameraController = controller
                }


            )

            Text(
                text = """
            ISO: ${currentMetadata?.iso ?: "—"}
            Exposure: ${currentMetadata?.exposureTime ?: "—"} ns
            Timestamp: ${currentMetadata?.sensorTimestamp ?: "—"}
            Focus distance: ${currentMetadata?.focusDistance ?: "—"}
            AF state: ${currentMetadata?.afState ?: "—"}
            AE state: ${currentMetadata?.aeState ?: "—"}
            AWB state: ${currentMetadata?.awbState ?: "—"}
            Rolling shutter: ${currentMetadata?.rollingShutterSkew ?: "—"} ns
        """.trimIndent(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.White)
                    .padding(8.dp)
            )

            //Row layout container for camera action buttons
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {


                Button(
                    onClick = {
                        Log.d("RAW_SAVE", "Save RAW button clicked")
                        Log.d("RAW_SAVE", "cameraController null? ${cameraController == null}")
                        cameraController?.saveRaw("test")
                    }
                ) {
                    Text("Capture")
                }

                Button(
                    onClick = {
                        cameraController?.let { controller ->
                            customImagingScript(controller)
                        }
                    }
                ) {
                    Text("Run Script")
                }
            }
        }

    } else {


        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(message)

            Button(
                onClick = {
                    camPermissionLauncher.launch(
                        Manifest.permission.CAMERA
                    )
                }
            ) {
                Text("Request Camera Permission")
            }

            Text(ID_result)

            Button(
                onClick = {
                    if (camPermission) {
                        showCamera = true
                    } else {
                        message = "Grant camera permission first."
                    }
                }
            ) {
                Text("Open Camera")
            }
        }
    }
}



//tests various properties for each camera, and lists them as capabilities
fun validateForCamera(context: Context): String {

    //Instantiate a CameraManager object. We use this to scrape internal camera properties
    val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    //get all camera IDs
    val cameraIds = cameraManager.cameraIdList

    var result = "Found ${cameraIds.size} camera(s)\n\n\n"

    //loop through each ID
    for (cameraId in cameraIds) {

        //get a comprehensive characteristic list for this cameraID
        val characteristics =
            cameraManager.getCameraCharacteristics(cameraId)

        val exposureRange =
            characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
            )

        val exposureStep =
            characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
            )

        //Identify which direction the lens faces
        val lensFacing =
            characteristics.get(CameraCharacteristics.LENS_FACING)

        val facingText = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        //get all specific capabilities
        val capabilities =
            characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )

        //check whether RAW capture is supported
        val supportsRaw =
            capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ) == true


        //check whether manual sensor control is supported
        val supportsManualSensor =
            capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true

        //check whether sensor settings can be read
        val supportsReadSensorSettings =
            capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS
            ) == true

        //Get native Bayer pattern color arrangement (GRBG, BGGR, GBRG, ETC.)
        val cfaArrangement =
            characteristics.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            )

        //switch statement to map arrangement
        val cfaText = when (cfaArrangement) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
            else -> "Unknown"
        }

        //Compile all info for this camera' into the result
        result += "Camera ID: $cameraId \n"
        result += "RAW: $supportsRaw\n"
        result += "Manual sensor: $supportsManualSensor\n"
        result += "Read sensor settings: $supportsReadSensorSettings\n"
        result += "Bayer pattern: $cfaText\n"
        result += "Exposure range: $exposureRange\n"
        result += "Exposure step: $exposureStep\n"

        if (facingText != "Unknown") {
            result += "Camera facing: $facingText "
        } else {
            result += "Unknown"
        }

        result += "\n\n"
    }

    return result
}
