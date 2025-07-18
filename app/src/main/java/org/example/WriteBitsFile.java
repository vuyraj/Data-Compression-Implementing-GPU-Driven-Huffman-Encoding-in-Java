package org.example;

import java.io.FileOutputStream;
import java.io.IOException;

public class WriteBitsFile {

  public static int pad;

  private FileOutputStream fileOut;
  private int currentByte;
  private int numBitsFilled;

  public WriteBitsFile(String f) throws IOException {
    fileOut = new FileOutputStream(f);
    currentByte = 0;
    numBitsFilled = 0;
  }

  // Writes a single bit to the stream (0 or 1)
  public void writeBit(int bit) throws IOException {
    if (bit != 0 && bit != 1)
      throw new IllegalArgumentException("Bit must be 0 or 1");

    currentByte = (currentByte << 1) | bit;
    numBitsFilled++;

    if (numBitsFilled == 8) {
      System.out.println(currentByte);
      fileOut.write(currentByte);
      numBitsFilled = 0;
      currentByte = 0;
    }
  }

  // Writes a string of bits like "110", "0101"
  public void writeCode(String bitString) throws IOException {
    for (char c : bitString.toCharArray()) {
      if (c == '0') {
        writeBit(0);
      } else if (c == '1') {
        writeBit(1);
      } else {
        throw new IllegalArgumentException("Invalid character in bit string: " + c);
      }
    }
  }

  public void close() throws IOException {
    if (numBitsFilled > 0) {
      pad = 8 - numBitsFilled;
      currentByte <<= (8 - numBitsFilled); // Pad with zeros
      fileOut.write(currentByte);
    }
    fileOut.close();
  }

}
