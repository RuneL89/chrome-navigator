# UAT Device Session — Owner's Guide (Wireless, "For Dummies" Edition)

> Your part: ~5 minutes of tapping on the phone, then sit back, watch it work, and make
> two judgment calls. Everything technical is done for you by the orchestrator.
> Session length: ~45 minutes end to end (most of it is a one-time wireless install).

---

## Part 1 — One-time phone setup (you, ~3 minutes)

1. **Unlock the phone, connect to your home Wi-Fi.** The phone and this computer must be
   on the same network.
2. **Enable Developer options** (skip if already done):
   - **Settings → About phone → Software information → tap "Build number" 7 times**
   - Enter your PIN when asked. You'll see "Developer mode enabled".
3. **Turn on Wireless debugging:**
   - **Settings → Developer options → Wireless debugging → toggle ON**
   - Tap **Allow** on the popup ("Allow wireless debugging on this network?" → Always allow).
4. **Keep the phone charged above ~50%.**

Done. Tell me: **"wireless debugging is on"**.

## Part 2 — The pairing handoff (you read, I type, ~2 minutes)

This is the only "technical" thing you'll do, and it's just reading numbers to me.

1. Tap into **Wireless debugging** (the screen behind the toggle).
2. Tap **"Pair device with pairing code"**. The phone shows:
   - **Wi-Fi pairing code** — a 6-digit number
   - **IP address & Port** — something like `192.168.1.42:37851`
3. **Tell me both, immediately** (the code expires in ~a minute). I run the pairing.
4. After I say "paired", go **back one screen** to the main Wireless debugging page and
   **tell me the IP address & Port shown there** (it's a different port than the pairing
   one). I connect. I'll confirm with "connected".

*If the code expires before I use it — no harm, just tap "Pair device with pairing code"
again for a fresh one.*

## Part 3 — Everything automatic (me, ~10–15 minutes, you wait)

While you wait, on your phone screen nothing visible happens except maybe brief
notifications. I am: connecting → installing the app (it's large, ~700 MB, because it
carries the on-device Laya model — wireless install takes a while, that's normal) →
switching on its accessibility service by command (no menus for you) → setting the screen
to stay awake → putting the app on Samsung's never-sleep list.

I'll say **"ready to watch"** when it's time for your part.

## Part 4 — UAT 6.1: Watch it work (you + me, ~15 minutes)

I'll start goals one at a time. For each one:

**What you'll see:** Chrome opens a real website by itself. A small step log updates as
the agent thinks and acts — you'll see it scroll, tap links, and read pages in real time.
This is the product's core experience: watching the agent work.

**Your three jobs:**
1. **Watch for visible progress.** Does it move with purpose toward the goal — or wander?
2. **Answer gate cards.** If the agent ever wants to do something high-stakes (enter a
   password, buy something, submit a payment), a card pops up with **Approve / Deny** and
   a 30-second countdown. **Timeout counts as Deny — nothing can happen without you.**
   Tap what you actually want. It's fine (and informative) to deny.
3. **Judge the ending.** The two failure feels: stops too early (claims success before the
   answer is really on screen) or too late (keeps going after the answer was visible).

**After each goal I'll ask you one question:** *"Approve, or what felt off?"* — one line
back is enough. Your words go into the project record verbatim.

**Known quirks you may see (found in pre-testing — seeing them live is part of the test):**
- On recipe/cooking sites there may be a **cookie consent wall** the agent handles badly
  (it knows the Accept button exists but tends to ignore it). Watch what happens — does it
  recover or give up?
- The agent can occasionally **get stuck in a loop** (tapping an image, going back,
  repeating). There's a detector that should kill the task cleanly — watch whether it does.
- Every task has a hard **budget cap ($0.25)** — worst case, a task stops itself with
  "budget exhausted". That's the safety net working, not a crash.

**Practical:** don't tap the screen while a goal runs (it cancels the agent's gestures);
keep the phone unlocked; if it gets warm, that's the on-device AI model running — expected.

## Part 5 — The hands-off run (me, ~5 minutes, you can put the phone down)

I run the full scripted suite on real sites with automatic gate answers to produce clean
measurement data — including the shadow-coverage number that needs real hardware.

## Part 6 — UAT 6.2: The Milestone 0 decision (you, ~5 minutes)

I present a one-page read-out: goal-by-goal results from stub vs emulator vs your phone,
spend, speed, agreement %. Then the one decision only you can make:

> **"Is the measured decision quality good enough to commit to building v1
> (the overlay UI, cloud roles, result screen, and final test suite)?"**

- **"Accept"** → Milestone 0 is officially complete; Phase 9 (the actual product UI)
  starts on your go.
- **"Not yet"** → we fix what bothered you first. Both answers are normal and correct.

## Part 7 — Cleanup (optional, 30 seconds)

- **Settings → Developer options → Wireless debugging → OFF** (you can leave it on if
  we'll do more sessions).
- Developer options can stay enabled — harmless.
- The app ("Navigator") stays installed for future sessions.

## FAQ

- **"Pairing failed / device not found"** → phone and computer on different networks
  (guest Wi-Fi vs main), or the code expired. Re-do Part 2.
- **"The install is slow"** → ~700 MB over Wi-Fi. 5–15 minutes is normal.
- **"A goal seems frozen"** → tell me; I'm watching the logs too. Every task is
  guaranteed to end by its budget (max ~2 minutes of work each).
- **"What does this cost?"** → cents per goal; every task hard-capped at $0.25; the whole
  session typically under $1.50.
- **"What leaves my phone?"** → page text goes to the decision API (OpenRouter) only;
  screenshots never leave the device; all logs stay on the phone.
- **"Can I stop mid-session?"** → any time. Say "stop" and I halt everything; progress
  and logs are kept.
