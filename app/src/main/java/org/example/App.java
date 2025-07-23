package org.example;

import java.io.FileInputStream;

public class App {

  private static String inputFile = "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/input";
  private static String compressedFile = "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/compressed.sz";
  private static String DecompressedFile = "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/output.txt";

  // private static String inputFile = "/home/vuyraj/FreeBSD.vdi";
  // private static String compressedFile = "/home/vuyraj/comp.sz";
  // private static String DecompressedFile = "/home/vuyraj/decomp.vdi";

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
      huff.printCodebook();
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
