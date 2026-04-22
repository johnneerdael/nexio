import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { hashDeviceCredential } from "../_shared/device-auth.ts";

type ExchangeRequest = {
  code?: string;
  device_nonce?: string;
};

type SessionRow = {
  id?: string;
  expires_at?: string;
  status?: string;
  approved_by_user_id?: string | null;
  requested_display_name?: string | null;
  device_name?: string | null;
  device_model?: string | null;
  device_platform?: string | null;
};

type DurableCredentialRecord = {
  owner_id: string;
  device_user_id: string;
  linked_device_id: string | null;
  device_public_id: string;
  credential_hash: string;
  display_name: string;
  device_name: string | null;
  device_model: string | null;
  device_platform: string | null;
  status: "active";
  last_seen_at: string;
};

type DurableCredentialClientPayload = {
  device_public_id: string;
  device_secret: string;
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
    publicClient: createClient(supabaseUrl, supabaseAnonKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    }),
  };
}

export async function buildDurableCredential(): Promise<{
  client: DurableCredentialClientPayload;
  server: Pick<DurableCredentialRecord, "device_public_id" | "credential_hash">;
}> {
  const devicePublicId = `tv_${crypto.randomUUID()}`;
  const deviceSecret = crypto.randomUUID().replace(/-/g, "") +
    crypto.randomUUID().replace(/-/g, "");
  const credentialHash = await hashDeviceCredential(
    devicePublicId,
    deviceSecret,
  );

  return {
    client: {
      device_public_id: devicePublicId,
      device_secret: deviceSecret,
    },
    server: {
      device_public_id: devicePublicId,
      credential_hash: credentialHash,
    },
  };
}

export async function buildApprovalExchangePayload(input: {
  ownerUserId: string;
  requesterUserId: string;
  linkedDeviceId: string | null;
  requestedDisplayName?: string | null;
  sessionRow: SessionRow;
}): Promise<DurableCredentialRecord> {
  const durableCredential = await buildDurableCredential();
  const stableDisplayName = input.requestedDisplayName?.trim() ||
    input.sessionRow.device_name ||
    input.sessionRow.device_model ||
    "Living Room TV";

  return {
    owner_id: input.ownerUserId,
    device_user_id: input.requesterUserId,
    linked_device_id: input.linkedDeviceId,
    device_public_id: durableCredential.server.device_public_id,
    credential_hash: durableCredential.server.credential_hash,
    display_name: stableDisplayName,
    device_name: input.sessionRow.device_name ?? null,
    device_model: input.sessionRow.device_model ?? null,
    device_platform: input.sessionRow.device_platform ?? null,
    status: "active",
    last_seen_at: new Date().toISOString(),
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

    const { userClient, adminClient } = createSupabaseClients(authHeader);

    const {
      data: { user: requesterUser },
      error: requesterError,
    } = await userClient.auth.getUser();

    if (requesterError || !requesterUser) {
      return json({ error: "Not authenticated" }, 401);
    }

    const body = (await req.json()) as ExchangeRequest;
    const code = (body.code ?? "").trim().toUpperCase();
    const deviceNonce = (body.device_nonce ?? "").trim();

    if (!code) {
      return json({ error: "Invalid TV login code" }, 400);
    }
    if (!deviceNonce) {
      return json({ error: "Invalid device nonce" }, 400);
    }

    const { data: sessionRow, error: sessionError } = await adminClient
      .from("tv_login_sessions")
      .select("*")
      .eq("code", code)
      .eq("device_nonce", deviceNonce)
      .eq("requester_user_id", requesterUser.id)
      .maybeSingle();

    if (sessionError) {
      return json(
        { error: `TV login lookup failed: ${sessionError.message}` },
        500,
      );
    }

    if (!sessionRow) {
      return json({ error: "Invalid TV login code or nonce" }, 400);
    }

    const expiresAt = new Date(sessionRow.expires_at);
    if (
      Number.isNaN(expiresAt.getTime()) || expiresAt.getTime() <= Date.now()
    ) {
      await adminClient
        .from("tv_login_sessions")
        .update({ status: "expired" })
        .eq("id", sessionRow.id);
      return json({ error: "TV login expired" }, 409);
    }

    if (sessionRow.status !== "approved") {
      return json({ error: "TV login pending approval" }, 409);
    }

    if (!sessionRow.approved_by_user_id) {
      return json({ error: "TV login approval is invalid" }, 409);
    }

    const ownerUserId = sessionRow.approved_by_user_id;
    const linkedAt = new Date().toISOString();
    const { data: linkedDeviceRow, error: linkedDeviceError } =
      await adminClient
        .from("linked_devices")
        .upsert(
          {
            owner_id: ownerUserId,
            device_user_id: requesterUser.id,
            device_name: sessionRow.device_name ?? null,
            device_model: sessionRow.device_model ?? null,
            device_platform: sessionRow.device_platform ?? "Android TV",
            status: "online",
            linked_at: linkedAt,
            last_seen_at: linkedAt,
          },
          { onConflict: "device_user_id" },
        )
        .select("id")
        .single();

    if (linkedDeviceError) {
      return json({
        error: `Failed to link device: ${linkedDeviceError.message}`,
      }, 500);
    }

    const durableCredential = await buildDurableCredential();
    const credentialRow = await buildApprovalExchangePayload({
      ownerUserId,
      requesterUserId: requesterUser.id,
      linkedDeviceId: linkedDeviceRow.id,
      requestedDisplayName: sessionRow.requested_display_name ?? null,
      sessionRow,
    });

    credentialRow.device_public_id = durableCredential.server.device_public_id;
    credentialRow.credential_hash = durableCredential.server.credential_hash;

    const { error: credentialError } = await adminClient
      .from("device_credentials")
      .upsert(credentialRow, { onConflict: "device_user_id" });

    if (credentialError) {
      return json({
        error: `Failed to issue durable credential: ${credentialError.message}`,
      }, 500);
    }

    await adminClient
      .from("tv_login_sessions")
      .update({ status: "used", used_at: new Date().toISOString() })
      .eq("id", sessionRow.id);

    return json(
      {
        device_public_id: durableCredential.client.device_public_id,
        device_secret: durableCredential.client.device_secret,
        display_name: credentialRow.display_name,
      },
      200,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected error";
    return json({ error: message }, 500);
  }
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
