export class D1Store {
  constructor(db) {
    this.db = db;
  }

  async countUsers() {
    const row = await this.db.prepare('SELECT COUNT(*) AS count FROM users').first();
    return Number(row?.count || 0);
  }

  async findUserByNormalizedUsername(usernameNorm) {
    const row = await this.db.prepare(`
      SELECT user_id, username, username_norm, password_salt, password_hash,
             password_iterations, created_at
      FROM users
      WHERE username_norm = ?1
      LIMIT 1
    `).bind(usernameNorm).first();
    if (!row) return null;
    return {
      userId: row.user_id,
      username: row.username,
      usernameNorm: row.username_norm,
      passwordSalt: row.password_salt,
      passwordHash: row.password_hash,
      passwordIterations: Number(row.password_iterations),
      createdAt: row.created_at
    };
  }

  async createUser(user) {
    await this.db.prepare(`
      INSERT INTO users (
        user_id, username, username_norm, password_salt, password_hash,
        password_iterations, created_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
    `).bind(
      user.userId,
      user.username,
      user.usernameNorm,
      user.passwordSalt,
      user.passwordHash,
      user.passwordIterations,
      user.createdAt
    ).run();
  }

  async createHousehold(household) {
    await this.db.prepare(`
      INSERT INTO households (household_id, name, owner_user_id, created_at)
      VALUES (?1, ?2, ?3, ?4)
    `).bind(
      household.householdId,
      household.name,
      household.ownerUserId,
      household.createdAt
    ).run();
  }

  async getHouseholdById(householdId) {
    const row = await this.db.prepare(`
      SELECT household_id, name, owner_user_id, created_at
      FROM households
      WHERE household_id = ?1
      LIMIT 1
    `).bind(householdId).first();
    if (!row) return null;
    return {
      householdId: row.household_id,
      name: row.name,
      ownerUserId: row.owner_user_id,
      createdAt: row.created_at
    };
  }

  async addHouseholdMember(member) {
    await this.db.prepare(`
      INSERT INTO household_members (
        household_id, user_id, role, display_label, joined_at
      ) VALUES (?1, ?2, ?3, ?4, ?5)
    `).bind(
      member.householdId,
      member.userId,
      member.role,
      member.displayLabel,
      member.joinedAt
    ).run();
  }

  async getMembershipForUser(userId) {
    const row = await this.db.prepare(`
      SELECT hm.household_id, hm.user_id, hm.role, hm.display_label, hm.joined_at,
             h.name AS household_name
      FROM household_members hm
      JOIN households h ON h.household_id = hm.household_id
      WHERE hm.user_id = ?1
      LIMIT 1
    `).bind(userId).first();
    if (!row) return null;
    return {
      householdId: row.household_id,
      userId: row.user_id,
      role: row.role,
      displayLabel: row.display_label,
      joinedAt: row.joined_at,
      householdName: row.household_name
    };
  }

  async listHouseholdMembers(householdId) {
    const rows = await this.db.prepare(`
      SELECT hm.household_id, hm.user_id, hm.role, hm.display_label, hm.joined_at,
             u.username
      FROM household_members hm
      JOIN users u ON u.user_id = hm.user_id
      WHERE hm.household_id = ?1
      ORDER BY CASE hm.role WHEN 'owner' THEN 0 ELSE 1 END, hm.joined_at ASC
    `).bind(householdId).all();
    return (rows.results || []).map((row) => ({
      householdId: row.household_id,
      userId: row.user_id,
      username: row.username,
      role: row.role,
      displayLabel: row.display_label,
      joinedAt: row.joined_at
    }));
  }

  async removeHouseholdMember(householdId, userId) {
    await this.db.prepare(`
      DELETE FROM household_members
      WHERE household_id = ?1 AND user_id = ?2
    `).bind(householdId, userId).run();
  }

  async createSession(session) {
    await this.db.prepare(`
      INSERT INTO sessions (
        session_id, user_id, token_hash, created_at, expires_at, last_seen_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
    `).bind(
      session.sessionId,
      session.userId,
      session.tokenHash,
      session.createdAt,
      session.expiresAt,
      session.lastSeenAt
    ).run();
  }

  async findSessionByTokenHash(tokenHash) {
    const row = await this.db.prepare(`
      SELECT s.session_id, s.user_id, s.token_hash, s.created_at, s.expires_at,
             s.last_seen_at, u.username, hm.household_id, hm.role,
             hm.display_label, h.name AS household_name
      FROM sessions s
      JOIN users u ON u.user_id = s.user_id
      LEFT JOIN household_members hm ON hm.user_id = s.user_id
      LEFT JOIN households h ON h.household_id = hm.household_id
      WHERE s.token_hash = ?1
      LIMIT 1
    `).bind(tokenHash).first();
    if (!row) return null;
    return {
      sessionId: row.session_id,
      userId: row.user_id,
      tokenHash: row.token_hash,
      createdAt: row.created_at,
      expiresAt: row.expires_at,
      lastSeenAt: row.last_seen_at,
      username: row.username,
      householdId: row.household_id,
      householdName: row.household_name,
      role: row.role,
      displayLabel: row.display_label
    };
  }

