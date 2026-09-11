# BillNest Auth + Household + Sync Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the current single-device BillNest build into the first usable household-capable v2 slice: secure username/password accounts, household creation/joining, shared current bills/paydays/manual-account metadata, scoped Plaid access, encrypted offline cache, and background sync without breaking the existing production Plaid connection.

**Architecture:** Keep the current production Cloudflare Worker + D1 + Plaid backend, but add session authentication and household scoping beside the existing master-key compatibility path. On Android, preserve the current encrypted `AppData` store during migration, add a separate encrypted sync cache/outbox, and progressively route shared edits through an authenticated sync client. The first owner bootstraps using the already-stored legacy backend key; invited household members register with a one-time invite code and never receive the master key.

**Tech Stack:** Kotlin 2.3.10, Android SDK 36/minSdk 28, Jetpack Compose/Material3, WorkManager, Android Keystore AES-GCM, `SQLiteOpenHelper`, Gson, Plaid Link 6.2.1, Cloudflare Workers, D1, Web Crypto PBKDF2/AES-GCM, Node `node:test`, Java 17, Gradle 8.11.1.

**Spec:** `docs/superpowers/specs/2026-09-11-billnest-full-finance-household-design.md`

## Global Constraints

- Preserve the currently working production Plaid environment and permanent backend URL `https://billnest-api.joshselusion.workers.dev`.
- Do not store Plaid secrets or production access tokens in the APK.
- Keep the existing `BILLNEST_API_KEY` compatibility path working for the installed v1.4.1 APK during this migration phase.
- End-user authentication must use username + real password; local biometric/PIN is quick unlock only.
- All household finance data is shared; there are no private household records.
- Both household members may connect banks; only the household Owner may disconnect/remove a bank connection.
- The app must remain usable offline with an encrypted local cache and queued edits.
- Existing local bills, paydays, manual accounts, settings, and current Plaid Items must migrate without duplication.
- Do not re-enable biometric startup behavior that can crash app startup; biometric remains opt-in and fail-safe.
- Maintain Android compileSdk/targetSdk 36, minSdk 28, Java/JVM 17.
- Do not change FiveM or unrelated repository content.
- Every task must leave the Worker tests and Android tests/build in a passing state before commit.

---

## File Structure Locked For This Plan

### Cloudflare Worker

- `cloudflare-worker/src/index.js` — small top-level request router and legacy compatibility entry point.
- `cloudflare-worker/src/http.js` — JSON responses, request-body parsing, typed HTTP errors.
- `cloudflare-worker/src/crypto.js` — random tokens, SHA-256, PBKDF2 password hashing, existing Plaid token AES-GCM helpers.
- `cloudflare-worker/src/schema.js` — idempotent D1 table/index creation.
- `cloudflare-worker/src/store.js` — D1 persistence methods used by services.
- `cloudflare-worker/src/auth.js` — session authentication, bootstrap/register/login/logout, rate limiting.
- `cloudflare-worker/src/households.js` — invite creation/join metadata/member removal and owner checks.
- `cloudflare-worker/src/sync.js` — generic shared-record mutation/pull protocol for current BillNest records.
- `cloudflare-worker/src/plaid.js` — existing Plaid HTTP calls plus household-scoped item/account behavior.
- `cloudflare-worker/test/auth.test.js` — password/session/bootstrap/invite service tests.
- `cloudflare-worker/test/household.test.js` — owner/member authorization tests.
- `cloudflare-worker/test/sync.test.js` — mutation dedupe/version/conflict/tombstone tests.
- `cloudflare-worker/test/plaid-scope.test.js` — household Plaid scoping and owner-only removal tests.
- `cloudflare-worker/test/schema.test.js` — schema initialization regression.
- `cloudflare-worker/schema.sql` — human-readable complete D1 schema matching `schema.js`.

### Android

- `app/src/main/java/com/baylee/billnest/model/AuthModels.kt` — session/household/member DTOs.
- `app/src/main/java/com/baylee/billnest/model/SyncModels.kt` — sync record/mutation/result DTOs.
- `app/src/main/java/com/baylee/billnest/data/HttpApiClient.kt` — shared authenticated `HttpURLConnection` JSON client.
- `app/src/main/java/com/baylee/billnest/data/AuthApi.kt` — bootstrap/register/login/logout/household endpoints.
- `app/src/main/java/com/baylee/billnest/data/SyncApi.kt` — sync push/pull endpoint.
- `app/src/main/java/com/baylee/billnest/data/SecureSessionStore.kt` — encrypted session/user/household metadata.
- `app/src/main/java/com/baylee/billnest/data/PayloadCipher.kt` — reusable Android Keystore AES-GCM string encryption.
- `app/src/main/java/com/baylee/billnest/data/LocalSyncDb.kt` — encrypted-payload SQLite cache/outbox/meta tables.
- `app/src/main/java/com/baylee/billnest/data/HouseholdSyncRepository.kt` — maps current BillNest models to shared records and applies remote changes.
- `app/src/main/java/com/baylee/billnest/notifications/HouseholdSyncWorker.kt` — periodic and queued sync.
- `app/src/main/java/com/baylee/billnest/ui/AuthViewModel.kt` — auth/household state and actions.
- `app/src/main/java/com/baylee/billnest/ui/auth/AuthGate.kt` — setup/sign-in/join screens.
- `app/src/main/java/com/baylee/billnest/ui/household/HouseholdSettings.kt` — invite/member/sync status UI.
- Existing `BankApi.kt`, `BillRepository.kt`, `BillNestApp.kt`, `MainActivity.kt`, `MainViewModel.kt`, `Models.kt` — modified only where needed to integrate the new foundation while preserving current behavior.

