import re

with open('./media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProvider.java', 'r') as f:
    content = f.read()

# Replace the permissive candidate probing with a strict one that matches Kodi.
# Kodi commits to the parsed stream type and doesn't unconditionally append DTS_HD, E_AC3, and TRUEHD to a fallback list.
old_probing = '''    ArrayList<PackerKind> candidateKinds = new ArrayList<>(4);
    addCandidateKind(candidateKinds, detectPackerKindKodiStyle(inspectionBytes));
    if (streamInfoHint != null) {
      addCandidateKind(candidateKinds, getPackerKind(streamInfoHint));
    }
    addCandidateKind(candidateKinds, PackerKind.DTS_HD);
    addCandidateKind(candidateKinds, PackerKind.E_AC3);
    addCandidateKind(candidateKinds, PackerKind.TRUEHD);'''

new_probing = '''    ArrayList<PackerKind> candidateKinds = new ArrayList<>(2);
    // Like Kodi DVDAudioCodecPassthrough::Init, commit to parsed or hinted type, do not unconditionally fallback to other codecs.
    addCandidateKind(candidateKinds, detectPackerKindKodiStyle(inspectionBytes));
    if (streamInfoHint != null) {
      addCandidateKind(candidateKinds, getPackerKind(streamInfoHint));
    }
    if (candidateKinds.isEmpty()) {
      // If we completely failed to identify the stream from its sync words and mime type,
      // fallback to guessing based on the incoming Format mime type instead of blindly probing all IEC packers.
      if (MimeTypes.AUDIO_DTS.equals(format.sampleMimeType) || MimeTypes.AUDIO_DTS_HD.equals(format.sampleMimeType)) {
        addCandidateKind(candidateKinds, PackerKind.DTS_HD);
      } else if (MimeTypes.AUDIO_AC3.equals(format.sampleMimeType) || MimeTypes.AUDIO_E_AC3.equals(format.sampleMimeType) || MimeTypes.AUDIO_E_AC3_JOC.equals(format.sampleMimeType)) {
        addCandidateKind(candidateKinds, PackerKind.E_AC3);
      } else if (MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)) {
        addCandidateKind(candidateKinds, PackerKind.TRUEHD);
      }
    }'''

content = content.replace(old_probing, new_probing)

with open('./media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/FireOsIec61937AudioOutputProvider.java', 'w') as f:
    f.write(content)

