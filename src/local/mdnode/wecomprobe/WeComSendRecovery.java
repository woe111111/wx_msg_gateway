package local.mdnode.wecomprobe;

final class WeComSendRecovery {
    private int failedReads;
    private boolean reopened;

    boolean retryRead() { return ++failedReads <= 3; }

    void readSucceeded() { failedReads = 0; }

    boolean mayReopen(boolean beforeClickReservation, WeComLayout.DraftState draft) {
        return beforeClickReservation && !reopened && draft == WeComLayout.DraftState.EMPTY;
    }

    void reopened() { reopened = true; }

    void reset() {
        failedReads = 0;
        reopened = false;
    }
}
