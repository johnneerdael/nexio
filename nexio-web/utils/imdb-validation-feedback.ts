export type ImdbValidationFeedback = {
  title: string
  message: string
}

type ImdbValidationFailureInput = {
  status?: number
  providerCode?: string | null
  providerMessage?: string | null
}

export function describeImdbValidationSuccess(baseUrl: string): ImdbValidationFeedback {
  return {
    title: 'IMDb provider validated',
    message: `Connected to ${baseUrl}.`
  }
}

export function describeImdbValidationFailure(input: ImdbValidationFailureInput): ImdbValidationFeedback {
  const providerCode = input.providerCode?.trim().toLowerCase() || ''
  const providerMessage = input.providerMessage?.trim() || ''

  switch (providerCode) {
    case 'missing_api_key':
      return {
        title: 'IMDb API key required',
        message: 'The IMDb provider did not receive an API key. Save the key in Nexio and validate again.'
      }
    case 'invalid_api_key':
      return {
        title: 'IMDb API key rejected',
        message: 'The IMDb provider rejected the configured API key.'
      }
    case 'not_ready':
      return {
        title: 'IMDb provider not ready',
        message: 'The IMDb provider is reachable but not ready yet.'
      }
    case 'invalid_request':
      return {
        title: 'IMDb validation request invalid',
        message: 'The IMDb provider rejected the validation request. Check the configured base URL.'
      }
    case 'not_found':
      return {
        title: 'IMDb endpoint not found',
        message: 'The IMDb provider did not expose /v1/meta/stats at this base URL.'
      }
    case 'internal_error':
      return {
        title: 'IMDb provider error',
        message: 'The IMDb provider returned an internal server error.'
      }
    default:
      break
  }

  if (input.status === 502) {
    return {
      title: 'IMDb provider unreachable',
      message: providerMessage || 'Nexio could not reach the IMDb provider from the server.'
    }
  }

  if (input.status && input.status >= 500) {
    return {
      title: 'IMDb provider error',
      message: providerMessage || 'The IMDb provider returned an unexpected server error.'
    }
  }

  return {
    title: 'IMDb validation failed',
    message: providerMessage || 'IMDb validation failed.'
  }
}
