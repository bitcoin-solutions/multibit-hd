package org.multibit.hd.core.error_reporting;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.eventbus.SubscriberExceptionContext;
import com.google.common.eventbus.SubscriberExceptionHandler;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.text.WordUtils;
import org.multibit.commons.crypto.PGPUtils;
import org.multibit.hd.brit.core.services.BRITServices;
import org.multibit.commons.utils.HttpsUtils;
import org.multibit.hd.common.error_reporting.ErrorReport;
import org.multibit.hd.common.error_reporting.ErrorReportLogEntry;
import org.multibit.hd.common.error_reporting.ErrorReportResult;
import org.multibit.hd.common.error_reporting.ErrorReportStatus;
import org.multibit.hd.core.config.Configurations;
import org.multibit.hd.core.config.Json;
import org.multibit.hd.core.events.CoreEvents;
import org.multibit.hd.core.events.ShutdownEvent;
import org.multibit.commons.files.SecureFiles;
import org.multibit.hd.core.logging.LogbackFactory;
import org.multibit.hd.core.managers.InstallationManager;
import org.multibit.hd.core.utils.OSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPPublicKey;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.security.NoSuchProviderException;
import java.util.Scanner;

/**
 * <p>Exception handler to provide the following to application:</p>
 * <ul>
 * <li>Displays a critical failure dialog to the user and handles process of bug reporting</li>
 * </ul>
 *
 * @since 0.0.1
 */
