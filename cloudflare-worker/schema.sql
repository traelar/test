CREATE TABLE IF NOT EXISTS plaid_items (
  item_id TEXT PRIMARY KEY,
  access_token_enc TEXT NOT NULL,
  label TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_plaid_items_created_at
  ON plaid_items(created_at);

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

CREATE TABLE IF NOT EXISTS sync_mutations (
  household_id TEXT NOT NULL,
  mutation_id TEXT NOT NULL,
  applied_at TEXT NOT NULL,
  PRIMARY KEY (household_id, mutation_id)
);

CREATE TABLE IF NOT EXISTS auth_rate_limits (
  bucket TEXT PRIMARY KEY,
  window_started_ms INTEGER NOT NULL,
  attempt_count INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_token_hash
  ON sessions(token_hash);

CREATE INDEX IF NOT EXISTS idx_household_members_user_id
  ON household_members(user_id);

CREATE INDEX IF NOT EXISTS idx_sync_events_household_event
  ON sync_events(household_id, event_id);

CREATE INDEX IF NOT EXISTS idx_finance_records_household_kind
  ON finance_records(household_id, kind);
