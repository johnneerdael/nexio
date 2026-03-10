public class TestPositionJitter {
    public static void main(String[] args) {
        long burstDurationUs = 200_000L;
        long deltaUs = 50_000L; // consumed before pause
        
        long superGetPositionUs = 1_000_000L + deltaUs; // advanced during write
        long syntheticPauseRemainingUs = burstDurationUs;
        long lastPauseUpdateRealtimeMs = 0;
        long now = 0;

        System.out.println("TimeMs | SuperPosUs | SynthRemainUs | OutputPosUs");
        for (int i = 0; i <= 300; i += 50) {
            now = i;
            
            // updateSyntheticPauseCompensation
            long elapsedUs = (i > 0) ? 50_000L : 0; 
            syntheticPauseRemainingUs = Math.max(0L, syntheticPauseRemainingUs - Math.max(0, elapsedUs));
            lastPauseUpdateRealtimeMs = now;
            
            // audio track advances in real time
            if (i > 0) {
                superGetPositionUs += 50_000L; 
            }
            
            long pos = Math.max(0L, superGetPositionUs - syntheticPauseRemainingUs);
            System.out.printf("%6d | %10d | %13d | %11d\n", now, superGetPositionUs, syntheticPauseRemainingUs, pos);
        }
    }
}
