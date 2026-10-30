public final class Preface95FrameGateTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        Preface95FrameGate gate = new Preface95FrameGate();
        long lease = gate.openSurface(), content = gate.changeContent();
        check(!gate.confirm(lease, content, false, true));
        check(!gate.confirm(lease, content, true, false));
        check(gate.confirm(lease, content, true, true));
        check(!gate.confirm(lease, content, true, true));
        check(gate.closeSurface());
        check(!gate.confirm(lease, content, true, true));
        long replacement = gate.openSurface();
        check(!gate.confirm(lease, content, true, true));
        long update = gate.changeContent();
        check(!gate.confirm(replacement, content, true, true));
        check(gate.confirm(replacement, update, true, true));
        check(gate.closeSurface());
        check(!gate.closeSurface());
        System.out.println("Frame lease: real-content requirement, readback failure, deduplication, stale update and surface reuse passed");
    }
}
