# Chrome Navigator V2 — the relaunch plan

Branch: `v2-relaunch` (from `phase-18-reliability-hardening` @ `8fc731c`).
Status: planning. No canon, no vision docs, no phase-gate machinery on this branch.
The old `Project Vision/` and `Implementation Plan/` trees are removed; they remain in
git history and on the earlier branches.

## 1. What this app is now

A personal Android app that does your web errands. It runs an embedded browser in the
background so you can keep using your phone, switches to real Chrome only with your
consent, and shows its work through two animated robot characters (Haiku and Jev) that
hand tasks to each other as the real pipeline runs.

The core promise, unchanged from Phase 18 but now enforced on a different engine:

> What you asked for · what the page says · what the models propose · what code approves ·
> what actually executed · what effect was verified · which milestone still stands.

## 2. The three lanes

1. **Fast lane — embedded WebView, background.** Reads the page structure directly
   (no screenshots needed for reading), acts on exact elements (no pixel guessing).
   Runs while you use the phone. One model layer per decision point instead of three per
   step.
2. **Human lane — real Chrome, foreground.** Used only when required: payment walls,
   captcha, broken/blocked sites, or when you press "Take over". Occupies the screen only
   with your explicit consent.
3. **Handoff.** The switch is a URL + parameters (never a session copy; WebView and Chrome
   don't share cookies). Switch points: product page by default; checkout review when the
   store's cart is account-based and you're logged in; early at the product page when
   cart type is unknown.

## 3. The rules (owner-approved, 2026-11-13)

### Global
- **Background by default, strictest rule wins.** A task leaves the background only for a
  required card (send/submit/post/change/delete/cancel/booking/invite) or required Chrome
  (payment walls, captcha, broken site, explicit consent).
- Goals are always entered in **English**; pages and labels may be any language.
- No stored secret ever appears in UI, logs, evidence, or fixtures.

### Purchases
- Fast lane never finishes a purchase. Payment and Place order always in Chrome.
- **Auto-place below €30** (editable number input), with a **15 s cancel window**.
  Above the limit: purchase card (item+qty, final total, store+address, masked payment +
  delivery). Timeout = no order.
- Budget overrun at checkout: ask with the delta ("€52.40 — €2.40 over €50"), timeout = no.
- Unspecified variants: best match to goal wording; still ambiguous → ask.
- Cookie walls: essentials only, remembered per site.
- Bills and donations follow purchase rules. Trading/betting/crypto allowed **with a card**.

### Messaging and social
- Email: webmail in the background WebView; **full draft card, you tap Send, always**.
  Replies identical. New recipients: extra gate + Setup trusted list. Groups/cc/bcc:
  always ask. Body: composed from the goal, shown exactly. Attachments: files you pick.
  After send: 30 s hold with Cancel (platform undo used when offered).
- WhatsApp: WhatsApp Web in the background; draft card. SMS: refused (app-only).
- Social: read allowed; every write (post/like/follow/comment/DM) carded; bulk refused.
- No daily send cap (every send is carded).

### Forms
- The agent **fills** in the background; **you always tap the final submit**.
- Long forms: fill what the goal covers; judgment fields handed to you.
- Uploads: any file you pick (IDs included) with a card.

### Account and irreversible actions
- Settings change: card naming the exact change. Cancel subscription: card naming what
  ends and when. New accounts: card showing site/email/accepted terms. Bookings: review
  card with date/time/place. Calendar event (own): auto + confirmation + 1 min undo.
  Calendar invite to others: draft like an email. Delete email/message: trash/archive only,
  with a card.
- Phishing: credentials requested on an unexpected domain → hard stop + warning, always.
- Captcha: always hand off. Login walls: stored logins (stores you added) with approval.

### Chrome switch consent
- Every switch: heads-up notification → "WebView needs Chrome. Approve? Yes/No" card with
  the reason shown. No answer in 5 min = No, task parked. **Always ask** — no remembered
  consent. After the Chrome leg: return to the background lane.

## 4. The UI/UX spec (40-question design interview, 2026-11-13)

### Identity
- **Two bots: Haiku (proposer) and Jev (judge).** They animate the REAL pipeline per step:
  Haiku thinks, passes a glowing task orb, Jev inspects and stamps approve/refuse, code
  executes. Silent characters, neutral copy — consequential text stays exact.
- Fun and inviting; bot-themed; dark-first comfortable; English UI; dark-only now.
- Vector rigs (Lottie-style); low-power idle; honor system reduced-motion (static poses +
  crossfade); no sounds, no haptics.

### Screens
- **App opens straight to "New Task"** (goal input) + gear top-right → Settings (today's
  Setup checks + all trust settings).
- **Corner chip**, only while a task runs: both bots + task name + `3/7`, handoff animation.
  Tap → checklist; long-press → Watch · Park · Stop. X closes it when done.
- **Watch screen**: full browser + floating log + bot stage; read-only until **Take over**;
  controls: Pause/Resume · Stop · Take over/hand back; checklist + bot stage.
- **Task list**: status cards with live mini-bots. **History**: result + checklist + final
  stamp + recorded log.
- **Empty state**: idle bots + example-goal chips.

### Cards
- Review (send): scrollable, inline edits, "edit in watch screen"; recipients chipped.
- Purchase: total is the hero; Jev stamps VERIFIED; Haiku holds Approve.
- Consent (switch to Chrome): reason → effect → Yes / Not now (parks).
- Finish: result + Copy, checklist with outcomes, bots' final stamp.

### Notifications
- Minimal (task name; no step detail) with **Watch · Stop · Approve (consent only)**.
- Needs-you: heads-up with inline Yes/Not now; tapping opens the card.
- Auto-clean; one per task.

### Settings
- Sections + bot guide: Keys · Device · Privacy · **Trust** (trusted people list with
  per-channel scope; logins as a table screen; auto-place limit as a number input) ·
  Bots (theme/animation).

## 5. Architecture (new work)

1. **WebView lane controller** (`app/.../harness/webview/`): a WebView the app owns;
   DOM extraction to a clean element list; element-targeted actions (CSS/text queries);
   navigation; cookie/session isolation; the same trust settings.
2. **Haiku/Jev contract for DOM**: Haiku receives page text + element list and returns a
   chosen element id (no boxes); Jev answers verification nouls and completion judgments
   (grounding/box primitives are retired in this lane). Prompt + schema versioned.
3. **Pipeline events** for the bots: a step event stream (proposed / judged / executed /
   verified / verified-refused) emitted by the lane; the overlay renders it as the
   handoff animation. The existing log lines map to these events.
4. **Carry-over safety laws** (adapted from Phase 18, now applied to DOM evidence):
   immutable goal contract; verified-effect progress; fresh pre-dispatch guard (element
   identity instead of coordinates); prefix revalidation; single final verifier; evidence
   record; bounded model calls.
5. **Chrome lane** stays as the human lane and fallback (the existing vision loop); the
   known search-oscillation defect must be fixed before it is the fallback of record.
6. **Settings/trust storage**: trusted people, store logins (encrypted, redacted),
   auto-place limit, cookie choices per site.

## 6. Roadmap

- **P0 — Foundations**: fix the search-oscillation defect in the Chrome lane; add the
  pipeline event stream; skeleton of the WebView lane behind a flag.
- **P1 — Fast lane core**: DOM reading, element actions, navigation, cookie walls,
  Haiku/Jev DOM contract, verified-effect loop; read/report/compare tasks work end-to-end
  in the background.
- **P2 — Trust layer**: goal contract + final verifier adapted to DOM; cards (review,
  purchase, consent, finish); the trust settings; the switch handoff to Chrome; stored
  logins; trusted people.
- **P3 — Bots & UI**: two-robot vector rigs; the redesign (New Task home, corner chip,
  watch screen, cards, task list, history, settings sections); reduced-motion support.
- **P4 — Purchase & messaging flows**: emails/drafts, forms fill-only, WhatsApp Web,
  bookings, calendar, accounts, social cards; the full card set live.
- **P5 — Hardening & UAT**: live runs on the phone (read tasks, messaging, a small
  purchase under the limit), evidence review, performance pass.

## 7. Open risks

- WebView fidelity: some sites break or block embedded browsers; the Chrome switch is the
  answer, but each site class needs a decision rule.
- Bot-wall detection on stores; the human lane is the mitigation.
- WhatsApp Web linking is a manual Setup step (QR).
- Cart continuity is guaranteed only by the switch discipline (product page by default).
- The old Chrome-lane code carries large historical AGENTS.md files referencing the
  removed canon docs; a docs cleanup pass is needed (child AGENTS.md references dangle
  until then; those sections are archives, not law, on this branch).
