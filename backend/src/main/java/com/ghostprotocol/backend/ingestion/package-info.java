/**
 * Scheduling, per-character sync orchestration, the SyncCheckpoint entity and
 * resume logic. The one module allowed to call across guardian, activity,
 * and weapon as an orchestrator. Depends on guardian, activity, weapon,
 * bungie, shared.
 */
package com.ghostprotocol.backend.ingestion;
