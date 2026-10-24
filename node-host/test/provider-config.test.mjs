import { test } from 'node:test';
import assert from 'node:assert/strict';
import { providerBaseURL } from '../dist/plugins/android-api/provider-config.js';

test('native Anthropic and OpenAI SDK endpoint normalization', () => {
  assert.equal(providerBaseURL('anthropic','https://relay.example/api/v1/messages/'),'https://relay.example/api');
  assert.equal(providerBaseURL('anthropic','https://relay.example/v1'),'https://relay.example');
  assert.equal(providerBaseURL('openai-compatible','https://relay.example/v1/chat/completions'),'https://relay.example/v1');
  assert.equal(providerBaseURL('openai-compatible','http://127.0.0.1:11434/'),'http://127.0.0.1:11434/v1');
});
