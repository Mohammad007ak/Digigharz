-- Same schema as server/db.js in the Node version: either backend can open
-- the other's database.
CREATE TABLE IF NOT EXISTS otp_codes (
    phone TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    attempts BIGINT NOT NULL DEFAULT 0,
    window_start BIGINT NOT NULL,
    sent_in_window BIGINT NOT NULL DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    phone TEXT NOT NULL,
    expires_at BIGINT NOT NULL
  );

  -- A fund's whole ledger is one JSON document, edited only by its manager.
  -- "version" guards against two tabs overwriting each other.
  CREATE TABLE IF NOT EXISTS funds (
    id TEXT PRIMARY KEY,
    owner_phone TEXT NOT NULL,
    data TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS funds_owner ON funds (owner_phone);

  -- Derived from funds.data on every save, so members can find their funds.
  CREATE TABLE IF NOT EXISTS fund_members (
    fund_id TEXT NOT NULL REFERENCES funds (id) ON DELETE CASCADE,
    member_id TEXT NOT NULL,
    phone TEXT NOT NULL,
    PRIMARY KEY (fund_id, member_id)
  );
  CREATE INDEX IF NOT EXISTS fund_members_phone ON fund_members (phone);

  -- Every draw is kept, including cancelled ones, so members can see
  -- whether the manager re-rolled a result they didn't like.
  CREATE TABLE IF NOT EXISTS draws (
    id TEXT PRIMARY KEY,
    fund_id TEXT NOT NULL REFERENCES funds (id) ON DELETE CASCADE,
    month TEXT NOT NULL,
    winner_member_id TEXT NOT NULL,
    winner_name TEXT NOT NULL,
    entries TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'confirmed', 'cancelled')),
    created_at BIGINT NOT NULL,
    resolved_at BIGINT
  );
  CREATE INDEX IF NOT EXISTS draws_fund ON draws (fund_id, created_at);

  -- ---------- guaranteed circles (paid product, Digipay as operator) ----------
  -- A circle is one run of a plan: N members, N months, one pot per month.
  -- Position 1 is always the operator, who takes month 1's pot.
  CREATE TABLE IF NOT EXISTS circles (
    id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('forming', 'active', 'completed', 'expired')),
    size BIGINT NOT NULL,
    months BIGINT NOT NULL,
    share BIGINT NOT NULL,
    chain_secret TEXT NOT NULL,
    anchor TEXT NOT NULL,
    nonce_digest TEXT,
    current_month BIGINT NOT NULL DEFAULT 0,
    closing BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    deadline BIGINT NOT NULL,
    started_at BIGINT
  );
  CREATE INDEX IF NOT EXISTS circles_plan ON circles (plan_id, status);

  CREATE TABLE IF NOT EXISTS circle_members (
    id TEXT PRIMARY KEY,
    circle_id TEXT NOT NULL REFERENCES circles (id),
    position BIGINT NOT NULL,
    phone TEXT,
    is_operator BIGINT NOT NULL DEFAULT 0,
    is_bot BIGINT NOT NULL DEFAULT 0,
    nonce TEXT NOT NULL,
    pay_method TEXT NOT NULL CHECK (pay_method IN ('operator', 'auto', 'manual')),
    mandate_id TEXT,
    -- Gateway reference of the first share, paid to take the seat.
    entry_ref TEXT,
    won_month BIGINT,
    -- The latest draw this member has watched (the reveal plays once).
    seen_month BIGINT NOT NULL DEFAULT 1,
    joined_at BIGINT NOT NULL,
    UNIQUE (circle_id, position)
  );
  CREATE INDEX IF NOT EXISTS circle_members_phone ON circle_members (phone);

  -- "covered" means the operator paid the pot share on the member's behalf
  -- (the guarantee); the member still owes it until settled_at is set.
  CREATE TABLE IF NOT EXISTS contributions (
    circle_id TEXT NOT NULL REFERENCES circles (id),
    member_id TEXT NOT NULL REFERENCES circle_members (id),
    month BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('due', 'paid', 'covered')),
    method TEXT,
    ref TEXT,
    paid_at BIGINT,
    settled_at BIGINT,
    PRIMARY KEY (circle_id, member_id, month)
  );

  -- One row per month. Lottery rows carry everything needed to re-check
  -- the result: the revealed hash-chain link, the seed and the entrants.
  CREATE TABLE IF NOT EXISTS circle_draws (
    circle_id TEXT NOT NULL REFERENCES circles (id),
    month BIGINT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('operator', 'lottery', 'last')),
    draw_no BIGINT,
    reveal TEXT,
    seed TEXT,
    eligible TEXT NOT NULL,
    winner_member_id TEXT NOT NULL,
    pot BIGINT NOT NULL,
    payout_ref TEXT,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (circle_id, month)
  );

  -- A month's pot nobody could take: everyone still waiting owes the
  -- guarantee. Digipay holds it until one of them settles (debt plus the
  -- late fee) and claims it; unclaimed past the grace period, Digipay keeps it.
  CREATE TABLE IF NOT EXISTS held_pots (
    circle_id TEXT NOT NULL REFERENCES circles (id),
    month BIGINT NOT NULL,
    pot BIGINT NOT NULL,
    held_at BIGINT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('held', 'claimed', 'forfeited')),
    member_id TEXT,
    resolved_at BIGINT,
    PRIMARY KEY (circle_id, month)
  );

  -- "entry" pays the first share to join a plan (the seat is taken once it's
  -- paid); "dues" pays a member's outstanding months.
  CREATE TABLE IF NOT EXISTS checkouts (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('entry', 'dues')),
    phone TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    nonce TEXT,
    circle_id TEXT REFERENCES circles (id),
    member_id TEXT,
    items TEXT NOT NULL,
    amount BIGINT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'paid', 'cancelled', 'refunded')),
    ref TEXT,
    created_at BIGINT NOT NULL
  );

  -- Every money movement and lifecycle step in the guaranteed plans, for the
  -- operator's reports and audit trail. Append-only.
  CREATE TABLE IF NOT EXISTS ops_events (
    id TEXT PRIMARY KEY,
    at BIGINT NOT NULL,
    kind TEXT NOT NULL,
    circle_id TEXT,
    member_id TEXT,
    phone TEXT,
    amount BIGINT,
    detail TEXT
  );
  CREATE INDEX IF NOT EXISTS ops_events_at ON ops_events (at);

  -- Members who have been shown the first-visit tour, so every new
  -- account gets it once right after signing in, on whatever device.
  CREATE TABLE IF NOT EXISTS tour_seen (
    phone TEXT PRIMARY KEY,
    seen_at BIGINT NOT NULL
  );

  -- Admin panel sign-ins (username/password), apart from members' sessions.
  CREATE TABLE IF NOT EXISTS admin_sessions (
    token_hash TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    expires_at BIGINT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS ops_events_circle ON ops_events (circle_id, at);

  -- ---------- product analytics (admin panel) ----------
  -- "day" is the Tehran calendar day (YYYY-MM-DD), stored so reports can
  -- GROUP BY it on either database.

  -- One row per browser/app install: the first open is the "install".
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    phone TEXT,
    platform TEXT NOT NULL,
    os_version TEXT,
    app_version TEXT,
    channel TEXT NOT NULL,
    campaign TEXT,
    first_at BIGINT NOT NULL,
    first_day TEXT NOT NULL,
    last_at BIGINT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS devices_first_day ON devices (first_day);

  -- One row per signed-in account; attribution is first touch.
  CREATE TABLE IF NOT EXISTS users (
    phone TEXT PRIMARY KEY,
    first_at BIGINT NOT NULL,
    first_day TEXT NOT NULL,
    last_at BIGINT NOT NULL,
    last_day TEXT NOT NULL,
    platform TEXT NOT NULL,
    channel TEXT NOT NULL,
    campaign TEXT,
    app_version TEXT,
    device_id TEXT,
    logins BIGINT NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS users_first_day ON users (first_day);
  CREATE INDEX IF NOT EXISTS users_last_at ON users (last_at);

  -- Product events: app opens, logins, feature use, purchases.
  CREATE TABLE IF NOT EXISTS analytics_events (
    id TEXT PRIMARY KEY,
    at BIGINT NOT NULL,
    day TEXT NOT NULL,
    name TEXT NOT NULL,
    device_id TEXT,
    phone TEXT,
    platform TEXT,
    channel TEXT,
    props TEXT
  );
  CREATE INDEX IF NOT EXISTS analytics_events_day ON analytics_events (day, name);
  CREATE INDEX IF NOT EXISTS analytics_events_phone ON analytics_events (phone, at);

  -- Errors from the app and the server, grouped by fingerprint.
  CREATE TABLE IF NOT EXISTS error_groups (
    fingerprint TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    message TEXT NOT NULL,
    stack TEXT,
    url TEXT,
    status TEXT NOT NULL DEFAULT 'open',
    count BIGINT NOT NULL DEFAULT 0,
    first_at BIGINT NOT NULL,
    last_at BIGINT NOT NULL,
    note TEXT
  );
  CREATE INDEX IF NOT EXISTS error_groups_last ON error_groups (last_at);
  CREATE TABLE IF NOT EXISTS error_logs (
    id TEXT PRIMARY KEY,
    fingerprint TEXT NOT NULL,
    at BIGINT NOT NULL,
    day TEXT NOT NULL,
    source TEXT NOT NULL,
    level TEXT NOT NULL,
    message TEXT NOT NULL,
    stack TEXT,
    url TEXT,
    device_id TEXT,
    phone TEXT,
    platform TEXT,
    app_version TEXT,
    context TEXT
  );
  CREATE INDEX IF NOT EXISTS error_logs_fp ON error_logs (fingerprint, at);
  CREATE INDEX IF NOT EXISTS error_logs_day ON error_logs (day);

  -- The AI's daily read of yesterday's numbers.
  CREATE TABLE IF NOT EXISTS ai_insights (
    day TEXT PRIMARY KEY,
    created_at BIGINT NOT NULL,
    model TEXT,
    status TEXT NOT NULL,
    content TEXT,
    metrics TEXT,
    error TEXT
  );
