package videodemo.suvidhapatekar.videodemo

import android.os.Environment
import java.io.File

fun createNewVideoFile(): File {
  val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
      .toString()
  val mkDir = File("$root/VideoRecorder")
  if (!mkDir.exists()) {
    mkDir.mkdirs()
  }
  val imageName = "video-" + System.currentTimeMillis() + ".mp4"
  return File(mkDir, imageName)
}
