import test from "node:test";
import assert from "node:assert/strict";
import {
  hashDeviceCredential,
  normalizeDeviceExchangeBody,
} from "../_shared/device-auth.ts";
import {
  buildApprovalExchangePayload,
  buildApprovalResponsePayload,
  buildDurableCredential,
} from "../tv-logins-exchange/index.ts";
import {
  buildDeviceSessionPayload,
  invalidCredentialResponse,
} from "../device-session-exchange/index.ts";
import {
  buildBackfillResponsePayload,
  findUniqueLegacyLinkedDeviceMatch,
  shouldBackfillDurableCredential,
  normalizeBackfillBody,
} from "../device-credential-backfill/index.ts";

const migrationContractText = String.raw`
device_public_id text not null check (length(trim(device_public_id)) > 0),
credential_hash text not null check (length(trim(credential_hash)) > 0),
display_name text not null check (length(trim(display_name)) > 0),
status text not null default 'active'
  check (status in ('active', 'revoked')),
revoked_at timestamptz null,
check (
  (status = 'revoked' and revoked_at is not null)
  or (status <> 'revoked' and revoked_at is null)
)
raise exception 'Invalid durable device credential';
raise exception 'Device credential not found or already revoked';
add column if not exists requested_display_name text;
create or replace function public.approve_tv_login_session(
  p_code text,
  p_device_nonce text,
  p_display_name text default null
)
requested_display_name = nullif(trim(coalesce(p_display_name, '')), ''),
revoked_at: null,
create or replace function public.url_encode(input text)
create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
v_web_url := v_base_url || '/approve?code=' || v_code || '&nonce=' || replace(v_nonce, '+', '%2B');
v_web_url := v_web_url || '&device_name=' || public.url_encode(v_device_name);
`;

test("hashDeviceCredential is deterministic for the same raw secret", async () => {
  const a = await hashDeviceCredential("public-id", "secret-value");
  const b = await hashDeviceCredential("public-id", "secret-value");
  assert.equal(a, b);
});

test("hashDeviceCredential matches the canonical golden hash", async () => {
  const hash = await hashDeviceCredential("public-id", "secret-value");

  assert.equal(
    hash,
    "db533cc9f987253d03a063d1c130580872ab28ba41a9b64d1cb45686ff89f6b4",
  );
});

test("hashDeviceCredential depends on the credential inputs", async () => {
  const base = await hashDeviceCredential("public-id", "secret-value");
  const differentSecret = await hashDeviceCredential(
    "public-id",
    "other-secret",
  );
  const differentPublicId = await hashDeviceCredential(
    "other-public-id",
    "secret-value",
  );

  assert.notEqual(base, differentSecret);
  assert.notEqual(base, differentPublicId);
});

test("hashDeviceCredential does not collide for colon-containing credential pairs", async () => {
  const a = await hashDeviceCredential("foo", "bar:baz");
  const b = await hashDeviceCredential("foo:bar", "baz");

  assert.notEqual(a, b);
});

test("normalizeDeviceExchangeBody returns trimmed credential strings", () => {
  assert.deepEqual(
    normalizeDeviceExchangeBody({
      device_public_id: "  public-id  ",
      device_secret: "  secret-value  ",
    }),
    { devicePublicId: "public-id", deviceSecret: "secret-value" },
  );
});

test("buildApprovalExchangePayload prefers requested display name and trims it", async () => {
  const payload = await buildApprovalExchangePayload({
    ownerUserId: "owner-user-id",
    requesterUserId: "requester-user-id",
    linkedDeviceId: "linked-device-id",
    requestedDisplayName: "  Bedroom TV  ",
    sessionRow: {
      device_name: "Session Device",
      device_model: "Chromecast",
      device_platform: "Android TV",
    },
  });

  assert.equal(payload.owner_id, "owner-user-id");
  assert.equal(payload.device_user_id, "requester-user-id");
  assert.equal(payload.linked_device_id, "linked-device-id");
  assert.equal(payload.display_name, "Bedroom TV");
  assert.equal(payload.device_name, "Session Device");
  assert.equal(payload.device_model, "Chromecast");
  assert.equal(payload.device_platform, "Android TV");
  assert.equal(payload.revoked_at, null);
  assert.match(payload.device_public_id, /^tv_[0-9a-f-]{36}$/);
  assert.match(payload.credential_hash, /^[0-9a-f]{64}$/);
});

