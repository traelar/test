# BillNest Dark Visual Overhaul Design

## Goal

Give the entire BillNest Android app a cohesive, premium, dark-only visual system without changing finance logic, Plaid Production behavior, household behavior, data models, or navigation semantics. The app should feel like one polished consumer-finance product rather than stock Material 3 screens.

## Non-negotiable constraints

- Dark-only UI. No white page backgrounds, white cards, white dialogs, white drawer surfaces, or stock light-mode surfaces.
- Preserve the existing Android package, signing setup, Plaid Production integration, Cloudflare backend integration, persisted user data, account/debt behavior, and Android back navigation.
- Do not add fake/demo balances, transactions, charts, or financial values.
- Do not hardcode any user-specific financial data.
- Keep all existing screens and working actions available.
- Maintain readable contrast and comfortable touch targets.
- Continue producing installable APKs through GitHub Actions and verify tests, build, and signing before completion.

## Visual direction

Use a modern premium finance-dashboard aesthetic with deep charcoal and near-black foundations, layered dark surfaces, restrained teal/blue/green brand accents, and semantic amber/red only for warning, overdue, debt, destructive, and reconnect states.

The app should avoid both extremes: it should not be flat black with thin gray text, and it should not become neon/gaming-themed. Depth comes from tonal surface layering, border contrast, spacing, typography, and selective accent use rather than bright backgrounds.

## Design system

### Color

Create a BillNest dark color system instead of `darkColorScheme()` defaults. Suggested role families:

- App background: near-black charcoal.
- Primary surface: dark graphite.
- Secondary/elevated surface: slightly lighter graphite.
- Divider/border: low-contrast slate.
- Primary text: soft off-white, not pure white.
- Secondary text: cool muted gray.
- Primary accent: teal/cyan-leaning finance accent.
- Positive/savings: restrained green.
- Informational: blue.
- Warning/upcoming: amber.
- Debt/overdue/destructive: red/coral.

All Material color roles used by Compose dialogs, menus, navigation, text fields, buttons, switches, cards, and sheets must be explicitly mapped so no default white/light surface can leak into the UI.

### Typography

Define a custom Material typography scale with stronger hierarchy:

- Large page/summary numbers receive prominent display/headline treatment.
- Screen titles remain strong but compact.
- Card titles and monetary values use medium/semibold emphasis.
- Supporting metadata uses readable secondary text, not tiny low-contrast labels.
- Status chips and category labels use concise medium-weight text.

Typography should improve scanability while keeping dense finance screens practical on phones.

### Shape and spacing

Use consistent rounded shapes for cards, buttons, fields, chips, dialogs, and navigation surfaces. Avoid excessive pill treatment for large controls. Standardize page padding, card padding, inter-section spacing, row spacing, and compact metadata gaps.

### Shared components

Introduce reusable Compose components/tokens so the redesign is maintained centrally instead of screen-by-screen styling duplication. Components should cover:

- Screen header / section header
- Summary/metric card
- Standard content card
- Money metric row
- Status/category chip
- Primary/secondary/destructive actions
- Finance list row
- Empty state
- Progress/utilization indicator
- Labeled metadata row
- Dark dialog/form styling

Existing logic remains passed into these components via callbacks and values.

## Navigation and app shell

Keep the existing drawer-based navigation and Android back behavior, but restyle the shell:

- Dark top app bar integrated with the page background.
- Dark navigation drawer with grouped destinations, clear selected state, icon treatment, and stronger BillNest branding.
- Remove stock bright Material surfaces.
- Preserve current destinations and destination history behavior.

## Dashboard

Redesign the dashboard around financial hierarchy rather than a stack of plain text:

- A dominant money summary card for total/available money.
- Compact visual breakdown for Spending, Savings, Retirement, and Reserved.
- Upcoming bills and next-payday information in distinct cards.
- Overdue/warning content receives semantic treatment.
- “View breakdown” remains available but uses a polished expandable surface.
- Real values only; no decorative fake charts.

