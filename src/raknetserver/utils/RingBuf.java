package raknetserver.utils;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * RingBuf with u24 seq bound compatible with rak
 */
public class RingBuf<T> {

    private final Object[] buf;
    private final int capacity;
    private final int mod;
    private int next;
    private int first;

    public RingBuf(int power) {
        capacity = 1 << power;
        if (capacity > UInt.U24.MAX_VALUE) {
            throw new IllegalArgumentException("power");
        }
        mod = capacity - 1;
        buf = new Object[capacity];
    }

    public int add(T add) throws IndexOutOfBoundsException {
        if (isFull()) {
            throw new IndexOutOfBoundsException("add");
        }
        buf[next & mod] = add;
        int out = next;
        next = UInt.U24.mod(next, 1);
        return out;
    }

    public boolean isFull() {
        return length() == capacity;
    }

    public int length() {
        return UInt.U24.delta(first, next);
    }

    /**
     * We use delta everywhere to prevent int overflow.
     */
//    private static int delta(int min, int max) {
//        return max - min;
//    }

    public int capacity() {
        return capacity;
    }

    public T head() {
        if (isEmpty()) {
            return null;
        }
        return _look(first);
    }

    public T get(int get) throws IndexOutOfBoundsException {
        if (!contains(get)) {
            throw new IndexOutOfBoundsException("get");
        }
        return _look(get);
    }

    public T _look(int id) {
        return (T) buf[id & mod];
    }

    public T tail() {
        if (isEmpty()) {
            return null;
        }
        return _look(next - 1);
    }

    public T remove() throws NoSuchElementException {
        if (isEmpty()) {
            throw new NoSuchElementException("remove");
        }
        return (T) buf[first++ & mod];
    }

    public void _shift() {
        first++;
    }

    public boolean isEmpty() {
        return length() == 0;
    }

    public void walk(IWalker<T> walker) {
        _walk(first, next, walker);
    }

    private void _walk(int start, int bound, IWalker<T> walker) {
        for (int _i = start; _i < bound; _i++) {
            walker.walk(_i, (T) buf[_i & mod]);
        }
    }

    public void walk(int start, IWalker<T> walker) throws IndexOutOfBoundsException {
        if (!contains(start)) {
            throw new IndexOutOfBoundsException("walk_start=" + start);
        }
        _walk(start, next, walker);
    }

    public boolean contains(int id) {
        int delta = UInt.U24.delta(id, next);
        return delta > 0 && delta <= length();
    }

    public void walk(int start, int bound, IWalker<T> walker) throws IndexOutOfBoundsException {
        if (!contains(start)) {
            throw new IndexOutOfBoundsException("walk_start=" + start + ", buf_head=" + first);
        }
        if (bound < start || !contains(bound - 1)) {
            throw new IndexOutOfBoundsException("walk_bound=" + bound + ", buf_tail=" + next);
        }
        _walk(start, bound, walker);
    }

    public int next() {
        return next;
    }

    public int first() {
        return first;
    }

    public void resetFully() {
        reset();
        Arrays.fill(buf, null);
    }

    public void reset() {
        next = first = 0;
    }

    public int remaining() {
        return capacity - length();
    }

    public interface IWalker<T> {

        void walk(int id, T ele);
    }
}
