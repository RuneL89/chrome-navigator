# Chrome Navigator V2 — functional requirements register

Companion to `docs/V2_PLAN.md`. Every requirement names its TOOL (the concrete API /
component) and its METHOD (how it is done). The **Haiku/Jev division of labour is
reserved** and must not change without an owner amendment:

| Role | Owner | Never |
|---|---|---|
| **Writer / proposer** | **Haiku** (OpenRouter, `anthropic/claude-haiku-5.5`) — plans ladders, proposes element-targeted actions, composes message bodies, repairs mis-shaped conditions, extracts answers | Haiku never judges its own proposal, never executes, never decides completion |
| **Judge / verifier** | **Jev** (TypeSafe systemone, `jev-1.14.0`) — choice scoring, noul judgments (outcome visible? confirmation? error?), verify-battery questions, grounding **in the Chrome lane only** | Jev never authors free-form actions, never composes text |
| **Code** | Everything that gates: goal contract, risk gate, approval cards, dispatch guard, postcondition checks, budgets, final verifier, evidence | Code never lets a model's word be the proof |

Legend: **M** = model role where applicable (Haiku / Jev / —).

## A. Lane orchestration

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-01 | Task runs in the background without the screen | Android `WebView` + foreground `Service` | WebView hosted off-screen (see FR-26); state machine in the service; UI reads state via binding/StateFlow | — |
| FR-02 | Real-Chrome human lane when required | Android intents (`ACTION_VIEW`) + existing a11y/vision engine | Launch URL in `com.android.chrome`; the existing loop drives it | — |
| FR-03 | Switch = handoff by data, never session copy | Intent extras + a switch record | URL + chosen variant + reasons; cart continuity by switch-point discipline (product page default; checkout when account cart) | — |
| FR-04 | Every switch asks (fresh consent) | Notification + overlay card | Reason → effect → Yes / Not now; 5 min no answer = No + park; no remembered consent | — |
| FR-05 | Park / resume tasks | Service state + persisted task record | `Parked` state; resume button on chip/watch/list; state survives process death | — |
| FR-06 | Take over / hand back | Watch-screen control + pause flag | Pause agent, unlock page for the owner, hand back resumes | — |
| FR-07 | New Task home + Settings gear | Compose activity | Launcher activity = goal input; gear → Settings (keys, device, privacy, trust, bots) | — |
| FR-08 | Task list + history | Room or DataStore-backed task records | Status cards; history = result + checklist + final stamp + recorded log | — |

## B. WebView fast lane — engine

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-09 | Page load + navigation | `WebView.loadUrl`, `WebViewClient` | Load/error callbacks; SPA route changes via `onPageCommitVisible` + URL polling | — |
| FR-10 | New-window/target=_blank handling | `WebChromeClient.onCreateWindow` | Adopt the child WebView into the same lane; never silently drop | — |
| FR-11 | DOM extraction to a clean element list | `evaluateJavascript` returning JSON | Script walks interactive/visible nodes → `[{id, role, label, valueState, required, bounds, tag, type}]`; snapshot-local ids | — |
| FR-12 | Stable element targeting | Snapshot-local key + semantic re-query script | Re-find by role + exact label + bounds proximity at dispatch time | — |
| FR-13 | Click action | JS `element.click()` or dispatched pointer events | Prefer `click()`; fall back to pointer event sequence when a site needs it (see research on `isTrusted`) | — |
| FR-14 | Text entry | JS native value setter + `input`/`change` dispatch | React/Vue-safe set: `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el, text)` then dispatch events; read-back verification | — |
| FR-15 | Select / radio / checkbox | JS property set + events | Native setters; verify state read-back | — |
| FR-16 | Submit | JS form submit **only after the owner's final tap** (fill-only law) | The agent never presses submit on forms; email/Send allowed per draft-card law | — |
| FR-17 | Scroll / viewport moves | JS `scrollBy` / element `scrollIntoView` | Verify viewport change before counting progress | — |
| FR-18 | Cookie handling | `CookieManager` + consent automation | Essentials-only where offered; choices remembered per site; cookies isolated per task unless account session consented | — |
| FR-19 | File upload | `WebChromeClient.onShowFileChooser` + SAF picker | Owner picks files in the card; IDs never auto-uploaded | — |
| FR-20 | Downloads (invoices etc.) | `DownloadListener` + `DownloadManager` | Save to Downloads; notify; no auto-open | — |
| FR-21 | Captcha / bot-wall detection | DOM heuristics + URL/status signals | Detect → stop → handoff card (no retry) | — |
| FR-22 | Anti-bot honesty | No UA spoofing by default | Real WebView UA; site failures become switch offers (research-confirmed limits) | — |
| FR-23 | Off-screen execution feasibility | Foreground service + hidden window | Bounded, research-verified pattern (FR-26 in plan §5); wake locks scoped; Doze behaviour documented | — |
| FR-24 | Memory/lifecycle bounds | One WebView per task, recycled | Destroy after N tasks/hours; surface crashes as honest failures | — |