test("buildApprovalExchangePayload falls back to session metadata and default name", async () => {
  const fromDeviceName = await buildApprovalExchangePayload({
    ownerUserId: "owner-user-id",
    requesterUserId: "requester-user-id",
    linkedDeviceId: null,
    requestedDisplayName: "   ",
    sessionRow: {
      device_name: "Den TV",
      device_model: "Chromecast",
      device_platform: "Android TV",
    },
  });

  assert.equal(fromDeviceName.display_name, "Den TV");

  const fromModel = await buildApprovalExchangePayload({
    ownerUserId: "owner-user-id",
    requesterUserId: "requester-user-id",
    linkedDeviceId: null,
    requestedDisplayName: null,
    sessionRow: {
      device_name: null,
      device_model: "NVIDIA Shield",
      device_platform: "Android TV",
    },
  });

  assert.equal(fromModel.display_name, "NVIDIA Shield");

  const fallback = await buildApprovalExchangePayload({
    ownerUserId: "owner-user-id",
    requesterUserId: "requester-user-id",
    linkedDeviceId: null,
    requestedDisplayName: null,
    sessionRow: {
      device_name: null,
      device_model: null,
      device_platform: null,
    },
  });

  assert.equal(fallback.display_name, "Living Room TV");
});

test("buildDurableCredential returns a client payload plus hashed server payload", async () => {
  const credential = await buildDurableCredential();

  assert.match(credential.client.device_public_id, /^tv_[0-9a-f-]{36}$/);
  assert.match(credential.client.device_secret, /^[0-9a-f]{64}$/);
  assert.equal(
    credential.server.credential_hash,
    await hashDeviceCredential(
      credential.client.device_public_id,
      credential.client.device_secret,
    ),
  );
});

test("buildDeviceSessionPayload returns Supabase token fields", () => {
  const payload = buildDeviceSessionPayload({
    access_token: "access-token",
    refresh_token: "refresh-token",
    token_type: "bearer",
    expires_in: 3600,
  });

  assert.deepEqual(payload, {
    access_token: "access-token",
    refresh_token: "refresh-token",
    token_type: "bearer",
    expires_in: 3600,
  });
});

test("normalizeBackfillBody trims metadata and requires at least one usable identifier", () => {
  assert.deepEqual(
    normalizeBackfillBody({
      device_name: "  Living Room TV  ",
      device_model: "  Chromecast  ",
      device_platform: "  Android TV  ",
    }),
    {
      deviceName: "Living Room TV",
      deviceModel: "Chromecast",
      devicePlatform: "Android TV",
    },
  );

  assert.throws(
    () =>
      normalizeBackfillBody({
        device_name: "   ",
        device_model: null,
        device_platform: "",
      }),
    /Invalid legacy device metadata/,
  );
});

test("findUniqueLegacyLinkedDeviceMatch narrows duplicate legacy rows until one remains", () => {
  const row = findUniqueLegacyLinkedDeviceMatch(
    [
      {
        id: "legacy-1",
        device_user_id: "device-user-1",
        device_name: "Living Room TV",
        device_model: "Chromecast",
        device_platform: "Android TV",
      },
      {
        id: "legacy-2",
        device_user_id: "device-user-2",
        device_name: "Living Room TV",
        device_model: "Shield",
        device_platform: "Android TV",
      },
    ],
    {
      deviceName: "Living Room TV",
      deviceModel: "Shield",
      devicePlatform: "Android TV",
    },
  );

  assert.equal(row?.id, "legacy-2");
});

test("findUniqueLegacyLinkedDeviceMatch returns null for zero or ambiguous matches", () => {
  const rows = [
    {
      id: "legacy-1",
      device_user_id: "device-user-1",
      device_name: "Living Room TV",
      device_model: "Chromecast",
      device_platform: "Android TV",
    },
    {
      id: "legacy-2",
      device_user_id: "device-user-2",
      device_name: "Living Room TV",
      device_model: "Chromecast",
      device_platform: "Android TV",
    },
  ];

  assert.equal(
    findUniqueLegacyLinkedDeviceMatch(rows, {
      deviceName: "Bedroom TV",
      deviceModel: null,
      devicePlatform: null,
    }),
    null,
  );
  assert.equal(
    findUniqueLegacyLinkedDeviceMatch(rows, {
      deviceName: "Living Room TV",
      deviceModel: null,
      devicePlatform: "Android TV",
    }),
    null,
  );
});

test("buildBackfillResponsePayload distinguishes successful backfill from reconnect fallback", () => {
  assert.deepEqual(
    buildBackfillResponsePayload({
      status: "backfilled",
      credential: {
        device_public_id: "tv_public_id",
        device_secret: "device-secret",
        display_name: "Living Room TV",
      },
    }),
    {
      status: "backfilled",
      device_public_id: "tv_public_id",
      device_secret: "device-secret",
      display_name: "Living Room TV",
    },
  );

  assert.deepEqual(
    buildBackfillResponsePayload({
      status: "needs_reconnect",
      reason: "ambiguous_legacy_match",
    }),
    {
      status: "needs_reconnect",
      reason: "ambiguous_legacy_match",
    },
  );
});

test("shouldBackfillDurableCredential refuses to reactivate revoked durable authority", () => {
  assert.equal(
    shouldBackfillDurableCredential({
      device_public_id: "tv_11111111-1111-4111-8111-111111111111",
      status: "revoked",
    }),
    false,
  );
  assert.equal(
    shouldBackfillDurableCredential({
      device_public_id: "tv_11111111-1111-4111-8111-111111111111",
      status: "active",
    }),
    true,
  );
  assert.equal(shouldBackfillDurableCredential(null), true);
});

