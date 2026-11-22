/**
 * The only module permitted to speak HTTP to bungie.net. Owns OAuth token
 * exchange/refresh, request/response DTOs, retry/backoff, and manifest
 * download/parsing. Exposes mapper-producing service methods; raw DTOs stay
 * package-private. Depends on shared only.
 */
package com.ghostprotocol.backend.bungie;
