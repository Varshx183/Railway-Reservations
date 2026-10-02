-- Upgrade the repaired v1 schema without deleting bookings.
CREATE TABLE IF NOT EXISTS accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(254) NOT NULL UNIQUE,
    display_name varchar(80) NOT NULL,
    password_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash varchar(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS owner_id uuid REFERENCES accounts(id);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS status varchar(10) NOT NULL DEFAULT 'CONFIRMED'
    CHECK (status IN ('CONFIRMED', 'CANCELLED'));
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS cancelled_at timestamptz;
ALTER TABLE passengers ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;
ALTER TABLE passengers DROP CONSTRAINT IF EXISTS passengers_train_id_journey_date_travel_class_seat_index_key;
CREATE UNIQUE INDEX IF NOT EXISTS unique_active_seat ON passengers(train_id, journey_date, travel_class, seat_index) WHERE active;
CREATE INDEX IF NOT EXISTS tickets_owner ON tickets(owner_id, booked_at DESC);
CREATE TABLE IF NOT EXISTS booking_requests (
    owner_id uuid NOT NULL REFERENCES accounts(id),
    request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    pnr uuid NOT NULL REFERENCES tickets(pnr),
    PRIMARY KEY (owner_id, request_id)
);

CREATE OR REPLACE FUNCTION book_tickets(p_train integer, p_date date, p_class varchar, p_names text[])
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE
    inventory seat_inventory%ROWTYPE;
    count_names integer := cardinality(p_names);
    ticket_pnr uuid := gen_random_uuid();
    seat_indices integer[];
    idx integer;
    ordinal integer := 0;
    berth integer;
    kind varchar(2);
    name text;
BEGIN
    IF p_train IS NULL OR p_train <= 0 OR p_date IS NULL OR p_class IS NULL
       OR p_class NOT IN ('AC', 'SL') OR count_names IS NULL OR count_names NOT BETWEEN 1 AND 100
       OR array_ndims(p_names) <> 1 THEN
        RAISE EXCEPTION 'INVALID_REQUEST' USING ERRCODE = '22023';
    END IF;
    FOREACH name IN ARRAY p_names LOOP
        IF name IS NULL OR length(btrim(name)) NOT BETWEEN 1 AND 100 THEN
            RAISE EXCEPTION 'INVALID_REQUEST' USING ERRCODE = '22023';
        END IF;
    END LOOP;
    SELECT * INTO inventory FROM seat_inventory
      WHERE train_id = p_train AND journey_date = p_date AND travel_class = p_class FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'TRAIN_NOT_AVAILABLE' USING ERRCODE = 'P0001'; END IF;
    IF inventory.coach_count * inventory.seats_per_coach - inventory.seats_booked < count_names THEN
        RAISE EXCEPTION 'SEATS_NOT_AVAILABLE' USING ERRCODE = 'P0001';
    END IF;
    SELECT array_agg(free_seat ORDER BY free_seat) INTO seat_indices FROM (
        SELECT candidate AS free_seat FROM generate_series(0, inventory.coach_count * inventory.seats_per_coach - 1) candidate
        WHERE NOT EXISTS (SELECT 1 FROM passengers p WHERE p.train_id=p_train
            AND p.journey_date=p_date AND p.travel_class=p_class AND p.seat_index=candidate AND p.active)
        ORDER BY candidate LIMIT count_names
    ) available;
    IF cardinality(seat_indices) IS DISTINCT FROM count_names THEN
        RAISE EXCEPTION 'SEATS_NOT_AVAILABLE' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO tickets(pnr, train_id, journey_date, travel_class, passenger_count)
      VALUES(ticket_pnr, p_train, p_date, p_class, count_names);
    FOREACH name IN ARRAY p_names LOOP
        ordinal := ordinal + 1;
        idx := seat_indices[ordinal];
        berth := idx % inventory.seats_per_coach + 1;
        IF p_class = 'AC' THEN
            kind := (ARRAY['LB','LB','UB','UB','SL','SU'])[((berth - 1) % 6) + 1];
        ELSE
            kind := (ARRAY['LB','MB','UB','LB','MB','UB','SL','SU'])[((berth - 1) % 8) + 1];
        END IF;
        INSERT INTO passengers(pnr, passenger_order, passenger_name, train_id, journey_date, travel_class,
            seat_index, coach_number, berth_number, berth_type)
          VALUES(ticket_pnr, ordinal, btrim(name), p_train, p_date, p_class,
            idx, idx / inventory.seats_per_coach + 1, berth, kind);
    END LOOP;
    UPDATE seat_inventory SET seats_booked = seats_booked + count_names
      WHERE train_id = p_train AND journey_date = p_date AND travel_class = p_class;
    RETURN ticket_pnr;
END;
$$;

CREATE OR REPLACE FUNCTION cancel_ticket(p_pnr uuid, p_owner uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE booking tickets%ROWTYPE;
BEGIN
    SELECT * INTO booking FROM tickets WHERE pnr=p_pnr AND owner_id=p_owner;
    IF NOT FOUND THEN RAISE EXCEPTION 'TICKET_NOT_FOUND' USING ERRCODE='P0001'; END IF;
    -- Match booking's lock order: inventory before ticket/passenger updates.
    PERFORM 1 FROM seat_inventory WHERE train_id=booking.train_id
        AND journey_date=booking.journey_date AND travel_class=booking.travel_class FOR UPDATE;
    SELECT * INTO booking FROM tickets WHERE pnr=p_pnr AND owner_id=p_owner FOR UPDATE;
    IF booking.status='CANCELLED' THEN RETURN false; END IF;
    UPDATE passengers SET active=false WHERE pnr=p_pnr;
    UPDATE tickets SET status='CANCELLED', cancelled_at=CURRENT_TIMESTAMP WHERE pnr=p_pnr;
    UPDATE seat_inventory SET seats_booked=seats_booked-booking.passenger_count
      WHERE train_id=booking.train_id AND journey_date=booking.journey_date AND travel_class=booking.travel_class;
    RETURN true;
END;
$$;
