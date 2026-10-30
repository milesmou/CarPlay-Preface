#include <cstdlib>

// Used only to prove rejection before invoking a vendor-shaped symbol. This
// fixture has its own build ID, no map implementation and no readiness output.
extern "C" void* foreignAnchor() asm("_ZN3hsl3HSL19GetMapServiceHandleEv");
extern "C" void* foreignAnchor() {
    std::abort();
}