test("buildApprovalResponsePayload returns session fields plus durable credential fields", () => {
  const payload = buildApprovalResponsePayload({
    session: {
      access_token: "access-token",
      refresh_token: "refresh-token",
      token_type: "bearer",
      expires_in: 3600,
    },
    credential: {
      device_public_id: "tv_public_id",
      device_secret: "device-secret",
      display_name: "Living Room TV",
    },
  });

  assert.deepEqual(payload, {
    access_token: "access-token",
    refresh_token: "refresh-token",
    token_type: "bearer",
    expires_in: 3600,
    device_public_id: "tv_public_id",
    device_secret: "device-secret",
    display_name: "Living Room TV",
  });
});

test("invalidCredentialResponse is a 401 contract for revoked or invalid credentials", async () => {
  const response = invalidCredentialResponse();

  assert.equal(response.status, 401);
  assert.equal(
    await response.text(),
    JSON.stringify({ error: "Invalid durable device credential" }),
  );
});

test("normalizeDeviceExchangeBody rejects blank credential inputs", () => {
  assert.throws(
    () =>
      normalizeDeviceExchangeBody({ device_public_id: " ", device_secret: "" }),
    /Invalid durable device credential/,
  );
});

test("normalizeDeviceExchangeBody rejects non-object bodies", () => {
  for (const body of [null, [], 7, true]) {
    assert.throws(
      () => normalizeDeviceExchangeBody(body as never),
      /Invalid durable device credential/,
    );
  }
});

test("normalizeDeviceExchangeBody rejects malformed credential field types", () => {
  for (
    const body of [
      { device_public_id: { nested: "value" }, device_secret: "secret-value" },
      { device_public_id: "public-id", device_secret: ["secret-value"] },
      { device_public_id: 7, device_secret: "secret-value" },
      { device_public_id: "public-id", device_secret: false },
    ]
  ) {
    assert.throws(
      () => normalizeDeviceExchangeBody(body as never),
      /Invalid durable device credential/,
    );
  }
});

test("normalizeDeviceExchangeBody rejects missing credential fields", () => {
  for (
    const body of [
      {},
      { device_public_id: "public-id" },
      { device_secret: "secret-value" },
    ]
  ) {
    assert.throws(
      () => normalizeDeviceExchangeBody(body as never),
      /Invalid durable device credential/,
    );
  }
});

test("durable device auth migration enforces non-empty authority fields", () => {
  assert.match(
    migrationContractText,
    /device_public_id text not null check \(length\(trim\(device_public_id\)\) > 0\)/,
  );
  assert.match(
    migrationContractText,
    /credential_hash text not null check \(length\(trim\(credential_hash\)\) > 0\)/,
  );
  assert.match(
    migrationContractText,
    /display_name text not null check \(length\(trim\(display_name\)\) > 0\)/,
  );
});

test("durable device auth migration only models active and revoked statuses", () => {
  assert.match(
    migrationContractText,
    /check \(status in \('active', 'revoked'\)\)/,
  );
});

test("durable device auth migration ties revoked status to revoked timestamp", () => {
  assert.match(
    migrationContractText,
    /status = 'revoked' and revoked_at is not null/,
  );
  assert.match(
    migrationContractText,
    /status <> 'revoked' and revoked_at is null/,
  );
});

test("durable device auth migration keeps revoke explicit and non-silent", () => {
  assert.match(
    migrationContractText,
    /raise exception 'Invalid durable device credential';/,
  );
  assert.match(
    migrationContractText,
    /raise exception 'Device credential not found or already revoked';/,
  );
});

test("durable device auth migration persists requested display name on tv login approval", () => {
  assert.match(
    migrationContractText,
    /add column if not exists requested_display_name text;/,
  );
  assert.match(
    migrationContractText,
    /create or replace function public\.approve_tv_login_session\(\s*p_code text,\s*p_device_nonce text,\s*p_display_name text default null/s,
  );
  assert.match(
    migrationContractText,
    /requested_display_name = nullif\(trim\(coalesce\(p_display_name, ''\)\), ''\)/,
  );
  assert.match(migrationContractText, /revoked_at: null,/);
});

test("tv login start migration includes device name in the approval url", () => {
  assert.match(
    migrationContractText,
    /create or replace function public\.url_encode\(input text\)/,
  );
  assert.match(
    migrationContractText,
    /create or replace function public\.start_tv_login_session\(\s*p_device_nonce text,\s*p_redirect_base_url text,\s*p_device_name text default null/s,
  );
  assert.match(
    migrationContractText,
    /v_web_url := v_base_url \|\| '\/approve\?code=' \|\| v_code \|\| '&nonce=' \|\| replace\(v_nonce, '\+', '%2B'\);/,
  );
  assert.match(
    migrationContractText,
    /v_web_url := v_web_url \|\| '&device_name=' \|\| public\.url_encode\(v_device_name\);/,
  );
});
