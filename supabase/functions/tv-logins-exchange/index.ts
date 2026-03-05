import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

type ExchangeRequest = {
  code?: string;
  device_nonce?: string;
};

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS"
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const supabaseServiceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

if (!supabaseUrl || !supabaseAnonKey || !supabaseServiceRoleKey) {
  throw new Error("Missing SUPABASE_URL / SUPABASE_ANON_KEY / SUPABASE_SERVICE_ROLE_KEY");
}

Deno.serve(async (req) => {
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

    const userClient = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: authHeader } },
      auth: { persistSession: false, autoRefreshToken: false }
    });

    const adminClient = createClient(supabaseUrl, supabaseServiceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false }
    });

    const publicClient = createClient(supabaseUrl, supabaseAnonKey, {
      auth: { persistSession: false, autoRefreshToken: false }
    });

    const {
      data: { user: requesterUser },
      error: requesterError
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
      .select("id, code, device_nonce, requester_user_id, approved_by_user_id, status, expires_at, device_name, device_model, device_platform")
      .eq("code", code)
      .eq("device_nonce", deviceNonce)
      .eq("requester_user_id", requesterUser.id)
      .maybeSingle();

    if (sessionError) {
      return json({ error: `TV login lookup failed: ${sessionError.message}` }, 500);
    }

    if (!sessionRow) {
      return json({ error: "Invalid TV login code or nonce" }, 400);
    }

    const expiresAt = new Date(sessionRow.expires_at);
    if (Number.isNaN(expiresAt.getTime()) || expiresAt.getTime() <= Date.now()) {
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
    const { data: ownerResult, error: ownerError } = await adminClient.auth.admin.getUserById(ownerUserId);
    if (ownerError || !ownerResult.user) {
      return json({ error: "Failed to load approved account" }, 500);
    }

    const ownerEmail = ownerResult.user.email;
    if (!ownerEmail) {
      return json({ error: "Approved account has no email; QR exchange unsupported" }, 409);
    }

    const { data: magicData, error: magicError } = await adminClient.auth.admin.generateLink({
      type: "magiclink",
      email: ownerEmail,
      options: {
        redirectTo: "http://localhost"
      }
    });

    if (magicError || !magicData.properties?.hashed_token) {
      return json({ error: `Failed to generate owner session link: ${magicError?.message ?? "unknown"}` }, 500);
    }

    const { data: verifyData, error: verifyError } = await publicClient.auth.verifyOtp({
      type: "magiclink",
      token_hash: magicData.properties.hashed_token
    });

    if (verifyError || !verifyData.session) {
      return json({ error: `Failed to mint owner session: ${verifyError?.message ?? "unknown"}` }, 500);
    }

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
          linked_at: new Date().toISOString(),
          last_seen_at: new Date().toISOString()
        },
        { onConflict: "device_user_id" }
      );

    await adminClient
      .from("tv_login_sessions")
      .update({ status: "used", used_at: new Date().toISOString() })
      .eq("id", sessionRow.id);

    return json(
      {
        access_token: verifyData.session.access_token,
        refresh_token: verifyData.session.refresh_token,
        token_type: verifyData.session.token_type,
        expires_in: verifyData.session.expires_in
      },
      200
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unexpected error";
    return json({ error: message }, 500);
  }
});

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders
    }
  });
}
