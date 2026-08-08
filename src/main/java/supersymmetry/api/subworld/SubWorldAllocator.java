package supersymmetry.api.subworld;

import java.util.ArrayList;
import java.util.List;

public final class SubWorldAllocator {

    public static final int HEAP_SIZE_CHUNKS = 4096;
      public static final int HEAP_ORIGIN_CHUNK = 1870848;

    public static final class Rect {

        public int x;
        public int z;
        public int w;
        public int h;

        public Rect(int x, int z, int w, int h) {
            this.x = x;
            this.z = z;
            this.w = w;
            this.h = h;
        }

        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= x && chunkX < x + w && chunkZ >= z && chunkZ < z + h;
        }

        public boolean intersects(Rect other) {
            return x < other.x + other.w && other.x < x + w && z < other.z + other.h && other.z < z + h;
        }

        public Rect copy() {
            return new Rect(x, z, w, h);
        }

        @Override
        public String toString() {
            return String.format("(%d,%d %dx%d)", x, z, w, h);
        }
    }

    private final int heapMinX;
    private final int heapMinZ;
    private final int heapSize;
    private final List<Rect> freeRects = new ArrayList<>();
    private final List<Rect> allocatedRects = new ArrayList<>();

    public SubWorldAllocator(int heapMinX, int heapMinZ, int heapSize) {
        this.heapMinX = heapMinX;
        this.heapMinZ = heapMinZ;
        this.heapSize = heapSize;
        this.freeRects.add(new Rect(heapMinX, heapMinZ, heapSize, heapSize));
    }

    public void rebuildFromSaved(List<Rect> allocated) {
        this.freeRects.clear();
        this.allocatedRects.clear();
        this.freeRects.add(new Rect(this.heapMinX, this.heapMinZ, this.heapSize, this.heapSize));
        for (Rect rect : allocated) {
            this.occupy(rect);
        }
        this.coalesce();
    }

    public void occupy(Rect rect) {
        this.carve(rect);
        this.allocatedRects.add(rect.copy());
    }

    public List<Rect> getAllocatedRects() {
        return this.allocatedRects;
    }

    public Rect allocate(int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Allocator request must be positive: " + w + "x" + h);
        }
        Rect best = null;
        for (Rect free : this.freeRects) {
            if (free.w >= w && free.h >= h) {
                best = free;
                break;
            }
        }
        if (best == null) {
            throw new IllegalStateException("Sub-world allocator is out of space");
        }
        Rect alloc = new Rect(best.x, best.z, w, h);
        this.freeRects.remove(best);
        if (best.w > w) {
            this.freeRects.add(new Rect(best.x + w, best.z, best.w - w, best.h));
        }
        if (best.h > h) {
            this.freeRects.add(new Rect(best.x, best.z + h, w, best.h - h));
        }
        this.allocatedRects.add(alloc);
        return alloc;
    }

    public void free(Rect rect) {
        this.allocatedRects.remove(rect);
        this.freeRects.add(rect.copy());
        this.coalesce();
    }

    public Rect extendInPlace(Rect rect, int targetW, int targetH) {
        int newW = Math.max(targetW, rect.w);
        int newH = Math.max(targetH, rect.h);
        if (newW <= rect.w && newH <= rect.h) {
            return rect;
        }
        if (rect.x + newW > this.heapMinX + this.heapSize || rect.z + newH > this.heapMinZ + this.heapSize) {
            return null;
        }
        Rect band = new Rect(rect.x, rect.z, newW, newH);
        for (Rect other : this.allocatedRects) {
            if (other == rect) {
                continue;
            }
            if (other.intersects(band)) {
                return null;
            }
        }
        rect.w = newW;
        rect.h = newH;
        this.carve(band);
        this.coalesce();
        return rect;
    }

    public Rect grow(Rect rect, int neededW, int neededH) {
        int targetW = Math.max(neededW, Math.max(rect.w * 2, 1));
        int targetH = Math.max(neededH, Math.max(rect.h * 2, 1));
        Rect extended = this.extendInPlace(rect, targetW, targetH);
        return extended != null ? extended : this.allocate(targetW, targetH);
    }

    private void carve(Rect cut) {
        List<Rect> result = new ArrayList<>();
        for (Rect free : this.freeRects) {
            if (!free.intersects(cut)) {
                result.add(free);
                continue;
            }
            if (cut.x > free.x) {
                result.add(new Rect(free.x, free.z, cut.x - free.x, free.h));
            }
            if (cut.x + cut.w < free.x + free.w) {
                result.add(new Rect(cut.x + cut.w, free.z, free.x + free.w - (cut.x + cut.w), free.h));
            }
            int bandX = Math.max(free.x, cut.x);
            int bandW = Math.min(free.x + free.w, cut.x + cut.w) - bandX;
            if (bandW > 0) {
                if (cut.z > free.z) {
                    result.add(new Rect(bandX, free.z, bandW, cut.z - free.z));
                }
                if (cut.z + cut.h < free.z + free.h) {
                    result.add(new Rect(bandX, cut.z + cut.h, bandW, free.z + free.h - (cut.z + cut.h)));
                }
            }
        }
        this.freeRects.clear();
        this.freeRects.addAll(result);
    }

    private void coalesce() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < this.freeRects.size(); i++) {
                for (int j = i + 1; j < this.freeRects.size(); j++) {
                    Rect a = this.freeRects.get(i);
                    Rect b = this.freeRects.get(j);
                    if (a.z == b.z && a.h == b.h && (a.x + a.w == b.x || b.x + b.w == a.x)) {
                        this.freeRects.set(i, new Rect(Math.min(a.x, b.x), a.z, a.w + b.w, a.h));
                        this.freeRects.remove(j);
                        changed = true;
                        break;
                    }
                    if (a.x == b.x && a.w == b.w && (a.z + a.h == b.z || b.z + b.h == a.z)) {
                        this.freeRects.set(i, new Rect(a.x, Math.min(a.z, b.z), a.w, a.h + b.h));
                        this.freeRects.remove(j);
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    break;
                }
            }
        }
    }
}
