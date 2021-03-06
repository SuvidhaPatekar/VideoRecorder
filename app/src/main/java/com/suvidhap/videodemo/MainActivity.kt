package com.suvidhap.videodemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaMuxer.OutputFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.content_main.btnPause
import kotlinx.android.synthetic.main.content_main.btnRecordVideo
import kotlinx.android.synthetic.main.content_main.txvCamera
import java.io.File
import java.lang.Long.signum
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Collections
import java.util.Comparator

class MainActivity : AppCompatActivity() {

  private var cameraDevice: CameraDevice? = null
  private var cameraCaptureSession: CameraCaptureSession? = null
  private lateinit var cameraCharacteristics: CameraCharacteristics
  private lateinit var cameraManager: CameraManager
  private lateinit var size: Size
  private lateinit var videoSize: Size
  private var previewSurface: Surface? = null
  private var recorderSurface: Surface? = null
  private var handler: Handler? = null
  private var handlerThread: HandlerThread? = null
  private lateinit var mediaRecorder: MediaRecorder
  private var videoPath: String? = null
  private var isRecordingVideo: Boolean = false
  private var isVideoPause: Boolean = false
  private var request: CaptureRequest.Builder? = null
  private var sensorOrientation = 0
  private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
  private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
  private val PERMISSIONS_REQUEST_CODE = 200

