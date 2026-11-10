# Experiments

One file per experiment: `NNNN-what-was-measured.md`, numbered like ADRs. An experiment
records what was measured and what came out; a decision taken because of it is an ADR that
links here.

Template:

```markdown
# NNNN. <What was measured>

- Date: YYYY-MM-DD
- Commit: <sha the measurement ran on>

## Hypothesis

<What you expected, and why. Written before running.>

## Setup

<Hardware, data size, load profile, configuration, exact commands. Enough to rerun it.>

## Result

<Numbers, charts, raw output location. Include runs that failed.>

## Conclusion

<What the result means, what it does not show, what to try next.>
```
