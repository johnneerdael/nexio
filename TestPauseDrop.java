public class TestPauseDrop {
    public static void main(String[] args) {
        long superGetPositionUs = 1_000_000L;
        long syntheticPauseRemainingUs = 0;
        
        // Before pause
        long posBefore = Math.max(0L, superGetPositionUs - syntheticPauseRemainingUs);
        System.out.println("Before pause: " + posBefore);
        
        // pause() is called
        // writePauseBurst(200)
        long burstDurationUs = 200_000L;
        syntheticPauseRemainingUs += burstDurationUs;
        
        // After pause
        long posAfter = Math.max(0L, superGetPositionUs - syntheticPauseRemainingUs);
        System.out.println("After pause (paused): " + posAfter);
    }
}
