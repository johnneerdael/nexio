public class TestPosition {
    public static void main(String[] args) throws Exception {
        long burstDurationUs = 200_000L;
        long syntheticPauseRemainingUs = burstDurationUs;
        long superGetPositionUs = 1_000_000L; // some position
        long now = 0;
        long lastPauseUpdateRealtimeMs = 0;

        System.out.println("TimeMs | SuperPosUs | SynthRemainUs | OutputPosUs");
        for (int i = 0; i <= 300; i += 50) {
            now = i;
            
            // updateSyntheticPauseCompensation
            long elapsedUs = (i > 0) ? 50_000L : 0; // elapsed
            syntheticPauseRemainingUs = Math.max(0L, syntheticPauseRemainingUs - Math.max(0, elapsedUs));
            lastPauseUpdateRealtimeMs = now;
            
            // audio track advances in real time while playing the burst
            if (i > 0) {
                superGetPositionUs += 50_000L; // 50ms elapsed
            }
            
            long pos = Math.max(0L, superGetPositionUs - syntheticPauseRemainingUs);
            System.out.printf("%6d | %10d | %13d | %11d\n", now, superGetPositionUs, syntheticPauseRemainingUs, pos);
        }
    }
}
