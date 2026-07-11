# AI-USAGE.md

I used `[Claude Code]` for effectively all of the code and prose in this
submission, including the review, the fixes, the tests, and this document.
I'm saying that plainly because the assignment is explicit that heavy AI
use isn't penalized — not knowing what your own code does is.

## My actual workflow

For each finding, roughly:

1. **Asked the AI to explain the mechanism before asking it to fix
   anything.** E.g. for the AWS SNS gateway, I first asked it to explain
   how SigV4 signing works and why RFC 3986 percent-encoding differs from
   `application/x-www-form-urlencoded` encoding on space, `~`, and `*`; for
   the segment-count bug, I asked it to explain GSM-7 vs UCS-2 and why
   concatenated segments have a smaller per-segment limit (153/67) than a
   single segment (160/70). I made sure I understood the explanation myself
   before letting it touch the code — I didn't want to be in a position
   where I couldn't tell if its fix was actually correct or just
   plausible-looking, which is the whole premise of this assignment.
2. **Had it implement the fix as a narrow, explicit task** rather than a
   broad one — e.g. "write an RFC 3986 percent-encoder: unreserved set
   `A-Z a-z 0-9 - _ . ~`, space becomes `%20`, uppercase hex," not "fix the
   signing." A broad prompt gets a broad, harder-to-verify answer.
3. **Had it write the failing test first, and made sure I understood why
   it would fail on the original code before adding it.** If I couldn't
   explain the failure myself, that told me I hadn't actually understood
   the bug yet, regardless of what the agent had produced.

## What I decided myself rather than delegating

- **Which of the 8+ findings were worth fixing.** Ranking severity and
  picking the top 3 is a judgment call about what actually costs money or
  leaks data in production — the agent can list issues, but doesn't know
  which ones matter most to this business.
- **Whether each explanation and each test actually held up** before I
  accepted it, per the workflow above.