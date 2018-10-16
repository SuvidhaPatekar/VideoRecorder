package videodemo.suvidhapatekar.videodemo

import android.os.Environment
import java.io.File
import java.nio.ByteBuffer

fun createNewVideoFile(): File {
  val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
      .toString()
  val mkDir = File("$root/VideoRecorder")
  if (!mkDir.exists()) {
    mkDir.mkdirs()
  }
  val imageName = "Video-" + System.currentTimeMillis() + ".mp4"
  return File(mkDir, imageName)
}

fun ByteBuffer.getByteArrayFromBuffer(): ByteArray {
  val bytes = ByteArray(remaining())
  get(bytes)
  return bytes
}