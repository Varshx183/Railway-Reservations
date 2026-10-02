# Repairs and design decisions

| Original issue | Resolution |
|---|---|
| AC capacity 18, but SQL used 24 for coach advancement | Derive assignment from inventory's seats-per-coach; regression tests cross both coach boundaries. |
| PNR encoding used as seat metadata | UUID PNR plus explicit passenger/coach/berth columns. |
| Only first name passed; names never saved | Parameterized PostgreSQL text array, one persisted assignment per passenger. |
| Table dynamically created per train/date/class | One inventory table keyed by train/date/class. |
| Broad exclusive table locking | Row locking and a transaction covering inventory, ticket, and passengers. |
| Duplicate train release race | Unique train/date key and INSERT ON CONFLICT; repeat release never resets seats. |
| No constraints preventing repeated seats | Unique train/date/class/seat-index key, foreign keys, capacity checks. |
| Two servers conflicted on port 7008 | One server for booking, availability, ticket lookup, and search. |
| Duplicate QueryRunner classes and unfinished server | One Java package and explicit main entry point. |
| Unclosed database resources | Scoped JDBC connections/statements/result sets, plus rollback on failure. |
| Missing # or disconnect could fail the server worker | EOF-safe framing and a request-response client. |
| Unlimited or malformed input | Line-size limit, input validation, consistent errors, idle timeout. |
| Hardcoded database passwords | Environment variables and gitignored local .env. |
| Any client could release trains | Admin token required for RELEASE. |
| Route-day ordering inconsistent in generated overnight rows | Explicit departure day and arrival offset, regenerated from timetable midnight crossings. |
| Connection search used special-case overnight arithmetic | Weekly minute arithmetic handles midnight and week rollover. |
| Missing build/setup documentation | Maven dependencies, Docker Compose, executable JAR, examples, CI, and documentation. |

This is a new schema and request protocol. Existing original databases and input files are not automatically migrated. Keep the original ZIP as a reference; create a dedicated `railway` database for this version.

## Web edition enhancements

- Browser interface for dated search, booking, account sign-in, ticket history, cancellation, and opening departures.
- Account ownership, salted password hashing, expiring hashed sessions, CSRF checks, and authentication throttling.
- Safe cancellation with inactive historical assignments and a unique index covering only active seats.
- A free-seat selection strategy that reuses cancellation gaps without moving existing passengers.
- Per-account request IDs for idempotent web booking retries.
- Migration from the repaired v1 schema, preserving its existing bookings.
- Additional password, account/privacy, cancellation/concurrency, migration, and HTTP tests.

The original coach capacities are retained. Cancellation now uses free-seat selection rather than treating the booked count as the next seat index.
