import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  hashDeviceCredential,
  normalizeDeviceExchangeBody,
} from "../_shared/device-auth.ts";

type SessionResult = {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
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

function createSupabaseClients() {
  const supabaseUrl = requireEnv("SUPABASE_URL");
  const supabaseAnonKey = requireEnv("SUPABASE_ANON_KEY");
  const supabaseServiceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  return {
    adminClient: createClient(supabaseUrl, supabaseServiceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    }),
    publicClient: createClient(supabaseUrl, supabaseAnonKey, {
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

export function invalidCredentialResponse(): Response {
  return json({ error: "Invalid durable device credential" }, 401);
}

export function buildDeviceSessionPayload(session: SessionResult) {
  return {
    access_token: session.access_token,
    refresh_token: session.refresh_token,
    token_type: session.token_type,
    expires_in: session.expires_in,
  };
}

async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    const body = normalizeDeviceExchangeBody(await req.json());
    const { adminClient, publicClient } = createSupabaseClients();

    const { data: credentialRow, error: credentialError } = await adminClient
      .from("device_credentials")
      .select("owner_id, linked_device_id, credential_hash, status")
      .eq("device_public_id", body.devicePublicId)
      .eq("status", "active")
      .maybeSingle();

    if (credentialError) {
      return json({
        error: `Device credential lookup failed: ${credentialError.message}`,
      }, 500);
    }

    if (!credentialRow) {
      return invalidCredentialResponse();
    }

    const candidateHash = await hashDeviceCredential(
      body.devicePublicId,
      body.deviceSecret,
    );

    if (candidateHash !== credentialRow.credential_hash) {
      return invalidCredentialResponse();
    }

    const { data: ownerResult, error: ownerError } = await adminClient.auth
      .admin.getUserById(
        credentialRow.owner_id,
      );
    if (ownerError || !ownerResult.user) {
      return json({ error: "Failed to load approved account" }, 500);
    }

    const ownerEmail = ownerResult.user.email;
    if (!ownerEmail) {
      return json({
        error: "Approved account has no email; device exchange unsupported",
      }, 409);
    }

    const { data: magicData, error: magicError } = await adminClient.auth.admin
      .generateLink({
        type: "magiclink",
        email: ownerEmail,
        options: {
          redirectTo: "http://localhost",
        },
      });

    if (magicError || !magicData.properties?.hashed_token) {
      return json(
        {
          error: `Failed to generate owner session link: ${
            magicError?.message ?? "unknown"
          }`,
        },
        500,
      );
    }

    const { data: verifyData, error: verifyError } = await publicClient.auth
      .verifyOtp({
        type: "magiclink",
        token_hash: magicData.properties.hashed_token,
      });

    if (verifyError || !verifyData.session) {
      return json(
        {
          error: `Failed to mint owner session: ${
            verifyError?.message ?? "unknown"
          }`,
        },
        500,
      );
    }

    const now = new Date().toISOString();
    await adminClient
      .from("device_credentials")
      .update({ last_seen_at: now })
      .eq("device_public_id", body.devicePublicId);

    if (credentialRow.linked_device_id) {
      await adminClient
        .from("linked_devices")
        .update({ status: "online", last_seen_at: now })
        .eq("id", credentialRow.linked_device_id);
    }

    return json(buildDeviceSessionPayload(verifyData.session), 200);
  } catch (error) {
    if (
      error instanceof Error &&
      error.message === "Invalid durable device credential"
    ) {
      return invalidCredentialResponse();
    }

    const message = error instanceof Error ? error.message : "Unexpected error";
    return json({ error: message }, 500);
  }
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