@SuppressFBWarnings({"DM_EXIT"})
public class ExceptionHandler extends EventQueue implements Thread.UncaughtExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ExceptionHandler.class);

  /**
   * The URL of the live error reporting daemon
   */
  public static final String LIVE_ERROR_REPORTING_URL = "https://multibit.org/error-reporting";

  /**
   * <p>Set this as the default uncaught exception handler</p>
   */
  public static void registerExceptionHandler() {

    // The EDT will defer to this handler if it terminates unexpectedly
    // Since Java 7 there is no need to set the "sun.awt.exception.handler" property as well
    Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler());

  }

  /**
   * <p>The handler of last resort when a programming error has caused an uncaught exception to occur</p>
   *
   * @param t The cause of the problem
   */
  @SuppressWarnings("unchecked")
  public static void handleThrowable(Throwable t) {

    log.error("Uncaught exception. Proceeding to show Error Reporting dialog...", t);

    final String message;
    if (t.getLocalizedMessage() == null || t.getLocalizedMessage().length() == 0) {
      message = "Fatal: " + t.getClass();
    } else {
      message = t.getLocalizedMessage();
    }

    // Determine if this error should be reported
    if (isBenign(t)) {
      return;
    }

    SwingUtilities.invokeLater(
      new Runnable() {
        @Override
        public void run() {

          try {
            // Internationalisation and layout issues require a dynamic invocation
            // of a class present in the Swing module
            Class nativeApplicationClass = Class.forName("org.multibit.hd.ui.error_reporting.ErrorReportingDialog");
            if (nativeApplicationClass == null) {
              throw new NullPointerException("Could not instantiate ErrorReportingDialog (error)");
            }
            // Instantiate through newInstance() and supply constructor arguments to indicate an apology is required
            // Dialog will show and handle all further requirements including hard shutdown
            nativeApplicationClass.getDeclaredConstructor(boolean.class).newInstance(true);
          } catch (IllegalStateException ise) {
            log.error("Unable to create dialog as one is already being shown");
          } catch (Exception e) {
            log.error("Unable to use standard error reporting dialog.", e);
            try {
              // This should never happen due to static binding of the Swing library
              JOptionPane.showMessageDialog(null,
                "Oh Snap!\n\n"
                  + "A serious error has occurred with the following message:\n"
                  + WordUtils.wrap(message, 30)
                  + "\n\nMultiBit HD will exit and you should restart, unlock and select Help | Report error to report this.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
              // Fire a hard shutdown after dialog closes with emergency fallback
              CoreEvents.fireShutdownEvent(ShutdownEvent.ShutdownType.HARD);
            } catch (Exception e1) {
              log.error("Unable to use fallback error reporting dialog. Forcing immediate shutdown.", e1);
              // Safest option at this point is an emergency shutdown
              System.exit(-1);
            }
          }

        }
      });

  }

  /**
   * Allow the user to manually enter a problem
   */
  @SuppressWarnings("unchecked")
  public static void handleManualErrorReport() {

    SwingUtilities.invokeLater(
      new Runnable() {
        @Override
        public void run() {

          try {
            // Internationalisation and layout issues require a dynamic invocation
            // of a class present in the Swing module
            Class nativeApplicationClass = Class.forName("org.multibit.hd.ui.error_reporting.ErrorReportingDialog");
            if (nativeApplicationClass == null) {
              throw new NullPointerException("Could not instantiate ErrorReportingDialog (manual)");
            }
            // Instantiate through newInstance() and supply constructor arguments to thank the user for their feedback
            // Dialog will show and handle all further requirements
            nativeApplicationClass.getDeclaredConstructor(boolean.class).newInstance(false);
          } catch (IllegalStateException ise) {
            log.error("Unable to create dialog as one is already being shown");
          } catch (Exception e) {
            log.error("Unable to use standard error reporting dialog.", e);
            try {
              // This should never happen due to static binding of the Swing library
              JOptionPane.showMessageDialog(null,
                "Oh Snap!\n\n"
                  + "The error reporting system has failed with the following message:\n"
                  + WordUtils.wrap(e.getMessage(), 30)
                  + "\n\nMultiBit HD will exit and you should report this error to the MultiBit HD developers.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
              // Fire a hard shutdown after dialog closes with emergency fallback
              CoreEvents.fireShutdownEvent(ShutdownEvent.ShutdownType.HARD);
            } catch (Exception e1) {
              log.error("Unable to use fallback error reporting dialog. Forcing immediate shutdown.", e1);
              // Safest option at this point is an emergency shutdown
              System.exit(-1);
            }
          }

        }
      });
  }

  @Override
  protected void dispatchEvent(AWTEvent newEvent) {
    try {
      super.dispatchEvent(newEvent);
    } catch (Exception e) {
      handleThrowable(e);
    }
  }

  @Override
  public void uncaughtException(Thread t, Throwable e) {
    handleThrowable(e);
  }

  /**
   * @return A subscriber exception handler for the Guava event bus
   */
  public static SubscriberExceptionHandler newSubscriberExceptionHandler() {

    return new SubscriberExceptionHandler() {
      @Override
      public void handleException(Throwable exception, SubscriberExceptionContext context) {

        // This is not serious enough to concern the user
        log.error(exception.getMessage(), exception);

      }
    };
  }

  /**
   * Reads an input stream and truncates it to 200Kb (20+ pages of logs) respecting a line break
   *
   * @param inputStream The input stream to truncate
   * @param maxLength   The maximum length (204_800) for 200Kb
   *
   * @return The truncated stream as a String
   */
  public static String readAndTruncateInputStream(InputStream inputStream, int maxLength) {

    // Read it
    try {
      String contents = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
      if (Strings.isNullOrEmpty(contents)) {
        return "Contents are empty";
      }

      // Remove personally identifying information in paths
      String userName = System.getProperty("user.name");
      contents = contents
        .replace("/" + userName + "/", "/*blank*/") // Unix paths
        .replace("\\" + userName + "\\", "\\*blank*\\"); // Windows paths

      // Truncate to 200Kb short of the end
      int offset = Math.max(0, contents.length() - maxLength);
      if (offset > 0) {
        // Find first line break to ensure correct parsing
        int firstLineBreak = contents.substring(offset).indexOf('\n') + 1;
        return contents.substring(offset + firstLineBreak);

      } else {
        // We're at the start of the log so correct by definition
        return contents.substring(offset);
      }

    } catch (IOException e) {
      return "Contents could not be read: " + e.getMessage();
    }

  }

  /**
   * @param userNotes         The additional user notes to upload
   * @param errorReportingUrl The error reporting URL to use
   *
   * @return The error report result
   */
  public static ErrorReportResult handleErrorReportUpload(String userNotes, URL errorReportingUrl) {

    final PGPPublicKey multibitPublicKey;
    try {
      // To test Error Reporting Service locally with a test key replace this code
      // with an InputStream targeting a test matcher key under src/main/resources
      multibitPublicKey = BRITServices.getMatcherPublicKey();
    } catch (IOException | PGPException e) {
      log.error("Failed to load MultiBit public key", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Attempt to load the current logging file
    Optional<File> currentLoggingFile = LogbackFactory.getCurrentLoggingFile();
    if (!currentLoggingFile.isPresent()) {
      log.error("No current log file.");
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Truncate the current log file
    final String truncatedCurrentLog;
    try {
      truncatedCurrentLog = readAndTruncateInputStream(new FileInputStream(currentLoggingFile.get()), 204_800);
    } catch (IOException e) {
      log.error("Failed to read log file", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Parse the current log into an ErrorReport
    ErrorReport errorReport = buildErrorReport(userNotes, truncatedCurrentLog);

    // Write this to the disk (it's already known to the system)
    final File errorReportFile = new File(InstallationManager.getOrCreateApplicationDataDirectory().getAbsolutePath() + "/logs/error-report.json");
    final FileOutputStream errorReportFOS;
    try {
      errorReportFOS = new FileOutputStream(errorReportFile);
      log.debug("Writing error report file as JSON");
      Json.writeJson(errorReportFOS, errorReport);
    } catch (IOException e) {
      log.error("Failed to write error-report.json", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Prepare the ASCII Armor output stream
    final FileOutputStream armoredErrorReportFOS;
    try {
      log.debug("Preparing armored error report file");
      armoredErrorReportFOS = new FileOutputStream(InstallationManager.getOrCreateApplicationDataDirectory().getAbsolutePath() + "/logs/error-report.json.asc");
    } catch (FileNotFoundException e) {
      log.error("Failed to prepare error-report.json.asc", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Encrypt with the MultiBit public key
    try {
      log.debug("Writing armored error report file");
      PGPUtils.encryptFile(armoredErrorReportFOS, errorReportFile, multibitPublicKey);
    } catch (IOException | NoSuchProviderException | PGPException e) {
      log.error("Failed to write error-report.json.asc", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Delete the plain text report
    try {
      log.debug("Deleting error report file");
      SecureFiles.secureDelete(errorReportFile);
    } catch (IOException e) {
      log.error("Failed to delete error-report.json", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Load the armored payload
    final byte[] armoredErrorReport;
    try {
      log.debug("Reading armored error report file");
      File armoredErrorReportFile = new File(InstallationManager.getOrCreateApplicationDataDirectory().getAbsolutePath() + "/logs/error-report.json.asc");
      armoredErrorReport = Files.toByteArray(armoredErrorReportFile);
    } catch (IOException e) {
      log.error("Failed to read error-report.json.asc", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    final byte[] response;
    try {
      log.debug("POSTing armored error report file to '{}'", errorReportingUrl);
      response = HttpsUtils.doPost(errorReportingUrl, armoredErrorReport, "text/plain");
    } catch (IOException e) {
      log.warn("Failed to POST error-report.json.asc", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    Optional<ErrorReportResult> result = Json.readJson(response, ErrorReportResult.class);

    if (result.isPresent()) {
      return result.get();
    } else {
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

  }

  /**
   * Parse the truncated log and build an ErrorReport. This approach guarantees that the
   * server will be able to rapidly process it.
   *
   * Reduced visibility to allow testing
   *
   * @param userNotes           The user notes
   * @param truncatedCurrentLog The truncated log
   *
   * @return The error report
   */
  static ErrorReport buildErrorReport(String userNotes, String truncatedCurrentLog) {

    java.util.List<ErrorReportLogEntry> errorReportLogEntryList = Lists.newArrayList();

    // Read in all lines
    final Scanner scanner = new Scanner(truncatedCurrentLog);
    while (scanner.hasNextLine()) {
      String logEntry = scanner.nextLine();

      Optional<ErrorReportLogEntry> entry = Json.readJson(
        logEntry.getBytes(Charsets.UTF_8),
        ErrorReportLogEntry.class
      );
      if (entry.isPresent()) {
        errorReportLogEntryList.add(entry.get());
      }
    }
    // Build the error report including basic operating system information
    ErrorReport errorReport = new ErrorReport();
    errorReport.setOsName(OSUtils.getOsName());
    errorReport.setOsVersion(OSUtils.getOsVersion());
    errorReport.setOsArch(OSUtils.is64Bit() ? "64" : "32");
    errorReport.setAppVersion(Configurations.currentConfiguration.getCurrentVersion());
    errorReport.setUserNotes(userNotes);
    errorReport.setLogEntries(errorReportLogEntryList);

    return errorReport;
  }

  /**
   * <p>Some exceptions are known to be caused by buggy JVM implementations across operating systems
   * and their effects are confined to data transfer operations (e.g. pasting clipboard or focus
   * transitions within dialogs).</p>
   *
   * <p>If, after  careful study through the error reporting process, there is no known solution
   * to the problem and <strong>it does not affect ongoing user operations</strong> then it can be
   * entered here to avoid repetitive logging of a known "cannot fix" problem.</p>
   *
   * <p><strong>If in doubt leave it out.</strong> It is better to show the user an error reporting
   * dialog and exit than it is for them to continue with an application in an unstable state due to
   * not all necessary code having executed after an exception.</p>
   *
   * <p>When evaluating the exception must have a complete stack trace that does not originate from any
   * non-JDK code. An automated check for this is built in.</p>
   *
   * @param t The Throwable to check
   *
   * @return True if the Throwable can be considered benign (ongoing operations will be OK)
   */
  private static boolean isBenign(Throwable t) {

    // Verify that no application code is involved
    for (StackTraceElement stackTraceElement : t.getStackTrace()) {
      if (stackTraceElement.getClassName().startsWith("org.multibit")) {
        // We can detect this so note this in the error report
        log.warn("Stack trace contains 'org.multibit'");
        return false;
      }
    }

    // Must be external code to be here

    // Look for simple known exception messages
    // These tend to be invariant across JVMs and allow for specific cases to be trapped
    // rather than looking for a particular class in a stack trace
    String message = t.getMessage();
    if (message == null) {
      // No information so assume the worst
      return false;
    }

    // Check for TimSort contract violation (see Issue #645)
    // This occurs occasionally in clipboard/focus operations and is based in the way
    // the JVM interacts with the underlying OS - there is nothing we can do
    // The application logic should not be affected
    if (message.contains("Comparison method violates its general contract!")) {
      log.warn("Detected TimSort exception. Treat as benign.");
      return true;
    }

    // Check for XlibWrapper atom name (see Issue #635)
    // This also occurs occasionally in clipboard operations and is based in the way
    // the JVM interacts with the underlying OS - there is nothing we can do
    // The application logic should not be affected
    if (message.contains("Failed to retrieve atom name")) {
      log.warn("Detected XlibWrapper exception. Treat as benign.");
      return true;
    }

    // Check for java.lang.ClassCastException: javax.swing.plaf.FontUIResource cannot be cast to javax.swing.Painter (see Issue #722)
    // This is a Swing glitch that we can do nothing about
    if (message.contains("javax.swing.plaf.FontUIResource cannot be cast to javax.swing.Painter")) {
      log.warn("Detected FontUIResource cannot be cast to Painter exception. Treat as benign.");
      return true;
    }

    // Check for java.lang.ClassCastException: sun.awt.image.BufImgSurfaceData cannot be cast to sun.java2d.xr.XRSurfaceData (see Issue #677)
    // This is a Swing glitch that we can do nothing about
    if (message.contains("sun.awt.image.BufImgSurfaceData cannot be cast to sun.java2d.xr.XRSurfaceData")) {
      log.warn("Detected BufImgSurfaceData cannot be cast to XRSurfaceData exception. Treat as benign.");
      return true;
    }

    // Check for sun.awt.Win32GraphicsConfig cannot be cast to sun.java2d.d3d.D3DGraphicsConfig (see Issue #735)
    // This is a Swing glitch that we can do nothing about
    if (message.contains("sun.awt.Win32GraphicsConfig cannot be cast to sun.java2d.d3d.D3DGraphicsConfig")) {
      log.warn("Detected Win32GraphicsConfig cannot be cast to D3DGraphicsConfig exception. Treat as benign.");
      return true;
    }

    // Check for javax.swing.plaf.nimbus.DerivedColor$UIResource cannot be cast to java.awt.Font (see Issue #754)
    // This is a Swing glitch that we can do nothing about
    if (message.contains("javax.swing.plaf.nimbus.DerivedColor$UIResource cannot be cast to java.awt.Font")) {
      log.warn("Detected DerivedColor$UIResource cannot be cast to java.awt.Font exception. Treat as benign.");
      return true;
    }

    // Check for java.lang.IllegalArgumentException: bad position: -1 (see Issue #628)
    // This is a Swing glitch that we can do nothing about
    if (message.contains("java.lang.IllegalArgumentException: bad position: -1")) {
      log.warn("Detected java.lang.IllegalArgumentException: bad position: -1 exception. Treat as benign.");
      return true;
    }

    // Check for org.bitcoinj.net.discovery.PeerDiscoveryException: No peer discovery returned any results: check internet connection? (see Issue 747)
    // This isn't really benign but as it is thrown on a PeerGroup executor thread we cannot intercept it anywhere else
    if (message.contains("org.bitcoinj.net.discovery.PeerDiscoveryException: No peer discovery returned any results: check internet connection?")) {
      log.warn("Detected org.bitcoinj.net.discovery.PeerDiscoveryException: No peer discovery returned any results: check internet connection? exception. Treat as benign.");
      return true;
    }

    // Anything else is a problem
    return false;
  }

}

