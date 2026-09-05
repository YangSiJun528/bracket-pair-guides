final class Sample {
    static int nestedTotal(int limit) {
        int total = 0;
        for (int outer = 0; outer < limit; outer++) {
            if (outer % 2 == 0) {
                for (int inner = 0; inner < outer; inner++) {
                    total += inner;
                }
            }
        }
        return total;
    }
}