## Accounts

Use richer dark account cards with:

- Account name and type hierarchy.
- Balance emphasized separately from metadata.
- Clear Spending / Savings / Retirement categorization.
- Plaid-connected/reconnect status treatment.
- Masked account details where already available.
- Reorder/edit actions styled consistently.
- Credit/debt accounts remain excluded from the asset-account list according to existing logic.

## Debt

Make Debt one of the strongest screens visually:

- Total debt summary.
- Credit-utilization progress for cards with limits.
- APR, minimum payment, due date, and linked-card state displayed as concise metadata/chips.
- Snowball and Avalanche payoff projections presented as comparable cards rather than plain text.
- Extra-payment input integrated cleanly into the comparison area.
- Preserve direct edit/delete behavior and Plaid-linked debt synchronization.

## Bills, transactions, budgets, savings, reserves, subscriptions, income, calendar

Apply one consistent dark finance language while preserving each workflow:

- Bills: due-date/status/category hierarchy and overdue emphasis.
- Transactions: clearer income/spending/transfer distinctions, search/filter styling, readable amount alignment.
- Budgets: progress/remaining presentation instead of plain text-only rows.
- Savings goals: progress visualization where target values permit it.
- Reserved funds: stronger balance/target/account-link hierarchy and cleaner action layout.
- Subscriptions: recurring-cost emphasis, frequency metadata, confirmed/ignored state treatment.
- Income: payday cards, detected-payday suggestions, and next-date emphasis.
- Calendar: month navigation and scheduled items presented as polished dark timeline/list content while keeping existing behavior.

## Forms, dialogs, menus, and settings

Replace the stock-looking feel while retaining existing interaction semantics:

- All dialogs and dropdowns use dark layered surfaces.
- Fields have clearer labels, focus states, borders, and spacing.
- Primary save actions are visually dominant; cancel/destructive actions are appropriately secondary/semantic.
- Date-picker entry points remain functional and visually consistent.
- Settings sections use grouped dark cards and readable descriptions instead of plain stacked controls.

## Household

Preserve all household/auth/member functionality. Apply the same shell, typography, card, button, form, and status styling so Household does not look like a separate app.

## Accessibility and behavior

- Maintain Material minimum touch targets.
- Ensure secondary text remains readable against dark surfaces.
- Do not encode financial state by color alone; pair color with labels/icons/text.
- Respect keyboard/input behavior and scrolling in forms.
- Keep Android system back navigation working.
- Avoid excessive animation; use short, subtle transitions only where existing Compose behavior permits them naturally.

## Architecture

The primary implementation should be centralized in a small design-system layer under the existing `ui`/`ui.theme` area. Existing screen composables should then migrate to shared BillNest components incrementally. Finance models, repository logic, networking, persistence, Plaid synchronization, and backend APIs should remain untouched unless a compile-only adjustment is required by UI refactoring.

The current single-file theme is intentionally replaced with explicit BillNest colors, typography, shapes, and shared component defaults. Large existing screen files may gain targeted extraction of reusable visual components, but unrelated business-logic refactoring is out of scope.

## Testing and verification

- Preserve all existing unit tests.
- Add UI-independent tests only if any display formatting/helper logic is extracted and testable without instrumentation.
- Run Cloudflare Worker syntax/regression tests unchanged.
- Run Android unit tests.
- Build the debug APK through the existing GitHub Actions workflow.
- Verify the stable signing certificate in CI.
- Bump Android versionCode/versionName and artifact naming for the new visual build so it can install as an update over alpha13.

## Success criteria

The redesign is complete when every primary BillNest screen, drawer/top bar, dialog, form, dropdown, and common state uses the dark BillNest visual language with no unintended white/light surfaces; working features behave as before; existing tests pass; the APK builds; signing verification passes; and the finished APK artifact is available from GitHub Actions.
