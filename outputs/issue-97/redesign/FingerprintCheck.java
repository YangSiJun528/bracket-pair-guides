import com.sijunyang.bracketpairguides.benchmarks.CalculationWorkload;
class FingerprintCheck {
  public static void main(String[] args) {
    for (String distribution : new String[]{"siblings", "nested", "sparse", "malformed"}) {
      for (int size : new int[]{64, 4096}) {
        System.out.println("CASE " + distribution + "/" + size);
        System.out.print(new CalculationWorkload(size, distribution).semanticFingerprint());
      }
    }
  }
}
