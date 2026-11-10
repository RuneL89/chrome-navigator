# Chrome Navigator

**You type the goal. It does the browsing.**

Chrome Navigator is a personal Android app that drives Google Chrome for you. Tap New task in the Navigator notification, type what you want in plain language ("find a recipe for apple pie", "add two cases of this drink to the cart and check out with delivery"), and watch an agent work through the site live. It plans the task into small steps it can verify, taps and types its way toward the goal, interrupts you before anything consequential, and ends with an answer or an honest refusal.

The ground rules, stated up front:

* It is personal and sideloaded. There is no Play Store listing; you build it, install it over adb, and it runs under your API keys. Its records stay on your device.
* It drives Chrome, stable channel, and nothing else. It is not a phone agent; other apps and other browsers are out of scope by design.
* Legibility is a feature. A corner log narrates every step in plain language, cards interrupt before risky actions, and every run leaves a complete evidence record behind.

What a run gives you:

* a readable plan: the goal becomes a ladder of up to eight subtasks, each with a done condition code can check, shown as a checklist that ticks off in the corner log
* a live log narrating every step as it happens
* a gate card before anything consequential: a purchase, a login, a payment, a form submit, with a 30-second countdown; no answer counts as no
* an extracted answer when the goal asks a question, plus the full run record: screenshots, every model call, the event log
* truthful endings: goal achieved, confirmed done, out of budget, cancelled by you, or refused with the reason on the log

This is not a hypothetical. A real grocery checkout (search, product page, cart, home delivery, saved card, order confirmation) has completed end to end: 55 steps, two mid-run re-plans, order placed.

