package org.multibit.hd.ui.utils;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.awt.image.BufferedImage;


/**
 * <p>Utilities to provide the following to UI:</p>
 * <ul>
 * <li>Generation of QR codes for Bitcoin URIs</li>
 * </ul>
 *
 * @since 0.0.1
 *
 */
public class QRCodes {

  private static final int QUIET_ZONE_SIZE = 4;

  /**
   * <p>Generate a QR code encoding the given contents</p>
   *
   * @param contents    The text to be encoded into the QR code (e.g. a canonical Bitcoin URI)
   * @param scaleFactor The scaling factor providing number of pixels per QR element
   *
   * @return A buffered image containing a QR code
   */
  public static Optional<BufferedImage> generateQRCode(String contents, int scaleFactor) {

    // Build the input matrix
    final ByteMatrix matrix;
    try {

      matrix = encode(contents);

    } catch (com.google.zxing.WriterException e) {
      return Optional.absent();
    } catch (IllegalArgumentException e) {
      return Optional.absent();
    }

    // Generate an image from the byte matrix
    int matrixWidth = matrix.getWidth();
    int matrixHeight = matrix.getHeight();
    int swatchWidth = matrixWidth * scaleFactor;
    int swatchHeight = matrixHeight * scaleFactor;

    // Create buffered image for drawing
    BufferedImage image = new BufferedImage(swatchWidth, swatchHeight, BufferedImage.TYPE_INT_RGB);

    // Iterate through the matrix and draw the pixels to the image
    for (int y = 0; y < matrixHeight; y++) {
      for (int x = 0; x < matrixWidth; x++) {
        byte imageValue = matrix.get(x, y);
        for (int scaleX = 0; scaleX < scaleFactor; scaleX++) {
          for (int scaleY = 0; scaleY < scaleFactor; scaleY++) {
            image.setRGB(x * scaleFactor + scaleX, y * scaleFactor + scaleY, imageValue);
          }
        }
      }
    }

    return Optional.of(image);
  }

  /**
   * <p>Create a ByteMatrix representing the contents for use as the input matrix</p>
   *
   * @param contents The text to be encoded into the QR code (e.g. a canonical Bitcoin URI)
   * @param code     The QR code
   *
   * @return A QR Code as a ByteMatrix 2D array of greyscale values
   */
  private static ByteMatrix encode(String contents) throws WriterException {

    Preconditions.checkState(!Strings.isNullOrEmpty(contents), "'contents' must be present");

    QRCode code = Encoder.encode(contents, ErrorCorrectionLevel.L);

    // Use a multiple of 2 for desktop screen
    return renderResult(code, 2);
  }

  /**
   * <p>The input matrix uses 0 == white, 1 == black</p>
   * <p>The output matrix uses 0 == black, 255 == white (i.e. an 8 bit greyscale bitmap)</p>
   *
   * @param code     The QR code
   * @param multiple The scaling multiple (number of pixels to allocate to each element)
   *
   * @return The ByteMatrix encoding the information
   */
  private static ByteMatrix renderResult(QRCode code, int multiple) {

    ByteMatrix input = code.getMatrix();
    int inputWidth = input.getWidth();
    int inputHeight = input.getHeight();
    int qrWidth = multiple * inputWidth + (QUIET_ZONE_SIZE << 1);
    int qrHeight = multiple * inputHeight + (QUIET_ZONE_SIZE << 1);

    ByteMatrix output = new ByteMatrix(qrWidth, qrHeight);
    byte[][] outputArray = output.getArray();

    // Create temporary storage for the row
    byte[] row = new byte[qrWidth];

    // 1. Write the white lines at the top
    for (int y = 0; y < QUIET_ZONE_SIZE; y++) {
      setRowColor(outputArray[y], (byte) 255);
    }

    // 2. Expand the QR image to the multiple
    byte[][] inputArray = input.getArray();
    for (int y = 0; y < inputHeight; y++) {
      // a. Write the white pixels at the left of each row
      for (int x = 0; x < QUIET_ZONE_SIZE; x++) {
        row[x] = (byte) 255;
      }

      // b. Write the contents of this row of the barcode
      int offset = QUIET_ZONE_SIZE;
      for (int x = 0; x < inputWidth; x++) {
        byte value = (inputArray[y][x] == 1) ? 0 : (byte) 255;
        for (int z = 0; z < multiple; z++) {
          row[offset + z] = value;
        }
        offset += multiple;
      }

      // c. Write the white pixels at the right of each row
      offset = QUIET_ZONE_SIZE + (inputWidth * multiple);
      for (int x = offset; x < qrWidth; x++) {
        row[x] = (byte) 255;
      }

      // d. Write the completed row multiple times
      offset = QUIET_ZONE_SIZE + (y * multiple);
      for (int z = 0; z < multiple; z++) {
        System.arraycopy(row, 0, outputArray[offset + z], 0, qrWidth);
      }
    }

    // 3. Write the white lines at the bottom
    int offset = QUIET_ZONE_SIZE + (inputHeight * multiple);
    for (int y = offset; y < qrHeight; y++) {
      setRowColor(outputArray[y], (byte) 255);
    }

    return output;
  }

  private static void setRowColor(byte[] row, byte value) {
    for (int x = 0; x < row.length; x++) {
      row[x] = value;
    }
  }
}
