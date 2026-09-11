# BillNest Full Finance + Household Sharing Design

Date: 2026-09-11
Branch: `billnest-apk-build`
Status: Approved architecture, pre-implementation design

## Goal

Upgrade BillNest from a single-device bill/account tracker into a polished, shared, offline-first personal finance app for a household while preserving the currently working Plaid Production integration and existing bill/account functionality.

BillNest should remain easy to use on Android, prioritize useful finance features over layout customization, and let two household members share the same financial picture across their phones.

## Product Direction

BillNest will use a fixed, polished modern-finance layout. The app will not include drag/reorder dashboard customization. Customization will focus on meaningful financial setup: accounts, categories, bills, budgets, reminders, debts, savings goals, household ownership labels, and preferences.

The selected visual direction is clean modern finance. The approved BillNest launcher icon will be used as the app icon.

## Architecture

BillNest will use a hybrid offline-first architecture.

### Android app

The Android app will:

- use username + password for account sign-in;
- optionally allow a local biometric/PIN quick unlock after a successful login;
- keep an encrypted local database/cache for fast startup and offline access;
- queue local changes while offline and sync them when connectivity returns;
- present the same shared household data on each authorized device;
- use background work for sync and reminders;
- keep the existing Plaid Link flow for connecting banks through the BillNest Cloudflare backend;
- split the current monolithic UI into focused screens/components and repositories so future changes are safer.

### Cloudflare Worker + D1

The Cloudflare backend will become the authoritative shared household store and will hold:

- users;
- password hashes and account metadata;
- authenticated device/user sessions;
- households;
- household memberships and roles;
- household invite codes;
- linked Plaid Items and encrypted Plaid access tokens;
- bank and manual accounts;
- transactions;
- bills and bill payment history;
- budgets;
- debt accounts/plans;
- savings goals/sinking funds;
- paydays/income;
- categories and user-editable finance settings;
- transaction-to-bill matching state;
- review queue entries;
- sync metadata and audit/history needed to reverse automatic decisions.

All household finance records are shared. There are no private household items in this design.

## Authentication and Household Sharing

### Sign-in

Each user gets an independent BillNest account using:

- username;
- real password;
- secure password hashing on the backend;
- server-issued session/token per device.

The current shared backend master API key must no longer serve as the end-user login mechanism once household authentication is implemented.

### Local quick unlock

After a device has authenticated successfully, the user may enable a local quick-unlock mechanism such as biometric authentication and/or a local PIN. This protects local app access only and does not replace the real online account password.

Biometric startup must not reintroduce the historical BillNest startup crash. The implementation must be opt-in and fail safely back to normal sign-in/local unlock.

### Household model

- One user creates a household and becomes Owner.
- The Owner can generate an invite code.
- Another user can create/sign into their own BillNest account and join the household using that invite code.
- All household finance records are visible to both members.
- Both members can connect their own bank accounts.
- Each account can be labeled with an owner such as Baylee, Fiancé, or Joint.
- Only the household Owner can remove/disconnect a Plaid bank connection.
- Both members can edit normal shared finance data unless a future permission requirement is added explicitly.
- Only the Owner can remove household members or perform owner-level household administration.

## Data Synchronization

The server is authoritative for shared household state, while each phone keeps an encrypted local copy.

Each mutable record will use stable identifiers and sync metadata such as version/update timestamps. Local edits made offline are queued and uploaded later.

Conflict handling should be deterministic and visible. Normal field edits can use server-side latest accepted version rules, while destructive or financially significant actions must retain history so the result can be audited or reversed.

Deleting important records should use tombstones/soft-delete sync semantics where necessary to prevent deleted data from reappearing from another device's stale cache.

## Bank Accounts and Plaid

The existing working Plaid Production connection remains the banking foundation.

BillNest must support:

- multiple Plaid Items from both household members;
- multiple checking, savings, credit, and supported financial accounts;
- account owner labels;
- current and available balances when provided;
- manual accounts alongside Plaid accounts;
- manual balance adjustments;
- clear refresh/sync state;
- owner-only bank disconnection;
- secure encrypted access-token storage on the backend.

No Plaid secret or production access token may be stored in the APK.

## Transactions

BillNest will add a real transaction system.

### Imported transactions

Plaid transactions will be pulled automatically and stored using Plaid transaction identifiers so repeated syncs do not create duplicates.

The system must handle transaction updates/removals from Plaid where applicable and preserve enough history to keep bill matching and reporting correct.

### Manual transactions

Users can create manual transactions for:

- cash purchases;
- missing external transactions;
- reimbursements;
- corrections;
- transfers or items that need manual representation.

