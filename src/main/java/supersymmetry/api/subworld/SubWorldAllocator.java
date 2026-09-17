package supersymmetry.api.subworld;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

public final class SubWorldAllocator {

    public static final int HEAP_SIZE_CHUNKS = 4096;
    public static final int HEAP_ORIGIN_CHUNK = 1870848;
    public static final int HEAP_BORDER_CHUNKS = 8;

    public static boolean isInHeapRegion(int chunkX, int chunkZ) {
        int min = HEAP_ORIGIN_CHUNK - HEAP_BORDER_CHUNKS;
        int max = HEAP_ORIGIN_CHUNK + HEAP_SIZE_CHUNKS;
        return chunkX >= min && chunkX < max && chunkZ >= min && chunkZ < max;
    }

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
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Rect)) return false;
            Rect r = (Rect) o;
            return x == r.x && z == r.z && w == r.w && h == r.h;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + z;
            result = 31 * result + w;
            result = 31 * result + h;
            return result;
        }

        @Override
        public String toString() {
            return String.format("(%d,%d %dx%d)", x, z, w, h);
        }
    }

    private final int heapMinX;
    private final int heapMinZ;
    private final int heapSize;
    private final int border;
    private final int mainMinX;
    private final int mainMinZ;
    private final List<Rect> freeRects = new ArrayList<>();
    private final List<Rect> allocatedRects = new ArrayList<>();

    public SubWorldAllocator(int mainMinX, int mainMinZ, int mainSize, int border) {
        this.heapMinX = mainMinX - border;
        this.heapMinZ = mainMinZ - border;
        this.heapSize = mainSize + border;
        this.border = border;
        this.mainMinX = mainMinX;
        this.mainMinZ = mainMinZ;
        seedFreeRects();
    }

    private void seedFreeRects() {
        this.freeRects.clear();
        this.freeRects.add(new Rect(this.mainMinX, this.mainMinZ,
                this.heapSize - this.border, this.heapSize - this.border));
        if (this.border > 0) {
            this.freeRects.add(new Rect(this.heapMinX, this.heapMinZ, this.heapSize, this.border));
            this.freeRects.add(new Rect(this.heapMinX, this.mainMinZ, this.border,
                    this.heapSize - this.border));
        }
    }

    public void rebuildFromSaved(List<Rect> allocated) {
        seedFreeRects();
        this.allocatedRects.clear();
        for (Rect rect : allocated) {
            if (rect.w <= 0 || rect.h <= 0) {
                throw new IllegalArgumentException("Saved rect must be positive: " + rect);
            }
            if (rect.x < this.heapMinX || rect.z < this.heapMinZ || rect.x + rect.w > this.heapMinX + this.heapSize ||
                    rect.z + rect.h > this.heapMinZ + this.heapSize) {
                throw new IllegalArgumentException("Saved rect out of heap bounds: " + rect);
            }
            for (Rect other : this.allocatedRects) {
                if (other.intersects(rect)) {
                    throw new IllegalArgumentException("Saved rect overlaps existing allocation: " + rect);
                }
            }
            this.occupy(rect);
        }
        this.coalesce();
    }

    public void occupy(Rect rect) {
        this.carve(rect);
        this.allocatedRects.add(rect.copy());
    }

    public void reoccupy(Rect rect) {
        this.occupy(rect);
    }

    public List<Rect> getAllocatedRects() {
        return this.allocatedRects;
    }

    public Rect allocate(int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Allocator request must be positive: " + w + "x" + h);
        }
        for (Rect free : this.freeRects) {
            if (hostsInMainRegion(free, w, h)) {
                return this.take(free, Math.max(free.x, this.mainMinX), Math.max(free.z, this.mainMinZ), w, h);
            }
        }
        for (Rect free : this.freeRects) {
            if (free.w >= w && free.h >= h) {
                return this.take(free, free.x, free.z, w, h);
            }
        }
        throw new IllegalStateException("Sub-world allocator is out of space");
    }

    private boolean hostsInMainRegion(Rect free, int w, int h) {
        return free.w >= w && free.h >= h && free.x + free.w > this.mainMinX && free.z + free.h > this.mainMinZ &&
                Math.max(free.x, this.mainMinX) + w <= free.x + free.w &&
                Math.max(free.z, this.mainMinZ) + h <= free.z + free.h;
    }

    @Nullable public Rect allocateContaining(int w, int h, int chunkX, int chunkZ) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Allocator request must be positive: " + w + "x" + h);
        }
        for (Rect free : this.freeRects) {
            if (free.w < w || free.h < h) {
                continue;
            }
            int ox = Math.max(free.x, Math.min(free.x + free.w - w, chunkX - w + 1));
            int oz = Math.max(free.z, Math.min(free.z + free.h - h, chunkZ - h + 1));
            if (chunkX >= ox && chunkX < ox + w && chunkZ >= oz && chunkZ < oz + h) {
                return this.take(free, ox, oz, w, h);
            }
        }
        return null;
    }

    @Nullable public Rect allocateAt(int x, int z, int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Allocator request must be positive: " + w + "x" + h);
        }
        if (x < this.heapMinX || z < this.heapMinZ || x + w > this.heapMinX + this.heapSize ||
                z + h > this.heapMinZ + this.heapSize) {
            return null;
        }
        Rect spot = new Rect(x, z, w, h);
        for (Rect other : this.allocatedRects) {
            if (other.intersects(spot)) {
                return null;
            }
        }
        this.carve(spot);
        this.allocatedRects.add(spot);
        this.coalesce();
        return spot.copy();
    }

    private Rect take(Rect free, int ox, int oz, int w, int h) {
        this.freeRects.remove(free);
        if (ox > free.x) {
            this.freeRects.add(new Rect(free.x, free.z, ox - free.x, free.h));
        }
        if (free.x + free.w > ox + w) {
            this.freeRects.add(new Rect(ox + w, free.z, free.x + free.w - ox - w, free.h));
        }
        if (oz > free.z) {
            this.freeRects.add(new Rect(ox, free.z, w, oz - free.z));
        }
        if (free.z + free.h > oz + h) {
            this.freeRects.add(new Rect(ox, oz + h, w, free.z + free.h - oz - h));
        }
        Rect alloc = new Rect(ox, oz, w, h);
        this.allocatedRects.add(alloc);
        this.coalesce();
        return alloc.copy();
    }

    public boolean isAllocated(Rect rect) {
        return this.allocatedRects.contains(rect);
    }

    public boolean freeOwned(Rect rect) {
        for (int i = 0; i < this.allocatedRects.size(); i++) {
            Rect owned = this.allocatedRects.get(i);
            if (owned.x == rect.x && owned.z == rect.z && owned.w == rect.w && owned.h == rect.h) {
                this.allocatedRects.remove(i);
                this.freeRects.add(rect.copy());
                this.coalesce();
                return true;
            }
        }
        return false;
    }

    public boolean expand(Rect rect, int west, int north, int east, int south) {
        if (west < 0 || north < 0 || east < 0 || south < 0) {
            return false;
        }
        if (west == 0 && north == 0 && east == 0 && south == 0) {
            return true;
        }
        int newX = rect.x - west;
        int newZ = rect.z - north;
        int newW = rect.w + west + east;
        int newH = rect.h + north + south;
        if (newX < this.heapMinX || newZ < this.heapMinZ || newX + newW > this.heapMinX + this.heapSize ||
                newZ + newH > this.heapMinZ + this.heapSize) {
            return false;
        }
        Rect band = new Rect(newX, newZ, newW, newH);
        Rect owned = null;
        for (Rect other : this.allocatedRects) {
            if (other.equals(rect)) {
                owned = other;
            } else if (other.intersects(band)) {
                return false;
            }
        }
        if (owned == null) {
            return false;
        }
        owned.x = newX;
        owned.z = newZ;
        owned.w = newW;
        owned.h = newH;
        rect.x = newX;
        rect.z = newZ;
        rect.w = newW;
        rect.h = newH;
        this.carve(band);
        this.coalesce();
        return true;
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