  private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
  }
  private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 270)
    append(Surface.ROTATION_90, 180)
    append(Surface.ROTATION_180, 90)
    append(Surface.ROTATION_270, 0)
  }

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
      setUpCamera(width, height)
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
    btnRecordVideo.setOnClickListener {
      if (isRecordingVideo) {
        stopRecordingVideo()
      } else {
        startRecordingVideo()
      }
    }

    btnPause.setOnClickListener {
      isVideoPause = if (!isVideoPause) {
        pauseRecordingVideo()
        btnPause.setText(R.string.resume)
        !isVideoPause
      } else {
        resumeRecordingVideo()
        btnPause.setText(R.string.pause)
        !isVideoPause
      }
    }
  }

  override fun onStart() {
    super.onStart()
    startBackgroundThread()
    startCameraIfAllowed()
  }

  override fun onStop() {
    super.onStop()
    closeOperations()
  }

  private fun startCameraIfAllowed() {
    if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(
          this,
          arrayOf(
              Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE
          ),
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
          if (grantResults[0] == PackageManager.PERMISSION_GRANTED &&
              grantResults[1] == PackageManager.PERMISSION_GRANTED
          ) {
            startCamera()
          } else {
            showToast(R.string.need_permission)
            startCameraIfAllowed()
          }
        }
        return
      }
    }
  }

  private fun startBackgroundThread() {
    handlerThread = HandlerThread("backgroundthread")
    handlerThread?.start()
    handler = Handler(handlerThread?.looper)
  }

  @SuppressLint("MissingPermission")
  private fun setUpCamera(
    width: Int,
    height: Int
  ) {
    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    mediaRecorder = MediaRecorder()

    val cameraId = cameraManager.cameraIdList[0]
    cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

    val streamConfigs: StreamConfigurationMap =
      cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

    sensorOrientation = cameraCharacteristics.get(SENSOR_ORIENTATION)

    videoSize = chooseVideoSize(streamConfigs.getOutputSizes(MediaRecorder::class.java))

    size = chooseOptimalSize(
        streamConfigs.getOutputSizes(SurfaceTexture::class.java),
        width, height, videoSize
    )
    cameraManager.openCamera(cameraId, cameraStateCallback, null)
  }

  private fun startCamera() {
    if (txvCamera.isAvailable) {
      setUpCamera(txvCamera.width, txvCamera.height)
    } else {
      txvCamera.surfaceTextureListener = surfaceTextureListener
    }
  }

  fun captureSurface() {
    closePreviewSession()
    previewSurface = Surface(txvCamera.surfaceTexture)
    if (previewSurface != null) {
      val surfaces = Arrays.asList(previewSurface!!)
      cameraDevice?.createCaptureSession(
          surfaces, object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession?) {
        }

        override fun onConfigured(session: CameraCaptureSession) {
          if (cameraDevice == null) return
          cameraCaptureSession = session
          startSession()
        }
      }, handler
      )
    }
  }

  private fun chooseOptimalSize(
    choices: Array<Size>,
    width: Int,
    height: Int,
    aspectRatio: Size
  ): Size {

    // Collect the supported resolutions that are at least as big as the preview Surface
    val w = aspectRatio.width
    val h = aspectRatio.height
    val bigEnough = choices.filter {
      it.height == it.width * h / w && it.width >= width && it.height >= height
    }

    // Pick the smallest of those, assuming we found any
    return if (bigEnough.isNotEmpty()) {
      Collections.min(bigEnough, CompareSizesByArea())
    } else {
      choices[0]
    }
  }

  private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
    it.width == it.height * 4 / 3 && it.width <= 1080
  } ?: choices[choices.size - 1]

  fun startSession() {
    cameraDevice?.let {
      request = it.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      request?.addTarget(previewSurface)
      cameraCaptureSession?.setRepeatingRequest(
          request?.build(), object : CaptureCallback() {}, handler
      )
    }
  }

  private fun updatePreview() {
    if (null == cameraDevice) {
      return
    }
    try {
      val thread = HandlerThread("previewthread")
      thread.start()
      cameraCaptureSession?.setRepeatingRequest(request?.build(), null, handler)
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun startRecordingVideo() {
    closePreviewSession()
    setUpMediaRecorder()

    val texture = txvCamera.surfaceTexture
    texture.setDefaultBufferSize(size.width, size.height)

    request = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

    // Set up Surface for the MediaRecorder
    recorderSurface = mediaRecorder.surface
    val surfaces = Arrays.asList(previewSurface, recorderSurface)
    request?.addTarget(previewSurface)
    request?.addTarget(recorderSurface)

    // Start a capture session
    // Once the session starts, we can update the UI and start recording
    cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(session: CameraCaptureSession?) {
        cameraCaptureSession = session
        updatePreview()

        runOnUiThread {
          isRecordingVideo = true
          btnRecordVideo.setText(R.string.stop)
          btnPause.setText(R.string.pause)
          btnPause.visibility = View.VISIBLE
          mediaRecorder.start()
        }
      }

      override fun onConfigureFailed(session: CameraCaptureSession?) {

      }

    }, handler)
  }

  private fun setUpMediaRecorder() {
    mediaRecorder.reset()

    val rotation = windowManager.defaultDisplay.rotation
    when (sensorOrientation) {
      SENSOR_ORIENTATION_DEFAULT_DEGREES ->
        mediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
      SENSOR_ORIENTATION_INVERSE_DEGREES ->
        mediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
    }

    if (videoPath == null) {
      videoPath = getVideoFilePath()
    }

    mediaRecorder.apply {
      setVideoSource(MediaRecorder.VideoSource.SURFACE)
      setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      setOutputFile(videoPath)
      setVideoSize(videoSize.width, videoSize.height)
      setVideoEncoder(MediaRecorder.VideoEncoder.MPEG_4_SP)
      prepare()
    }
  }

  private fun stopRecordingVideo() {
    isRecordingVideo = false
    btnRecordVideo.setText(R.string.start)
    btnPause.visibility = View.GONE
    mediaRecorder.apply {
      stop()
      reset()
    }

    addAudioToVideo(videoPath!!)
    videoPath = null
    startCamera()
  }

  private fun pauseRecordingVideo() {
    mediaRecorder.pause()
  }

  private fun resumeRecordingVideo() {
    mediaRecorder.resume()
  }

  private fun closeOperations() {
    try {
      cameraDevice?.close()
      cameraDevice = null
      closePreviewSession()
      stopBackgroundThread()
      mediaRecorder.release()
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun closePreviewSession() {
    if (cameraCaptureSession != null) {
      cameraCaptureSession?.close()
      cameraCaptureSession = null
    }
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

  private fun addAudioToVideo(
    videoPath: String
  ) {
    try {
      val outputFile: String = getVideoFilePath()

      val videoExtractor = MediaExtractor()
      videoExtractor.setDataSource(videoPath)

      val afd = assets.openFd(getString(R.string.audio_aac))

      val audioExtractor = MediaExtractor()
      audioExtractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

      val muxer = MediaMuxer(outputFile, OutputFormat.MUXER_OUTPUT_MPEG_4)

      videoExtractor.selectTrack(0)
      val videoFormat = videoExtractor.getTrackFormat(0)
      val videoTrack = muxer.addTrack(videoFormat)

      audioExtractor.selectTrack(0)
      val audioFormat = audioExtractor.getTrackFormat(0)
      val audioTrack = muxer.addTrack(audioFormat)

      var sawEOS = false
      val offset = 100
      val sampleSize = 256 * 1024
      val videoBuf = ByteBuffer.allocate(sampleSize)
      val audioBuf = ByteBuffer.allocate(sampleSize)
      val videoBufferInfo = MediaCodec.BufferInfo()
      val audioBufferInfo = MediaCodec.BufferInfo()


      videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
      audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

      muxer.start()

      while (!sawEOS) {
        videoBufferInfo.offset = offset
        videoBufferInfo.size = videoExtractor.readSampleData(videoBuf, offset)


        if (videoBufferInfo.size < 0 || audioBufferInfo.size < 0) {
          sawEOS = true
          videoBufferInfo.size = 0
        } else {
          videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
          videoBufferInfo.flags = videoExtractor.sampleFlags
          muxer.writeSampleData(videoTrack, videoBuf, videoBufferInfo)
          videoExtractor.advance()
        }
      }

      var sawEOS2 = false
      while (!sawEOS2) {
        audioBufferInfo.offset = offset
        audioBufferInfo.size = audioExtractor.readSampleData(audioBuf, offset)

        if (videoBufferInfo.size < 0 || audioBufferInfo.size < 0) {
          sawEOS2 = true
          audioBufferInfo.size = 0
        } else {
          audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
          audioBufferInfo.flags = audioExtractor.sampleFlags
          muxer.writeSampleData(audioTrack, audioBuf, audioBufferInfo)
          audioExtractor.advance()
        }
      }

      muxer.stop()
      muxer.release()
      showToast("Video file saved at $outputFile")

      val file = File(videoPath)
      if (file.exists()) {
        file.delete()
      }

    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun showToast(message: Int) {
    Toast.makeText(
        this, message, Toast.LENGTH_SHORT
    )
        .show()
  }

  private fun showToast(message: String) {
    Toast.makeText(
        this, message, Toast.LENGTH_SHORT
    )
        .show()
  }

  inner class CompareSizesByArea : Comparator<Size> {
    // We cast here to ensure the multiplications won't overflow
    override fun compare(
      lhs: Size,
      rhs: Size
    ) =
      signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
  }
}
