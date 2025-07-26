package org.example;

import java.io.FileInputStream;

public class App {

  // private static String inputFile =
  // "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/Java-lab-1-9.pdf";
  // private static String compressedFile =
  // "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/compressedjava.sz";
  // private static String DecompressedFile =
  // "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/out";

  private static String inputFile = "/home/vuyraj/input.tar";
  private static String compressedFile = "/home/vuyraj/comp.tar.sz";
  private static String DecompressedFile = "/home/vuyraj/decomp.tar";

  public static void main(String[] args) {

    System.out.println("Data Compression");

    cpuHuffman huff = new cpuHuffman();

    try {
      FileInputStream in = new FileInputStream(inputFile);
      int inp;

      while ((inp = in.read()) != -1) {

        huff.frequencyCount(inp);

      }

      Node rootNode = huff.huffmanTree();

      huff.codeBookCreation(rootNode, "");

      cpuHuffman.toCannonicalCode();

      // huff.printCodebook();

      System.out.println("Now compressing the file...");
      cpuCompression.compress(inputFile, compressedFile);
      in.close();
      System.out.println("Compression Successful");
      System.out.println("Decompressing the file ...");

      cpuDecompression dcmp = new cpuDecompression(compressedFile, DecompressedFile);
      dcmp.decompress();
      System.out.println("Decompression successful");

      dcmp.close();

    } catch (Exception e) {
      System.out.println("error occured: " + e);
    }

  }

}