Manual transactions count toward budgets, cash flow, spending reports, and bill matching where appropriate.

### Transaction experience

Users can:

- browse recent transactions;
- filter/search by merchant, category, account, date, amount, and type;
- edit categorization for supported records;
- distinguish pending/imported/manual transactions;
- inspect account-specific activity.

## Categories and Merchant Classification

BillNest will support editable household categories rather than a fixed hard-coded list only.

Imported transactions should use Plaid category/merchant data as an initial classification source, then apply BillNest rules/corrections.

User corrections should be reusable so repeated merchants can be categorized consistently in later syncs.

## Bills and Automatic Payment Matching

The existing recurring bill system will be upgraded to support payment history and automatic transaction matching.

### Matching behavior

A matching engine will compare transactions against unpaid bills using signals such as:

- merchant/payee similarity;
- amount and tolerance;
- expected due/payment date window;
- recurrence history;
- linked account;
- prior confirmed matches.

### High-confidence match

When confidence is high, BillNest automatically marks the matching bill occurrence as paid and records:

- transaction ID;
- bill occurrence;
- confidence/reasoning metadata needed for audit;
- automatic action timestamp;
- source account.

Automatic matching must be reversible.

### Needs Review

Ambiguous matches go into a Needs Review queue rather than being marked automatically.

The review flow lets the user:

- confirm a proposed bill match;
- reject it;
- select a different bill;
- mark the transaction unrelated;
- correct category/merchant rules where useful.

Confirmed corrections can improve later deterministic matching rules.

## Cash Flow and Forecasting

BillNest will provide household cash-flow views using:

- account balances;
- upcoming bills;
- expected paydays/income;
- known recurring transactions;
- savings/debt contributions.

The app should clearly answer questions such as:

- what is due before the next payday;
- how much is safe after upcoming bills;
- expected balance over the next 7/14/30 days;
- upcoming income vs expenses;
- overdue and at-risk bills.

Forecasts must clearly distinguish known/persisted values from estimates.

## Budgets

BillNest will support category budgets with these periods:

- weekly;
- biweekly;
- monthly;
- yearly;
- custom date range.

Each budget can optionally roll unused/overspent amounts forward when rollover is enabled.

Budget spending uses both imported and manual transactions and should expose:

- budget amount;
- spent;
- remaining;
- percent used;
- over-budget state;
- relevant transactions.

## Debt and Credit

BillNest will support debt tracking for credit cards, loans, and manual debt entries.

Debt records should support:

- current balance;
- APR;
- minimum payment;
- due date;
- credit limit where applicable;
- utilization for revolving credit;
- extra payment amount;
- payoff progress;
- projected payoff date;
- projected interest cost.

### Payoff strategies

Users can compare:

- snowball: smallest balance first;
- avalanche: highest APR first.

The comparison should show estimated payoff order, time, and interest impact using the user's current debt inputs and extra-payment amount.

## Savings Goals / Sinking Funds

BillNest will support savings goals such as emergency funds, holidays, travel, vehicle repairs, and other sinking funds.

Each goal can include:

- name;
- target amount;
- current amount/progress;
- optional target date;
- recurring contribution;
- optional payday-based funding rule;
- optional linked account.

Goal progress should be visible on the dashboard and dedicated goals screen without requiring manual dashboard reordering.

## Dashboard and Navigation

The dashboard keeps a fixed polished structure rather than user-reorderable widgets.

The exact final arrangement can be refined during implementation, but it should prioritize:

- total household cash/balances;
- safe-to-spend / upcoming obligations;
- next payday and bills before payday;
- Needs Review count;
- upcoming bills;
- budget warnings;
- debt progress;
- savings goal progress;
- recent activity.

Navigation will be reorganized if needed to avoid overcrowding as features grow. Screens should remain phone-friendly and labels must not wrap or clip.

## Settings and User Control

BillNest should let the household configure useful behavior rather than rearrange the UI.

Settings should include, where applicable:

- editable transaction/bill categories;
- reminder timing;
- automatic bill matching enable/disable and thresholds if exposed safely;
- budget rollover;
- account owner labels;
- account display names;
- payday/income setup;
- sync controls/status;
- biometric/PIN quick unlock;
- household invite/member controls;
- notification preferences;
- theme/appearance options appropriate to the modern-finance design.

Server/backend URL and low-level API configuration should no longer be prominent normal-user setup once production authentication is in place.

## Notifications and Background Work

Background processing should support:

- scheduled account/transaction sync within platform and Plaid constraints;
- reminder rescheduling after data sync;
- bill due reminders;
- budget threshold alerts;
- Needs Review alerts;
- bank connection errors requiring re-authentication;
- important debt/savings milestones where enabled.