---

### Task 1: Add D1 identity, household, sync, and Plaid-mapping schema

**Files:**
- Create: `cloudflare-worker/src/schema.js`
- Modify: `cloudflare-worker/schema.sql`
- Modify: `cloudflare-worker/src/index.js`
- Modify: `cloudflare-worker/test/schema.test.js`

**Interfaces:**
- Produces: `ensureSchema(env): Promise<void>` from `src/schema.js`.
- Preserves: existing `plaid_items(item_id, access_token_enc, label, created_at)` exactly.
- Adds tables consumed by all later backend tasks.

- [ ] **Step 1: Write the failing schema regression test**

Add assertions in `cloudflare-worker/test/schema.test.js` that `ensureSchema()` prepares all required tables and never calls multiline `DB.exec()`:

```js
const requiredFragments = [
  'CREATE TABLE IF NOT EXISTS users',
  'CREATE TABLE IF NOT EXISTS sessions',
  'CREATE TABLE IF NOT EXISTS households',
  'CREATE TABLE IF NOT EXISTS household_members',
  'CREATE TABLE IF NOT EXISTS household_invites',
  'CREATE TABLE IF NOT EXISTS plaid_item_households',
  'CREATE TABLE IF NOT EXISTS finance_records',
  'CREATE TABLE IF NOT EXISTS sync_events',
  'CREATE TABLE IF NOT EXISTS auth_rate_limits'
];

assert.equal(requiredFragments.every(fragment => preparedSql.some(sql => sql.includes(fragment))), true);
```

- [ ] **Step 2: Run the Worker tests and verify failure**

Run:

```bash
node --test cloudflare-worker/test/*.test.js
```

Expected: FAIL because the new schema fragments are not yet prepared.

- [ ] **Step 3: Implement `schema.js` with one prepared statement per table/index**

Use these exact core table shapes:

```sql
CREATE TABLE IF NOT EXISTS users (
  user_id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  username_norm TEXT NOT NULL UNIQUE,
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  password_iterations INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS households (
  household_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  owner_user_id TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS household_members (
  household_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('owner','member')),
  display_label TEXT NOT NULL,
  joined_at TEXT NOT NULL,
  PRIMARY KEY (household_id, user_id)
);

CREATE TABLE IF NOT EXISTS sessions (
  session_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS household_invites (
  invite_id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL,
  code_hash TEXT NOT NULL UNIQUE,
  created_by_user_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  redeemed_at TEXT
);

CREATE TABLE IF NOT EXISTS plaid_item_households (
  item_id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL,
  connected_by_user_id TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS finance_records (
  household_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  record_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  version INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  updated_by_user_id TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (household_id, kind, record_id)
);

CREATE TABLE IF NOT EXISTS sync_events (
  event_id INTEGER PRIMARY KEY AUTOINCREMENT,
  household_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  record_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  payload_json TEXT NOT NULL,
  deleted INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_rate_limits (
  bucket TEXT PRIMARY KEY,
  window_started_ms INTEGER NOT NULL,
  attempt_count INTEGER NOT NULL
);
```

Keep the existing `plaid_items` table and index. Add indexes for `sessions(token_hash)`, `household_members(user_id)`, `sync_events(household_id,event_id)`, and `finance_records(household_id,kind)`.

- [ ] **Step 4: Replace the inline `ensureSchema` in `index.js` with the imported function**

Use:

```js
import { ensureSchema } from './schema.js';
```

and remove the old two-statement local implementation.

- [ ] **Step 5: Mirror the exact schema in `schema.sql`**

`schema.sql` must contain the same tables/indexes as `schema.js` so manual D1 inspection remains accurate.

- [ ] **Step 6: Run tests**

Run:

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add cloudflare-worker/src/schema.js cloudflare-worker/src/index.js cloudflare-worker/schema.sql cloudflare-worker/test/schema.test.js
git commit -m "feat: add BillNest household data schema"
```

---

### Task 2: Implement secure password/session authentication and first-owner bootstrap

**Files:**
- Create: `cloudflare-worker/src/http.js`
- Create: `cloudflare-worker/src/crypto.js`
- Create: `cloudflare-worker/src/store.js`
- Create: `cloudflare-worker/src/auth.js`
- Create: `cloudflare-worker/test/auth.test.js`
- Modify: `cloudflare-worker/src/index.js`

**Interfaces:**
- Produces: `hashPassword(password, saltBase64?, iterations?)`, `verifyPassword(password, stored)`, `randomToken(byteLength)`, `sha256Hex(value)`.
- Produces: `authenticateSession(request, env)` returning `{ userId, householdId, role, username, displayLabel }`.
- Produces HTTP routes: `POST /api/auth/bootstrap`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/me`.
- Bootstrap requires the legacy `BILLNEST_API_KEY`; normal login requires only username/password.

