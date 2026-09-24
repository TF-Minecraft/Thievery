package net.tfminecraft.thievery.door;

final class FactionLockWarningGate {

    static final long MIN_GAP_MS = 30_000L;

    private FactionLockWarningGate() {}

    static boolean shouldSend(boolean dismissed, Long previousSentAtMs, long nowMs) {
        if (dismissed) {
            return false;
        }
        return previousSentAtMs == null || nowMs - previousSentAtMs >= MIN_GAP_MS;
    }
}