The rest of this page shows how a run works, twice: first at a glance, then in detail. If you just want to run it, skip to [Getting started](#getting-started).

## Getting started

You need an Android 14 or newer phone with Chrome (stable) and adb on your computer. Build the debug APK and install it:

```
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

(On Linux or macOS, use `./gradlew` instead of `gradlew.bat`.)

First launch opens a setup wizard with five checks:

1. **Accessibility service.** The app reads pages and performs gestures through Android's accessibility service. Android 13 and newer hide the toggle behind "Allow restricted settings" for newly installed apps; the wizard explains where it is.
2. **Battery whitelist.** On Samsung phones, exempt the app from battery optimization so the service survives. Stock Android offers no programmatic way in, so the wizard walks you through the taps by text. Other phones pass this row automatically.
3. **OpenRouter key.** This key carries the writer model. It is stored encrypted on the device and never rendered back.
4. **TypeSafe key.** This key carries the judgment model. Same storage, same rules.
5. **Test keys.** One tap that asks both providers whether the stored keys are live. It never runs by itself and never blocks the wizard.

Every row re-evaluates the moment you come back from Settings, so nothing needs a restart. When all rows are green, the app starts the navigator service and a persistent notification appears with a New task action.

When it works, this is the experience: tap New task, the screen blurs behind a text box, you type the goal and tap Submit. A small log in the bottom corner starts streaming steps. Whenever an action is consequential, a card takes over: what it wants to do, on which site, with what consequence, Approve or Deny, 30 seconds. At the end the log prints the terminal line, and if the goal asked a question, the answer is in the run report.

## A run, start to finish

![A goal run from the overlay to the answer: the writer model plans a ladder of subtasks, then a loop of read the page, done check, propose and ground controls, risk gate, and act runs until every subtask is done, ending with the answer and the evidence record on the device](docs/images/goal-run-overview.png)

*How to read it: the boxed loop repeats once per subtask; a refused step stays inside the loop, and the done check is the only way out.*

The goal you type is rewritten by the writer model into a ladder: up to eight subtasks in order, each with a done condition code can check (this URL reached, this text on screen, this element present, a count reached, an answer extracted). The plan shows up at the top of the corner log as a checklist and ticks off as subtasks complete.

Then the loop runs. Each step reads the page (the accessibility tree plus a screenshot of Chrome's window), checks whether the subtask is already done, and if not, decides and performs one action: a tap, a typed field, a scroll. Two kinds of card can interrupt:

* **A gate card** comes before consequential actions. It names the action's class, the site, and the consequence, and counts down from 30 seconds. Deny, or let the countdown run out, and the step is refused and logged. The first approval of a class extends trust for the rest of the task. Credential fields (logins, card numbers, passwords) get a masked input on the card instead: what you type there goes straight into the field, lives in memory for a moment, and is never logged and never sent to any model.
* **A failure card** comes when the agent is genuinely stuck: three failed attempts on one subtask, or a detector tripping on repeated no-progress. It offers Retry, Re-plan (a new ladder, informed by what failed), or Stop. Thirty seconds without an answer stops.

Near the end, one more card can appear: the agent believes the goal is complete and asks you to confirm (Confirm done or Keep going; a timeout keeps going). The card is a last check, not the decision; completion is verified by code.

The endings are explicit: goal achieved, confirmed done, re-plans exhausted, steps or budget used up, cancelled by you, or refused, with the reason on the log. Every run also writes its evidence to the device: the event log, every model request and response, and the screenshots, keyed by run id.

## How it works inside

![One vision-engine step as a sequence diagram between loop code, the writer model, the judgment model, Chrome, and you: read the page, check the done condition, run a verification battery, propose up to three controls, ground and pick one, classify the risk, show a gate card for a consequential action, then act in Chrome](docs/images/vision-step-detail.png)

*How to read it: solid arrows are requests and dashed arrows are answers; the boxed section is the only branch, and it opens only for a consequential action.*

Two cloud models do the thinking, and their jobs do not overlap:

* The **writer** (Haiku, over OpenRouter) handles every generative job: it turns the goal into the ladder, authors the verification battery, proposes up to three controls for the current screen, repairs done conditions, and extracts the final answer. It can propose and describe; it can never execute anything and never declares the goal complete.
* The **judgment model** (Jev, over the TypeSafe route) answers four question types only: which of these options to take, is this claim true (a yes or no with a confidence number; the project calls the answer a noul), how do these candidate targets score, and where is this target (a box on the screenshot). It cannot author an action.

Code owns everything with a number in it: the done-condition checks, the risk gate, the budgets, the timeouts, the thresholds, and the final verdict. Page content, text and pixels alike, is labeled untrusted in every prompt; instructions found on a page are never obeyed.

The verification battery runs before the first touch. For the active subtask, the writer authors a handful of checks; the judgment model answers them over text and image; the writer adds one independent image pass. Any confident red flag stops the step before the screen is touched. Only then are controls proposed, grounded (the model's box is matched to a real accessibility node, with a similarity floor of 0.20 and documented fallbacks), and dispatched through the code-owned gate.

Every run is auditable after the fact: prompts and state formats are versioned, and the run directory holds every request and response, so a step can be replayed and checked later.

### The three engines

The vision engine above is the default. Two more engine policies exist behind the same product surface:

* **ALWAYS_VISION** (the default) is the loop described above: read both channels every step, both models on every step.
* **TREE_ONLY** is the retained text-only engine: the judgment model picks from a code-enumerated tree of candidates, no screenshots. It runs when you switch it on explicitly in Setup, and it is kept alive because the planned hybrid combines a11y speed with image accuracy.
* **TREE_FIRST** is that hybrid, implemented but not yet the default. The a11y tree carries ordinary steps at a fraction of the cost; three code-owned signals (no unique ground, a thin or empty tree, a step that changed nothing) escalate the active subtask to the vision engine until that subtask ends, and the tree resumes at the next one. It is selectable today through a debug-only adb switch. The Setup selector and the default flip wait on its acceptance comparison.

## Honest limitations

* **Your keys, your spend.** Every step makes paid model calls at two providers. Budgets bound the damage: soft ceilings warn, hard ceilings stop the run. The shipped hard ceilings are deliberately generous (6,000 steps, about 16 hours, $25 per task, and a $10 sub-ceiling for the writer) so a runaway loop still has a wall; the checkout above used 55 steps.
* **One phone, one user.** Personal software: no Play Store, no accounts, no multi-device support. Sideload or nothing.
* **Chrome stable only.** The reader specializes on Chrome's stable channel; Beta, Canary, other browsers, and WebView are explicitly out.
* **Android 14 and newer.** The minimum SDK is 34.
* **The Samsung battery row is manual.** Stock Android gives an app no lawful way to whitelist itself, so the wizard guides your taps instead.
* **The tree-first hybrid is not the default yet.** It works and it is selectable, but the product default moves only after its acceptance comparison says so.
* **It refuses rather than improvises.** An unreadable page, a failed plan, an exhausted budget: the run ends with the truthful reason instead of pressing on. Sometimes that is the right answer; sometimes it is just an early stop.

## Technical appendix

Pure Kotlin. Jetpack Compose for the UI, OkHttp for transport, kotlinx.serialization for the wire formats; minSdk 34, targetSdk 36, Java 17. Two Gradle modules, and no on-device models (an earlier on-device training program was retired and removed):

```
core/                          :core, pure-JVM Kotlin
  types, wire clients, the a11y state builder, the ladder schema and
  validator, the done-condition checker, the risk gate's classification net
app/                           :app, the Android application
  src/main/kotlin/navigator/app/
    observe/                   accessibility reader, browser allowlist, URL-bar reader
    overlay/                   goal input, corner live log, gate cards, the foreground service
    setup/                     five-check wizard, encrypted key store, engine policy
    gen/                       writer client, ladder schema
    loop/                      the retained a11y engine, shared ladder/gate machinery
    harness/vision/            the vision engine (the default)
```

Decisions worth knowing before you change code:

* Both model roles are pinned in code: model ids, endpoints, and integer micro-dollar prices. Upgrading a model is a deliberate version bump gated by the test suite, not a config edit.
* Every threshold (risk classes, confidence bands, similarity floors, budgets, countdowns) lives in code. Models answer questions; they never do arithmetic on the run.
* Prompts and state formats are versioned, and golden fixtures in the test suites pin them byte for byte.
* The docs are load-bearing: `AGENTS.md` files are binding contracts per folder, [Project Vision](Project%20Vision/) is the canon the code is checked against, [Implementation Plan](Implementation%20Plan/) holds the phase docs, and `.state/` keeps the compliance and verification history.

Chrome Navigator is a working title; the documents use the slug NAVIGATOR.

## Where things live

* On the device: each run's evidence under `Android/data/navigator.app/files/run-reports/vision-exp/<runId>/` (event log, raw model calls, screenshots, summary), pullable with adb. Keys live in the app's encrypted store and nowhere else.
* Tests: `gradlew.bat :core:test` (decision core) and `gradlew.bat :app:testDebugUnitTest` (app and vision suites).
* [docs/diagrams/](docs/diagrams/): the editable Mermaid sources behind the two images above; each file carries its render command.
* [docs/UAT_DEVICE_SESSION_GUIDE.md](docs/UAT_DEVICE_SESSION_GUIDE.md): running acceptance sessions on a device.
* [NOTICE](NOTICE): attribution for the observation layer, reimplemented with reference to PokeClaw.
* [AGENTS.md](AGENTS.md): start here to navigate the whole documentation chain.
