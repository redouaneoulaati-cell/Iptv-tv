const test = require('node:test');
const assert = require('node:assert/strict');
const { PLANS } = require('../src/plans');

test('exposes three USD plan identifiers with configured display prices', () => {
  assert.deepEqual(Object.keys(PLANS), ['monthly', 'quarterly', 'yearly']);
  assert.equal(PLANS.monthly.months, 1);
  assert.equal(PLANS.quarterly.months, 3);
  assert.equal(PLANS.yearly.months, 12);
  assert.deepEqual(Object.values(PLANS).map(p => p.currency), ['USD', 'USD', 'USD']);
  assert.deepEqual(Object.values(PLANS).map(p => p.price), ['$4.99', '$12.99', '$44.99']);
});
