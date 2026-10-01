# Durable Travel Graph — Design

## Scope

Make Neo4j the authoritative store for destinations and complete travel aggregates in the `infra` profile. Retain the current HTTP request and response shape so existing clients continue to work. The default profile keeps the in-memory implementation for lightweight tests and local startup. This package addresses the travel persistence finding in the 2026-09-29 audit; the separate OpenAPI/domain-shape mismatch and cross-service deletion workflow remain tracked findings.

## Graph model

`Destination` is shared and has `id`, `name`, `country`, normalized name/country, and `createdAt`. `Travel` has `id`, `name`, `startDate`, `endDate`, `durationDays`, and `createdAt`. A travel owns `DestinationStop`, `Activity`, `Accommodation`, `Transport`, and `Participation` nodes through typed relationships. Each stop points to a shared destination through `AT`. The current API supplies activities, accommodations, and transports as travel-level arrays, so these nodes attach directly to `Travel`; each stores an input sequence to preserve array order. Participation stores the external traveler UUID without a cross-service graph lookup.

Each owned node has a stable UUID. A travel update replaces its itinerary nodes in one graph transaction, retaining the travel ID, creation time, and participants. A travel delete removes the root and all owned nodes in one transaction, leaving destinations intact. Destination deletion returns `DESTINATION_IN_USE` while any stop points to it.

## Reads and writes

`TravelStore` retains validation and selects a repository implementation based on the presence of the Neo4j driver. The Neo4j repository uses one managed write transaction per aggregate mutation and closes every driver session. A single Cypher read projects the root and all owned lists so detail and list responses are reconstructed from the graph, including participants. The service never treats its process-local maps as a cache in the `infra` profile. Database failures propagate as unavailable responses rather than returning stale local data.

At startup, the repository creates repeat-safe UUID constraints for all owned labels and a composite normalized-destination constraint. Existing destination nodes are given normalized keys before that constraint is created. If legacy duplicate destinations prevent the constraint, startup fails and the operator must reconcile those rows; silently choosing one would change destination identity.

## Legacy data

The previous implementation wrote only top-level `Travel` and `Destination` nodes. In-memory itinerary components cannot be reconstructed after restart. The upgrade runbook will require backup and manual reconstruction of incomplete legacy travels from an external source. Reads of a legacy travel without stops return an explicit conflict, so the API does not present an incomplete itinerary as a complete one.

## Validation and evidence

Keep the existing date and stop validation and improve it where needed to reject invalid aggregate writes before storage. Add an in-process Neo4j Harness test that creates a multi-destination itinerary, reads it from a second store instance, updates it, manages participants, rejects deletion of a shared destination, deletes the travel, and confirms owned nodes are gone while destinations remain. Run the full Maven reactor and Compose configuration checks. A live Docker deployment rehearsal remains separate because the Docker daemon is unavailable on this host.
