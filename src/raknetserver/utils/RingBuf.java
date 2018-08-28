package raknetserver.utils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static raknetserver.utils.UInt.U24.between;
import static raknetserver.utils.UInt.U24.delta;

/**
 * RingBuf with u24 seq bound compatible with rak
 */
public class RingBuf<T> implements Iterable<T> {

    private final Object[] buf;
    private final int capacity;
    private final int mod;
    private int next;
    private int first;
    private int length;

    public RingBuf(int power) {
        capacity = 1 << power;
        if (capacity > UInt.U24.MAX_VALUE) {
            throw new IllegalArgumentException(String.format("capacity=%s,max=%s", capacity, UInt.U24.MAX_VALUE));
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
        length++;
        return out;
    }

    public boolean isFull() {
        return length() == capacity;
    }

    public int length() {
        return length;
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
        T out = _look(first);
        first = UInt.U24.mod(first, 1);
        length--;
        return out;
    }

    public void _shift() {
        first = UInt.U24.mod(first, 1);
        length--;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public boolean contains(int id) {
        if (isEmpty() || !(UInt.U24.mod(id) == id)) {
            return false;
        }
        return between(first, next, id);
    }

    @Override
    public Range<T> iterator() {
        return new Range<>(this, first, next);
    }

    public Range<T> iterator(int begin) {
        return iterator(begin, next);
    }

    public Range<T> iterator(int begin, int bound) {
        if ((begin != first && !contains(begin)) || delta(begin, bound) > delta(begin, next)) {
            throw new IndexOutOfBoundsException(String.format("begin=%s,bound=%s,buf_first=%s,buf_bound=%s", begin, bound, first, next));
        }
        return new Range<>(this, begin, bound);
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
        length = next = first = 0;
    }

    public int remaining() {
        return capacity - length();
    }

    public static class Range<T> implements Iterator<T>, Iterable<T> {

        private final RingBuf<T> _buf;
        private final int bound;
        private int inext;
        private int id;

        private Range(RingBuf<T> buf, int begin, int bound) {
            _buf = buf;
            inext = begin;
            this.bound = bound;
        }

        @Override
        public boolean hasNext() {
            return UInt.U24.delta(inext, bound) >= 1;
        }

        @Override
        public T next() {
            id = inext;
            inext = UInt.U24.mod(inext, 1);
            return _buf._look(id);
        }

        public int id() {
            return id;
        }

        @Override
        public Iterator<T> iterator() {
            return this;
        }
    }

}
