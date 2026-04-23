import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { hashDeviceCredential, normalizeDeviceExchangeBody } from "../_shared/device-auth.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders,
    },
  });
}

function requireEnv(name: string): string {
  const value = Deno.env.get(name) ?? "";
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

function createSupabaseClients(authHeader: string) {
  const supabaseUrl = requireEnv("SUPABASE_URL");
  const supabaseAnonKey = requireEnv("SUPABASE_ANON_KEY");
  const supabaseServiceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  return {
    userClient: createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: authHeader } },
      auth: { persistSession: false, autoRefreshToken: false },
    }),
    adminClient: createClient(supabaseUrl, supabaseServiceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    }),
  };
}

export function isCredentialHandoffExpired(
  expiresAt: string | null | undefined,
  now = Date.now(),
): boolean {
  if (!expiresAt) return true;
  const parsed = new Date(expiresAt);
  const expiresAtMs = parsed.getTime();
  return Number.isNaN(expiresAtMs) || expiresAtMs <= now;
}

async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

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

    const body = normalizeDeviceExchangeBody(await req.json());
    const candidateHash = await hashDeviceCredential(
      body.devicePublicId,
      body.deviceSecret,
    );

    const { data: handoffRow, error: handoffError } = await adminClient
      .from("device_credential_handoffs")
      .select("*")
      .eq("device_user_id", requesterUser.id)
      .eq("device_public_id", body.devicePublicId)
      .is("used_at", null)
      .maybeSingle();

    if (handoffError) {
      return json({ error: `Handoff lookup failed: ${handoffError.message}` }, 500);
    }

    if (!handoffRow || handoffRow.credential_hash !== candidateHash) {
      return json({ error: "Invalid durable device credential" }, 401);
    }
    if (isCredentialHandoffExpired(handoffRow.expires_at)) {
      return json({ error: "Durable device credential handoff expired" }, 409);
    }

    const { data: activated, error: activationError } = await adminClient
      .rpc("activate_device_credential_handoff", {
        p_device_user_id: requesterUser.id,
        p_device_public_id: body.devicePublicId,
        p_credential_hash: candidateHash,
      });

    if (activationError) {
      return json({ error: `Credential activation failed: ${activationError.message}` }, 500);
    }
    if (!activated) {
      return json({ error: "Durable device credential handoff is no longer valid" }, 409);
    }

    return json({ activated: true }, 200);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected error";
    return json({ error: message }, 500);
  }
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
