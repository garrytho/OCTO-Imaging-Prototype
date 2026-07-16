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
import java.io.FileOutputStream
import java.io.File


//A place for a custom imaging script to be specified using methods from CameraMethods.kt
fun customImagingScript(camera2Controller: Camera2Controller){

    //NOTE: HAVING TOO SHORT A DELAY CAUSES A CRASH!
    camera2Controller.startFocalSweep(1.0f, 300)
}

