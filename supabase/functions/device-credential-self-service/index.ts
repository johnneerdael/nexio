import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  hashDeviceCredential,
  normalizeDeviceExchangeBody,
} from "../_shared/device-auth.ts";

type CredentialAction = "status" | "revoke";
type CredentialStatus = "active" | "revoked";

type CredentialSelfServiceBody = {
  device_public_id?: unknown;
  device_secret?: unknown;
  action?: unknown;
};

type CredentialSelfServiceClients = {
  adminClient: {
    from: (table: string) => any;
  };
};

type CredentialSelfServiceHandlerDependencies = {
  createSupabaseClients?: () => CredentialSelfServiceClients;
  now?: () => Date;
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
  const supabaseServiceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  return {
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

export function normalizeCredentialSelfServiceBody(body: unknown) {
  const credentials = normalizeDeviceExchangeBody(body);
  const action = (body as CredentialSelfServiceBody).action;

  if (typeof action !== "string") {
    throw new Error("Invalid durable device credential action");
  }

  const normalizedAction = action.trim();
  if (normalizedAction !== "status" && normalizedAction !== "revoke") {
    throw new Error("Invalid durable device credential action");
  }

  return {
    ...credentials,
    action: normalizedAction as CredentialAction,
  };
}

export function buildCredentialSelfServicePayload(status: CredentialStatus) {
  return {
    status,
    active: status === "active",
    revoked: status === "revoked",
  };
}

export function buildCredentialSelfServiceUpdate(revokedAt: string) {
  return {
    status: "revoked" as const,
    revoked_at: revokedAt,
  };
}

function invalidCredentialResponse(): Response {
  return json({ error: "Invalid durable device credential" }, 401);
}

function invalidRequestResponse(): Response {
  return json({ error: "Invalid request" }, 400);
}

function serviceUnavailableResponse(): Response {
  return json({ error: "Credential self service unavailable" }, 500);
}

function logInternalError(context: string, error: unknown) {
  console.error(`[device-credential-self-service] ${context}`, error);
}

async function parseCredentialSelfServiceBody(req: Request) {
  try {
    return normalizeCredentialSelfServiceBody(await req.json());
  } catch (error) {
    if (
      error instanceof Error &&
      error.message === "Invalid durable device credential"
    ) {
      throw error;
    }

    throw new Error("Invalid durable device credential request");
  }
}

function isCredentialStatus(status: unknown): status is CredentialStatus {
  return status === "active" || status === "revoked";
}

export function createCredentialSelfServiceHandler(
  dependencies: CredentialSelfServiceHandlerDependencies = {},
) {
  const clientsFactory = dependencies.createSupabaseClients ??
    createSupabaseClients;
  const now = dependencies.now ?? (() => new Date());

  return async function handleRequest(req: Request): Promise<Response> {
    if (req.method === "OPTIONS") {
      return new Response("ok", { headers: corsHeaders });
    }

    if (req.method !== "POST") {
      return json({ error: "Method not allowed" }, 405);
    }

    try {
      const body = await parseCredentialSelfServiceBody(req);
      const { adminClient } = clientsFactory();

      const { data: credentialRow, error: credentialError } = await adminClient
        .from("device_credentials")
        .select("credential_hash, status")
        .eq("device_public_id", body.devicePublicId)
        .maybeSingle();

      if (credentialError) {
        logInternalError("credential lookup failed", credentialError);
        return serviceUnavailableResponse();
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

      if (!isCredentialStatus(credentialRow.status)) {
        logInternalError("credential row has invalid status", {
          device_public_id: body.devicePublicId,
          status: credentialRow.status,
        });
        return serviceUnavailableResponse();
      }

      if (body.action === "status" || credentialRow.status === "revoked") {
        return json(
          buildCredentialSelfServicePayload(credentialRow.status),
          200,
        );
      }

      const update = buildCredentialSelfServiceUpdate(now().toISOString());
      const { error: revokeError } = await adminClient
        .from("device_credentials")
        .update(update)
        .eq("device_public_id", body.devicePublicId)
        .eq("status", "active");

      if (revokeError) {
        logInternalError("credential revoke failed", revokeError);
        return serviceUnavailableResponse();
      }

      return json(buildCredentialSelfServicePayload("revoked"), 200);
    } catch (error) {
      if (
        error instanceof Error &&
        error.message === "Invalid durable device credential"
      ) {
        return invalidCredentialResponse();
      }

      if (
        error instanceof Error &&
        error.message === "Invalid durable device credential request"
      ) {
        return invalidRequestResponse();
      }

      logInternalError("unexpected request failure", error);
      return serviceUnavailableResponse();
    }
  };
}

const handleRequest = createCredentialSelfServiceHandler();

if (import.meta.main) {
  Deno.serve(handleRequest);
}
