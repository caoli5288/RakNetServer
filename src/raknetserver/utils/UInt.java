package raknetserver.utils;

public class UInt {

    public static class U16 {

        public static final int MAX_VALUE = (1 << 16) - 1;
    }

    public static class U24 {

        public static final int MAX_VALUE = (1 << 24) - 1;

        public static int mod(int value, int delta) {
            int after = value + delta;
            return after & MAX_VALUE;
        }

        public static int mod(int value) {
            return value & MAX_VALUE;
        }

        public static int delta(int small, int large) {
            int delta = large - small;
            if (large < small) {
                delta += MAX_VALUE + 1;
            }
            return delta;
        }

        public static boolean between(int lower, int bound, int value) {
            if (lower == value) {
                return true;
            }
            if (lower > bound) {
                return lower < value || value < bound;
            }
            return lower < value && value < bound;
        }
    }
}
