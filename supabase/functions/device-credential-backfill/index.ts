import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { buildDurableCredential } from "../tv-logins-exchange/index.ts";

type BackfillRequest = {
  device_name?: string | null;
  device_model?: string | null;
  device_platform?: string | null;
};

type LegacyLinkedDeviceRow = {
  id: string;
  device_user_id: string;
  device_name?: string | null;
  device_model?: string | null;
  device_platform?: string | null;
};

type DurableCredentialRecord = {
  owner_id: string;
  device_user_id: string;
  linked_device_id: string;
  device_public_id: string;
  credential_hash: string;
  display_name: string;
  device_name: string | null;
  device_model: string | null;
  device_platform: string | null;
  status: "active";
  last_seen_at: string;
  revoked_at: null;
};

type BackfillMetadata = {
  deviceName: string | null;
  deviceModel: string | null;
  devicePlatform: string | null;
};

type BackfillResult =
  | {
      status: "backfilled";
      credential: {
        device_public_id: string;
        device_secret: string;
        display_name: string;
      };
    }
  | {
      status: "needs_reconnect";
      reason:
        | "no_legacy_match"
        | "ambiguous_legacy_match"
        | "revoked_durable_credential";
    };

type ExistingCredentialRow = {
  device_public_id?: string | null;
  status?: "active" | "revoked" | null;
};

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function requireEnv(name: string): string {
  const value = Deno.env.get(name) ?? "";
  if (!value) {
    throw new Error(`Missing ${name}`);
  }

  return value;
}

function createSupabaseClients(authHeader?: string) {
  const supabaseUrl = requireEnv("SUPABASE_URL");
  const supabaseAnonKey = requireEnv("SUPABASE_ANON_KEY");
  const supabaseServiceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  return {
    userClient: createClient(supabaseUrl, supabaseAnonKey, {
      global: authHeader
        ? { headers: { Authorization: authHeader } }
        : undefined,
      auth: { persistSession: false, autoRefreshToken: false },
    }),
    adminClient: createClient(supabaseUrl, supabaseServiceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    }),
  };
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders,
    },
  });
}

function normalizeOptionalString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed || null;
}

export function normalizeBackfillBody(body: unknown): BackfillMetadata {
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    throw new Error("Invalid legacy device metadata");
  }

  const request = body as BackfillRequest;
  const metadata = {
    deviceName: normalizeOptionalString(request.device_name),
    deviceModel: normalizeOptionalString(request.device_model),
    devicePlatform: normalizeOptionalString(request.device_platform),
  };

  if (!metadata.deviceName && !metadata.deviceModel && !metadata.devicePlatform) {
    throw new Error("Invalid legacy device metadata");
  }

  return metadata;
}

function matchesValue(candidate: string | null | undefined, expected: string | null) {
  if (!expected) return true;
  return (candidate?.trim() || null) === expected;
}

export function findUniqueLegacyLinkedDeviceMatch(
  rows: LegacyLinkedDeviceRow[],
  metadata: BackfillMetadata,
): LegacyLinkedDeviceRow | null {
  let matches = rows.filter((row) => matchesValue(row.device_name, metadata.deviceName));
  if (matches.length === 0) return null;
  if (matches.length === 1) return matches[0];

  if (metadata.deviceModel) {
    matches = matches.filter((row) => matchesValue(row.device_model, metadata.deviceModel));
  }
  if (matches.length === 1) return matches[0];

  if (metadata.devicePlatform) {
    matches = matches.filter((row) =>
      matchesValue(row.device_platform, metadata.devicePlatform)
    );
  }

  return matches.length === 1 ? matches[0] : null;
}

export function buildBackfillResponsePayload(input: BackfillResult) {
  if (input.status === "backfilled") {
    return {
      status: "backfilled" as const,
      device_public_id: input.credential.device_public_id,
      device_secret: input.credential.device_secret,
      display_name: input.credential.display_name,
    };
  }

  return {
    status: "needs_reconnect" as const,
    reason: input.reason,
  };
}

export function shouldBackfillDurableCredential(
  existingCredential: ExistingCredentialRow | null,
): boolean {
  return existingCredential?.status !== "revoked";
}

async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
      return json({ error: "Not authenticated" }, 401);
    }

    const metadata = normalizeBackfillBody(await req.json());
    const { userClient, adminClient } = createSupabaseClients(authHeader);

    const {
      data: { user },
      error: userError,
    } = await userClient.auth.getUser();

    if (userError || !user) {
      return json({ error: "Not authenticated" }, 401);
    }

    const { data: linkedRows, error: linkedError } = await adminClient
      .from("linked_devices")
      .select("id, device_user_id, device_name, device_model, device_platform")
      .eq("owner_id", user.id);

    if (linkedError) {
      return json(
        { error: `Legacy device lookup failed: ${linkedError.message}` },
        500,
      );
    }

    const matchedRow = findUniqueLegacyLinkedDeviceMatch(linkedRows ?? [], metadata);
    if (!matchedRow) {
      const duplicateNameMatches = (linkedRows ?? []).filter((row) =>
        matchesValue(row.device_name, metadata.deviceName)
      ).length;
      return json(
        buildBackfillResponsePayload({
          status: "needs_reconnect",
          reason: duplicateNameMatches > 1
            ? "ambiguous_legacy_match"
            : "no_legacy_match",
        }),
        200,
      );
    }

    const { data: existingCredential, error: existingCredentialError } =
      await adminClient
        .from("device_credentials")
        .select("device_public_id, status")
        .eq("owner_id", user.id)
        .eq("device_user_id", matchedRow.device_user_id)
        .maybeSingle();

    if (existingCredentialError) {
      return json(
        {
          error: `Durable credential lookup failed: ${existingCredentialError.message}`,
        },
        500,
      );
    }

    if (!shouldBackfillDurableCredential(existingCredential)) {
      return json(
        buildBackfillResponsePayload({
          status: "needs_reconnect",
          reason: "revoked_durable_credential",
        }),
        200,
      );
    }

    const durableCredential = await buildDurableCredential();
    const displayName = matchedRow.device_name?.trim() ||
      matchedRow.device_model?.trim() ||
      "Living Room TV";
    const now = new Date().toISOString();
    const credentialRow: DurableCredentialRecord = {
      owner_id: user.id,
      device_user_id: matchedRow.device_user_id,
      linked_device_id: matchedRow.id,
      device_public_id: durableCredential.server.device_public_id,
      credential_hash: durableCredential.server.credential_hash,
      display_name: displayName,
      device_name: matchedRow.device_name?.trim() || metadata.deviceName,
      device_model: matchedRow.device_model?.trim() || metadata.deviceModel,
      device_platform: matchedRow.device_platform?.trim() || metadata.devicePlatform,
      status: "active",
      last_seen_at: now,
      revoked_at: null,
    };

    const { error: credentialError } = await adminClient
      .from("device_credentials")
      .upsert(credentialRow, { onConflict: "device_user_id" });

    if (credentialError) {
      return json(
        { error: `Failed to backfill durable credential: ${credentialError.message}` },
        500,
      );
    }

    return json(
      buildBackfillResponsePayload({
        status: "backfilled",
        credential: {
          device_public_id: durableCredential.client.device_public_id,
          device_secret: durableCredential.client.device_secret,
          display_name: displayName,
        },
      }),
      200,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected error";
    const status = message === "Invalid legacy device metadata" ? 400 : 500;
    return json({ error: message }, status);
  }
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