## C. Planning, contract, validation

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-25 | Immutable goal contract | `:core` GoalContract (carried from Phase 18) | Deterministic parse of the raw goal (qty/budget/address/account/site/answer shape); Haiku may propose extraction, code validates | Haiku proposes |
| FR-26 | Ladder plan | Haiku JSON plan | Subtasks with done-conditions + verify questions; validated by code (schema + goal fidelity) | Haiku |
| FR-27 | Site policy | Code (GoalSitePolicy carry-over) | Named site → no detour; site-agnostic source/store → search first | — |
| FR-28 | Intermediate-step condition law (the oscillation fix) | Code validator | A non-final search/navigation step may not carry a URL-condition; error-fed retry asks Haiku for a content condition | Haiku retried |
| FR-29 | Re-plan reuse | Code | Same contract; strategy may change; parameters may not weaken; site-first enforced only for the INITIAL plan | Haiku proposes |
| FR-30 | Prefix revalidation | Code (PrefixAuditor carry-over, DOM evidence) | URL conditions always; DOM content conditions scoped to their own page; rollback to earliest invalid; never across an irreversible milestone | — |

## D. Decision loop

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-31 | Per-step proposal | Haiku proposal schema v-DOM | `{elementId, action, expectedEffect}` — element id, never coordinates | Haiku |
| FR-32 | Independent judgment | Jev noul / choice | Scores the proposed action and the page state; `none`/low = loud refusal, never a fallback pick | Jev |
| FR-33 | Pre-dispatch guard | Code | Re-query the element: identity, visibility, enabled, ownership of the target page; risk gate; refusal = zero dispatch | — |
| FR-34 | Execution | Code | JS action through the guarded executor; dispatch result ≠ success | — |
| FR-35 | Postcondition verification | Code + optional Jev battery | DOM state/URL/field check; only a verified effect counts as progress | Jev (questions) |
| FR-36 | No-progress budgets | Code (NoProgressBudget carry-over) | Independent counters; resets only on verified effects; bounded model calls | — |
| FR-37 | Single final verifier | Code (FinalGoalVerifier carry-over) | Contract parameters + required subtasks + final condition + answer source, on every success path | — |
| FR-38 | Answer extraction | Haiku | Extract from the cited DOM snapshot; code checks it is nonblank and tied to the source | Haiku |
| FR-39 | Evidence + bot events | Local evidence store + event bus | Every step emits `proposed/judged/executed/verified/refused`; no secrets; feeds the bot animation | — |

## E. Trust, approvals, risk

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-40 | Risk gate | Code (H1–H9 carry-over) | Deterministic classification; ambiguous fails closed | — |
| FR-41 | Review-card classes | Overlay cards | Send/submit/post/change/delete/cancel/booking/invite always carded | — |
| FR-42 | Purchase approval | Card + auto-place rule | Below €30: 15 s cancel notification then place; above: card with item+qty, final total, store+address, masked payment+delivery; timeout = no | — |
| FR-43 | Budget overrun | Card | Ask with the delta; timeout = no order | — |
| FR-44 | Trusted people | Setup list (+contacts) | Per-channel scope (email/messages); removal one tap; new recipients get an extra gate | — |
| FR-45 | Stored logins | Table screen + EncryptedSharedPreferences/Keystore | Per-store rows; test login; values never shown again; redaction law | — |
| FR-46 | Phishing guard | Code | Credential/payment request on unexpected domain → hard stop + warning | — |
| FR-47 | Captcha handoff | Card/notification | Always hand off; no retry | — |
| FR-48 | Cookie choices | Per-site record | Essentials-only default; remembered; changeable | — |

