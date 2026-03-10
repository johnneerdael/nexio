import java.nio.ByteBuffer;

public class TestFireOsOutput {

    static class FakeAudioOutput {
        long framesWritten = 0;
        long framesPlayed = 0;
        int sampleRate = 48000;
        boolean playing = false;
        long lastRealtimeMs = 0;
        
        public void play(long nowMs) {
            update(nowMs);
            playing = true;
        }
        
        public void pause(long nowMs) {
            update(nowMs);
            playing = false;
        }
        
        public boolean write(int bytes, long nowMs) {
            update(nowMs);
            int frames = bytes / 4; // assuming 16-bit stereo = 4 bytes/frame
            framesWritten += frames;
            return true;
        }
        
        public long getPositionUs(long nowMs) {
            update(nowMs);
            return (framesPlayed * 1_000_000L) / sampleRate;
        }
        
        private void update(long nowMs) {
            if (lastRealtimeMs == 0) lastRealtimeMs = nowMs;
            long elapsedMs = nowMs - lastRealtimeMs;
            if (playing && elapsedMs > 0) {
                long framesToPlay = (elapsedMs * sampleRate) / 1000L;
                long unplayed = framesWritten - framesPlayed;
                framesPlayed += Math.min(framesToPlay, unplayed);
            }
            lastRealtimeMs = nowMs;
        }
    }

    static class FireOsIecOutput {
        FakeAudioOutput superOutput;
        long syntheticPauseRemainingUs = 0;
        long lastPauseUpdateRealtimeMs = 0;
        boolean playing = false;
        
        public FireOsIecOutput(FakeAudioOutput out) {
            this.superOutput = out;
        }
        
        public void play(long nowMs) {
            updateSyntheticPauseCompensation(nowMs);
            playing = true;
            superOutput.play(nowMs);
        }
        
        public void pause(long nowMs) {
            updateSyntheticPauseCompensation(nowMs);
            playing = false;
            writePauseBurst(200, nowMs); // 200ms pause burst
            superOutput.pause(nowMs);
        }
        
        public boolean write(int bytes, long nowMs) {
            updateSyntheticPauseCompensation(nowMs);
            return superOutput.write(bytes, nowMs);
        }
        
        public long getPositionUs(long nowMs) {
            updateSyntheticPauseCompensation(nowMs);
            return Math.max(0L, superOutput.getPositionUs(nowMs) - syntheticPauseRemainingUs);
        }
        
        private void writePauseBurst(int millis, long nowMs) {
            int bytes = (millis * 48000 / 1000) * 4;
            superOutput.write(bytes, nowMs);
            syntheticPauseRemainingUs += millis * 1000L;
            lastPauseUpdateRealtimeMs = nowMs;
        }
        
        private void updateSyntheticPauseCompensation(long nowMs) {
            if (!playing || syntheticPauseRemainingUs <= 0) {
                lastPauseUpdateRealtimeMs = nowMs;
                return;
            }
            long elapsedUs = Math.max(0L, (nowMs - lastPauseUpdateRealtimeMs) * 1000L);
            syntheticPauseRemainingUs = Math.max(0L, syntheticPauseRemainingUs - elapsedUs);
            lastPauseUpdateRealtimeMs = nowMs;
        }
    }

    public static void main(String[] args) {
        FakeAudioOutput fakeOut = new FakeAudioOutput();
        FireOsIecOutput fireOut = new FireOsIecOutput(fakeOut);
        
        long now = 1000;
        
        // Write 1 second of normal audio
        fireOut.write(48000 * 4, now);
        fireOut.play(now);
        
        System.out.println("TimeMs | RealPlayheadUs | SynthRemainUs | OutputPosUs");
        for (int i = 0; i <= 500; i += 50) {
            long pos = fireOut.getPositionUs(now);
            System.out.printf("%6d | %14d | %13d | %11d\n", now, fakeOut.getPositionUs(now), fireOut.syntheticPauseRemainingUs, pos);
            now += 50;
        }
        
        // Pause for 500ms
        System.out.println("PAUSING");
        fireOut.pause(now);
        for (int i = 0; i <= 500; i += 50) {
            long pos = fireOut.getPositionUs(now);
            System.out.printf("%6d | %14d | %13d | %11d\n", now, fakeOut.getPositionUs(now), fireOut.syntheticPauseRemainingUs, pos);
            now += 50;
        }
        
        // Resume
        System.out.println("RESUMING");
        fireOut.play(now);
        for (int i = 0; i <= 500; i += 50) {
            long pos = fireOut.getPositionUs(now);
            System.out.printf("%6d | %14d | %13d | %11d\n", now, fakeOut.getPositionUs(now), fireOut.syntheticPauseRemainingUs, pos);
            now += 50;
        }
    }
}