- [ ] **Step 1: Write failing crypto/auth service tests**

`auth.test.js` must include these cases:

```js
test('password hash verifies the right password and rejects a wrong password', async () => {
  const stored = await hashPassword('Correct Horse Battery Staple');
  assert.equal(await verifyPassword('Correct Horse Battery Staple', stored), true);
  assert.equal(await verifyPassword('wrong', stored), false);
});

test('bootstrap creates exactly one owner household and cannot run twice', async () => {
  const first = await service.bootstrap({ username: 'baylee', password: 'a real password', householdName: 'Our Household' });
  assert.equal(first.membership.role, 'owner');
  await assert.rejects(
    () => service.bootstrap({ username: 'other', password: 'another password', householdName: 'Other' }),
    error => error.status === 409
  );
});

test('session token lookup uses the token hash rather than storing the raw token', async () => {
  const result = await service.login({ username: 'baylee', password: 'a real password' });
  assert.equal(memoryStore.rawSessionTokens.includes(result.sessionToken), false);
});
```

Use an in-memory store implementing the same methods as `D1Store` so tests do not need remote D1.

- [ ] **Step 2: Run the tests and verify failure**

```bash
node --test cloudflare-worker/test/auth.test.js
```

Expected: FAIL because auth modules do not exist.

- [ ] **Step 3: Implement password hashing in `crypto.js`**

Use Web Crypto PBKDF2-SHA-256 with 210,000 iterations and a 16-byte random salt:

```js
export async function hashPassword(password, saltBase64 = null, iterations = 210000) {
  if (typeof password !== 'string' || password.length < 10 || password.length > 200) {
    throw httpError(400, 'Password must be 10 to 200 characters');
  }
  const salt = saltBase64 ? base64ToBytes(saltBase64) : crypto.getRandomValues(new Uint8Array(16));
  const material = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, material, 256);
  return { salt: bytesToBase64(salt), hash: bytesToBase64(new Uint8Array(bits)), iterations };
}
```

`verifyPassword` must derive with the stored salt/iterations and compare fixed-length byte arrays without early exit.

- [ ] **Step 4: Implement session tokens**

Generate a 32-byte random token, return only the base64url raw token to the device, and store only `sha256Hex(rawToken)` in D1. Sessions expire 30 days after creation. Authentication may update `last_seen_at` but must not silently extend `expires_at` in this first slice.

- [ ] **Step 5: Implement D1 store methods used by auth**

`D1Store` must expose exact methods:

```js
countUsers()
findUserByNormalizedUsername(usernameNorm)
createUser(user)
createHousehold(household)
addHouseholdMember(member)
getMembershipForUser(userId)
createSession(session)
findSessionByTokenHash(tokenHash)
deleteSessionByTokenHash(tokenHash)
claimUnmappedPlaidItems(householdId, userId, createdAt)
readRateLimit(bucket)
writeRateLimit(bucket, windowStartedMs, attemptCount)
```

- [ ] **Step 6: Implement rate limiting**

For bootstrap/login, derive the bucket from route + normalized username + `CF-Connecting-IP`. Permit 8 attempts per rolling 15-minute window; on excess throw HTTP 429. Successful login resets that bucket to zero.

- [ ] **Step 7: Implement bootstrap/login/logout/me routes**

Bootstrap contract:

```json
POST /api/auth/bootstrap
Authorization: Bearer <legacy BILLNEST_API_KEY>
{
  "username": "baylee",
  "password": "real password",
  "householdName": "Our Household"
}
```

Return:

```json
{
  "sessionToken": "...",
  "user": { "userId": "...", "username": "baylee" },
  "household": { "householdId": "...", "name": "Our Household", "role": "owner", "displayLabel": "baylee" }
}
```

Bootstrap must fail with 409 once any user exists, and on success must associate every currently unmapped `plaid_items.item_id` to the new household in `plaid_item_households`.

- [ ] **Step 8: Keep legacy master-key authorization working for existing v1.4.1 Plaid routes**

Do not replace the legacy key check yet. Add session auth as a second path; existing callers presenting `BILLNEST_API_KEY` must continue to work exactly as before.

- [ ] **Step 9: Run tests and commit**

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
git add cloudflare-worker/src cloudflare-worker/test/auth.test.js
git commit -m "feat: add BillNest user authentication"
```

---

### Task 3: Add household invite/join/member management and server-side owner authorization

**Files:**
- Create: `cloudflare-worker/src/households.js`
- Create: `cloudflare-worker/test/household.test.js`
- Modify: `cloudflare-worker/src/store.js`
- Modify: `cloudflare-worker/src/auth.js`
- Modify: `cloudflare-worker/src/index.js`

**Interfaces:**
- Produces routes: `POST /api/auth/register`, `GET /api/household`, `POST /api/household/invites`, `DELETE /api/household/members/:userId`.
- Invite codes are single-use, expire after 7 days, and are stored only as SHA-256 hashes.
- Owner/member role checks happen on the Worker, never only in Android UI.

- [ ] **Step 1: Write failing household tests**

Include:

```js
test('owner can create a single-use invite and invited user joins the same household', async () => {
  const invite = await householdService.createInvite(ownerContext);
  const joined = await authService.registerWithInvite({
    inviteCode: invite.inviteCode,
    username: 'fiance',
    password: 'another real password'
  });
  assert.equal(joined.household.householdId, ownerContext.householdId);
  assert.equal(joined.household.role, 'member');
});

