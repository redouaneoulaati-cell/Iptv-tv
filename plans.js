'use strict';
// These are display prices only. Create matching recurring USD prices in Stripe
// and matching USD plans in PayPal, then store their IDs in environment variables.
const PLANS = {
  monthly: { id: 'monthly', name: 'Monthly', months: 1, price: '$4.99', currency: 'USD', stripeEnv: 'STRIPE_PRICE_MONTHLY', paypalEnv: 'PAYPAL_PLAN_MONTHLY' },
  quarterly: { id: 'quarterly', name: 'Quarterly', months: 3, price: '$12.99', currency: 'USD', stripeEnv: 'STRIPE_PRICE_QUARTERLY', paypalEnv: 'PAYPAL_PLAN_QUARTERLY' },
  yearly: { id: 'yearly', name: 'Yearly', months: 12, price: '$44.99', currency: 'USD', stripeEnv: 'STRIPE_PRICE_YEARLY', paypalEnv: 'PAYPAL_PLAN_YEARLY' }
};
module.exports = { PLANS };
