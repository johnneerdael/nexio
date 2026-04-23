import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

type BackfillRequest = {
  device_name?: string | null;
  device_model?: string | null;
  device_platform?: string | null;
};

type BackfillMetadata = {
  deviceName: string | null;
  deviceModel: string | null;
  devicePlatform: string | null;
};

type BackfillResult = {
  status: "needs_reconnect";
  reason: "legacy_backfill_disabled";
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

export function buildBackfillResponsePayload(input: BackfillResult) {
  return {
    status: "needs_reconnect" as const,
    reason: input.reason,
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
    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
      return json({ error: "Not authenticated" }, 401);
    }

    normalizeBackfillBody(await req.json());
    const { userClient } = createSupabaseClients(authHeader);

    const {
      data: { user },
      error: userError,
    } = await userClient.auth.getUser();

    if (userError || !user) {
      return json({ error: "Not authenticated" }, 401);
    }

    return json(
      buildBackfillResponsePayload({
        status: "needs_reconnect",
        reason: "legacy_backfill_disabled",
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
