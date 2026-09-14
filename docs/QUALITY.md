# Transition quality: measured state

Every render is scored by `engine:metrics` (`ArtifactMetrics`). This file records what the metrics say today on
the synthetic fixture set, so the numbers you are tuning against are written down rather than assumed. Regenerate
it any time with:

```
muisc synth --set --out fixtures/
muisc mix fixtures/*.wav -o set.wav --context shuffle
muisc ab fixtures/t120C.wav fixtures/t126Am.wav -o ab/
```

## What the metrics mean

| Metric | Meaning | Fails at |
|---|---|---|
| `clicks` | Sample-level discontinuities not explained by an onset in either source | any |
| `levelJumpDb` | Short-term RMS jump not explained by a source onset | 6 dB |
| `truePeakDbtp` | Inter-sample peak of the render | above -0.5 dBTP |
| `seamIdentity` | The render's first/last frames must equal the deck-gained source samples | mismatch |
| `beatAlignmentMs` / `beatAlignmentMaxMs` | Detected onsets in the render against the master beat grid | median 5 ms / max 12 ms |
| `loudnessSmoothness` | Second derivative of short-term loudness | 3 LU/s² |
| `tailContainedDb` | Effect tails must not spill past the segment | audible spill |

## Known failures on the fixture set

These are real, reproducible, and open. They are recorded here rather than hidden because the whole point of the
metrics is to make transition quality measurable while it is tuned.

1. **Clicks in the assembled program.** A four-track shuffle mix reports `clicks = 2` on the final output even
   though each transition segment is individually click-free. The defect is therefore in how segments are joined
   by the player (the seam fade or a body boundary), not inside a strategy.
2. **Level jumps through `echoOut`.** 27–35 dB short-term jumps at the point where the dry signal is cut and only
   the echo tail continues. The cut is correct in principle (the frozen `LiveNode.Echo` contract says A is silent
   after the cut) but the drop is far too abrupt as rendered.
3. **Beat alignment beyond budget.** Max deviations of 60–85 ms against the master grid on `echoOut` and
   `phraseCut`, well outside the 12 ms budget. These two strategies are not beat-domain, so some deviation is
   expected, but not this much; either the metric should not apply to them or the entry points are wrong.
4. **Zero-length body segment.** A mix can schedule one transition to end exactly where the next begins, leaving a
   body segment of zero frames. It is audibly harmless today but it means a track can be scheduled with none of
   itself actually playing, which is wrong.
5. **`seamIdentity = 0` on `phraseCut`.** Worth confirming whether the metric is mis-applied to a hard cut or the
   strategy genuinely breaks the splice contract.
6. **Loudness smoothness** warns on most transitions (6–24 LU/s² against a 3 LU/s² budget).

## Honest limits

- The fixtures are synthetic. They have exact grids and clean spectra, so they flatter beat tracking and key
  detection; real music will be harder in ways this corpus cannot show.
- Pseudo-stems are signal processing, not source separation. `stemSwap` with them behaves like a staggered
  three-band EQ mix, and is scored accordingly.
- "Every song into every song" is guaranteed only as "a plan always exists and the render is checked". For a
  genuinely incompatible pair that plan is an echo-out, an ambient bridge or a crossfade.
