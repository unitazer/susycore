package supersymmetry.common;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import supersymmetry.api.SusyLog;

public class Native {

  private static final String LIB_NAME = "susycore";
  private static final String NATIVE_DIR = ".susycore/natives";
  public static boolean ENABLED = false;

  static {
    loadLibrary();
  }

  private static String getNativeName() {
    String osName = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();

    if (arch.startsWith("aarch64") || arch.equals("arm64")) {
      arch = "aarch64";
    } else if (arch.equals("amd64")) {
      arch = "x86_64";
    }

    if (osName.contains("windows")) {
      return LIB_NAME + "_" + arch + "_windows.dll";
    } else if (osName.contains("mac")) {
      return LIB_NAME + "_" + arch + "_macos.dylib";
    } else {
      return LIB_NAME + "_" + arch + "_linux.so";
    }
  }

  private static void loadLibrary() {
    String nativeName = getNativeName();
    SusyLog.logger.info("trying to load the native library {}", nativeName);
    try (InputStream is =
        Native.class.getResourceAsStream("/natives/" + LIB_NAME + "/" + nativeName)) {
      if (is == null) {
        throw new FileNotFoundException("Native not found: " + nativeName);
      }

      Path dir = Paths.get(NATIVE_DIR);
      if (!Files.exists(dir)) {
        Files.createDirectories(dir);
      }

      Path tempFile = dir.resolve(nativeName);
      Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
      System.load(tempFile.toAbsolutePath().toString());
      ENABLED = true;
    } catch (Throwable t) {
      ENABLED = false;
      t.printStackTrace();
    }
  }

  public static void log(int level, String message) {
    switch (level) {
      case 1:
        SusyLog.logger.error(message);
        break;
      case 2:
        SusyLog.logger.warn(message);
        break;
      case 3:
        SusyLog.logger.info(message);
        break;
      case 4:
        SusyLog.logger.debug(message);
        break;
      case 5:
        SusyLog.logger.trace(message);
        break;
    }
  }

  // Native methods
  public static native void goog();

  public static native void init();
}
