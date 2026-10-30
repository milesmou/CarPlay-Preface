/** Owns only our Surface lease; it never reads a vendor object layout. */
public final class Preface95FrameGate {
    private long generation;
    private long revision;
    private boolean surface;
    private boolean ready;

    public long openSurface() {
        generation++;
        surface = true;
        ready = false;
        return generation;
    }

    public long changeContent() { return ++revision; }
    public long generation() { return generation; }
    public long revision() { return revision; }
    public boolean ready() { return ready; }

    public boolean current(long lease, long content) {
        return surface && generation == lease && revision == content;
    }

    public boolean confirm(long lease, long content, boolean guidance, boolean pixelsMatch) {
        if (!guidance || !pixelsMatch || !current(lease, content) || ready) return false;
        ready = true;
        return true;
    }

    public boolean closeSurface() {
        boolean hadReady = ready;
        generation++;
        surface = false;
        ready = false;
        return hadReady;
    }
}
