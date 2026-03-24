import test from 'node:test'
import assert from 'node:assert/strict'
import {
  describeImdbValidationFailure,
  describeImdbValidationSuccess
} from '../utils/imdb-validation-feedback'

test('describeImdbValidationSuccess returns a clean success toast payload', () => {
  assert.deepEqual(
    describeImdbValidationSuccess('https://api.nexioapp.org/v1'),
    {
      title: 'IMDb provider validated',
      message: 'Connected to https://api.nexioapp.org/v1.'
    }
  )
})

test('describeImdbValidationFailure maps invalid_api_key to a clean message', () => {
  assert.deepEqual(
    describeImdbValidationFailure({
      status: 401,
      providerCode: 'invalid_api_key',
      providerMessage: 'api key invalid'
    }),
    {
      title: 'IMDb API key rejected',
      message: 'The IMDb provider rejected the configured API key.'
    }
  )
})

test('describeImdbValidationFailure maps not_found to a base-url hint', () => {
  assert.deepEqual(
    describeImdbValidationFailure({
      status: 404,
      providerCode: 'not_found',
      providerMessage: 'resource not found'
    }),
    {
      title: 'IMDb endpoint not found',
      message: 'The IMDb provider did not expose /v1/meta/stats at this base URL.'
    }
  )
})

test('describeImdbValidationFailure falls back to the provider message when no code is known', () => {
  assert.deepEqual(
    describeImdbValidationFailure({
      status: 400,
      providerMessage: 'IMDb base URL is invalid.'
    }),
    {
      title: 'IMDb validation failed',
      message: 'IMDb base URL is invalid.'
    }
  )
})
