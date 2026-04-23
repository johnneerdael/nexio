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

    const { error: credentialError } = await adminClient
      .from("device_credentials")
      .upsert(
        {
          owner_id: handoffRow.owner_id,
          device_user_id: handoffRow.device_user_id,
          linked_device_id: handoffRow.linked_device_id,
          device_public_id: handoffRow.device_public_id,
          credential_hash: handoffRow.credential_hash,
          display_name: handoffRow.display_name,
          device_name: handoffRow.device_name,
          device_model: handoffRow.device_model,
          device_platform: handoffRow.device_platform,
          status: "active",
          last_seen_at: new Date().toISOString(),
          revoked_at: null,
        },
        { onConflict: "device_user_id" },
      );

    if (credentialError) {
      return json({ error: `Credential activation failed: ${credentialError.message}` }, 500);
    }

    await adminClient
      .from("device_credential_handoffs")
      .update({ used_at: new Date().toISOString() })
      .eq("id", handoffRow.id);

    return json({ activated: true }, 200);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected error";
    return json({ error: message }, 500);
  }
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
