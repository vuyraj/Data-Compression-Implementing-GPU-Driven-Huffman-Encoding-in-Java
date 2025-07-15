package org.example;

public class Node implements Comparable<Node> {
  int alpha;
  int freq;
  Node left;
  Node right;

  Node(int c, int f) {
    this.alpha = c;
    this.freq = f;
    this.left = null;
    this.right = null;
  }

  @Override
  public int compareTo(Node otherNode) {
    return this.freq - otherNode.freq;

  }

}
