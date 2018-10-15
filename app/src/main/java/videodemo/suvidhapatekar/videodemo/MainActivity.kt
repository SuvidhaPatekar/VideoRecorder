package videodemo.suvidhapatekar.videodemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.toolbar
import kotlinx.android.synthetic.main.content_main.txvCamera
import java.util.Arrays

class MainActivity : AppCompatActivity() {

  private val PERMISSIONS_REQUEST_CODE = 200

  private var cameraDevice: CameraDevice? = null
  private var cameraCaptureSession: CameraCaptureSession? = null
  private lateinit var cameraCharacteristics: CameraCharacteristics
  private lateinit var cameraManager: CameraManager
  private lateinit var size: Size
  private var previewSurface: Surface? = null
  private var handler: Handler? = null
  private var handlerThread: HandlerThread? = null

  private val surfaceTextureListener = object : SurfaceTextureListener {
    override fun onSurfaceTextureSizeChanged(
      surface: SurfaceTexture?,
      width: Int,
      height: Int
    ) {
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
      return false
    }

    override fun onSurfaceTextureAvailable(
      surface: SurfaceTexture,
      width: Int,
      height: Int
    ) {
      setUpCamera()
    }
  }

  private val cameraStateCallback = object : CameraDevice.StateCallback() {
    override fun onDisconnected(camera: CameraDevice) {
      camera.close()
      cameraDevice = null
    }

    override fun onError(
      camera: CameraDevice,
      error: Int
    ) {
      camera.close()
      cameraDevice = null
    }

    override fun onOpened(camera: CameraDevice) {
      cameraDevice = camera
      captureSurface()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)
  }

  override fun onStart() {
    super.onStart()
    startBackgroundThread()
    getPermission()
  }

  override fun onStop() {
    super.onStop()
    closeOperations()
  }

  private fun getPermission() {
    if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(
          this,
          arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
          PERMISSIONS_REQUEST_CODE
      )
    } else {
      startCamera()
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    when (requestCode) {
      PERMISSIONS_REQUEST_CODE -> {
        if ((grantResults.isNotEmpty())) {
          if (grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
          } else {
            showToast(R.string.need_permission)
            getPermission()
          }
        } else {

        }
        return
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun setUpCamera() {
    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    for (id in cameraManager.cameraIdList) {
      cameraCharacteristics = cameraManager.getCameraCharacteristics(id)
      val cOrientation = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
      val streamConfigs: StreamConfigurationMap =
        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

      if (cOrientation == CameraCharacteristics.LENS_FACING_BACK) {
        size = streamConfigs.getOutputSizes(ImageFormat.JPEG)[0]
      }
      cameraManager.openCamera(id, cameraStateCallback, null)
    }

    previewSurface = Surface(txvCamera.surfaceTexture)
  }

  private fun startCamera() {
    if (txvCamera.isAvailable) {
      setUpCamera()
    } else {
      txvCamera.surfaceTextureListener = surfaceTextureListener
    }
  }

  fun captureSurface() {
    Log.d("capture surface", "Inside capture surface")
    closePreviewSession()
    val surfaces = Arrays.asList(previewSurface!!)
    cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
      override fun onConfigureFailed(session: CameraCaptureSession?) {

      }

      override fun onConfigured(session: CameraCaptureSession) {
        if (cameraDevice == null) return
        cameraCaptureSession = session
        startSession()
      }
    }, handler)
  }

  fun startSession() {
    Log.d("Start session", "Inside start session")
    try {
      cameraDevice?.let {
        val request = it.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        request.addTarget(previewSurface)


        cameraCaptureSession?.setRepeatingRequest(request.build(), object : CaptureCallback() {
          override fun onCaptureCompleted(
            session: CameraCaptureSession?,
            request: CaptureRequest?,
            result: TotalCaptureResult?
          ) {

          }
        }, handler)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun closePreviewSession() {
    if (cameraCaptureSession != null) {
      cameraCaptureSession?.close()
      cameraCaptureSession = null
    }
  }

  private fun closeOperations() {
    try {
      cameraDevice?.close()
      cameraDevice = null
      closePreviewSession()
      stopBackgroundThread()
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun startBackgroundThread() {
    handlerThread = HandlerThread("background thread")
    handlerThread?.start()
    handler = Handler(handlerThread?.looper)
  }

  private fun stopBackgroundThread() {
    handlerThread?.quitSafely()
    try {
      handlerThread?.join()
      handlerThread = null
      handler = null
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }

  }

  private fun showToast(
    message: Int?
  ) {
    message?.let {
      Toast.makeText(
          this, it, Toast.LENGTH_SHORT
      )
          .show()
    }
  }
}
