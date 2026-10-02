# Project and interview guide

## What you built

A Java/PostgreSQL railway reservation application with a responsive HTML/CSS/JavaScript interface. Passengers can search by date, create accounts, book groups, view their tickets, and cancel an entire booking. An admin token protects opening train inventory. The original TCP client/server remains as a separate learning interface.

## Booking flow

1. The browser sends a validated booking request with the user's session/CSRF token and a random request ID.
2. Java verifies the session and validates train, date, class, and passenger names.
3. `WebStore` starts a transaction and locks the account row to serialize duplicate-request checks.
4. If the same request ID was completed, the existing PNR is returned; changed details are rejected.
5. `book_tickets` locks the inventory row, selects free seat indices, and inserts the ticket/passenger assignments.
6. Java records account ownership and the request ID, commits, and returns the ticket.

The account, inventory, ticket, and request-ID checks are in one transaction. A failed group booking cannot leave a partial reservation.

## Cancellation flow

Cancellation first checks ownership, then locks inventory before the ticket to match booking's lock order. It marks the ticket cancelled, marks seat assignments inactive, and decreases the booked count. A partial unique index protects active seats while allowing cancelled seats to be reused. A repeated cancellation returns the cancelled ticket without changing capacity again.

Historical assignments remain visible but no longer occupy seats. New bookings select the lowest free indices rather than treating the count of booked seats as the next free index. This is why cancellation does not corrupt remaining tickets.

## Seat calculation

For a zero-based seat index `i` and coach capacity `c`:

```
coach = i / c + 1       (integer division)
berth = i % c + 1
```

AC uses c=18, sleeper c=24. At index 18, an AC passenger receives coach 2/berth 1. Groups are contiguous when there are no cancellation holes; otherwise seats may span free gaps.

## Account protection

Passwords are salted and hashed with PBKDF2-HMAC-SHA256. Random session tokens are stored only as hashes and issued as HttpOnly/SameSite cookies. Every private request checks ownership, so another user's PNR is insufficient to access or cancel a ticket. CSRF headers protect changes initiated from the browser. Logout deletes the server-side session.

## Route search

PostgreSQL checks direct segments and joins two different trains at an intermediate station. Weekly minute arithmetic handles midnight and week rollover with a wait strictly between 0 and 120 minutes. The web API filters the results to the requested departure weekday and includes available direct-train capacity for that date.

## Questions to prepare for

- Why a row lock instead of Java synchronized? Separate application processes share the database, so database locking coordinates them.
- Why UUID PNRs? Identity is separate from seat calculations and ticket ownership.
- Why an idempotency key? A lost response and retry should not charge inventory twice.
- How do cancellations avoid duplicate seats? Inactive historical assignments are excluded from the active-seat unique index.
- Why hash session tokens? A session database record should not contain the usable bearer token.
- Why preserve cancelled records? Users retain their history, and capacity updates can be verified.
- Why test migration? Existing bookings must survive schema changes.
- What would you develop next? Segment-based availability, timetable administration, email verification/password reset, and atomic connected itineraries.

## Resume wording

> Developed a Java/PostgreSQL railway reservation web application with user authentication, transactional seat booking, ticket cancellation, and date-based route search; added automated concurrency, ownership, migration, and HTTP API tests with GitHub Actions CI.

Refer to VERIFICATION.md for the measured test count. Do not claim production traffic, payment processing, or live railway integration; these features are outside the implemented scope.