## F. Communication

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-49 | Email draft | Webmail in the WebView lane | Haiku composes from the goal; full draft card (recipients incl. cc/bcc, subject, full body, attachment names, sending account); owner edits inline; owner taps Send | Haiku |
| FR-50 | Send hold | Post-send timer | 30 s cancel window; platform undo used when offered | — |
| FR-51 | WhatsApp | WhatsApp Web in the WebView lane | Linked session (QR, one-time Setup); draft card; 30 s hold | Haiku |
| FR-52 | SMS | — | Refused honestly (app-only) | — |
| FR-53 | Group sends | Card | Always ask for >1 recipient | — |
| FR-54 | Attachments | SAF picker | Owner-picked files only | — |
| FR-55 | Message deletion | Webmail/WhatsApp UI | Trash/archive only, carded | — |
| FR-56 | Social | WebView lane | Read allowed; every write carded; bulk actions refused | Haiku (drafts) |
| FR-57 | Calendar | `CalendarContract` insert intent / web calendar | Own calendar: auto + confirm + 1 min undo; invites: draft like email | — |

## G. Forms

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-58 | Field-level form state | DOM extractor | Key/label/role/required-else-unknown/populated/validation-error/bounds; no values in evidence | — |
| FR-59 | Fill loop | Haiku proposals + JS writes | Required-empty keeps the step open; read-back after every write; retry the same field only | Haiku |
| FR-60 | Fill-only submit | Code | The agent never submits a form; the owner taps submit (review state shown) | — |
| FR-61 | Long forms | Code + card | Fill what the goal covers; judgment fields handed to the owner | — |
| FR-62 | Uploads in forms | SAF picker | Owner-picked files only (IDs included) with a card | — |

## H. Purchases and money

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-63 | Checkout integrity | Code | Cart/address/payment milestones; no restart after a preserved checkout; revalidate after any restore | — |
| FR-64 | Payment walls | Switch rule | Card entry, wallets, bank/invoice/financing, external redirects → Chrome only | — |
| FR-65 | Bills / donations | Purchase rules | Same cards and limits as purchases | — |
| FR-66 | Trading/betting/crypto | Card | Allowed with a purchase-class card | — |
| FR-67 | Order closeout | Completion card | Owner choice: card only; no confirmation evidence stored | — |

## I. UI / UX / bots

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-68 | Two bots, silent, neutral copy | Vector rigs (Lottie-style) in Compose | Haiku (proposer) + Jev (judge); poses idle/thinking/acting + orb handoff; reduced-motion honoured | — |
| FR-69 | Pipeline animation | Event bus (FR-39) | The screen shows the REAL pipeline per step, at real speed | — |
| FR-70 | Corner chip | Overlay window (`TYPE_APPLICATION_OVERLAY`) | Only while a task runs; both bots + task + step count; tap → checklist; long-press → Watch/Park/Stop; X closes when done | — |
| FR-71 | Watch screen | Compose + embedded WebView view | Full browser + floating log + bot stage; read-only + Take over; Pause/Resume · Stop · Take over | — |
| FR-72 | Cards | Overlay cards | Review (scrollable, inline edits) · Purchase (total hero) · Consent (reason→effect) · Finish (result+Copy+checklist+stamp) | — |
| FR-73 | Notifications | `NotificationCompat` | Minimal ongoing (Watch/Stop/Approve-consent); heads-up needs-you with inline answers; auto-clean; one per task | — |
| FR-74 | Settings | Compose | Sections + bot guide: Keys · Device · Privacy · Trust (people, logins table, auto-place number input) · Bots | — |
| FR-75 | Home / empty / history | Compose | Opens to New Task; empty = idle bots + example chips; history = summary + recorded log | — |
| FR-76 | Theme / language | Compose theme | Dark-only now; English copy; comfortable density | — |

## J. Infrastructure

| ID | Requirement | Tool | Method | M |
|---|---|---|---|---|
| FR-77 | Bounded model calls | `ModelCallPolicy` carry-over | 3 attempts / finite deadline / cancellable transport; typed failures; no silent zeros | Haiku/Jev |
| FR-78 | Credential safety | Android Keystore/EncryptedSharedPreferences | Encrypted at rest; redacted display; never in logs/evidence/fixtures | — |
| FR-79 | Foreground service | `startForeground` + FGS type | Correct type for Android 14–17; persistent minimal notification | — |
| FR-80 | Verification harness | JVM scripted tests + phone UAT | Scripted transports only in tests; live runs recorded in evidence | — |
