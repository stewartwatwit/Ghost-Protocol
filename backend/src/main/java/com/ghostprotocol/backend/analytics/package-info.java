/**
 * Derived/rollup read models (weapon-usage-over-time, win-rate trends, K/D
 * and KAD), computed on-demand via queries against activity/weapon tables.
 * Never writes to activity or weapon tables. Depends on activity, weapon,
 * shared.
 */
package com.ghostprotocol.backend.analytics;