Background work must fail gracefully without crashing startup or blocking normal app use.

## Reliability and Error Handling

BillNest must prioritize correctness over silently hiding failures.

The app should:

- show last successful sync time;
- distinguish offline state from server/Plaid errors;
- keep local data usable during temporary outages;
- retry queued changes safely;
- prevent duplicate imported transactions;
- prevent duplicate bill-payment application;
- preserve audit/history for automatic bill matches;
- never silently discard offline edits;
- provide actionable errors for expired Plaid Items or authentication problems.

## Security

The implementation must:

- hash passwords server-side using an appropriate slow password hash;
- issue revocable/expiring authentication sessions or tokens;
- store device auth credentials securely on Android;
- retain encrypted local finance storage;
- keep Plaid access tokens encrypted at rest in D1;
- keep Plaid secrets and token-encryption secrets only in Cloudflare runtime secrets;
- stop using the shared backend master key as the normal household login;
- rate-limit authentication and invite-code attempts;
- avoid exposing secret values in logs or UI;
- enforce household scope and owner-only destructive bank actions server-side, not just in the UI.

## App Icon and Branding

The approved clean-modern-finance BillNest icon will become the Android launcher icon, including appropriate adaptive icon assets so it displays correctly on Samsung/Android launchers.

The UI should visually align with that icon: clean, modern, high-contrast, polished, and financial rather than playful or overly decorative.

## Migration From Current BillNest

Existing local BillNest data must not simply disappear after upgrade.

On first migration into the household architecture, the app should offer/perform a controlled migration of existing local data into the signed-in household, including:

- bills;
- paydays;
- manual accounts;
- Plaid account presentation metadata where compatible;
- reminder settings;
- relevant categories/settings.

Existing live Plaid Items already stored in the backend must be associated with the initial household in a controlled migration rather than duplicated.

Migration must be idempotent so rerunning startup does not duplicate bills/accounts.

## Code Organization

The current oversized `MainActivity.kt` should be decomposed as part of this feature work.

Target structure should separate:

- navigation/app shell;
- feature screens;
- reusable UI components;
- local persistence;
- sync engine;
- authentication;
- Plaid/bank API client;
- repositories/domain logic;
- background workers;
- models/DTOs.

The Cloudflare Worker should also be split into focused modules/routes as it grows rather than placing authentication, sync, Plaid, and finance logic into one oversized file.

This refactor is limited to what is needed to support the new architecture and maintainability; unrelated rewrites are out of scope.

## Testing and Verification

Implementation is not considered complete based only on compilation.

Required verification will include:

- Cloudflare Worker regression tests;
- authentication tests;
- household authorization/owner-action tests;
- invite flow tests;
- Plaid item/account/transaction normalization tests;
- transaction deduplication/update tests;
- bill matching tests including ambiguous review cases;
- budget calculation tests for every supported period;
- debt snowball/avalanche calculation tests;
- savings goal calculation tests;
- sync conflict/offline queue tests;
- migration tests from current AppData;
- Android unit tests;
- debug APK build in GitHub Actions;
- real-device verification of login, household sharing, offline behavior, Plaid sync, and automatic bill matching before calling the release complete.

## Delivery Strategy

Because this is a large upgrade, implementation should be staged behind migrations and compatible APIs rather than replacing everything at once without verification.

A practical delivery sequence is:

1. backend identity/household schema and authenticated API foundation;
2. Android authentication + encrypted local database/sync foundation;
3. migrate existing BillNest data into household storage;
4. shared accounts and Plaid Items;
5. transaction sync + manual transactions;
6. bills/payment history + automatic matching + Needs Review;
7. budgets;
8. debt/credit and payoff planning;
9. savings goals;
10. dashboard/polish/settings/background notifications;
11. final migration, reliability, security, and end-to-end verification pass.

Each stage must preserve a buildable app and avoid regressing the currently working production bank connection.

## Explicit Non-Goals

This design does not include:

- drag-and-drop/reorderable dashboards;
- private household finance records;
- email-based authentication;
- sharing the raw backend master API key as the household login;
- storing Plaid secrets in the APK;
- requiring internet just to view already-synced household data.

## Success Criteria

The upgrade is successful when two household members can install BillNest on separate Android phones, sign in with separate usernames/passwords, join one household, connect their own banks, see the same shared household financial data, continue viewing/editing data offline, sync changes safely, receive imported transactions automatically, have high-confidence bills marked paid automatically, review uncertain matches, manage budgets/debts/savings goals, and use a polished stable app without needing to manage raw backend secrets in normal day-to-day use.