  async deleteSessionByTokenHash(tokenHash) {
    await this.db.prepare('DELETE FROM sessions WHERE token_hash = ?1').bind(tokenHash).run();
  }

  async deleteSessionsForUser(userId) {
    await this.db.prepare('DELETE FROM sessions WHERE user_id = ?1').bind(userId).run();
  }

  async claimUnmappedPlaidItems(householdId, userId, createdAt) {
    await this.db.prepare(`
      INSERT INTO plaid_item_households (
        item_id, household_id, connected_by_user_id, created_at
      )
      SELECT p.item_id, ?1, ?2, ?3
      FROM plaid_items p
      LEFT JOIN plaid_item_households ph ON ph.item_id = p.item_id
      WHERE ph.item_id IS NULL
    `).bind(householdId, userId, createdAt).run();
  }

  async createInvite(invite) {
    await this.db.prepare(`
      INSERT INTO household_invites (
        invite_id, household_id, code_hash, created_by_user_id,
        created_at, expires_at, redeemed_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
    `).bind(
      invite.inviteId,
      invite.householdId,
      invite.codeHash,
      invite.createdByUserId,
      invite.createdAt,
      invite.expiresAt,
      invite.redeemedAt
    ).run();
  }

  async findUsableInviteByCodeHash(codeHash, nowIso) {
    const row = await this.db.prepare(`
      SELECT invite_id, household_id, code_hash, created_by_user_id,
             created_at, expires_at, redeemed_at
      FROM household_invites
      WHERE code_hash = ?1
        AND redeemed_at IS NULL
        AND expires_at > ?2
      LIMIT 1
    `).bind(codeHash, nowIso).first();
    if (!row) return null;
    return {
      inviteId: row.invite_id,
      householdId: row.household_id,
      codeHash: row.code_hash,
      createdByUserId: row.created_by_user_id,
      createdAt: row.created_at,
      expiresAt: row.expires_at,
      redeemedAt: row.redeemed_at
    };
  }

  async redeemInvite(inviteId, redeemedAt) {
    const result = await this.db.prepare(`
      UPDATE household_invites
      SET redeemed_at = ?2
      WHERE invite_id = ?1 AND redeemed_at IS NULL
    `).bind(inviteId, redeemedAt).run();
    return Number(result?.meta?.changes ?? result?.changes ?? 0) > 0;
  }

  async createInvitedMemberBundle({ user, member, session, inviteId, redeemedAt }) {
    const usable = await this.db.prepare(`
      SELECT invite_id
      FROM household_invites
      WHERE invite_id = ?1 AND redeemed_at IS NULL AND expires_at > ?2
      LIMIT 1
    `).bind(inviteId, redeemedAt).first();
    if (!usable) throw Object.assign(new Error('Invite is invalid or already used'), { status: 409 });

    await this.db.batch([
      this.db.prepare(`
        INSERT INTO users (
          user_id, username, username_norm, password_salt, password_hash,
          password_iterations, created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
      `).bind(
        user.userId, user.username, user.usernameNorm, user.passwordSalt,
        user.passwordHash, user.passwordIterations, user.createdAt
      ),
      this.db.prepare(`
        INSERT INTO household_members (
          household_id, user_id, role, display_label, joined_at
        ) VALUES (?1, ?2, ?3, ?4, ?5)
      `).bind(member.householdId, member.userId, member.role, member.displayLabel, member.joinedAt),
      this.db.prepare(`
        INSERT INTO sessions (
          session_id, user_id, token_hash, created_at, expires_at, last_seen_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
      `).bind(
        session.sessionId, session.userId, session.tokenHash,
        session.createdAt, session.expiresAt, session.lastSeenAt
      ),
      this.db.prepare(`
        UPDATE household_invites
        SET redeemed_at = ?2
        WHERE invite_id = ?1 AND redeemed_at IS NULL
      `).bind(inviteId, redeemedAt)
    ]);
  }

  async readRateLimit(bucket) {
    const row = await this.db.prepare(`
      SELECT bucket, window_started_ms, attempt_count
      FROM auth_rate_limits
      WHERE bucket = ?1
      LIMIT 1
    `).bind(bucket).first();
    if (!row) return null;
    return {
      bucket: row.bucket,
      windowStartedMs: Number(row.window_started_ms),
      attemptCount: Number(row.attempt_count)
    };
  }

  async writeRateLimit(bucket, windowStartedMs, attemptCount) {
    await this.db.prepare(`
      INSERT INTO auth_rate_limits (bucket, window_started_ms, attempt_count)
      VALUES (?1, ?2, ?3)
      ON CONFLICT(bucket) DO UPDATE SET
        window_started_ms = excluded.window_started_ms,
        attempt_count = excluded.attempt_count
    `).bind(bucket, windowStartedMs, attemptCount).run();
  }
}
