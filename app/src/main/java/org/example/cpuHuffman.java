package org.example;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

public class cpuHuffman {
  public static Map<Integer, Integer> fmap = new HashMap<>();
  public static Map<Integer, String> codebook = new HashMap<>();
  public static Map<String, Integer> decodebook = new HashMap<>();

  public void frequencyCount(int inp) {

    if (!fmap.containsKey(inp)) {
      fmap.put(inp, 1);
    } else {
      fmap.put(inp, fmap.get(inp) + 1);
    }
  }

  public Node huffmanTree() {

    PriorityQueue<Node> minNodes = new PriorityQueue<>();

    for (Map.Entry<Integer, Integer> set : fmap.entrySet()) {
      Node n = new Node(set.getKey(), set.getValue());
      minNodes.add(n);
    }

    while (minNodes.size() != 1) {

      Node first = minNodes.poll();
      Node second = minNodes.poll();

      Node t = new Node('\0', first.freq + second.freq);
      t.left = first;
      t.right = second;
      minNodes.add(t);

    }

    Node root = minNodes.poll();
    return root;
  }

  public void codeBookCreation(Node n, String ctn) {
    if (n == null) {
      return;
    }
    if (n.left == null && n.right == null) {

      codebook.put(n.alpha, ctn);
      decodebook.put(ctn, n.alpha);

    }

    codeBookCreation(n.left, ctn + "0");
    codeBookCreation(n.right, ctn + "1");

  }

  public void printCodebook() {
    for (Map.Entry<Integer, String> s : codebook.entrySet()) {
      System.out.println(s.getKey() + " : " + s.getValue());
    }
  }

  public void compress(String inFileName, String outFileName) {
    int inp;
    try {
      FileInputStream fd = new FileInputStream(inFileName);
      WriteBitsFile wbf = new WriteBitsFile(outFileName);
      while ((inp = fd.read()) != -1) {
        String code = codebook.get(inp);
        wbf.writeCode(code);
      }

      wbf.close();
      fd.close();

    } catch (Exception e) {
      System.out.println(e);
    }

  }

}