test('redeemed invite cannot be reused', async () => {
  const invite = await householdService.createInvite(ownerContext);
  await authService.registerWithInvite({ inviteCode: invite.inviteCode, username: 'one', password: '1234567890x' });
  await assert.rejects(
    () => authService.registerWithInvite({ inviteCode: invite.inviteCode, username: 'two', password: '1234567890y' }),
    error => error.status === 409
  );
});

test('member cannot remove another member', async () => {
  await assert.rejects(() => householdService.removeMember(memberContext, targetUserId), error => error.status === 403);
});
```

- [ ] **Step 2: Implement invite storage and lookup methods**

Add exact store methods:

```js
createInvite(invite)
findUsableInviteByCodeHash(codeHash, nowIso)
redeemInvite(inviteId, redeemedAt)
listHouseholdMembers(householdId)
removeHouseholdMember(householdId, userId)
```

- [ ] **Step 3: Implement invite generation**

Generate 10 random bytes, encode as an uppercase human-enterable code in groups of four characters, return the raw code only once, and store only its SHA-256 hash. Expiration is `createdAt + 7 days`.

- [ ] **Step 4: Implement invited registration**

`POST /api/auth/register` body:

```json
{ "inviteCode": "ABCD-EFGH-IJKL-MNOP", "username": "fiance", "password": "..." }
```

The service must atomically create the user/member/session and mark the invite redeemed. If any write fails, do not return a usable session.

- [ ] **Step 5: Implement household read/remove routes**

`GET /api/household` returns household metadata plus members. `DELETE /api/household/members/:userId` requires owner role, cannot remove the owner, and revokes all sessions belonging to the removed user.

- [ ] **Step 6: Run tests and commit**

```bash
node --test cloudflare-worker/test/*.test.js
git add cloudflare-worker/src cloudflare-worker/test/household.test.js
git commit -m "feat: add BillNest household invites and roles"
```

---

### Task 4: Scope Plaid items/accounts to household sessions and enforce owner-only disconnect

**Files:**
- Create: `cloudflare-worker/src/plaid.js`
- Create: `cloudflare-worker/test/plaid-scope.test.js`
- Modify: `cloudflare-worker/src/index.js`
- Modify: `cloudflare-worker/src/store.js`

**Interfaces:**
- Existing endpoints remain: `/api/plaid/link-token`, `/api/plaid/exchange`, `/api/plaid/accounts`, `/api/plaid/items`, `/api/plaid/items/:itemId`.
- Session-authenticated callers see only their household's Plaid Items.
- Both owner/member can exchange a new public token.
- Only owner can DELETE an item.
- Legacy master-key caller retains the pre-v2 all-items behavior during migration.

- [ ] **Step 1: Write failing Plaid authorization tests**

Test these exact rules:

```js
test('member may attach a new Plaid item to the shared household', async () => {
  const saved = await plaidService.saveExchangedItem(memberContext, { itemId: 'item-1', accessToken: 'access-1', label: 'Fiance Bank' });
  assert.equal(saved.householdId, memberContext.householdId);
  assert.equal(saved.connectedByUserId, memberContext.userId);
});

test('member cannot disconnect a Plaid item', async () => {
  await assert.rejects(() => plaidService.removeItem(memberContext, 'item-1'), error => error.status === 403);
});

test('owner cannot access an item mapped to another household', async () => {
  await assert.rejects(() => plaidService.getItemAccessToken(ownerContext, 'other-house-item'), error => error.status === 404);
});
```

- [ ] **Step 2: Extract the current Plaid helpers from `index.js` into `plaid.js`**

Move `plaidBaseUrl`, `plaidPost`, access-token encrypt/decrypt, account normalization, and route-specific functions without changing request payloads or production host selection.

- [ ] **Step 3: Map session exchanges to households**

After `/item/public_token/exchange`, continue writing encrypted access token to `plaid_items`, then insert/update:

```sql
INSERT INTO plaid_item_households (item_id, household_id, connected_by_user_id, created_at)
VALUES (?1, ?2, ?3, ?4)
ON CONFLICT(item_id) DO UPDATE SET
  household_id = excluded.household_id,
  connected_by_user_id = excluded.connected_by_user_id;
```

- [ ] **Step 4: Scope account/item reads**

Session mode selects only Plaid Items joined through `plaid_item_households.household_id = context.householdId`. Legacy-key mode retains the current `SELECT ... FROM plaid_items ORDER BY created_at ASC` behavior.

- [ ] **Step 5: Enforce disconnect authorization**

For session mode, reject non-owner with 403 before decrypting or calling Plaid `/item/remove`. Owner may remove only an Item belonging to the same household. Legacy-key mode remains compatible during this phase.

- [ ] **Step 6: Run tests and commit**

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
git add cloudflare-worker/src cloudflare-worker/test/plaid-scope.test.js
git commit -m "feat: scope Plaid connections to BillNest households"
```

---

### Task 5: Implement versioned household sync API with outbox-safe mutation IDs

**Files:**
- Create: `cloudflare-worker/src/sync.js`
- Create: `cloudflare-worker/test/sync.test.js`
- Modify: `cloudflare-worker/src/store.js`
- Modify: `cloudflare-worker/src/index.js`

**Interfaces:**
- Produces `POST /api/sync` for push + pull in one authenticated request.
- Supported first-slice record kinds: `bill`, `payday`, `manual_account`, `settings`.
- Mutation contract:

```json
{
  "sinceEventId": 0,
  "mutations": [
    {
      "mutationId": "uuid",
      "kind": "bill",
      "recordId": "bill-uuid",
      "baseVersion": 0,
      "deleted": false,
      "payload": { "id": "bill-uuid", "name": "Electric" }
    }
  ]
}
```

- Response contract:

```json
{
  "cursor": 42,
  "applied": [{ "mutationId": "uuid", "kind": "bill", "recordId": "bill-uuid", "version": 1 }],
  "conflicts": [],
  "changes": [{ "eventId": 42, "kind": "bill", "recordId": "bill-uuid", "version": 1, "deleted": false, "payload": {} }]
}
```

- [ ] **Step 1: Add an applied-mutation dedupe table to schema**

Add:

```sql
CREATE TABLE IF NOT EXISTS sync_mutations (
  household_id TEXT NOT NULL,
  mutation_id TEXT NOT NULL,
  applied_at TEXT NOT NULL,
  PRIMARY KEY (household_id, mutation_id)
);
```

Update schema test accordingly.

- [ ] **Step 2: Write failing sync service tests**

Required tests:

```js
test('replaying the same mutation id is idempotent', async () => {
  const first = await syncService.sync(context, request);
  const second = await syncService.sync(context, request);
  assert.equal(first.applied[0].version, 1);
  assert.equal(second.applied[0].version, 1);
  assert.equal(store.countRecords('bill'), 1);
});

test('stale baseVersion returns a conflict instead of overwriting server state', async () => {
  await syncService.sync(context, createVersionOne);
  await syncService.sync(context, updateToVersionTwo);
  const result = await syncService.sync(otherDeviceContext, staleVersionOneUpdate);
  assert.equal(result.conflicts.length, 1);
  assert.equal(result.conflicts[0].serverRecord.version, 2);
});

test('delete produces a tombstone event', async () => {
  const result = await syncService.sync(context, deleteRequest);
  assert.equal(result.changes.at(-1).deleted, true);
});
```

- [ ] **Step 3: Validate input strictly**

Allow only the four first-slice kinds. Reject mutation batches over 100 entries, payload JSON over 64 KiB per record, missing IDs, negative versions, and malformed bodies with HTTP 400.

- [ ] **Step 4: Implement optimistic version writes**

Create with `baseVersion == 0`. Update/delete only when current `version == baseVersion`; otherwise return conflict with the current server record. Every accepted change increments version and appends one `sync_events` row. Record `deleted=1` rather than physically deleting.

- [ ] **Step 5: Make mutation replay idempotent**

Before applying a mutation, check `sync_mutations` for `(household_id, mutation_id)`. If present, return its current record version as already-applied and do not insert another sync event.

- [ ] **Step 6: Return all household events newer than `sinceEventId`**

Order ascending by `event_id`, cap to 500 events per response, and return the highest returned event ID as `cursor`. If no new changes exist, return the caller's cursor unchanged.

- [ ] **Step 7: Run tests and commit**

```bash
node --test cloudflare-worker/test/*.test.js
git add cloudflare-worker/src cloudflare-worker/schema.sql cloudflare-worker/test
git commit -m "feat: add BillNest offline household sync API"
```

---

### Task 6: Add Android encrypted session storage, auth client, and auth/household UI gate

**Files:**
- Create: `app/src/main/java/com/baylee/billnest/model/AuthModels.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/PayloadCipher.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/SecureSessionStore.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/HttpApiClient.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/AuthApi.kt`
- Create: `app/src/main/java/com/baylee/billnest/ui/AuthViewModel.kt`
- Create: `app/src/main/java/com/baylee/billnest/ui/auth/AuthGate.kt`
- Modify: `app/src/main/java/com/baylee/billnest/BillNestApp.kt`
- Modify: `app/src/main/java/com/baylee/billnest/MainActivity.kt`
- Test: `app/src/test/java/com/baylee/billnest/data/AuthStateTest.kt`

**Interfaces:**
- `SessionData(sessionToken, userId, username, householdId, householdName, role, displayLabel)`.
- `AuthApi.bootstrap(...)`, `login(...)`, `registerWithInvite(...)`, `logout(...)`, `fetchMe(...)`.
- `AuthViewModel.state: StateFlow<AuthUiState>` where state is `Loading`, `SignedOut`, or `SignedIn(SessionData)`.

- [ ] **Step 1: Write model/state tests**

```kotlin
@Test
fun ownerSessionReportsOwnerRole() {
    val session = SessionData(
        sessionToken = "token",
        userId = "u1",
        username = "baylee",
        householdId = "h1",
        householdName = "Our Household",
        role = "owner",
        displayLabel = "baylee"
    )
    assertTrue(session.isOwner)
}
```

Run:

```bash
gradle :app:testDebugUnitTest --stacktrace
```

Expected: FAIL until new models exist.

- [ ] **Step 2: Extract reusable AES-GCM handling into `PayloadCipher`**

Use Android Keystore alias `billnest_payload_key_v2`. Public API:

```kotlin
class PayloadCipher {
    fun encrypt(plainText: String): String
    fun decrypt(packedBase64: String): String
}
```

Packed format is Base64 of `[4-byte IV length][IV][ciphertext]`. Keep `EncryptedStore` reading the existing `billnest_data_key_v1` file untouched so v1 data remains readable.

- [ ] **Step 3: Implement `SecureSessionStore`**

Store one encrypted JSON file `billnest-session-v2.sec`. API:

```kotlin
fun load(): SessionData?
fun save(session: SessionData)
fun clear()
fun isLegacyMigrationComplete(householdId: String): Boolean
fun markLegacyMigrationComplete(householdId: String)
```

Never persist the plaintext password.

- [ ] **Step 4: Implement `HttpApiClient`**

Exact call signature:

```kotlin
suspend fun request(
    backendUrl: String,
    path: String,
    method: String = "GET",
    bearerToken: String? = null,
    jsonBody: String? = null
): String
```

Use 15s connect / 30s read timeouts, JSON content type, clear error messages with HTTP status, and no logging of Authorization values.

- [ ] **Step 5: Implement `AuthApi`**

`bootstrap` accepts the legacy backend API key only for first-owner setup. `login`, `registerWithInvite`, `logout`, and `fetchMe` use session token contracts from Tasks 2-3.

- [ ] **Step 6: Implement `AuthViewModel`**

On launch, read cached `SessionData`. If present, immediately expose `SignedIn` for offline access, then verify `/api/me` asynchronously when network is available. A network failure does not sign the user out; HTTP 401 clears the expired/revoked session and switches to `SignedOut`.

- [ ] **Step 7: Add `AuthGate`**

Provide three flows in a clean modern-finance Compose UI:

1. **Set up this household** — username, password, household name; uses the encrypted legacy backend key already stored by v1.x.
2. **Sign in** — username + password.
3. **Join household** — invite code + username + password.

Do not display or ask the invited member for the master backend key.

- [ ] **Step 8: Gate the existing BillNest UI in `MainActivity`**

When signed out, show `AuthGate`. When signed in, show the existing `BillNestHome` unchanged except for a small household name/user affordance. Preserve the current notification permission request and Plaid Link registration.

- [ ] **Step 9: Run Android tests/build and commit**

```bash
gradle :app:testDebugUnitTest --stacktrace
gradle :app:assembleDebug --stacktrace
git add app/src/main/java app/src/test/java
git commit -m "feat: add BillNest household sign in"
```

---

### Task 7: Add encrypted Android sync cache/outbox, migrate current data, and sync both phones

**Files:**
- Create: `app/src/main/java/com/baylee/billnest/model/SyncModels.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/LocalSyncDb.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/SyncApi.kt`
- Create: `app/src/main/java/com/baylee/billnest/data/HouseholdSyncRepository.kt`
- Create: `app/src/test/java/com/baylee/billnest/data/SyncMappingTest.kt`
- Modify: `app/src/main/java/com/baylee/billnest/data/BillRepository.kt`
- Modify: `app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/baylee/billnest/BillNestApp.kt`

**Interfaces:**
- `LocalSyncDb.enqueue(kind, recordId, baseVersion, deleted, payloadJson): String` returns mutation ID.
- `LocalSyncDb.pendingMutations(limit = 100): List<SyncMutation>`.
- `LocalSyncDb.applyServerChange(change: SyncChange)` stores encrypted payload and new version/tombstone.
- `HouseholdSyncRepository.syncNow(): SyncSummary`.
- Current model mapping kinds: `Bill -> bill`, `Payday -> payday`, manual `Account -> manual_account`, shared settings -> `settings/household`.

- [ ] **Step 1: Write mapping tests before database code**

```kotlin
@Test
fun billRoundTripsThroughSharedRecordPayload() {
    val bill = Bill(name = "Electric", amount = 121.44, dueDateIso = "2026-09-20")
    val encoded = SyncMapper.encodeBill(bill)
    val decoded = SyncMapper.decodeBill(encoded)
    assertEquals(bill, decoded)
}

@Test
fun plaidAccountsAreNotUploadedAsManualAccountRecords() {
    val plaid = Account(name = "Checking", source = AccountSource.PLAID, plaidAccountId = "p1")
    assertNull(SyncMapper.accountMutation(plaid))
}
```

- [ ] **Step 2: Implement `LocalSyncDb` using `SQLiteOpenHelper`**

Database `billnest-sync-v2.db`, version 1. Tables:

```sql
CREATE TABLE records (
  kind TEXT NOT NULL,
  record_id TEXT NOT NULL,
  encrypted_payload TEXT NOT NULL,
  version INTEGER NOT NULL,
  deleted INTEGER NOT NULL,
  PRIMARY KEY(kind, record_id)
);

CREATE TABLE outbox (
  mutation_id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  record_id TEXT NOT NULL,
  base_version INTEGER NOT NULL,
  deleted INTEGER NOT NULL,
  encrypted_payload TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);

CREATE TABLE meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
```

All finance payloads and outbox payloads must be encrypted with `PayloadCipher`; IDs/kinds/version metadata may remain queryable.

- [ ] **Step 3: Implement `SyncApi`**

Read the current cursor from `LocalSyncDb.meta['cursor']`, send up to 100 queued mutations to `/api/sync`, parse applied/conflicts/changes, and never delete an outbox row until its mutation ID appears in `applied`.

- [ ] **Step 4: Implement server-change application**

For each server change, update local sync record first, then apply it to `BillRepository` through explicit methods:

```kotlin
fun applyRemoteBill(bill: Bill?, deletedId: String?)
fun applyRemotePayday(payday: Payday?, deletedId: String?)
fun applyRemoteManualAccount(account: Account?, deletedId: String?)
fun applyRemoteSharedSettings(settings: SharedSettings)
```

Remote application must not enqueue a second outbound mutation.

- [ ] **Step 5: Queue every current shared local edit**

After the existing encrypted `AppData` update succeeds, enqueue the matching sync mutation for add/update/delete bill, payday, and manual account changes. `markPaid` queues the resulting updated bill. Plaid account balance refresh stays server/Plaid-derived and is not uploaded as a manual account record.

- [ ] **Step 6: Implement first-owner legacy migration**

After successful bootstrap/sign-in and before marking migration complete:

- enqueue every existing Bill by its existing `bill.id`;
- enqueue every existing Payday by existing `payday.id`;
- enqueue only `AccountSource.MANUAL` accounts by existing `account.id`;
- enqueue one `settings` record with `recordId = "household"` containing reminder days and non-secret shared preferences;
- do not upload `backendApiKey`, session token, Plaid access tokens, or legacy `AccountBalance` objects;
- sync until all migration mutations are acknowledged;
- then call `markLegacyMigrationComplete(householdId)`.

Because record IDs are stable and server create uses version 0, rerunning migration cannot create duplicate records.

- [ ] **Step 7: Handle conflicts visibly**

Store conflicts in `LocalSyncDb` using a `conflicts` table with encrypted local/server payloads. Expose `conflictCount` in `HouseholdSyncRepository`. For this phase provide two resolution operations: `useServer(conflictId)` and `keepThisDevice(conflictId)`, where keep-this-device re-enqueues against the server record version.

- [ ] **Step 8: Verify two-device flow with unit tests**

Add tests for mapping, idempotent migration IDs, no Plaid account upload, and server-apply not re-enqueuing.

Run:

```bash
gradle :app:testDebugUnitTest --stacktrace
gradle :app:assembleDebug --stacktrace
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java app/src/test/java
git commit -m "feat: sync BillNest household data offline first"
```

---

### Task 8: Switch Android Plaid calls to household sessions, add background sync/member controls, icon, and alpha APK verification

**Files:**
- Modify: `app/src/main/java/com/baylee/billnest/data/BankApi.kt`
- Create: `app/src/main/java/com/baylee/billnest/notifications/HouseholdSyncWorker.kt`
- Create: `app/src/main/java/com/baylee/billnest/ui/household/HouseholdSettings.kt`
- Modify: `app/src/main/java/com/baylee/billnest/BillNestApp.kt`
- Modify: `app/src/main/java/com/baylee/billnest/MainActivity.kt`
- Modify: `app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/build-billnest-apk.yml`
- Create launcher resources under `app/src/main/res/mipmap-*` and `app/src/main/res/mipmap-anydpi-v26/` from the approved BillNest icon.

**Interfaces:**
- `BankApi` receives `sessionToken` for v2 calls instead of the master backend key.
- `HouseholdSyncWorker` calls `HouseholdSyncRepository.syncNow()` and retries only transient network/server failures.
- Household settings exposes invite generation, members, sync status, and conflict resolution.

- [ ] **Step 1: Change `BankApi` parameter naming/behavior to bearer session token**

Use:

```kotlin
suspend fun createLinkToken(backendUrl: String, sessionToken: String): String
suspend fun exchangePublicToken(backendUrl: String, sessionToken: String, publicToken: String, label: String?)
suspend fun fetchAccounts(backendUrl: String, sessionToken: String): List<Account>
```

Keep the exact backend URL and existing Plaid Link behavior. MainActivity obtains the token from `SecureSessionStore`/auth state, never from the old Settings key.

- [ ] **Step 2: Add periodic and immediate household sync**

Schedule a 1-hour periodic `HouseholdSyncWorker` with network connectivity constraint:

```kotlin
val request = PeriodicWorkRequestBuilder<HouseholdSyncWorker>(1, TimeUnit.HOURS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()
```

Also enqueue a unique one-time sync after local edits and on successful sign-in/app foreground. Do not block UI startup on network.

- [ ] **Step 3: Add Household section to Settings**

Show household name, signed-in username/role, last successful sync, pending change count, conflict count, member list, and Owner-only `Create invite` / `Remove member` controls. Members must not see a functional remove-bank/member control.

- [ ] **Step 4: Hide raw backend key from normal v2 Settings**

Retain the encrypted value internally only for migration/rollback compatibility in this alpha. Do not show it as a normal editable field after a session exists. Backend URL may remain in an Advanced/diagnostics section but defaults permanently to `BILLNEST_BACKEND_URL`.

- [ ] **Step 5: Install the approved BillNest launcher icon**

Generate foreground/background/adaptive icon assets from the approved clean-modern-finance icon. Update `AndroidManifest.xml` application attributes:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

Verify the icon is not clipped in the adaptive safe zone.

- [ ] **Step 6: Bump the alpha build version**

Set:

```kotlin
versionCode = 8
versionName = "2.0.0-alpha1"
```

Do not change any FiveM `fx_version` files; this is the Android Gradle version only.

- [ ] **Step 7: Update GitHub Actions artifact naming**

Rename/upload:

```yaml
- name: Rename APK
  run: cp app/build/outputs/apk/debug/app-debug.apk BillNest-v2.0.0-alpha1-debug.apk
- uses: actions/upload-artifact@v4
  with:
    name: BillNest-v2.0.0-alpha1-debug-apk
    path: BillNest-v2.0.0-alpha1-debug.apk
    if-no-files-found: error
```

Retain Worker syntax tests, all `node:test` files, Android unit tests, and APK build.

- [ ] **Step 8: Run full local verification**

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
gradle :app:testDebugUnitTest --stacktrace
gradle :app:assembleDebug --stacktrace
```

Expected: every command exits 0.

- [ ] **Step 9: Run the real-device acceptance checklist before calling the phase complete**

Verify on the current owner's phone:

1. v1.4.1 data remains visible after update.
2. First-owner setup uses the stored legacy key once and creates owner household.
3. Existing real Plaid accounts remain connected and balances refresh.
4. Create invite code.

Verify on fiancé phone:

5. Fresh install can join using invite + own username/password with no backend master key.
6. Bills/paydays/manual accounts from owner appear after sync.
7. Add/edit a bill on fiancé phone; owner phone receives it after sync.
8. Both phones can connect a bank.
9. Member cannot disconnect a bank; owner can.
10. Put one phone offline, edit a bill, reopen app and confirm cached data works; reconnect and confirm queued edit syncs.
11. Force an edit conflict on the same record and confirm it appears in the conflict/review UI instead of silently overwriting.
12. Sign out and back in; cached household state remains encrypted and normal sign-in restores sync.

- [ ] **Step 10: Commit and tag the executable milestone**

```bash
git add app cloudflare-worker .github/workflows/build-billnest-apk.yml
git commit -m "feat: deliver BillNest v2 household foundation"
```

Do not claim the full v2 finance redesign is complete at this milestone. This alpha establishes identity, sharing, offline sync, current-data migration, household-scoped Plaid, and the approved icon. Transactions, automatic bill matching/Needs Review, budgets, debt payoff, goals, and final dashboard polish each get their own subsequent implementation plan and testable release slice.

---

## Subsequent Plan Sequence

After this plan passes real-device acceptance, create and execute separate plans in this order:

1. `BillNest Transactions + Plaid Sync` — transaction storage/import/update/removal, manual transactions, merchant/category rules, account activity.
2. `BillNest Bill Matching + Needs Review` — payment occurrences, high-confidence auto-pay marking, reversible audit history, uncertain review queue.
3. `BillNest Budgets + Cash Flow` — weekly/biweekly/monthly/yearly/custom budgets, rollover, safe-to-spend, forecasts.
4. `BillNest Debt + Payoff Planning` — debts, APR/utilization, snowball/avalanche projections.
5. `BillNest Savings Goals + Payday Funding` — goals/sinking funds and recurring/payday contributions.
6. `BillNest Final UI + Reliability` — final modern-finance dashboard, screen decomposition, notification polish, security/rate-limit review, final v2 migration and production acceptance.

## Plan Self-Review

- **Spec coverage for this slice:** identity, username/password auth, household roles/invites, owner-only destructive bank action, both-member bank connection, encrypted offline cache, migration of current local data, scoped existing Plaid Items, background sync, conflict visibility, app icon, and current-data non-regression are all assigned to tasks.
- **Deferred intentionally:** transaction import, bill matching, budgets, debt, savings goals, and final dashboard redesign are independent subsystems and are explicitly split into later plans rather than hidden as placeholders in this one.
- **Placeholder scan:** this plan contains no TBD/TODO implementation gaps; endpoint contracts, table shapes, interfaces, limits, role rules, versioning, and acceptance checks are explicit.
- **Type/interface consistency:** session auth is `SessionData` on Android and bearer session token on Worker; sync kinds and mutation fields match across backend and Android tasks; Plaid removal role is owner-only in both backend and UI; legacy master key remains only as migration compatibility/bootstrap.
